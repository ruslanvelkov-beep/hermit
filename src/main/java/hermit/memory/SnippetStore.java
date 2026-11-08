package hermit.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SnippetStore implements AutoCloseable {

    /** A search result: the stored unit plus its cosine similarity to the query. */
    public record Hit(Snippet snippet, double score) {}

    private final Connection conn;

    // Near-duplicate guard: on upsert, similar units of the same kind/lang count as duplicates.
    // Tune with -Dhermit.snippets.dedupSimilarity (default 0.97, deliberately strict).
    private double nearDupThreshold =
            Double.parseDouble(System.getProperty("hermit.snippets.dedupSimilarity", "0.97"));

    public SnippetStore(String jdbcUrl) {
        try {
            this.conn = DriverManager.getConnection(jdbcUrl);
            initSchema();
        } catch (SQLException e) {
            throw new RuntimeException("open snippet store failed: " + e.getMessage(), e);
        }
    }

    public void setNearDupThreshold(double t) {
        this.nearDupThreshold = t;
    }

    public static SnippetStore atFile(String path) {
        return new SnippetStore("jdbc:sqlite:" + path);
    }

    public static SnippetStore inMemory() {
        return new SnippetStore("jdbc:sqlite::memory:");
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS snippet (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    kind          TEXT NOT NULL,
                    lang          TEXT NOT NULL,
                    description   TEXT NOT NULL,
                    code          TEXT,
                    content_hash  TEXT NOT NULL UNIQUE,
                    embed_model   TEXT NOT NULL,
                    embed_dim     INTEGER NOT NULL,
                    embedding     BLOB NOT NULL,
                    source_query  TEXT,
                    use_count     INTEGER NOT NULL DEFAULT 0,
                    active        INTEGER NOT NULL DEFAULT 1,
                    created_at    INTEGER NOT NULL
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS ix_snippet_filter ON snippet(kind, lang, active)");
        }
    }

    public long upsert(Snippet s) {
        String hash = s.contentHash() != null ? s.contentHash() : sha256(normalize(s.code()));
        try {
            Long existing = idByHash(hash);
            if (existing != null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE snippet SET use_count = use_count + 1 WHERE id = ?")) {
                    ps.setLong(1, existing);
                    ps.executeUpdate();
                }
                return existing;
            }
            // Near-duplicate: a very-similar unit (same kind/lang) already covers this -
            // bump it instead of adding a redundant row.
            Long dup = nearDuplicateId(s);
            if (dup != null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE snippet SET use_count = use_count + 1 WHERE id = ?")) {
                    ps.setLong(1, dup);
                    ps.executeUpdate();
                }
                return dup;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO snippet(kind,lang,description,code,content_hash,embed_model,"
                    + "embed_dim,embedding,source_query,use_count,active,created_at) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, s.kind());
                ps.setString(2, s.lang());
                ps.setString(3, s.description());
                ps.setString(4, s.code());
                ps.setString(5, hash);
                ps.setString(6, s.embedModel());
                ps.setInt(7, s.embedding().length);
                ps.setBytes(8, toBytes(s.embedding()));
                ps.setString(9, s.sourceQuery());
                ps.setInt(10, Math.max(0, s.useCount()));
                ps.setInt(11, s.active() ? 1 : 0);
                ps.setLong(12, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : -1;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("upsert failed: " + e.getMessage(), e);
        }
    }

    private Long nearDuplicateId(Snippet s) throws SQLException {
        if (nearDupThreshold >= 1.0 || s.embedding() == null) {
            return null;
        }
        long bestId = -1;
        double best = -1.0;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, embedding FROM snippet WHERE active = 1 AND kind = ? AND lang = ?")) {
            ps.setString(1, s.kind());
            ps.setString(2, s.lang());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double sim = EmbeddingProvider.cosine(s.embedding(), fromBytes(rs.getBytes("embedding")));
                    if (sim > best) {
                        best = sim;
                        bestId = rs.getLong("id");
                    }
                }
            }
        }
        return best >= nearDupThreshold ? bestId : null;
    }

    private Long idByHash(String hash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM snippet WHERE content_hash = ?")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    public List<Hit> search(float[] queryVec, String kind, String lang, int topK, double floor) {
        List<Hit> hits = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM snippet WHERE active = 1");
        if (kind != null) {
            sql.append(" AND kind = ?");
        }
        if (lang != null) {
            sql.append(" AND lang = ?");
        }
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int p = 1;
            if (kind != null) {
                ps.setString(p++, kind);
            }
            if (lang != null) {
                ps.setString(p++, lang);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    float[] emb = fromBytes(rs.getBytes("embedding"));
                    double score = EmbeddingProvider.cosine(queryVec, emb);
                    if (score >= floor) {
                        hits.add(new Hit(row(rs, emb), score));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("search failed: " + e.getMessage(), e);
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        List<Hit> top = hits.size() > topK ? hits.subList(0, topK) : hits;
        for (Hit h : top) {
            bumpUse(h.snippet().id());
        }
        return new ArrayList<>(top);
    }

    private void bumpUse(long id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE snippet SET use_count = use_count + 1 WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException ignored) {
            // usage stats are best-effort
        }
    }

    private Snippet row(ResultSet rs, float[] emb) throws SQLException {
        return new Snippet(
                rs.getLong("id"), rs.getString("kind"), rs.getString("lang"),
                rs.getString("description"), rs.getString("code"), rs.getString("content_hash"),
                rs.getString("embed_model"), emb, rs.getString("source_query"),
                rs.getInt("use_count"), rs.getInt("active") == 1);
    }

    public long count() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM snippet WHERE active = 1")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("count failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // closing best-effort
        }
    }

    static byte[] toBytes(float[] v) {
        ByteBuffer bb = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float x : v) {
            bb.putFloat(x);
        }
        return bb.array();
    }

    static float[] fromBytes(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[b.length / 4];
        for (int i = 0; i < v.length; i++) {
            v[i] = bb.getFloat();
        }
        return v;
    }

    static String normalize(String code) {
        return code == null ? "" : code.strip().replaceAll("\\s+", " ");
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}

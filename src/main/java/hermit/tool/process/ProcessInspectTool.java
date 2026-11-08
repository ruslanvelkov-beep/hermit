package hermit.tool.process;

import hermit.tool.api.Tool;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Inspects a process (by PID or name) and reports its state - whether it is
 * alive, using CPU, has files open for writing, or looks stuck. Uses `ps` for
 * state/CPU and `lsof` for open files, and derives a plain-language verdict.
 */
public class ProcessInspectTool implements Tool {

    private static final int TIMEOUT_SEC = 20;

    @Override
    public String getDocumentation() {
        return "ProcessInspect: report a process's state (alive? using CPU? writing files? stuck?). "
                + "Parameters: {\"pid\": <int>} or {\"name\": \"<process name>\"}. Returns per-PID "
                + "state, %CPU, elapsed time, a sample of files open for writing, and a verdict.";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> parameters) {
        List<String> pids = new ArrayList<>();
        Object pid = parameters.get("pid");
        String name = (String) parameters.get("name");
        if (pid != null) {
            pids.add(String.valueOf(pid instanceof Number n ? n.longValue() : pid).trim());
        } else if (name != null && !name.isBlank()) {
            if (!name.matches("[A-Za-z0-9_.\\-]+")) {
                return Map.of("status", "Error", "message", "illegal process name: " + name);
            }
            CliResult pg = run("pgrep", "-f", name);
            for (String line : pg.output.split("\n")) {
                if (line.matches("\\d+")) {
                    pids.add(line.trim());
                }
            }
            if (pids.isEmpty()) {
                return Map.of("status", "Success", "found", 0,
                        "message", "no running process matches '" + name + "'");
            }
        } else {
            return Map.of("status", "Error", "message", "pid or name is required.");
        }

        List<Map<String, Object>> reports = new ArrayList<>();
        for (String p : pids) {
            if (!p.matches("\\d+")) {
                continue;
            }
            reports.add(inspect(p));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "Success");
        result.put("found", reports.size());
        result.put("processes", reports);
        return result;
    }

    private Map<String, Object> inspect(String pid) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pid", pid);
        // ps: state, %cpu, elapsed, command.
        CliResult ps = run("ps", "-p", pid, "-o", "stat=,%cpu=,etime=,comm=");
        String line = ps.output.strip();
        if (ps.exitCode != 0 || line.isEmpty()) {
            r.put("alive", false);
            r.put("verdict", "not running (no such PID)");
            return r;
        }
        String[] f = line.split("\\s+", 4);
        String stat = f.length > 0 ? f[0] : "?";
        String cpu = f.length > 1 ? f[1] : "?";
        String etime = f.length > 2 ? f[2] : "?";
        String comm = f.length > 3 ? f[3] : "?";
        r.put("alive", true);
        r.put("state", stat);
        r.put("cpuPercent", cpu);
        r.put("elapsed", etime);
        r.put("command", comm);

        // lsof: files open for writing (mode contains 'w' or 'u').
        List<String> writing = new ArrayList<>();
        CliResult lsof = run("lsof", "-p", pid);
        for (String l : lsof.output.split("\n")) {
            String[] cols = l.split("\\s+");
            if (cols.length >= 9 && (cols[3].contains("w") || cols[3].contains("u"))
                    && (l.contains("REG") || l.contains("/"))) {
                String path = cols[cols.length - 1];
                if (path.startsWith("/") && !path.startsWith("/dev") && writing.size() < 5) {
                    writing.add(path);
                }
            }
        }
        r.put("filesOpenForWriting", writing);

        double cpuVal = parseDouble(cpu);
        String verdict;
        if (stat.startsWith("T")) {
            verdict = "stopped/suspended";
        } else if (stat.startsWith("Z")) {
            verdict = "zombie (finished, not reaped)";
        } else if (cpuVal >= 5.0) {
            verdict = "actively working (using CPU)" + (writing.isEmpty() ? "" : ", writing files");
        } else if (!writing.isEmpty()) {
            verdict = "low CPU but has files open for writing — likely producing output";
        } else if (stat.startsWith("S") || stat.startsWith("I")) {
            verdict = "idle/sleeping — waiting on I/O or input; could be stuck if this persists";
        } else {
            verdict = "running (state " + stat + ")";
        }
        r.put("verdict", verdict);
        return r;
    }

    private double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private record CliResult(int exitCode, String output) {}

    private CliResult run(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            if (!p.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new CliResult(-1, out.toString());
            }
            return new CliResult(p.exitValue(), out.toString());
        } catch (Exception e) {
            return new CliResult(-1, "");
        }
    }
}

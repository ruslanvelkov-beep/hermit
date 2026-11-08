package hermit.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a program into reusable units along real structural boundaries (top-level
 * functions / classes), each with a description drawn from its docstring or the comment
 * line just above it (falling back to a humanized name). Deterministic, no LLM - an
 * approximation of AST splitting good enough for Python and simple Java;
 * a full tree-sitter/AST pass is a later upgrade.
 */
public final class StructuralSplitter {

    /** One extracted unit: its name, its source, and a natural-language description. */
    public record Block(String name, String code, String description) {}

    private StructuralSplitter() {}

    public static List<Block> split(String lang, String source) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        if ("python".equalsIgnoreCase(lang)) {
            return splitPython(source);
        }
        // Other languages: treat the whole program as one block for now.
        return List.of(new Block("program", source.strip(), firstComment(source)));
    }

    private static List<Block> splitPython(String source) {
        String[] lines = source.split("\n", -1);
        List<Block> blocks = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.strip();
            boolean isTop = (line.startsWith("def ") || line.startsWith("async def ")
                    || line.startsWith("class ")) ;
            if (isTop) {
                // gather a preceding comment line as a description hint
                String precedingComment = (blocks.isEmpty() && i > 0) || i == 0 ? null : commentAbove(lines, i);
                int start = i;
                i++;
                // consume the body: indented lines (and blank lines) until the next top-level stmt
                while (i < lines.length) {
                    String l = lines[i];
                    if (l.isBlank() || l.startsWith(" ") || l.startsWith("\t")) {
                        i++;
                    } else {
                        break;
                    }
                }
                StringBuilder code = new StringBuilder();
                for (int j = start; j < i; j++) {
                    code.append(lines[j]).append('\n');
                }
                String block = code.toString().strip();
                blocks.add(new Block(nameOf(trimmed), block,
                        describe(trimmed, block, precedingComment)));
            } else {
                i++;
            }
        }
        if (blocks.isEmpty()) {
            blocks.add(new Block("program", source.strip(), firstComment(source)));
        }
        return blocks;
    }

    private static String commentAbove(String[] lines, int defIdx) {
        int k = defIdx - 1;
        while (k >= 0 && lines[k].isBlank()) {
            k--;
        }
        if (k >= 0 && lines[k].strip().startsWith("#")) {
            return lines[k].strip().replaceFirst("^#+\\s*", "");
        }
        return null;
    }

    /** Description = docstring, else the preceding comment, else a humanized name. */
    private static String describe(String header, String block, String precedingComment) {
        String doc = docstring(block);
        if (doc != null && !doc.isBlank()) {
            return doc.strip();
        }
        if (precedingComment != null && !precedingComment.isBlank()) {
            return precedingComment.strip();
        }
        return humanize(nameOf(header));
    }

    private static String docstring(String block) {
        for (String q : new String[] {"\"\"\"", "'''"}) {
            int a = block.indexOf(q);
            if (a >= 0) {
                int b = block.indexOf(q, a + 3);
                if (b > a) {
                    return block.substring(a + 3, b).strip();
                }
            }
        }
        return null;
    }

    private static String nameOf(String header) {
        String h = header.replaceFirst("^(async\\s+)?def\\s+", "").replaceFirst("^class\\s+", "");
        int paren = h.indexOf('(');
        int colon = h.indexOf(':');
        int cut = paren >= 0 ? paren : (colon >= 0 ? colon : h.length());
        return h.substring(0, Math.max(0, cut)).strip();
    }

    private static String humanize(String name) {
        return name.replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase().strip();
    }

    private static String firstComment(String source) {
        for (String l : source.split("\n")) {
            String t = l.strip();
            if (t.startsWith("#") || t.startsWith("//")) {
                return t.replaceFirst("^([#/]+)\\s*", "");
            }
        }
        return "code block";
    }
}

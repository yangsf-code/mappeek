package mappeek.idea;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** mappeek-edit.csv 的读写，列：apiKey,direction,field,type,required,confidence,description,summary */
final class FieldCsv {

    static final String HEADER = "apiKey,direction,field,type,required,confidence,description,summary";

    private FieldCsv() {
    }

    static List<String[]> readRows(Path csv) {
        List<String[]> rows = new ArrayList<>();
        if (!Files.isRegularFile(csv)) {
            return rows;
        }
        try {
            List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                    line = line.substring(1);
                }
                List<String> cols = split(line);
                if (cols.size() < 7) {
                    continue;
                }
                rows.add(new String[]{cols.get(0), cols.get(1), cols.get(2),
                        cols.get(3), cols.get(4), cols.get(5), cols.get(6),
                        cols.size() > 7 ? cols.get(7) : ""});
            }
        } catch (IOException ignored) {
            return new ArrayList<>();
        }
        return rows;
    }

    static void write(Path csv, List<String[]> rows) throws IOException {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (String[] r : rows) {
            sb.append(quote(r[0])).append(',').append(r[1]).append(',')
                    .append(quote(r[2])).append(',').append(quote(r[3])).append(',')
                    .append(r[4]).append(',').append(quote(r[5])).append(',')
                    .append(quote(r[6])).append(',')
                    .append(quote(r.length > 7 ? r[7] : "")).append('\n');
        }
        Files.write(csv, ("\uFEFF" + sb).getBytes(StandardCharsets.UTF_8));
    }

    static String quote(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}

package mappeek;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;

/**
 * 把扫描结果导出成多种格式：给前端的 JSON、可导入文档工具的 OpenAPI、
 * 人类可读的 Markdown、以及带注释的 DTO 骨架。
 */
final class Exporters {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private Exporters() {
    }

    static void write(Path outDir, MapPeek.Report report, Set<String> formats) throws IOException {
        if (formats.contains("json")) {
            Files.writeString(outDir.resolve("mappeek-api.json"),
                    MAPPER.writeValueAsString(apiJson(report)), StandardCharsets.UTF_8);
        }
        if (formats.contains("openapi")) {
            Files.writeString(outDir.resolve("mappeek-openapi.json"),
                    MAPPER.writeValueAsString(openApi(report)), StandardCharsets.UTF_8);
        }
        if (formats.contains("md")) {
            Files.writeString(outDir.resolve("mappeek-api.md"), markdown(report), StandardCharsets.UTF_8);
        }
        if (formats.contains("dto")) {
            writeDtos(outDir, report);
        }
    }

    // ==================== 前端 JSON ====================

    static ObjectNode apiJson(MapPeek.Report report) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("generatedAt", LocalDateTime.now().withNano(0).toString());
        root.put("tool", "mappeek");
        root.put("note", "字段类型为静态分析尽力推断，confidence=low 的字段需人工确认");
        ArrayNode apis = root.putArray("apis");
        for (MapPeek.ApiModel api : report.apis) {
            ObjectNode node = apis.addObject();
            node.put("key", api.key);
            node.put("tag", api.tag);
            node.put("summary", api.summary);
            if (api.tagDescription != null) {
                node.put("tagDescription", api.tagDescription);
            }
            if (api.description != null) {
                node.put("description", api.description);
            }
            node.put("method", api.httpMethod);
            node.put("path", api.path);
            node.put("source", api.file + ":" + api.line);
            if (api.mapIn) {
                ObjectNode request = fieldsNode(api.requestFields);
                request.set("structure", schema(api.requestFields.values()));
                node.set("request", request);
            }
            if (api.mapOut) {
                ObjectNode response = fieldsNode(api.responseFields);
                response.set("structure", schema(api.responseFields.values()));
                node.set("response", response);
            }
        }
        return root;
    }

    static ObjectNode fieldsNode(java.util.Map<String, MapPeek.FieldModel> fieldMap) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("count", fieldMap.size());
        ArrayNode fields = node.putArray("fields");
        for (MapPeek.FieldModel f : fieldMap.values()) {
            ObjectNode item = fields.addObject();
            item.put("name", f.name);
            item.put("type", f.type);
            if (f.format != null) {
                item.put("format", f.format);
            }
            item.put("required", f.required);
            if (f.description != null) {
                item.put("description", f.description);
            }
            item.put("confidence", f.confidence);
            item.put("origin", f.origin);
            item.put("source", f.file + ":" + f.line);
        }
        return node;
    }

    // ==================== OpenAPI 3 ====================

    static ObjectNode openApi(MapPeek.Report report) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("openapi", "3.0.3");
        ObjectNode info = root.putObject("info");
        info.put("title", "MapPeek 导出");
        info.put("version", "1.0.0");
        info.put("description", "由 MapPeek M0 从源码生成，字段类型为尽力推断，请人工确认");

        ArrayNode tagNodes = root.putArray("tags");
        Set<String> seenTags = new HashSet<>();
        for (MapPeek.ApiModel api : report.apis) {
            if (api.tag == null || !seenTags.add(api.tag)) {
                continue;
            }
            ObjectNode tag = tagNodes.addObject();
            tag.put("name", api.tag);
            if (api.tagDescription != null) {
                tag.put("description", api.tagDescription);
            }
        }
        if (tagNodes.isEmpty()) {
            root.remove("tags");
        }

        ObjectNode paths = root.putObject("paths");
        ObjectNode schemas = root.putObject("components").putObject("schemas");
        Set<String> usedOperationIds = new HashSet<>();

        for (MapPeek.ApiModel api : report.apis) {
            String simple = simpleClassName(api.key);
            String methodName = methodNameOf(api.key);
            String base = simple + capital(methodName);

            ObjectNode pathNode = paths.has(api.path)
                    ? (ObjectNode) paths.get(api.path)
                    : paths.putObject(api.path);
            ObjectNode operation = pathNode.putObject(api.httpMethod.toLowerCase(Locale.ROOT));
            operation.put("operationId",
                    unique(usedOperationIds, lowerFirst(simple) + capital(methodName)));
            operation.put("summary", api.summary == null ? api.key : api.summary);
            if (api.description != null) {
                operation.put("description", api.description);
            }
            if (api.tag != null) {
                operation.putArray("tags").add(api.tag);
            }
            operation.put("x-source", api.file + ":" + api.line);

            if (api.mapIn) {
                boolean bodyMethod = !("GET".equals(api.httpMethod) || "DELETE".equals(api.httpMethod)
                        || "HEAD".equals(api.httpMethod));
                if (bodyMethod) {
                    String schemaName = base + "Request";
                    schemas.set(schemaName, schema(api.requestFields.values()));
                    ObjectNode body = operation.putObject("requestBody");
                    body.put("required", true);
                    body.putObject("content").putObject("application/json")
                            .set("schema", ref(schemaName));
                } else {
                    ArrayNode parameters = operation.putArray("parameters");
                    for (MapPeek.FieldModel f : api.requestFields.values()) {
                        ObjectNode p = parameters.addObject();
                        p.put("name", f.name);
                        p.put("in", "query");
                        p.put("required", f.required);
                        if (f.description != null) {
                            p.put("description", f.description);
                        }
                        ObjectNode ps = p.putObject("schema");
                        ps.put("type", f.type == null ? "string" : f.type);
                        if (f.format != null) {
                            ps.put("format", f.format);
                        }
                    }
                }
            }

            ObjectNode responses = operation.putObject("responses");
            ObjectNode ok = responses.putObject("200");
            ok.put("description", "OK");
            if (api.mapOut) {
                String schemaName = base + "Response";
                schemas.set(schemaName, schema(api.responseFields.values()));
                ok.putObject("content").putObject("application/json")
                        .set("schema", ref(schemaName));
            }
        }
        return root;
    }

    static ObjectNode ref(String schemaName) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("$ref", "#/components/schemas/" + schemaName);
        return node;
    }

    static ObjectNode schema(Collection<MapPeek.FieldModel> fields) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        for (MapPeek.FieldModel f : fields) {
            String[] parts = f.name.split("\\.");
            ObjectNode current = properties;
            for (int i = 0; i < parts.length; i++) {
                String segment = parts[i];
                boolean array = segment.endsWith("[]");
                String part = array ? segment.substring(0, segment.length() - 2) : segment;
                boolean leaf = i == parts.length - 1;
                ObjectNode existing = current.has(part) ? (ObjectNode) current.get(part) : null;
                if (existing == null) {
                    existing = MAPPER.createObjectNode();
                    current.set(part, existing);
                }
                if (array) {
                    existing.put("type", "array");
                    if (!existing.has("items")) {
                        existing.putObject("items");
                    }
                    ObjectNode items = (ObjectNode) existing.get("items");
                    if (leaf) {
                        items.put("type", "array".equals(f.type) ? "object" : f.type);
                        if (f.format != null) {
                            items.put("format", f.format);
                        }
                    } else {
                        items.put("type", "object");
                        if (!items.has("properties")) {
                            items.putObject("properties");
                        }
                        current = (ObjectNode) items.get("properties");
                    }
                } else if (leaf) {
                    if (!existing.has("properties")) {
                        existing.put("type", f.type == null ? "object" : f.type);
                        if (f.format != null) {
                            existing.put("format", f.format);
                        }
                    }
                    if (f.description != null && !existing.has("description")) {
                        existing.put("description", f.description);
                    }
                    existing.put("x-confidence", f.confidence);
                    existing.put("x-origin", f.origin);
                } else {
                    if (!existing.has("type")) {
                        existing.put("type", "object");
                    }
                    if (!existing.has("properties")) {
                        existing.putObject("properties");
                    }
                    current = (ObjectNode) existing.get("properties");
                }
            }
        }
        return root;
    }

    // ==================== Markdown ====================

    static String markdown(MapPeek.Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 接口字段文档\n\n");
        sb.append("> 由 MapPeek M0 从源码自动生成。类型为静态推断，置信度 low 的字段请人工核对。\n\n");
        for (MapPeek.ApiModel api : report.apis) {
            sb.append("## ").append(api.httpMethod).append(" `").append(api.path).append("`");
            if (api.summary != null) {
                sb.append(" — ").append(api.summary);
            }
            sb.append("\n\n");
            if (api.description != null) {
                sb.append(api.description).append("\n\n");
            }
            sb.append("来源：`").append(api.file).append(":").append(api.line).append("`\n\n");
            if (api.mapIn) {
                sb.append("### 入参\n\n");
                appendTable(sb, api.requestFields);
            }
            if (api.mapOut) {
                sb.append("### 出参\n\n");
                appendTable(sb, api.responseFields);
            }
        }
        return sb.toString();
    }

    static void appendTable(StringBuilder sb, java.util.Map<String, MapPeek.FieldModel> fieldMap) {
        if (fieldMap.isEmpty()) {
            sb.append("暂未识别到字段。\n\n");
            return;
        }
        sb.append("| 字段 | 类型 | 必填 | 说明 | 置信度 |\n|---|---|---|---|---|\n");
        for (MapPeek.FieldModel f : fieldMap.values()) {
            sb.append("| `").append(f.name).append("` | ").append(typeLabel(f)).append(" | ")
                    .append(f.required ? "是" : "否").append(" | ")
                    .append(f.description == null ? "-" : f.description.replace("|", "\\|")).append(" | ")
                    .append(f.confidence).append(" |\n");
        }
        sb.append('\n');
    }

    static String typeLabel(MapPeek.FieldModel f) {
        if (f.format != null) {
            return f.type + "(" + f.format + ")";
        }
        return f.type == null ? "string" : f.type;
    }

    // ==================== DTO 骨架 ====================

    static final class Node {
        String name;
        String type = "object";
        String format;
        String description;
        String confidence;
        boolean array;
        LinkedHashMap<String, Node> children = new LinkedHashMap<>();
    }

    static void writeDtos(Path outDir, MapPeek.Report report) throws IOException {
        Path dir = outDir.resolve("dto");
        Files.createDirectories(dir);
        Set<String> used = new HashSet<>();
        for (MapPeek.ApiModel api : report.apis) {
            String simple = simpleClassName(api.key);
            String method = capital(methodNameOf(api.key));
            if (api.mapIn) {
                emitDto(dir, used, simple + method + "Request", api, "入参", api.requestFields.values());
            }
            if (api.mapOut) {
                emitDto(dir, used, simple + method + "Response", api, "出参", api.responseFields.values());
            }
        }
    }

    static void emitDto(Path dir, Set<String> used, String baseName, MapPeek.ApiModel api,
                        String roleLabel, Collection<MapPeek.FieldModel> fields) throws IOException {
        String className = baseName;
        int suffix = 2;
        while (!used.add(className)) {
            className = baseName + suffix++;
        }
        Node root = tree(fields);
        StringBuilder sb = new StringBuilder();
        sb.append("package mappeek.generated;\n\n");
        sb.append("import java.math.BigDecimal;\n");
        sb.append("import java.util.List;\n\n");
        sb.append("/**\n");
        sb.append(" * ").append(escapeDoc(api.summary == null ? api.key : api.summary))
                .append(" ").append(roleLabel).append("\n");
        sb.append(" * 来源：").append(escapeDoc(api.key)).append("\n");
        sb.append(" * 自动生成，字段类型为尽力推断，请人工确认\n");
        sb.append(" */\n");
        sb.append("public class ").append(className).append(" {\n\n");
        emitFields(sb, root, "    ");
        sb.append("}\n");
        Files.writeString(dir.resolve(className + ".java"), sb.toString(), StandardCharsets.UTF_8);
    }

    static Node tree(Collection<MapPeek.FieldModel> fields) {
        Node root = new Node();
        root.name = "Root";
        for (MapPeek.FieldModel f : fields) {
            String[] parts = f.name.split("\\.");
            Node current = root;
            for (int i = 0; i < parts.length; i++) {
                String segment = parts[i];
                boolean array = segment.endsWith("[]");
                String name = array ? segment.substring(0, segment.length() - 2) : segment;
                Node child = current.children.computeIfAbsent(name, k -> {
                    Node n = new Node();
                    n.name = k;
                    return n;
                });
                if (array) {
                    child.array = true;
                }
                if (i == parts.length - 1) {
                    child.type = f.type;
                    child.format = f.format;
                    child.description = f.description;
                    child.confidence = f.confidence;
                }
                current = child;
            }
        }
        markObjects(root);
        return root;
    }

    static void markObjects(Node node) {
        for (Node child : node.children.values()) {
            if (!child.children.isEmpty()) {
                child.type = child.array ? "array" : "object";
            }
            markObjects(child);
        }
    }

    static void emitFields(StringBuilder sb, Node parent, String indent) {
        for (Node n : parent.children.values()) {
            if (n.description != null || "low".equals(n.confidence)) {
                sb.append(indent).append("/**\n");
                if (n.description != null) {
                    sb.append(indent).append(" * ").append(escapeDoc(n.description)).append("\n");
                }
                if ("low".equals(n.confidence)) {
                    sb.append(indent).append(" * 类型自动推断失败，待确认\n");
                }
                sb.append(indent).append(" */\n");
            }
            sb.append(indent).append("private ").append(javaType(n)).append(" ")
                    .append(javaName(n.name)).append(";\n\n");
        }
        for (Node n : parent.children.values()) {
            if (n.children.isEmpty()) {
                continue;
            }
            sb.append(indent).append("public static class ").append(capital(javaName(n.name))).append(" {\n\n");
            emitFields(sb, n, indent + "    ");
            sb.append(indent).append("}\n\n");
        }
    }

    static String javaType(Node n) {
        if (!n.children.isEmpty()) {
            return n.array ? "List<" + capital(javaName(n.name)) + ">" : capital(javaName(n.name));
        }
        if (n.array) {
            return "List<Object>";
        }
        if ("low".equals(n.confidence)) {
            return "Object";
        }
        String type = n.type == null ? "string" : n.type;
        switch (type) {
            case "integer":
                return "int64".equals(n.format) ? "Long" : "Integer";
            case "number":
                return "BigDecimal";
            case "boolean":
                return "Boolean";
            case "array":
                return "List<Object>";
            case "string":
                return "String";
            default:
                return "Object";
        }
    }

    // ==================== 工具 ====================

    static String simpleClassName(String apiKey) {
        int hash = apiKey.indexOf('#');
        String left = hash >= 0 ? apiKey.substring(0, hash) : apiKey;
        int dot = left.lastIndexOf('.');
        return dot >= 0 ? left.substring(dot + 1) : left;
    }

    static String methodNameOf(String apiKey) {
        int hash = apiKey.indexOf('#');
        return hash >= 0 ? apiKey.substring(hash + 1) : apiKey;
    }

    static String capital(String s) {
        if (s == null || s.isEmpty()) {
            return "Value";
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String lowerFirst(String s) {
        if (s == null || s.isEmpty()) {
            return "api";
        }
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    static String javaName(String raw) {
        StringBuilder sb = new StringBuilder();
        boolean upperNext = false;
        for (char c : raw.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_') {
                if (upperNext) {
                    sb.append(Character.toUpperCase(c));
                    upperNext = false;
                } else {
                    sb.append(c);
                }
            } else {
                upperNext = true;
            }
        }
        if (sb.length() == 0) {
            return "field";
        }
        if (Character.isDigit(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }

    static String escapeDoc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("*/", "* /").replace('\n', ' ').replace('\r', ' ');
    }

    static String unique(Set<String> used, String candidate) {
        String name = candidate;
        int suffix = 2;
        while (!used.add(name)) {
            name = candidate + suffix++;
        }
        return name;
    }
}

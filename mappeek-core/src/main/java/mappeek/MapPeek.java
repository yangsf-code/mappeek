package mappeek;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * MapPeek M0 可行性验证脚本。
 *
 * 只做两件事：提取 map.put 的字面量 key、提取 MyBatis 查询的列别名。
 * 不做类型推断、不做跨方法数据流、不改任何业务代码。
 *
 * 用法：
 *   java -jar mappeek.jar --src <项目源码根目录> [--mapper <mapper xml 目录>] [--out <输出目录>]
 *   java -jar mappeek.jar --verify <填写后的 mappeek-groundtruth.csv>
 */
public final class MapPeek {

    static final String VERSION = "0.1.0";

    static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping");

    /** 只对白名单里的包装方法做解包，避免把业务方法的参数误当成返回值。 */
    static final Set<String> WRAPPER_CALLS = Set.of(
            "ok", "success", "result", "of", "singletonMap", "unmodifiableMap", "wrap", "build", "response");

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);
        if (a == null) {
            printUsage();
            return;
        }
        if (a.verifyFile != null) {
            new Reporter(a.outDir, Set.of()).verify(a.verifyFile);
            return;
        }
        if (a.srcDir == null || !Files.exists(a.srcDir)) {
            System.out.println("错误: --src 必须是存在的目录或 .java 文件");
            printUsage();
            return;
        }
        Set<String> formats = parseFormats(a.format);
        Report report = new Analyzer(a).run();
        applyOverrides(report, loadOverrides(a.overrides));
        Reporter reporter = new Reporter(a.outDir, formats);
        reporter.writeAll(report);
        report.printConsole(a.outDir);
        printExported(a.outDir, formats);
    }

    static Set<String> parseFormats(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split(",")) {
            String t = part.trim().toLowerCase();
            if (t.equals("all")) {
                out.addAll(List.of("json", "openapi", "md", "dto", "report"));
            } else if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    static void printExported(Path outDir, Set<String> formats) {
        if (formats.contains("json")) {
            System.out.println("  " + outDir.resolve("mappeek-api.json") + "        <- 给前端");
        }
        if (formats.contains("openapi")) {
            System.out.println("  " + outDir.resolve("mappeek-openapi.json") + "    <- 导入 Apifox/YApi/Swagger");
        }
        if (formats.contains("md")) {
            System.out.println("  " + outDir.resolve("mappeek-api.md") + "           <- 接口字段文档");
        }
        if (formats.contains("dto")) {
            System.out.println("  " + outDir.resolve("dto") + "\\                  <- 带注释的 DTO 骨架");
        }
    }

    /** mappeek-edit.csv 里人工填写的内容：字段说明按 接口|方向|字段 定位，标题按接口定位 */
    static final class Overrides {
        final Map<String, String> descriptions = new LinkedHashMap<>();
        final Map<String, String> summaries = new LinkedHashMap<>();
        final Map<String, String> types = new LinkedHashMap<>();

        boolean isEmpty() {
            return descriptions.isEmpty() && summaries.isEmpty() && types.isEmpty();
        }
    }

    static Overrides loadOverrides(Path file) throws IOException {
        Overrides out = new Overrides();
        if (file == null || !Files.isRegularFile(file)) {
            return out;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                line = line.substring(1);
            }
            List<String> cols = Reporter.parseCsvLine(line);
            if (cols.size() < 7) {
                continue;
            }
            String desc = cols.get(6).trim();
            String fieldKey = cols.get(0) + "|" + cols.get(1) + "|" + cols.get(2);
            if (!desc.isEmpty()) {
                out.descriptions.put(fieldKey, desc);
            }
            String type = cols.get(3).trim();
            if (!type.isEmpty()) {
                out.types.put(fieldKey, type);
            }
            if (cols.size() > 7) {
                String summary = cols.get(7).trim();
                if (!summary.isEmpty()) {
                    // 同一接口的标题会重复出现在每行，取第一行非空值（IDE 保存时会把所有行同步成一致）
                    out.summaries.putIfAbsent(cols.get(0), summary);
                }
            }
        }
        return out;
    }

    /** 人工填写的说明与标题优先于静态分析结果 */
    static void applyOverrides(Report report, Overrides overrides) {
        if (overrides.isEmpty()) {
            return;
        }
        for (ApiModel api : report.apis) {
            String summary = overrides.summaries.get(api.key);
            if (summary != null) {
                api.summary = summary;
            }
            api.requestFields.forEach((name, field) -> {
                applyFieldOverrides(overrides, api.key + "|request|" + name, field);
            });
            api.responseFields.forEach((name, field) -> {
                applyFieldOverrides(overrides, api.key + "|response|" + name, field);
            });
        }
    }

    /** 人工填写的说明与类型优先；类型改动时清掉原有 format，避免出现 integer + date-time 这类冲突 */
    static void applyFieldOverrides(Overrides overrides, String key, FieldModel field) {
        String v = overrides.descriptions.get(key);
        if (v != null) {
            field.description = v;
            field.origin = "manual";
        }
        String type = normalizeSchemaType(overrides.types.get(key));
        if (type != null && !type.equals(field.type)) {
            field.type = type;
            field.format = null;
        }
    }

    /** 把 Java / 数据库常见类型归一成 JSON Schema 类型 */
    static String normalizeSchemaType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.contains("[]") || t.contains("list") || t.contains("set")
                || t.contains("collection") || t.contains("array")) {
            return "array";
        }
        if (t.contains("map") || t.contains("object") || t.contains("jsonobject")
                || t.equals("dto") || t.endsWith("dto") || t.endsWith("vo")) {
            return "object";
        }
        if (t.contains("integer") || t.equals("int") || t.equals("long")
                || t.contains("short") || t.contains("biginteger")) {
            return "integer";
        }
        if (t.equals("number") || t.contains("double") || t.contains("float")
                || t.contains("bigdecimal") || t.contains("decimal")) {
            return "number";
        }
        if (t.equals("boolean") || t.contains("bool")) {
            return "boolean";
        }
        return "string";
    }

    static void printUsage() {
        System.out.println("MapPeek v" + VERSION);
        System.out.println();
        System.out.println("用法:");
        System.out.println("  java -jar mappeek.jar --src <源码根目录> [--mapper <mapper目录>] [--out <输出目录>]");
        System.out.println("  java -jar mappeek.jar --verify <填写后的 mappeek-groundtruth.csv> [--out <输出目录>]");
        System.out.println();
        System.out.println("参数:");
        System.out.println("  --src      必填，分析范围：目录或单个 .java 文件");
        System.out.println("  --resolve  可选，解析范围：默认与 --src 相同，建议给模块根目录以保证能跟到 service");
        System.out.println("  --mapper   可选，MyBatis XML 所在目录，默认与 --resolve 相同");
        System.out.println("  --out      可选，输出目录，默认 ./mappeek-out");
        System.out.println("  --only     可选，只处理一个接口，格式 Class#method");
        System.out.println("  --format   可选，导出格式：openapi,md,dto,json,report，默认 openapi,md，all 表示全部");
        System.out.println("  --overrides 可选，人工编辑过的 mappeek-edit.csv，扫描时回填说明与标题");
        System.out.println("  --title-annotations 可选，额外识别为接口标题的注解，逗号分隔，格式 注解名#成员名");
        System.out.println("              例如 com.foo.DocApi#name,com.foo.Doc#value");
        System.out.println("  --level    可选，被扫描项目的 Java 语言级别，支持 8/11/17/21，默认 17");
        System.out.println("  --verify   可选，对比人工填写的 groundtruth，计算召回率与错误率");
    }

    // ==================== 参数 ====================

    static final class Args {
        /** 分析范围：只报告这个目录或文件里的接口 */
        Path srcDir;
        /** 解析范围：方法索引与常量索引的建立范围，默认同 srcDir */
        Path resolveDir;
        Path mapperDir;
        Path outDir = Paths.get("mappeek-out");
        Path verifyFile;
        /** 只处理某个接口，格式 Class#method 或全限定名#method */
        String only;
        /** 额外导出格式：openapi,md,dto,json,report，可逗号组合，all 表示全部 */
        String format = "openapi,md";
        /** 人工编辑后的说明文件，扫描时回填 */
        Path overrides;
        String level = "17";
        /** 额外识别为接口标题的注解，格式 注解名#成员名，例如 com.foo.DocApi#name */
        List<String[]> titleAnnotations = new ArrayList<>();

        static Args parse(String[] argv) {
            Args r = new Args();
            for (int i = 0; i < argv.length; i++) {
                String k = argv[i];
                switch (k) {
                    case "--src" -> r.srcDir = Paths.get(next(argv, ++i, k));
                    case "--resolve" -> r.resolveDir = Paths.get(next(argv, ++i, k));
                    case "--mapper" -> r.mapperDir = Paths.get(next(argv, ++i, k));
                    case "--out" -> r.outDir = Paths.get(next(argv, ++i, k));
                    case "--verify" -> r.verifyFile = Paths.get(next(argv, ++i, k));
                    case "--only" -> r.only = next(argv, ++i, k);
                    case "--format" -> r.format = next(argv, ++i, k);
                    case "--overrides" -> r.overrides = Paths.get(next(argv, ++i, k));
                    case "--title-annotations" -> r.titleAnnotations.addAll(parseTitleAnnotations(next(argv, ++i, k)));
                    case "--level" -> r.level = next(argv, ++i, k);
                    case "-h", "--help" -> {
                        return null;
                    }
                    default -> System.out.println("忽略未知参数: " + k);
                }
            }
            if (r.resolveDir == null) {
                r.resolveDir = r.srcDir;
            }
            if (r.mapperDir == null) {
                r.mapperDir = r.resolveDir;
            }
            return r;
        }

        static List<String[]> parseTitleAnnotations(String raw) {
            List<String[]> out = new ArrayList<>();
            if (raw == null) {
                return out;
            }
            for (String part : raw.split(",")) {
                String t = part.trim();
                if (t.isEmpty()) {
                    continue;
                }
                int i = t.indexOf('#');
                String name = (i >= 0 ? t.substring(0, i) : t).trim();
                String member = (i >= 0 ? t.substring(i + 1) : "value").trim();
                if (!name.isEmpty()) {
                    out.add(new String[]{name, member.isEmpty() ? "value" : member});
                }
            }
            return out;
        }

        static String next(String[] argv, int i, String key) {
            if (i >= argv.length) {
                throw new IllegalArgumentException("参数 " + key + " 缺少取值");
            }
            return argv[i];
        }
    }

    // ==================== 模型 ====================

    static final class FieldVal {
        int line;
        String origin;
        String type;
        String format;
        String description;
        String confidence;
        /** 字段声明所在文件，实体/DTO 展开时使用 */
        String file;
        /** 来自 @NotNull/@NotBlank 或 @Schema(required) 等 */
        boolean required;

        FieldVal(int line, String origin) {
            this(line, origin, "string", null, null, "low");
        }

        FieldVal(int line, String origin, String type, String format, String description, String confidence) {
            this.line = line;
            this.origin = origin;
            this.type = type;
            this.format = format;
            this.description = description;
            this.confidence = confidence;
        }
    }

    static final class FieldModel {
        String name;
        String origin;
        String file;
        String via;
        int line;
        String type;
        String format;
        String description;
        String confidence;
        boolean required;
    }

    static final class ApiModel {
        String key;
        String httpMethod;
        String path;
        String role;
        String summary;
        /** 接口长描述：@Operation(description) / @ApiOperation(notes) / JavaDoc 正文 */
        String description;
        String tag;
        /** 分组描述：@Tag(description) / @Api(value) */
        String tagDescription;
        String file;
        int line;
        boolean mapIn;
        boolean mapOut;
        LinkedHashMap<String, FieldModel> fields = new LinkedHashMap<>();
        /** 入参字段：来自对 Map 参数的 get/containsKey 读取 */
        LinkedHashMap<String, FieldModel> requestFields = new LinkedHashMap<>();
        /** 出参字段：来自方法内构建的 Map */
        LinkedHashMap<String, FieldModel> responseFields = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
    }

    static final class MapperStmt {
        String namespace;
        String id;
        String file;
        List<String> fields = new ArrayList<>();
        boolean unknown;
        String reason;
        boolean attributed;
    }

    static final class MethodRef {
        MethodDeclaration md;
        Path file;
        String className;

        String key() {
            return file + "#" + className + "#" + md.getNameAsString() + "#" + md.getRange().map(r -> r.begin.line).orElse(0);
        }
    }

    static final class Report {
        int javaFiles;
        int resolvedFiles;
        int parseFailures;
        int controllers;
        int mappingMethods;
        int mapApis;
        int mapInApis;
        int mapOutApis;
        List<ApiModel> apis = new ArrayList<>();
        List<MapperStmt> mappers = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        int unattributedMethods;
        int unattributedKeys;
        List<String> unattributedSamples = new ArrayList<>();
        int distinctFields;

        void printConsole(Path outDir) {
            System.out.println();
            System.out.println("==== MapPeek 扫描结果 ====");
            System.out.printf("Java 文件(分析范围): %d（解析范围 %d，解析失败 %d）%n",
                    javaFiles, resolvedFiles, parseFailures);
            System.out.printf("Controller         : %d（映射方法 %d）%n", controllers, mappingMethods);
            System.out.printf("可导出字段的接口    : %d（入参 %d / 出参 %d）%n", mapApis, mapInApis, mapOutApis);
            System.out.printf("自动识别字段        : %d（去重后 %d）%n", countFields(), distinctFields);
            System.out.printf("未归属字段          : %d（来自 %d 个方法）%n", unattributedKeys, unattributedMethods);
            System.out.printf("MyBatis 语句        : %d%n", mappers.size());
            System.out.println();
            System.out.println("可编辑字段清单: " + outDir.resolve("mappeek-edit.csv"));
        }

        int countFields() {
            int n = 0;
            for (ApiModel a : apis) {
                n += a.fields.size();
            }
            return n;
        }
    }

    // ==================== 分析器 ====================

    static final class Analyzer {

        record CommentInfo(int startLine, int endLine, int column, String text) {
        }

        record TypeInfo(String type, String format, String confidence) {
        }

        record KeyInfo(String key, String doc) {
        }

        record ClassRef(String name, ClassOrInterfaceDeclaration cls, RecordDeclaration rec, Path file) {
            ClassRef(String name, ClassOrInterfaceDeclaration cls, Path file) {
                this(name, cls, null, file);
            }
        }

        final Args args;

        final Map<Path, CompilationUnit> units = new LinkedHashMap<>();
        final Map<String, MethodRef> methodsByKey = new LinkedHashMap<>();
        final Map<String, List<MethodRef>> methodIndex = new HashMap<>();
        final Map<String, ClassRef> classIndex = new HashMap<>();
        final Map<String, String> constBySimple = new HashMap<>();
        final Map<String, String> constByQualified = new HashMap<>();
        final Map<String, String> constDocBySimple = new HashMap<>();
        final Map<String, String> constDocByQualified = new HashMap<>();
        final Map<Path, List<CommentInfo>> commentsByFile = new HashMap<>();
        final Set<String> ambiguousConsts = new HashSet<>();
        final Set<String> consumedMethods = new HashSet<>();

        int javaFiles;
        int resolvedFiles;
        int parseFailures;
        int controllers;
        int mappingMethods;
        int mapApis;
        int mapInApis;
        int mapOutApis;
        List<ApiModel> apis = new ArrayList<>();
        List<MapperStmt> mappers = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        Analyzer(Args args) {
            this.args = args;
        }

        static ParserConfiguration.LanguageLevel resolveLevel(String level) {
            try {
                return ParserConfiguration.LanguageLevel.valueOf("JAVA_" + level);
            } catch (IllegalArgumentException e) {
                System.out.println("警告: 不支持的语言级别 " + level + "，回退到 17");
                return ParserConfiguration.LanguageLevel.JAVA_17;
            }
        }

        Report run() throws IOException {
            StaticJavaParser.getConfiguration()
                    .setLanguageLevel(resolveLevel(args.level));
            collectAndParse();
            indexConstants();
            indexMethods();
            scanMapperXml();
            scanApis();
            Report r = new Report();
            r.javaFiles = javaFiles;
            r.resolvedFiles = resolvedFiles;
            r.parseFailures = parseFailures;
            r.controllers = controllers;
            r.mappingMethods = mappingMethods;
            r.mapApis = mapApis;
            r.mapInApis = mapInApis;
            r.mapOutApis = mapOutApis;
            r.apis = apis;
            r.mappers = mappers;
            r.diagnostics = diagnostics;
            r.distinctFields = (int) apis.stream()
                    .flatMap(a -> a.fields.keySet().stream())
                    .distinct()
                    .count();
            collectUnattributed(r);
            return r;
        }

        // ---------- 文件收集与解析 ----------

        void collectAndParse() throws IOException {
            List<Path> files;
            try (Stream<Path> s = Files.walk(args.resolveDir)) {
                files = s.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !isIgnored(p))
                        .sorted()
                        .toList();
            }
            resolvedFiles = files.size();
            javaFiles = (int) files.stream().filter(this::inScope).count();
            for (Path p : files) {
                try {
                    CompilationUnit cu = StaticJavaParser.parse(p);
                    units.put(p, cu);
                    collectComments(p, cu);
                } catch (RuntimeException e) {
                    parseFailures++;
                    diagnostics.add("解析失败: " + rel(p) + " -> " + brief(e));
                }
            }
        }

        /** 预先收集注释，按行号索引，便于取 put 上方或行尾的说明 */
        void collectComments(Path p, CompilationUnit cu) {
            List<CommentInfo> list = new ArrayList<>();
            for (Comment c : cu.getAllContainedComments()) {
                c.getRange().ifPresent(r -> {
                    String text = cleanComment(c.getContent());
                    if (text != null) {
                        list.add(new CommentInfo(r.begin.line, r.end.line, r.begin.column, text));
                    }
                });
            }
            commentsByFile.put(p, list);
        }

        static String cleanComment(String raw) {
            if (raw == null) {
                return null;
            }
            String s = raw.replaceAll("\\s+", " ").trim();
            s = s.replaceAll("^[*\\s]+", "");
            s = s.replaceAll("<[^>]+>", "");
            s = s.replaceAll("\\{@\\w+\\s+([^}]*)}", "$1");
            s = s.trim();
            if (s.isEmpty()) {
                return null;
            }
            return s.length() > 120 ? s.substring(0, 120) + "…" : s;
        }

        /** 取某个位置最近的说明：上一行整行的注释优先，其次是同行行尾注释 */
        String descriptionNear(Path file, int line, int column) {
            List<CommentInfo> list = commentsByFile.get(file);
            if (list == null) {
                return null;
            }
            String above = null;
            String trailing = null;
            for (CommentInfo c : list) {
                if (c.endLine() == line - 1 && above == null) {
                    above = c.text();
                } else if (c.startLine() == line && c.column() > column && trailing == null) {
                    trailing = c.text();
                }
            }
            return above != null ? above : trailing;
        }

        /** 是否落在分析范围内：--src 是目录时按前缀，是文件时要求完全相等 */
        boolean inScope(Path p) {
            Path scope = args.srcDir.toAbsolutePath().normalize();
            Path q = p.toAbsolutePath().normalize();
            if (Files.isDirectory(args.srcDir)) {
                return q.startsWith(scope);
            }
            return q.equals(scope);
        }

        /** 只处理一个接口时用于过滤，支持 Class#method 与全限定名 */
        boolean matchesOnly(String apiKey) {
            String only = args.only;
            if (only == null || only.isBlank()) {
                return true;
            }
            if (apiKey.equals(only)) {
                return true;
            }
            int i = only.indexOf('#');
            if (i < 0) {
                return apiKey.endsWith("#" + only);
            }
            String cls = only.substring(0, i);
            String method = only.substring(i + 1);
            return apiKey.equals(cls + "#" + method) || apiKey.endsWith("." + cls + "#" + method);
        }

        void indexConstants() {
            for (Map.Entry<Path, CompilationUnit> e : units.entrySet()) {
                for (FieldDeclaration fd : e.getValue().findAll(FieldDeclaration.class)) {
                    boolean inInterface = fd.findAncestor(ClassOrInterfaceDeclaration.class)
                            .map(ClassOrInterfaceDeclaration::isInterface)
                            .orElse(false);
                    if (!inInterface && (!fd.isStatic() || !fd.isFinal())) {
                        continue;
                    }
                    String cls = fd.findAncestor(ClassOrInterfaceDeclaration.class)
                            .map(ClassOrInterfaceDeclaration::getNameAsString)
                            .orElse("");
                    for (VariableDeclarator vd : fd.getVariables()) {
                        Expression init = vd.getInitializer().orElse(null);
                        if (!(init instanceof StringLiteralExpr lit)) {
                            continue;
                        }
                        String name = vd.getNameAsString();
                        constByQualified.put(cls + "." + name, lit.asString());
                        String prev = constBySimple.putIfAbsent(name, lit.asString());
                        if (prev != null && !prev.equals(lit.asString())) {
                            ambiguousConsts.add(name);
                        }
                        String doc = fd.getJavadocComment().map(c -> cleanComment(c.getContent())).orElse(null);
                        if (doc != null) {
                            constDocByQualified.put(cls + "." + name, doc);
                            constDocBySimple.putIfAbsent(name, doc);
                        }
                    }
                }
            }
        }

        void indexMethods() {
            for (Map.Entry<Path, CompilationUnit> e : units.entrySet()) {
                String pkg = e.getValue().getPackageDeclaration()
                        .map(p -> p.getNameAsString())
                        .orElse("");
                for (ClassOrInterfaceDeclaration cls : e.getValue().findAll(ClassOrInterfaceDeclaration.class)) {
                    String className = cls.getNameAsString();
                    String fqn = pkg.isEmpty() ? className : pkg + "." + className;
                    for (MethodDeclaration md : cls.getMethods()) {
                        MethodRef ref = new MethodRef();
                        ref.md = md;
                        ref.file = e.getKey();
                        ref.className = fqn;
                        methodsByKey.put(ref.key(), ref);
                        String idx = md.getNameAsString() + "#" + md.getParameters().size();
                        methodIndex.computeIfAbsent(idx, k -> new ArrayList<>()).add(ref);
                    }
                    classIndex.putIfAbsent(className, new ClassRef(className, cls, e.getKey()));
                }
                for (RecordDeclaration rec : e.getValue().findAll(RecordDeclaration.class)) {
                    classIndex.putIfAbsent(rec.getNameAsString(),
                            new ClassRef(rec.getNameAsString(), null, rec, e.getKey()));
                }
            }
        }

        // ---------- MyBatis ----------

        void scanMapperXml() {
            List<Path> xmls;
            try (Stream<Path> s = Files.walk(args.mapperDir)) {
                xmls = s.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".xml"))
                        .filter(p -> !isIgnored(p))
                        .sorted()
                        .toList();
            } catch (IOException e) {
                diagnostics.add("mapper 目录遍历失败: " + brief(e));
                return;
            }
            for (Path p : xmls) {
                Document doc = parseXml(p);
                if (doc == null) {
                    continue;
                }
                Element root = doc.getDocumentElement();
                if (root == null || !"mapper".equals(root.getTagName())) {
                    continue;
                }
                String ns = root.getAttribute("namespace");
                Map<String, List<String>> resultMaps = new HashMap<>();
                NodeList rmNodes = doc.getElementsByTagName("resultMap");
                for (int i = 0; i < rmNodes.getLength(); i++) {
                    Element rm = (Element) rmNodes.item(i);
                    List<String> cols = new ArrayList<>();
                    NodeList children = rm.getChildNodes();
                    for (int j = 0; j < children.getLength(); j++) {
                        if (children.item(j) instanceof Element c && "result".equals(c.getTagName())) {
                            String prop = c.getAttribute("property");
                            if (!prop.isEmpty()) {
                                cols.add(prop);
                            }
                        }
                    }
                    resultMaps.put(rm.getAttribute("id"), cols);
                }
                NodeList selects = doc.getElementsByTagName("select");
                for (int i = 0; i < selects.getLength(); i++) {
                    Element el = (Element) selects.item(i);
                    String id = el.getAttribute("id");
                    String resultType = el.getAttribute("resultType");
                    String resultMap = el.getAttribute("resultMap");
                    if (id.isEmpty()) {
                        continue;
                    }
                    boolean mapish = resultType.toLowerCase().contains("map")
                            || (!resultMap.isEmpty() && resultMaps.containsKey(shortId(resultMap)));
                    if (!mapish) {
                        continue;
                    }
                    MapperStmt st = new MapperStmt();
                    st.namespace = ns;
                    st.id = id;
                    st.file = rel(p);
                    SqlFields sf = extractAliases(el.getTextContent());
                    st.fields = sf.names;
                    st.unknown = sf.unknown;
                    st.reason = sf.reason;
                    if (st.fields.isEmpty() && !resultMap.isEmpty()) {
                        st.fields = resultMaps.getOrDefault(shortId(resultMap), List.of());
                    }
                    st.attributed = false;
                    mappers.add(st);
                }
                // 注解 @Select 在 scanApis 阶段通过方法索引补充
            }
            scanAnnotatedSelects();
        }

        void scanAnnotatedSelects() {
            for (MethodRef ref : methodsByKey.values()) {
                AnnotationExpr ann = annotation(ref.md, "Select");
                if (ann == null) {
                    continue;
                }
                String type = ref.md.getTypeAsString();
                if (!isMapType(type)) {
                    continue;
                }
                String sql = annotationStringValue(ann);
                if (sql == null) {
                    continue;
                }
                MapperStmt st = new MapperStmt();
                st.namespace = ref.className;
                st.id = ref.md.getNameAsString();
                st.file = rel(ref.file);
                SqlFields sf = extractAliases(sql);
                st.fields = sf.names;
                st.unknown = sf.unknown;
                st.reason = sf.reason;
                mappers.add(st);
            }
        }

        Document parseXml(Path p) {
            try {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setFeature("http://xml.org/sax/features/external-general-entities", false);
                f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                f.setXIncludeAware(false);
                f.setExpandEntityReferences(false);
                return f.newDocumentBuilder().parse(p.toFile());
            } catch (Exception e) {
                diagnostics.add("XML 解析失败: " + rel(p) + " -> " + brief(e));
                return null;
            }
        }

        // ---------- Controller 扫描 ----------

        void scanApis() {
            for (Map.Entry<Path, CompilationUnit> e : units.entrySet()) {
                Path file = e.getKey();
                if (!inScope(file)) {
                    continue;
                }
                String pkg = e.getValue().getPackageDeclaration()
                        .map(p -> p.getNameAsString())
                        .orElse("");
                for (ClassOrInterfaceDeclaration cls : e.getValue().findAll(ClassOrInterfaceDeclaration.class)) {
                    if (!isController(cls)) {
                        continue;
                    }
                    controllers++;
                    String fqn = pkg.isEmpty() ? cls.getNameAsString() : pkg + "." + cls.getNameAsString();
                    String basePath = classBasePath(cls);
                    for (MethodDeclaration md : cls.getMethods()) {
                        AnnotationExpr mapping = mappingAnnotation(md);
                        if (mapping == null) {
                            continue;
                        }
                        mappingMethods++;
                        boolean pojoIn = md.getParameters().stream()
                                .anyMatch(p -> isPojoType(p.getTypeAsString()));
                        boolean bodyIn = md.getParameters().stream()
                                .anyMatch(p -> parsedBodyType(md, p) != null);
                        boolean pojoOut = pojoReturn(md.getTypeAsString()) != null;
                        boolean mapIn = md.getParameters().stream()
                                .anyMatch(p -> isMapType(p.getTypeAsString())) || pojoIn || bodyIn;
                        boolean mapOut = isMapType(md.getTypeAsString()) || pojoOut;
                        if (!mapIn && !mapOut) {
                            continue;
                        }
                        String apiKey = fqn + "#" + md.getNameAsString();
                        if (!matchesOnly(apiKey)) {
                            continue;
                        }
                        mapApis++;
                        if (mapIn) {
                            mapInApis++;
                        }
                        if (mapOut) {
                            mapOutApis++;
                        }

                        ApiModel api = new ApiModel();
                        api.key = apiKey;
                        api.httpMethod = mapping.getNameAsString().replace("Mapping", "").toUpperCase();
                        api.path = joinPath(basePath, methodPath(mapping));
                        api.file = rel(file);
                        api.line = md.getBegin().map(pos -> pos.line).orElse(0);
                        api.mapIn = mapIn;
                        api.mapOut = mapOut;
                        api.role = mapIn && mapOut ? "both" : (mapIn ? "requestBody" : "responseBody");
                        api.summary = methodSummary(md);
                        api.description = methodDescription(md);
                        api.tag = classTag(cls);
                        api.tagDescription = classTagDescription(cls);

                        consumedMethods.add(methodRefOf(md, file).key());
                        mergeFields(api, extractFields(md, file), api.file, "direct", "put");

                        List<MethodRef> callees = callees(md, file);
                        for (MethodRef ref : callees) {
                            consumedMethods.add(ref.key());
                            mergeFields(api, extractFields(ref.md, ref.file), rel(ref.file),
                                    "via:" + ref.md.getNameAsString(), "put");
                        }

                        attributeMappers(api, md, callees);
                        buildDirectionalFields(api, md, callees, file);
                        expandPojoFields(api, md, file);
                        apis.add(api);
                    }
                }
            }
        }

        MethodRef methodRefOf(MethodDeclaration md, Path file) {
            for (MethodRef r : methodsByKey.values()) {
                if (r.md == md) {
                    return r;
                }
            }
            MethodRef r = new MethodRef();
            r.md = md;
            r.file = file;
            r.className = "";
            return r;
        }

        List<MethodRef> callees(MethodDeclaration md, Path file) {
            LinkedHashMap<String, MethodRef> found = new LinkedHashMap<>();
            if (md.getBody().isEmpty()) {
                return List.of();
            }
            for (MethodCallExpr mc : md.getBody().get().findAll(MethodCallExpr.class)) {
                String key = mc.getNameAsString() + "#" + mc.getArguments().size();
                List<MethodRef> cands = methodIndex.get(key);
                if (cands == null || cands.size() != 1) {
                    continue;
                }
                MethodRef ref = cands.get(0);
                if (ref.md == md) {
                    continue;
                }
                found.putIfAbsent(ref.key(), ref);
            }
            return new ArrayList<>(found.values());
        }

        void attributeMappers(ApiModel api, MethodDeclaration md, List<MethodRef> callees) {
            String ref = annotationStringOnMethodOrParams(md, "MapSchemaRef");
            List<MapperStmt> hits = new ArrayList<>();
            if (ref != null) {
                for (MapperStmt st : mappers) {
                    if (ref.equals(st.id) || ref.equals(st.namespace + "#" + st.id)) {
                        hits.add(st);
                    }
                }
            } else {
                Set<String> names = new LinkedHashSet<>();
                if (md.getBody().isPresent()) {
                    for (MethodCallExpr mc : md.getBody().get().findAll(MethodCallExpr.class)) {
                        names.add(mc.getNameAsString());
                    }
                }
                for (MethodRef ref2 : callees) {
                    if (ref2.md.getBody().isPresent()) {
                        for (MethodCallExpr mc : ref2.md.getBody().get().findAll(MethodCallExpr.class)) {
                            names.add(mc.getNameAsString());
                        }
                    }
                }
                for (MapperStmt st : mappers) {
                    if (names.contains(st.id)) {
                        hits.add(st);
                    }
                }
            }
            for (MapperStmt st : hits) {
                st.attributed = true;
                Map<String, FieldVal> fields = new LinkedHashMap<>();
                for (String f : st.fields) {
                    fields.putIfAbsent(f, new FieldVal(0, "sql", "string", null, null, "medium"));
                }
                mergeFields(api, fields, st.file, "sql:" + st.id, "sql");
                if (st.unknown) {
                    api.notes.add("SQL " + st.id + " 存在无法解析的部分（" + st.reason + "），字段可能不全");
                }
            }
        }

        // ---------- 入参 / 出参拆分 ----------

        /**
         * 出参 = 方法内构建的 Map；入参 = 对 Map 参数的 get/containsKey 读取到的 key。
         * 两者分开，避免把出参字段误标成入参。
         */
        void buildDirectionalFields(ApiModel api, MethodDeclaration md, List<MethodRef> callees, Path file) {
            if (api.mapOut) {
                api.responseFields.putAll(api.fields);
            }
            if (api.mapIn) {
                Set<String> paramNames = new LinkedHashSet<>();
                for (Parameter p : md.getParameters()) {
                    if (isMapType(p.getTypeAsString())) {
                        paramNames.add(p.getNameAsString());
                    }
                }
                if (!paramNames.isEmpty()) {
                    collectReadKeys(md, paramNames, file, api.requestFields);
                    for (MethodRef ref : callees) {
                        collectReadKeys(ref.md, paramNames, ref.file, api.requestFields);
                    }
                }
                if (api.requestFields.isEmpty() && !api.mapOut) {
                    api.requestFields.putAll(api.fields);
                }
            }
        }

        void collectReadKeys(MethodDeclaration md, Set<String> paramNames, Path file,
                             Map<String, FieldModel> out) {
            if (md.getBody().isEmpty()) {
                return;
            }
            for (MethodCallExpr mc : md.getBody().get().findAll(MethodCallExpr.class)) {
                String name = mc.getNameAsString();
                if (!(name.equals("get") || name.equals("getOrDefault")
                        || name.equals("containsKey") || name.equals("remove"))) {
                    continue;
                }
                String scope = mc.getScope()
                        .map(expr -> expr instanceof NameExpr ne ? ne.getNameAsString() : null)
                        .orElse(null);
                if (scope == null || !paramNames.contains(scope) || mc.getArguments().isEmpty()) {
                    continue;
                }
                KeyInfo info = keyInfo(mc.getArgument(0));
                if (info == null || out.containsKey(info.key())) {
                    continue;
                }
                int line = lineOf(mc);
                String doc = descriptionNear(file, line, mc.getBegin().map(p -> p.column).orElse(0));
                FieldModel f = new FieldModel();
                f.name = info.key();
                f.type = "string";
                f.description = doc != null ? doc : info.doc();
                f.confidence = "low";
                f.origin = "read";
                f.file = rel(file);
                f.line = line;
                f.via = "read";
                out.put(f.name, f);
            }
        }

        // ---------- 实体 / DTO / VO 入参与出参 ----------

        /** 是否是源码范围内可展开的实体类 */
        boolean isPojoType(String type) {
            if (type == null || type.isBlank() || isMapType(type) || isListType(type)) {
                return false;
            }
            String simple = simpleType(type);
            return !simple.isEmpty() && classIndex.containsKey(simple);
        }

        /** 出参实体：支持直接返回、List<VO>、ResponseEntity/Optional 包装，以及源码外的泛型包装如 R<LoginVo> */
        String pojoReturn(String type) {
            if (type == null || type.isBlank()) {
                return null;
            }
            String simple = simpleType(type);
            if ("ResponseEntity".equals(simple) || "HttpEntity".equals(simple)
                    || "Optional".equals(simple) || "Callable".equals(simple)) {
                String inner = genericInner(type);
                return inner == null ? null : pojoReturn(inner);
            }
            if (isPojoType(type)) {
                return simple;
            }
            if (isListType(type)) {
                String inner = genericInner(type);
                if (inner != null && isPojoType(inner)) {
                    return simpleType(inner);
                }
            }
            String inner = genericInner(type);
            if (inner != null) {
                String nested = isPojoType(inner) ? simpleType(inner) : pojoReturn(inner);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        /** 参数是 String / Object，方法体里再转成 DTO 的常见写法 */
        String parsedBodyType(MethodDeclaration md, Parameter p) {
            String paramType = p.getTypeAsString();
            if (!("String".equals(paramType) || "Object".equals(paramType)
                    || "CharSequence".equals(paramType))) {
                return null;
            }
            if (md.getBody().isEmpty()) {
                return null;
            }
            String name = p.getNameAsString();
            for (MethodCallExpr mc : md.getBody().get().findAll(MethodCallExpr.class)) {
                if (!PARSE_METHODS.contains(mc.getNameAsString())) {
                    continue;
                }
                // 兼容 JSON.parseObject(body).toJavaObject(X.class) 这类链式调用
                if (!mc.toString().contains(name)) {
                    continue;
                }
                String type = classArgument(mc);
                if (type != null && isPojoType(type)) {
                    return type;
                }
            }
            return null;
        }

        static final Set<String> PARSE_METHODS = Set.of("parseObject", "parse", "readValue",
                "toBean", "toJavaObject", "convertValue", "fromJson");

        /** 从调用的参数里找 Xxx.class 或 TypeReference&lt;Xxx&gt; */
        static String classArgument(MethodCallExpr mc) {
            for (Expression arg : mc.getArguments()) {
                if (arg instanceof ClassExpr ce) {
                    return ce.getTypeAsString();
                }
                if (arg instanceof ObjectCreationExpr oce) {
                    String t = oce.getTypeAsString();
                    if (t.contains("TypeReference")) {
                        String inner = genericInner(t);
                        if (inner != null) {
                            return simpleType(inner);
                        }
                    }
                }
            }
            return null;
        }

        /** 实体入参按字段展开，实体出参同理；Map 已识别的字段优先 */
        void expandPojoFields(ApiModel api, MethodDeclaration md, Path file) {
            for (Parameter p : md.getParameters()) {
                String pojoParam = isPojoType(p.getTypeAsString())
                        ? p.getTypeAsString() : parsedBodyType(md, p);
                if (pojoParam == null) {
                    continue;
                }
                Map<String, FieldVal> vals = new LinkedHashMap<>();
                expandObject(simpleType(pojoParam), "", vals, 0, new HashSet<>(),
                        bindTypeVars(pojoParam));
                mergePojo(api.requestFields, vals, file, "param");
            }
            String pojo = pojoReturn(md.getTypeAsString());
            if (pojo != null) {
                Map<String, FieldVal> vals = new LinkedHashMap<>();
                expandObject(pojo, "", vals, 0, new HashSet<>(),
                        bindTypeVars(md.getTypeAsString()));
                mergePojo(api.responseFields, vals, file, "return");
            }
        }

        void mergePojo(Map<String, FieldModel> out, Map<String, FieldVal> vals, Path file, String via) {
            for (Map.Entry<String, FieldVal> e : vals.entrySet()) {
                String name = e.getKey().startsWith(".") ? e.getKey().substring(1) : e.getKey();
                if (name.isEmpty() || out.containsKey(name)) {
                    continue;
                }
                FieldVal v = e.getValue();
                FieldModel f = new FieldModel();
                f.name = name;
                f.origin = "pojo";
                f.type = v.type;
                f.format = v.format;
                f.description = v.description;
                f.confidence = v.confidence;
                f.required = v.required;
                f.file = v.file == null ? rel(file) : v.file;
                f.line = v.line;
                f.via = via;
                out.put(name, f);
            }
        }

        // ---------- 字段提取 ----------

        Map<String, FieldVal> extractFields(MethodDeclaration md, Path file) {
            LinkedHashMap<String, FieldVal> result = new LinkedHashMap<>();
            if (md.getBody().isEmpty()) {
                return result;
            }
            BlockStmt body = md.getBody().get();
            Map<String, LinkedHashMap<String, FieldVal>> vars = new LinkedHashMap<>();
            Map<String, LinkedHashMap<String, FieldVal>> listElems = new LinkedHashMap<>();
            Map<String, String> objectVars = new LinkedHashMap<>();
            Set<String> dynamicVars = new LinkedHashSet<>();

            body.walk(n -> {
                if (n instanceof VariableDeclarator vd) {
                    String type = vd.getTypeAsString();
                    Expression init = vd.getInitializer().orElse(null);
                    if (isListType(type)) {
                        listElems.putIfAbsent(vd.getNameAsString(), new LinkedHashMap<>());
                    } else if (isMapCreation(init) || isMapType(type)) {
                        vars.putIfAbsent(vd.getNameAsString(), new LinkedHashMap<>());
                        if (init instanceof MethodCallExpr mc && isMapOfCall(mc)) {
                            vars.get(vd.getNameAsString()).putAll(mapOfFields(mc, file));
                        }
                    } else {
                        String simple = simpleType(type);
                        if (classIndex.containsKey(simple)) {
                            objectVars.putIfAbsent(vd.getNameAsString(), simple);
                        }
                    }
                } else if (n instanceof MethodCallExpr mc) {
                    String name = mc.getNameAsString();
                    if (name.equals("put") || name.equals("putIfAbsent")
                            || name.equals("replace") || name.equals("computeIfAbsent")) {
                        handlePut(mc, vars, dynamicVars, file, listElems, objectVars);
                    } else if (name.equals("putAll")) {
                        handlePutAll(mc, vars);
                    } else if (name.equals("add") || name.equals("addAll")) {
                        handleListAdd(mc, vars, listElems, file);
                    }
                }
            });

            String outVar = resolveOutputVar(md, body);
            if (outVar != null && vars.containsKey(outVar)) {
                result.putAll(vars.get(outVar));
            } else {
                // 找不到返回变量，退化为方法内全部字段的并集，交给报告标记
                for (LinkedHashMap<String, FieldVal> v : vars.values()) {
                    result.putAll(v);
                }
            }
            return result;
        }

        void handlePut(MethodCallExpr mc, Map<String, LinkedHashMap<String, FieldVal>> vars,
                       Set<String> dynamicVars, Path file,
                       Map<String, LinkedHashMap<String, FieldVal>> listElems,
                       Map<String, String> objectVars) {
            String var = scopeName(mc);
            if (var == null || mc.getArguments().isEmpty()) {
                return;
            }
            KeyInfo keyInfo = keyInfo(mc.getArgument(0));
            if (keyInfo == null) {
                dynamicVars.add(var);
                return;
            }
            String key = keyInfo.key();
            LinkedHashMap<String, FieldVal> target =
                    vars.computeIfAbsent(var, k -> new LinkedHashMap<>());
            int line = lineOf(mc);
            int column = mc.getBegin().map(p -> p.column).orElse(0);
            String doc = descriptionNear(file, line, column);
            if (doc == null) {
                doc = keyInfo.doc();
            }
            if (mc.getArguments().size() < 2) {
                target.putIfAbsent(key, new FieldVal(line, "put", "string", null, doc, "low"));
                return;
            }
            Expression val = mc.getArgument(1);
            LinkedHashMap<String, FieldVal> nested = new LinkedHashMap<>();
            if (val instanceof NameExpr ne && vars.containsKey(ne.getNameAsString())) {
                nested.putAll(vars.get(ne.getNameAsString()));
            } else if (val instanceof MethodCallExpr vmc && isMapOfCall(vmc)) {
                nested.putAll(mapOfFields(vmc, file));
            }
            if (!nested.isEmpty()) {
                for (Map.Entry<String, FieldVal> e : nested.entrySet()) {
                    target.putIfAbsent(key + "." + e.getKey(), copy(e.getValue()));
                }
                return;
            }
            // List 值：把元素 Map 的字段挂到 items[] 下
            LinkedHashMap<String, FieldVal> elems = null;
            if (val instanceof NameExpr ne && listElems.containsKey(ne.getNameAsString())) {
                elems = listElems.get(ne.getNameAsString());
            } else if (val instanceof MethodCallExpr vmc && isListOfCall(vmc)) {
                elems = new LinkedHashMap<>();
                for (Expression arg : vmc.getArguments()) {
                    if (arg instanceof MethodCallExpr inner && isMapOfCall(inner)) {
                        elems.putAll(mapOfFields(inner, file));
                        break;
                    }
                }
            }
            if (elems != null) {
                String arrayPath = key + "[]";
                target.putIfAbsent(arrayPath, new FieldVal(line, "put", "array", null, doc, "medium"));
                for (Map.Entry<String, FieldVal> e : elems.entrySet()) {
                    target.putIfAbsent(arrayPath + "." + e.getKey(), copy(e.getValue()));
                }
                return;
            }
            // 对象值：展开类字段，形成多级结构
            if (val instanceof NameExpr ne && objectVars.containsKey(ne.getNameAsString())) {
                target.putIfAbsent(key, new FieldVal(line, "put", "object", null, doc, "medium"));
                LinkedHashMap<String, FieldVal> expanded = new LinkedHashMap<>();
                expandObject(objectVars.get(ne.getNameAsString()), key, expanded, 0, new HashSet<>());
                target.putAll(expanded);
                return;
            }
            TypeInfo type = inferType(val);
            target.putIfAbsent(key, new FieldVal(line, "put", type.type(), type.format(), doc, type.confidence()));
        }

        static FieldVal copy(FieldVal v) {
            return new FieldVal(v.line, v.origin, v.type, v.format, v.description, v.confidence);
        }

        void handleListAdd(MethodCallExpr mc, Map<String, LinkedHashMap<String, FieldVal>> vars,
                           Map<String, LinkedHashMap<String, FieldVal>> listElems, Path file) {
            String list = scopeName(mc);
            if (list == null || !listElems.containsKey(list) || mc.getArguments().isEmpty()) {
                return;
            }
            Expression arg = mc.getArgument(0);
            LinkedHashMap<String, FieldVal> elems = new LinkedHashMap<>();
            if (arg instanceof NameExpr ne && vars.containsKey(ne.getNameAsString())) {
                elems.putAll(vars.get(ne.getNameAsString()));
            } else if (arg instanceof NameExpr ne && listElems.containsKey(ne.getNameAsString())) {
                elems.putAll(listElems.get(ne.getNameAsString()));
            } else if (arg instanceof MethodCallExpr inner && isMapOfCall(inner)) {
                elems.putAll(mapOfFields(inner, file));
            }
            listElems.get(list).putAll(elems);
        }

        /** 递归展开普通 Java 对象（含 record）的字段，最多三层，遇到环就停 */
        void expandObject(String typeName, String prefix, Map<String, FieldVal> out,
                          int depth, Set<String> visited) {
            expandObject(typeName, prefix, out, depth, visited, Collections.emptyMap());
        }

        void expandObject(String typeName, String prefix, Map<String, FieldVal> out,
                          int depth, Set<String> visited, Map<String, String> typeVars) {
            if (depth > 3 || !visited.add(typeName)) {
                return;
            }
            ClassRef ref = classIndex.get(typeName);
            if (ref == null) {
                return;
            }
            if (ref.cls != null) {
                for (FieldDeclaration fd : ref.cls.getFields()) {
                    if (fd.isStatic() || annotationIn(fd.getAnnotations(), "JsonIgnore") != null) {
                        continue;
                    }
                    MethodDeclaration getter = fd.getVariables().isEmpty()
                            ? null : getterOf(ref.cls, fd.getVariables().get(0).getNameAsString());
                    for (VariableDeclarator vd : fd.getVariables()) {
                        List<AnnotationExpr> anns = mergedAnnotations(fd, getter);
                        String fieldName = aliasFrom(anns, vd.getNameAsString());
                        String doc = descriptionFrom(anns,
                                fd.getJavadocComment().map(c -> cleanComment(c.getContent())).orElse(null),
                                ref.file, lineOf(vd), vd.getBegin().map(p -> p.column).orElse(0));
                        addExpandedField(fieldName, resolveTypeVariable(vd.getTypeAsString(), typeVars),
                                doc, requiredFrom(anns), lineOf(vd), ref.file,
                                prefix, out, depth, visited);
                    }
                }
                return;
            }
            if (ref.rec != null) {
                for (Parameter p : ref.rec.getParameters()) {
                    List<AnnotationExpr> anns = p.getAnnotations();
                    String fieldName = aliasFrom(anns, p.getNameAsString());
                    String doc = descriptionFrom(anns, recordParamDoc(ref.rec, p.getNameAsString()),
                            ref.file, lineOf(p), p.getBegin().map(x -> x.column).orElse(0));
                    addExpandedField(fieldName, resolveTypeVariable(p.getTypeAsString(), typeVars),
                            doc, requiredFrom(anns), lineOf(p), ref.file,
                            prefix, out, depth, visited);
                }
            }
        }

        void addExpandedField(String fieldName, String fieldType, String doc, boolean required,
                              int line, Path file, String prefix, Map<String, FieldVal> out,
                              int depth, Set<String> visited) {
            String confidence = doc == null ? "medium" : "high";
            String path = prefix + "." + fieldName;
            if (isListType(fieldType)) {
                String inner = genericInner(fieldType);
                String arrayPath = path + "[]";
                FieldVal fv = new FieldVal(line, "object", "array", null, doc, confidence);
                fv.file = rel(file);
                fv.required = required;
                out.putIfAbsent(arrayPath, fv);
                if (inner != null) {
                    String innerSimple = simpleType(inner);
                    if (classIndex.containsKey(innerSimple)) {
                        expandObject(innerSimple, arrayPath, out, depth + 1, new HashSet<>(visited),
                                bindTypeVars(inner));
                    }
                }
                return;
            }
            TypeInfo ti = fromJavaType(fieldType, "medium");
            FieldVal fv = new FieldVal(line, "object", ti.type(), ti.format(), doc, confidence);
            fv.file = rel(file);
            fv.required = required;
            out.putIfAbsent(path, fv);
            String simple = simpleType(fieldType);
            if (!isMapType(fieldType) && classIndex.containsKey(simple)) {
                expandObject(simple, path, out, depth + 1, new HashSet<>(visited),
                        bindTypeVars(fieldType));
            }
        }

        /** 字段上的注解 + 同名 getter 上的注解，字段优先 */
        static List<AnnotationExpr> mergedAnnotations(FieldDeclaration fd, MethodDeclaration getter) {
            List<AnnotationExpr> anns = new ArrayList<>(fd.getAnnotations());
            if (getter != null) {
                anns.addAll(getter.getAnnotations());
            }
            return anns;
        }

        static MethodDeclaration getterOf(ClassOrInterfaceDeclaration cls, String field) {
            if (field.isEmpty()) {
                return null;
            }
            String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
            for (MethodDeclaration m : cls.getMethods()) {
                if (m.getParameters().isEmpty()
                        && (m.getNameAsString().equals("get" + cap)
                        || m.getNameAsString().equals("is" + cap))) {
                    return m;
                }
            }
            return null;
        }

        /** record 组件的说明写在类 Javadoc 的 @param 里 */
        static String recordParamDoc(RecordDeclaration rec, String name) {
            String raw = rec.getJavadocComment().map(c -> c.getContent()).orElse(null);
            if (raw == null) {
                return null;
            }
            for (String line : raw.split("\\R")) {
                String t = line.trim().replaceFirst("^\\*+", "").trim();
                if (t.startsWith("@param " + name + " ")) {
                    return t.substring(("@param " + name).length()).trim();
                }
            }
            return null;
        }

        /** 绑定泛型实参，如 Result<OrderVO> -> {T: OrderVO} */
        Map<String, String> bindTypeVars(String declaredType) {
            if (declaredType == null) {
                return Collections.emptyMap();
            }
            int open = declaredType.indexOf('<');
            int close = declaredType.lastIndexOf('>');
            if (open < 0 || close <= open) {
                return Collections.emptyMap();
            }
            ClassRef ref = classIndex.get(simpleType(declaredType));
            if (ref == null) {
                return Collections.emptyMap();
            }
            List<String> params = new ArrayList<>();
            if (ref.cls != null) {
                ref.cls.getTypeParameters().forEach(tp -> params.add(tp.getNameAsString()));
            } else if (ref.rec != null) {
                ref.rec.getTypeParameters().forEach(tp -> params.add(tp.getNameAsString()));
            }
            if (params.isEmpty()) {
                return Collections.emptyMap();
            }
            List<String> args = splitGenerics(declaredType.substring(open + 1, close));
            Map<String, String> vars = new LinkedHashMap<>();
            for (int i = 0; i < params.size() && i < args.size(); i++) {
                vars.put(params.get(i), args.get(i).trim());
            }
            return vars;
        }

        static List<String> splitGenerics(String text) {
            List<String> out = new ArrayList<>();
            int depth = 0;
            int start = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '<') {
                    depth++;
                } else if (c == '>') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    out.add(text.substring(start, i).trim());
                    start = i + 1;
                }
            }
            out.add(text.substring(start).trim());
            return out;
        }

        /** 把字段类型里的泛型变量替换成实际类型 */
        static String resolveTypeVariable(String type, Map<String, String> typeVars) {
            if (typeVars.isEmpty() || type == null) {
                return type;
            }
            String resolved = type;
            for (Map.Entry<String, String> e : typeVars.entrySet()) {
                resolved = resolved.replaceAll("\\b" + Pattern.quote(e.getKey()) + "\\b",
                        Matcher.quoteReplacement(e.getValue()));
            }
            return resolved;
        }

        /** 字段说明：@Schema > @ApiModelProperty > JavaDoc > 行尾注释 */
        String descriptionFrom(List<AnnotationExpr> anns, String javadoc, Path file, int line, int col) {
            String v = annotationNamedValue(annotationIn(anns, "Schema"), "description");
            if (!isText(v)) {
                AnnotationExpr amp = annotationIn(anns, "ApiModelProperty");
                v = annotationNamedValue(amp, "value");
                if (!isText(v)) {
                    v = annotationNamedValue(amp, "notes");
                }
            }
            if (isText(v)) {
                return v.trim();
            }
            if (isText(javadoc)) {
                return javadoc.trim();
            }
            return descriptionNear(file, line, col);
        }

        /** 必填：校验注解、@Schema(required/requiredMode)、@ApiModelProperty(required)、@JsonProperty(required) */
        static boolean requiredFrom(List<AnnotationExpr> anns) {
            for (String name : new String[]{"NotNull", "NotBlank", "NotEmpty", "NonNull"}) {
                if (annotationIn(anns, name) != null) {
                    return true;
                }
            }
            AnnotationExpr schema = annotationIn(anns, "Schema");
            String mode = annotationToken(schema, "requiredMode");
            return annotationBoolean(schema, "required")
                    || (mode != null && mode.toUpperCase().endsWith("REQUIRED"))
                    || annotationBoolean(annotationIn(anns, "ApiModelProperty"), "required")
                    || annotationBoolean(annotationIn(anns, "JsonProperty"), "required");
        }

        /** 字段对外名称：优先 @JsonProperty / @JSONField 别名 */
        static String aliasFrom(List<AnnotationExpr> anns, String fallback) {
            AnnotationExpr json = annotationIn(anns, "JsonProperty");
            String alias = annotationNamedValue(json, "value");
            if (alias == null) {
                alias = annotationStringValue(json);
            }
            if (alias == null || alias.isBlank()) {
                alias = annotationNamedValue(annotationIn(anns, "JSONField"), "name");
            }
            return alias == null || alias.isBlank() ? fallback : alias.trim();
        }

        static AnnotationExpr annotationIn(Iterable<AnnotationExpr> anns, String simpleName) {
            if (anns == null) {
                return null;
            }
            for (AnnotationExpr a : anns) {
                if (a.getName().getIdentifier().equals(simpleName)) {
                    return a;
                }
            }
            return null;
        }

        static AnnotationExpr fieldAnnotation(FieldDeclaration fd, String simpleName) {
            return annotationIn(fd.getAnnotations(), simpleName);
        }

        /** 取注解成员的字面写法，用于枚举值，如 requiredMode = REQUIRED */
        static String annotationToken(AnnotationExpr a, String name) {
            if (a instanceof NormalAnnotationExpr na) {
                for (MemberValuePair pair : na.getPairs()) {
                    if (pair.getNameAsString().equals(name)) {
                        return pair.getValue().toString();
                    }
                }
            }
            return null;
        }

        static boolean annotationBoolean(AnnotationExpr a, String name) {
            if (a instanceof NormalAnnotationExpr na) {
                for (MemberValuePair pair : na.getPairs()) {
                    if (pair.getNameAsString().equals(name)
                            && pair.getValue() instanceof BooleanLiteralExpr b) {
                        return b.getValue();
                    }
                }
            }
            return false;
        }

        void handlePutAll(MethodCallExpr mc, Map<String, LinkedHashMap<String, FieldVal>> vars) {
            String var = scopeName(mc);
            if (var == null || mc.getArguments().isEmpty()) {
                return;
            }
            if (mc.getArgument(0) instanceof NameExpr ne) {
                LinkedHashMap<String, FieldVal> src = vars.get(ne.getNameAsString());
                if (src != null) {
                    vars.computeIfAbsent(var, k -> new LinkedHashMap<>()).putAll(src);
                }
            }
        }

        String resolveOutputVar(MethodDeclaration md, BlockStmt body) {
            List<com.github.javaparser.ast.stmt.Statement> stmts = body.getStatements();
            for (int i = stmts.size() - 1; i >= 0; i--) {
                Optional<ReturnStmt> ret = stmts.get(i).findFirst(ReturnStmt.class);
                if (ret.isEmpty()) {
                    continue;
                }
                Expression e = ret.get().getExpression().orElse(null);
                Expression unwrapped = unwrapReturn(e);
                if (unwrapped instanceof NameExpr ne) {
                    return ne.getNameAsString();
                }
                return null;
            }
            return null;
        }

        Expression unwrapReturn(Expression e) {
            int guard = 0;
            while (e instanceof MethodCallExpr mc && guard++ < 3) {
                if (isMapOfCall(mc)) {
                    return null;
                }
                if (!WRAPPER_CALLS.contains(mc.getNameAsString()) || mc.getArguments().isEmpty()) {
                    return null;
                }
                e = mc.getArgument(0);
            }
            return e;
        }

        // ==================== SQL 别名提取 ====================

        static final class SqlFields {
            List<String> names = new ArrayList<>();
            boolean unknown;
            String reason = "";
        }

        static SqlFields extractAliases(String rawSql) {
            SqlFields out = new SqlFields();
            String sql = stripSqlComments(rawSql).replaceAll("\\s+", " ").trim();
            int sel = indexOfWord(sql, "select", 0);
            if (sel < 0) {
                out.unknown = true;
                out.reason = "未找到 select";
                return out;
            }
            int from = indexOfWordAtDepth0(sql, "from", sel + 6);
            if (from < 0) {
                out.unknown = true;
                out.reason = "未找到顶层 from";
                return out;
            }
            String list = sql.substring(sel + 6, from).trim();
            if (list.toLowerCase().startsWith("distinct ")) {
                list = list.substring("distinct ".length()).trim();
            }
            for (String item : splitTop(list, ',')) {
                String t = item.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (t.equals("*") || t.endsWith(".*")) {
                    out.unknown = true;
                    out.reason = "存在 SELECT *";
                    continue;
                }
                String alias = aliasOf(t);
                if (alias == null) {
                    out.unknown = true;
                    out.reason = "存在无别名的表达式列";
                    continue;
                }
                out.names.add(alias);
            }
            return out;
        }

        static String aliasOf(String item) {
            String lower = item.toLowerCase();
            int as = lastIndexOfWordAtDepth0(lower, "as");
            String candidate;
            if (as >= 0) {
                candidate = item.substring(as + 2).trim();
            } else {
                int sp = lastSpaceAtDepth0(item);
                if (sp < 0) {
                    candidate = item;
                } else {
                    String tail = item.substring(sp + 1).trim();
                    candidate = isIdentifierTail(tail) ? tail : item;
                }
            }
            candidate = unquote(candidate);
            if (candidate.contains("(") || candidate.contains(" ")) {
                return null;
            }
            int dot = candidate.lastIndexOf('.');
            if (dot >= 0) {
                candidate = candidate.substring(dot + 1);
            }
            candidate = unquote(candidate);
            if (!candidate.matches("[A-Za-z_][A-Za-z0-9_$]*") && !candidate.matches("[\\u4e00-\\u9fa5A-Za-z0-9_$]+")) {
                return null;
            }
            return candidate;
        }

        static boolean isIdentifierTail(String s) {
            String t = unquote(s.trim());
            return t.matches("[A-Za-z_][A-Za-z0-9_$]*");
        }

        static String unquote(String s) {
            String t = s.trim();
            if (t.length() >= 2) {
                char c0 = t.charAt(0);
                char c1 = t.charAt(t.length() - 1);
                if ((c0 == '`' && c1 == '`') || (c0 == '"' && c1 == '"')
                        || (c0 == '[' && c1 == ']')) {
                    return t.substring(1, t.length() - 1);
                }
            }
            return t;
        }

        static String stripSqlComments(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            boolean inSingle = false;
            boolean inDouble = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (!inDouble && c == '\'') {
                    inSingle = !inSingle;
                } else if (!inSingle && c == '"') {
                    inDouble = !inDouble;
                }
                if (!inSingle && !inDouble && c == '-' && i + 1 < s.length() && s.charAt(i + 1) == '-') {
                    while (i < s.length() && s.charAt(i) != '\n') {
                        i++;
                    }
                    sb.append(' ');
                    continue;
                }
                if (!inSingle && !inDouble && c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                    i += 2;
                    while (i + 1 < s.length() && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
                        i++;
                    }
                    i++;
                    sb.append(' ');
                    continue;
                }
                sb.append(c);
            }
            return sb.toString();
        }

        static int indexOfWord(String s, String word, int from) {
            String lower = s.toLowerCase();
            for (int i = from; i <= lower.length() - word.length(); i++) {
                if (!lower.startsWith(word, i)) {
                    continue;
                }
                boolean leftOk = i == 0 || !isWordChar(lower.charAt(i - 1));
                int end = i + word.length();
                boolean rightOk = end >= lower.length() || !isWordChar(lower.charAt(end));
                if (leftOk && rightOk) {
                    return i;
                }
            }
            return -1;
        }

        static int indexOfWordAtDepth0(String s, String word, int from) {
            String lower = s.toLowerCase();
            int depth = 0;
            boolean inSingle = false;
            boolean inDouble = false;
            for (int i = from; i < lower.length(); i++) {
                char c = lower.charAt(i);
                if (!inDouble && c == '\'') {
                    inSingle = !inSingle;
                } else if (!inSingle && c == '"') {
                    inDouble = !inDouble;
                }
                if (inSingle || inDouble) {
                    continue;
                }
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (depth == 0 && lower.startsWith(word, i)) {
                    boolean leftOk = i == 0 || !isWordChar(lower.charAt(i - 1));
                    int end = i + word.length();
                    boolean rightOk = end >= lower.length() || !isWordChar(lower.charAt(end));
                    if (leftOk && rightOk) {
                        return i;
                    }
                }
            }
            return -1;
        }

        static int lastIndexOfWordAtDepth0(String s, String word) {
            int found = -1;
            int from = 0;
            while (true) {
                int idx = indexOfWordAtDepth0(s, word, from);
                if (idx < 0) {
                    return found;
                }
                found = idx;
                from = idx + word.length();
            }
        }

        static int lastSpaceAtDepth0(String s) {
            int depth = 0;
            boolean inSingle = false;
            int found = -1;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\'') {
                    inSingle = !inSingle;
                }
                if (inSingle) {
                    continue;
                }
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (depth == 0 && Character.isWhitespace(c)) {
                    found = i;
                }
            }
            return found;
        }

        static List<String> splitTop(String s, char sep) {
            List<String> out = new ArrayList<>();
            int depth = 0;
            boolean inSingle = false;
            int start = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\'') {
                    inSingle = !inSingle;
                }
                if (inSingle) {
                    continue;
                }
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (depth == 0 && c == sep) {
                    out.add(s.substring(start, i));
                    start = i + 1;
                }
            }
            out.add(s.substring(start));
            return out;
        }

        static boolean isWordChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '$';
        }

        // ==================== 通用工具 ====================

        void mergeFields(ApiModel api, Map<String, FieldVal> found, String file, String via, String defaultOrigin) {
            for (Map.Entry<String, FieldVal> e : found.entrySet()) {
                FieldModel existing = api.fields.get(e.getKey());
                if (existing == null) {
                    FieldModel f = new FieldModel();
                    f.name = e.getKey();
                    f.line = e.getValue().line;
                    f.origin = e.getValue().origin == null ? defaultOrigin : e.getValue().origin;
                    f.type = e.getValue().type;
                    f.format = e.getValue().format;
                    f.description = e.getValue().description;
                    f.confidence = e.getValue().confidence;
                    f.required = e.getValue().required;
                    f.file = file;
                    f.via = via;
                    api.fields.put(f.name, f);
                } else if (!"direct".equals(existing.via) && "direct".equals(via)) {
                    existing.via = via;
                    existing.file = file;
                    existing.line = e.getValue().line;
                    existing.type = e.getValue().type;
                    existing.format = e.getValue().format;
                    existing.description = e.getValue().description;
                    existing.confidence = e.getValue().confidence;
                }
            }
        }

        void collectUnattributed(Report r) {
            if (args.only != null && !args.only.isBlank()) {
                return;
            }
            int methodCount = 0;
            Set<String> keys = new LinkedHashSet<>();
            for (MethodRef ref : methodsByKey.values()) {
                if (consumedMethods.contains(ref.key())) {
                    continue;
                }
                if (!inScope(ref.file)) {
                    continue;
                }
                Map<String, FieldVal> fields = extractFields(ref.md, ref.file);
                if (fields.isEmpty()) {
                    continue;
                }
                methodCount++;
                keys.addAll(fields.keySet());
                if (r.unattributedSamples.size() < 30) {
                    r.unattributedSamples.add(ref.className + "#" + ref.md.getNameAsString()
                            + " (" + fields.size() + " 个字段)");
                }
            }
            for (MapperStmt st : mappers) {
                if (st.attributed) {
                    continue;
                }
                if (st.fields.isEmpty() && !st.unknown) {
                    continue;
                }
                keys.addAll(st.fields.stream().map(f -> "sql:" + f).toList());
                if (r.unattributedSamples.size() < 30) {
                    String count = st.fields.isEmpty() ? "无法枚举字段" : st.fields.size() + " 个字段";
                    r.unattributedSamples.add("SQL " + st.namespace + "#" + st.id + "（" + count + "）");
                }
            }
            r.unattributedMethods = methodCount;
            r.unattributedKeys = keys.size();
        }

        KeyInfo keyInfo(Expression e) {
            if (e instanceof StringLiteralExpr s) {
                return new KeyInfo(s.asString(), null);
            }
            if (e instanceof NameExpr ne) {
                String name = ne.getNameAsString();
                if (ambiguousConsts.contains(name)) {
                    return null;
                }
                String v = constBySimple.get(name);
                return v == null ? null : new KeyInfo(v, constDocBySimple.get(name));
            }
            if (e instanceof FieldAccessExpr fa) {
                String scope = fa.getScope().toString();
                String key = scope + "." + fa.getNameAsString();
                String v = constByQualified.get(key);
                if (v != null) {
                    return new KeyInfo(v, constDocByQualified.get(key));
                }
                String simple = constBySimple.get(fa.getNameAsString());
                return simple == null ? null : new KeyInfo(simple, constDocBySimple.get(fa.getNameAsString()));
            }
            if (e instanceof BinaryExpr be && be.getOperator() == BinaryExpr.Operator.PLUS) {
                KeyInfo left = keyInfo(be.getLeft());
                KeyInfo right = keyInfo(be.getRight());
                if (left != null && right != null) {
                    return new KeyInfo(left.key() + right.key(), left.doc() != null ? left.doc() : right.doc());
                }
            }
            return null;
        }

        String literalKey(Expression e) {
            KeyInfo info = keyInfo(e);
            return info == null ? null : info.key();
        }

        LinkedHashMap<String, FieldVal> mapOfFields(MethodCallExpr mc, Path file) {
            LinkedHashMap<String, FieldVal> out = new LinkedHashMap<>();
            int line = lineOf(mc);
            if (mc.getNameAsString().equals("ofEntries")) {
                for (Expression arg : mc.getArguments()) {
                    if (arg instanceof MethodCallExpr entry
                            && entry.getNameAsString().equals("entry")
                            && entry.getArguments().size() >= 2) {
                        KeyInfo keyInfo = keyInfo(entry.getArgument(0));
                        if (keyInfo == null) {
                            continue;
                        }
                        int entryLine = lineOf(entry);
                        int column = entry.getBegin().map(p -> p.column).orElse(0);
                        String doc = descriptionNear(file, entryLine, column);
                        TypeInfo type = inferType(entry.getArgument(1));
                        out.putIfAbsent(keyInfo.key(), new FieldVal(entryLine, "mapof",
                                type.type(), type.format(),
                                doc != null ? doc : keyInfo.doc(), type.confidence()));
                    }
                }
            } else {
                for (int i = 0; i + 1 < mc.getArguments().size(); i += 2) {
                    KeyInfo keyInfo = keyInfo(mc.getArgument(i));
                    if (keyInfo == null) {
                        continue;
                    }
                    TypeInfo type = inferType(mc.getArgument(i + 1));
                    out.putIfAbsent(keyInfo.key(), new FieldVal(line, "mapof",
                            type.type(), type.format(), keyInfo.doc(), type.confidence()));
                }
            }
            return out;
        }

        static boolean isMapOfCall(MethodCallExpr mc) {
            String name = mc.getNameAsString();
            String scope = mc.getScope().map(Object::toString).orElse("");
            if (name.equals("ofEntries")) {
                return scope.endsWith("Map");
            }
            return name.equals("of") && (scope.endsWith("Map") || scope.equals("Map"));
        }

        static boolean isMapCreation(Expression e) {
            if (e instanceof ObjectCreationExpr oce) {
                String t = oce.getTypeAsString();
                return t.contains("HashMap") || t.contains("Map") || t.contains("TreeMap");
            }
            if (e instanceof MethodCallExpr mc) {
                if (isMapOfCall(mc)) {
                    return true;
                }
                String scope = mc.getScope().map(Object::toString).orElse("");
                return (scope.equals("Maps") || scope.endsWith("Maps"))
                        && (mc.getNameAsString().startsWith("new"));
            }
            return false;
        }

        static String scopeName(MethodCallExpr mc) {
            Expression scope = mc.getScope().orElse(null);
            if (scope instanceof NameExpr ne) {
                return ne.getNameAsString();
            }
            if (scope instanceof FieldAccessExpr fa) {
                return fa.getNameAsString();
            }
            if (scope instanceof MethodCallExpr inner && inner.getScope().isPresent()) {
                return inner.getScope().get() instanceof NameExpr ne ? ne.getNameAsString() : null;
            }
            return null;
        }

        static boolean isMapType(String type) {
            if (type == null) {
                return false;
            }
            return type.contains("Map") || type.contains("JSONObject") || type.contains("ModelMap");
        }

        static boolean isListType(String type) {
            return type != null && (type.contains("List") || type.contains("Set") || type.contains("Collection"));
        }

        static boolean isListOfCall(MethodCallExpr mc) {
            String scope = mc.getScope().map(Object::toString).orElse("");
            String name = mc.getNameAsString();
            return (scope.endsWith("List") || scope.endsWith("Arrays") || scope.endsWith("Collections"))
                    && (name.equals("of") || name.equals("asList") || name.equals("singletonList"));
        }

        /** 去掉泛型与包名，只留类名 */
        static String simpleType(String type) {
            if (type == null) {
                return "";
            }
            String s = type.replaceAll("<.*>", "").trim();
            int dot = s.lastIndexOf('.');
            return dot >= 0 ? s.substring(dot + 1) : s;
        }

        static String genericInner(String type) {
            if (type == null) {
                return null;
            }
            int open = type.indexOf('<');
            int close = type.lastIndexOf('>');
            if (open < 0 || close <= open) {
                return null;
            }
            return simpleType(type.substring(open + 1, close));
        }

        static boolean isController(ClassOrInterfaceDeclaration cls) {
            return cls.getAnnotations().stream()
                    .map(a -> a.getName().getIdentifier())
                    .anyMatch(n -> n.equals("RestController") || n.equals("Controller"));
        }

        static AnnotationExpr mappingAnnotation(MethodDeclaration md) {
            for (AnnotationExpr a : md.getAnnotations()) {
                if (MAPPING_ANNOTATIONS.contains(a.getName().getIdentifier())) {
                    return a;
                }
            }
            return null;
        }

        static AnnotationExpr annotation(MethodDeclaration md, String simpleName) {
            for (AnnotationExpr a : md.getAnnotations()) {
                if (a.getName().getIdentifier().equals(simpleName)) {
                    return a;
                }
            }
            return null;
        }

        static String annotationStringOnMethodOrParams(MethodDeclaration md, String simpleName) {
            AnnotationExpr a = annotation(md, simpleName);
            if (a != null) {
                return annotationStringValue(a);
            }
            for (Parameter p : md.getParameters()) {
                for (AnnotationExpr pa : p.getAnnotations()) {
                    if (pa.getName().getIdentifier().equals(simpleName)) {
                        return annotationStringValue(pa);
                    }
                }
            }
            return null;
        }

        static String annotationStringValue(AnnotationExpr a) {
            if (a instanceof SingleMemberAnnotationExpr sm) {
                return sm.getMemberValue() instanceof StringLiteralExpr s ? s.asString() : null;
            }
            if (a instanceof NormalAnnotationExpr na) {
                for (MemberValuePair pair : na.getPairs()) {
                    if (pair.getNameAsString().equals("value")
                            && pair.getValue() instanceof StringLiteralExpr s) {
                        return s.asString();
                    }
                }
            }
            return null;
        }

        static String annotationNamedValue(AnnotationExpr a, String name) {
            if (a instanceof NormalAnnotationExpr na) {
                for (MemberValuePair pair : na.getPairs()) {
                    if (pair.getNameAsString().equals(name)
                            && pair.getValue() instanceof StringLiteralExpr s) {
                        return s.asString();
                    }
                }
            }
            return null;
        }

        /** 接口标题：@Operation(summary) > @ApiOperation > 自定义注解 > JavaDoc 首句 > 方法名 */
        String methodSummary(MethodDeclaration md) {
            AnnotationExpr operation = annotation(md, "Operation");
            if (operation != null) {
                String v = annotationNamedValue(operation, "summary");
                if (isText(v)) {
                    return trimTitle(v);
                }
            }
            AnnotationExpr apiOperation = annotation(md, "ApiOperation");
            if (apiOperation != null) {
                String v = annotationNamedValue(apiOperation, "value");
                if (v == null) {
                    v = annotationStringValue(apiOperation);
                }
                if (isText(v)) {
                    return trimTitle(v);
                }
            }
            String custom = customAnnotationValue(md);
            if (isText(custom)) {
                return trimTitle(custom);
            }
            String doc = javadocTitle(md.getJavadocComment().map(c -> c.getContent()).orElse(null));
            return trimTitle(doc == null ? md.getNameAsString() : doc);
        }

        /** 去掉标题末尾的句号，避免「登录方法。」这类多余标点 */
        static String trimTitle(String text) {
            String t = text.trim();
            while (!t.isEmpty() && (t.endsWith("。") || t.endsWith("."))) {
                t = t.substring(0, t.length() - 1).trim();
            }
            return t;
        }

        /** 接口长描述：@Operation(description) > @ApiOperation(notes) > JavaDoc 正文 */
        String methodDescription(MethodDeclaration md) {
            AnnotationExpr operation = annotation(md, "Operation");
            if (operation != null) {
                String v = annotationNamedValue(operation, "description");
                if (isText(v)) {
                    return v.trim();
                }
            }
            AnnotationExpr apiOperation = annotation(md, "ApiOperation");
            if (apiOperation != null) {
                String v = annotationNamedValue(apiOperation, "notes");
                if (isText(v)) {
                    return v.trim();
                }
            }
            return javadocRest(md.getJavadocComment().map(c -> c.getContent()).orElse(null));
        }

        /** 分组：类上 @Tag(name) > @Api(tags) > 类名 */
        String classTag(ClassOrInterfaceDeclaration cls) {
            AnnotationExpr tag = classAnnotation(cls, "Tag");
            if (tag != null) {
                String v = annotationNamedValue(tag, "name");
                if (isText(v)) {
                    return v.trim();
                }
            }
            AnnotationExpr api = classAnnotation(cls, "Api");
            if (api != null) {
                String v = annotationArrayFirst(api, "tags");
                if (!isText(v)) {
                    v = annotationNamedValue(api, "value");
                }
                if (isText(v)) {
                    return v.trim();
                }
            }
            return cls.getNameAsString();
        }

        /** 分组描述：@Tag(description) > @Api(value) > 类 JavaDoc 首句 */
        String classTagDescription(ClassOrInterfaceDeclaration cls) {
            AnnotationExpr tag = classAnnotation(cls, "Tag");
            if (tag != null) {
                String v = annotationNamedValue(tag, "description");
                if (isText(v)) {
                    return v.trim();
                }
            }
            AnnotationExpr api = classAnnotation(cls, "Api");
            if (api != null) {
                String v = annotationNamedValue(api, "value");
                if (isText(v)) {
                    return v.trim();
                }
            }
            return javadocTitle(cls.getJavadocComment().map(c -> c.getContent()).orElse(null));
        }

        /** 按 --title-annotations 配置依次查找自定义注解的标题值 */
        String customAnnotationValue(MethodDeclaration md) {
            for (String[] spec : args.titleAnnotations) {
                for (AnnotationExpr a : md.getAnnotations()) {
                    if (!matchesAnnotation(a, spec[0])) {
                        continue;
                    }
                    String v = annotationNamedValue(a, spec[1]);
                    if (v == null && "value".equals(spec[1])) {
                        v = annotationStringValue(a);
                    }
                    if (isText(v)) {
                        return v;
                    }
                }
            }
            return null;
        }

        static boolean matchesAnnotation(AnnotationExpr a, String name) {
            String actual = a.getNameAsString();
            return actual.equals(name) || actual.endsWith("." + name);
        }

        static AnnotationExpr classAnnotation(ClassOrInterfaceDeclaration cls, String simpleName) {
            for (AnnotationExpr a : cls.getAnnotations()) {
                if (a.getName().getIdentifier().equals(simpleName)) {
                    return a;
                }
            }
            return null;
        }

        /** 取数组注解的第一个字符串，如 @Api(tags = {"订单"}) */
        static String annotationArrayFirst(AnnotationExpr a, String name) {
            if (a instanceof NormalAnnotationExpr na) {
                for (MemberValuePair pair : na.getPairs()) {
                    if (pair.getNameAsString().equals(name)
                            && pair.getValue() instanceof ArrayInitializerExpr arr
                            && !arr.getValues().isEmpty()
                            && arr.getValues().get(0) instanceof StringLiteralExpr s) {
                        return s.asString();
                    }
                }
            }
            return null;
        }

        static boolean isText(String v) {
            return v != null && !v.isBlank();
        }

        /** JavaDoc 首句，去掉块标签与 HTML */
        static String javadocTitle(String raw) {
            String body = javadocBody(raw);
            if (body == null) {
                return null;
            }
            int cut = firstSentenceEnd(body);
            String text = stripInlineHtml(cut > 0 ? body.substring(0, cut) : body);
            return text.isEmpty() ? null : truncate(text, 120);
        }

        /** JavaDoc 首句之后的内容 */
        static String javadocRest(String raw) {
            String body = javadocBody(raw);
            if (body == null) {
                return null;
            }
            int cut = firstSentenceEnd(body);
            if (cut <= 0) {
                return null;
            }
            String text = stripInlineHtml(body.substring(cut));
            return text.isEmpty() ? null : truncate(text, 500);
        }

        /** 只留正文：去掉每行行首的星号，遇到 @param 等块标签就停止 */
        static String javadocBody(String raw) {
            if (raw == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (String line : raw.split("\\R")) {
                String t = line.trim().replaceFirst("^\\*+", "").trim();
                if (t.startsWith("@")) {
                    break;
                }
                if (!t.isEmpty()) {
                    sb.append(t).append(' ');
                }
            }
            String text = sb.toString().replaceAll("\\s+", " ").trim();
            return text.isEmpty() ? null : text;
        }

        static int firstSentenceEnd(String text) {
            int best = -1;
            for (String sep : new String[]{"。", "！", "？", ". "}) {
                int i = text.indexOf(sep);
                if (i < 0) {
                    continue;
                }
                int end = i + sep.length();
                if (best < 0 || end < best) {
                    best = end;
                }
            }
            return best;
        }

        static String stripInlineHtml(String text) {
            return text.replaceAll("<[^>]+>", "")
                    .replaceAll("\\{@\\w+\\s+([^}]*)}", "$1")
                    .trim();
        }

        static String truncate(String text, int max) {
            return text.length() > max ? text.substring(0, max) + "…" : text;
        }

        /** 尽力推断字段类型，失败降级为 string + low，绝不猜 */
        TypeInfo inferType(Expression e) {
            if (e == null) {
                return UNKNOWN_TYPE;
            }
            if (e instanceof IntegerLiteralExpr) {
                return new TypeInfo("integer", "int32", "high");
            }
            if (e instanceof LongLiteralExpr) {
                return new TypeInfo("integer", "int64", "high");
            }
            if (e instanceof DoubleLiteralExpr) {
                return new TypeInfo("number", "double", "high");
            }
            if (e instanceof BooleanLiteralExpr) {
                return new TypeInfo("boolean", null, "high");
            }
            if (e instanceof StringLiteralExpr || e instanceof CharLiteralExpr) {
                return new TypeInfo("string", null, "high");
            }
            if (e instanceof EnclosedExpr en) {
                return inferType(en.getInner());
            }
            if (e instanceof CastExpr ce) {
                return fromJavaType(ce.getTypeAsString(), "medium");
            }
            if (e instanceof ObjectCreationExpr oce) {
                return fromJavaType(oce.getTypeAsString(), "medium");
            }
            if (e instanceof MethodCallExpr mc) {
                String n = mc.getNameAsString();
                if (n.equals("toString")) {
                    return new TypeInfo("string", null, "medium");
                }
                if (n.equals("size") || n.equals("length") || n.equals("intValue") || n.equals("hashCode")) {
                    return new TypeInfo("integer", "int32", "medium");
                }
                if (n.equals("doubleValue") || n.equals("floatValue")) {
                    return new TypeInfo("number", "double", "medium");
                }
                if (n.equals("booleanValue")) {
                    return new TypeInfo("boolean", null, "medium");
                }
                return UNKNOWN_TYPE;
            }
            if (e instanceof BinaryExpr be) {
                if (be.getOperator() == BinaryExpr.Operator.PLUS
                        && (be.getLeft() instanceof StringLiteralExpr || be.getRight() instanceof StringLiteralExpr)) {
                    return new TypeInfo("string", null, "medium");
                }
                TypeInfo left = inferType(be.getLeft());
                return "high".equals(left.confidence()) ? left : UNKNOWN_TYPE;
            }
            return UNKNOWN_TYPE;
        }

        static final TypeInfo UNKNOWN_TYPE = new TypeInfo("string", null, "low");

        static TypeInfo fromJavaType(String rawType, String confidence) {
            String t = rawType == null ? "" : rawType.replaceAll("<.*>", "").trim();
            switch (t) {
                case "int", "Integer", "short", "Short", "byte", "Byte":
                    return new TypeInfo("integer", "int32", confidence);
                case "long", "Long":
                    return new TypeInfo("integer", "int64", confidence);
                case "double", "Double", "float", "Float", "BigDecimal", "Number":
                    return new TypeInfo("number", "double", confidence);
                case "boolean", "Boolean":
                    return new TypeInfo("boolean", null, confidence);
                case "String", "CharSequence", "char", "Character":
                    return new TypeInfo("string", null, confidence);
                case "LocalDate", "Date":
                    return new TypeInfo("string", "date", confidence);
                case "LocalDateTime", "Instant", "Timestamp", "ZonedDateTime":
                    return new TypeInfo("string", "date-time", confidence);
                case "List", "Set", "Collection", "ArrayList", "LinkedList", "HashSet":
                    return new TypeInfo("array", null, confidence);
                case "Map", "HashMap", "LinkedHashMap", "TreeMap":
                    return new TypeInfo("object", null, confidence);
                default:
                    return new TypeInfo("object", null, "low");
            }
        }

        static String classBasePath(ClassOrInterfaceDeclaration cls) {
            AnnotationExpr a = cls.getAnnotations().stream()
                    .filter(x -> x.getName().getIdentifier().equals("RequestMapping"))
                    .findFirst()
                    .orElse(null);
            return a == null ? "" : pathOf(a);
        }

        static String methodPath(AnnotationExpr a) {
            String p = pathOf(a);
            return p;
        }

        static String pathOf(AnnotationExpr a) {
            if (a instanceof SingleMemberAnnotationExpr sm) {
                return literalPath(sm.getMemberValue());
            }
            if (a instanceof NormalAnnotationExpr na) {
                for (MemberValuePair pair : na.getPairs()) {
                    if (pair.getNameAsString().equals("value") || pair.getNameAsString().equals("path")) {
                        return literalPath(pair.getValue());
                    }
                }
            }
            return "";
        }

        static String literalPath(Expression e) {
            if (e instanceof StringLiteralExpr s) {
                return s.asString();
            }
            if (e instanceof ArrayInitializerExpr arr && !arr.getValues().isEmpty()) {
                Expression first = arr.getValues().get(0);
                return first instanceof StringLiteralExpr s ? s.asString() : "";
            }
            return "";
        }

        static String joinPath(String base, String sub) {
            String b = base == null ? "" : base.trim();
            String s = sub == null ? "" : sub.trim();
            if (!b.isEmpty() && !b.startsWith("/")) {
                b = "/" + b;
            }
            if (!s.isEmpty() && !s.startsWith("/")) {
                s = "/" + s;
            }
            String joined = (b + s);
            return joined.isEmpty() ? "/" : joined.replaceAll("//+", "/");
        }

        static String shortId(String id) {
            if (id == null) {
                return "";
            }
            int dot = id.lastIndexOf('.');
            int hash = id.lastIndexOf('#');
            int cut = Math.max(dot, hash);
            return cut >= 0 ? id.substring(cut + 1) : id;
        }

        static int lineOf(Node n) {
            return n.getBegin().map(p -> p.line).orElse(0);
        }

        static boolean isIgnored(Path p) {
            String s = p.toString().replace('\\', '/');
            return s.contains("/target/") || s.contains("/build/") || s.contains("/out/")
                    || s.contains("/.git/") || s.contains("/src/test/") || s.contains("/node_modules/");
        }

        String rel(Path p) {
            try {
                return args.resolveDir.toAbsolutePath().relativize(p.toAbsolutePath())
                        .toString().replace('\\', '/');
            } catch (RuntimeException e) {
                return p.toString().replace('\\', '/');
            }
        }

        static String brief(Exception e) {
            String m = e.getMessage();
            if (m == null) {
                return e.getClass().getSimpleName();
            }
            int nl = m.indexOf('\n');
            return nl > 0 ? m.substring(0, nl) : m;
        }
    }

    // ==================== 输出 ====================

    static final class Reporter {

        final Path outDir;
        final Set<String> formats;

        Reporter(Path outDir, Set<String> formats) {
            this.outDir = outDir;
            this.formats = formats;
        }

        void writeAll(Report r) throws IOException {
            Files.createDirectories(outDir);
            // 诊断类文件只在显式要求时生成，日常只产出 openapi + md
            if (formats.contains("report")) {
                Files.writeString(outDir.resolve("mappeek-report.md"), buildReport(r), StandardCharsets.UTF_8);
                Files.writeString(outDir.resolve("mappeek-fields.csv"), buildFieldsCsv(r), StandardCharsets.UTF_8);
                Files.writeString(outDir.resolve("mappeek-groundtruth.csv"), buildGroundTruth(r),
                        StandardCharsets.UTF_8);
            }
            Files.write(outDir.resolve("mappeek-edit.csv"),
                    ("\uFEFF" + buildEditCsv(r)).getBytes(StandardCharsets.UTF_8));
            Exporters.write(outDir, r, formats);
        }

        /** 供 IDE 页面编辑的字段清单，人工填写的说明与标题会在下次扫描时回填 */
        String buildEditCsv(Report r) {
            StringBuilder sb = new StringBuilder(
                    "apiKey,direction,field,type,required,confidence,description,summary\n");
            for (ApiModel a : r.apis) {
                if (a.mapIn) {
                    appendEditRows(sb, a, "request", a.requestFields);
                }
                if (a.mapOut) {
                    appendEditRows(sb, a, "response", a.responseFields);
                }
            }
            return sb.toString();
        }

        static void appendEditRows(StringBuilder sb, ApiModel api, String direction,
                                   Map<String, FieldModel> fields) {
            for (FieldModel f : fields.values()) {
                sb.append(csv(api.key)).append(',').append(direction).append(',')
                        .append(csv(f.name)).append(',').append(csv(f.type)).append(',')
                        .append(f.required).append(',').append(csv(f.confidence)).append(',')
                        .append(csv(f.description == null ? "" : f.description)).append(',')
                        .append(csv(api.summary == null ? "" : api.summary)).append('\n');
            }
        }

        String buildReport(Report r) {
            StringBuilder sb = new StringBuilder();
            sb.append("# MapPeek M0 验证报告\n\n");
            sb.append("生成时间：").append(java.time.LocalDateTime.now().withNano(0)).append("\n\n");

            sb.append("## 概览\n\n");
            sb.append("| 指标 | 数值 |\n|---|---|\n");
            sb.append("| Java 文件 | ").append(r.javaFiles)
                    .append("（解析范围 ").append(r.resolvedFiles)
                    .append("，解析失败 ").append(r.parseFailures).append("） |\n");
            sb.append("| Controller | ").append(r.controllers).append("（映射方法 ").append(r.mappingMethods).append("） |\n");
            sb.append("| 可导出字段的接口 | ").append(r.mapApis)
                    .append("（入参 ").append(r.mapInApis)
                    .append(" / 出参 ").append(r.mapOutApis).append("） |\n");
            sb.append("| 自动识别字段 | ").append(r.countFields())
                    .append("（去重 ").append(r.distinctFields).append("） |\n");
            sb.append("| 未归属字段 | ").append(r.unattributedKeys)
                    .append("（来自 ").append(r.unattributedMethods).append(" 个方法） |\n");
            sb.append("| MyBatis 语句 | ").append(r.mappers.size()).append(" |\n\n");

            long high = 0;
            long mapof = 0;
            long sql = 0;
            for (ApiModel a : r.apis) {
                for (FieldModel f : a.fields.values()) {
                    if ("sql".equals(f.origin)) {
                        sql++;
                    } else if ("mapof".equals(f.origin)) {
                        mapof++;
                    } else {
                        high++;
                    }
                }
            }
            sb.append("来源分布：put 字面量 ").append(high)
                    .append(" / Map.of ").append(mapof)
                    .append(" / SQL 别名 ").append(sql).append("\n\n");

            sb.append("## 接口明细\n\n");
            sb.append("| 接口 | 方法 | 路径 | 角色 | 字段数 | 字段 |\n|---|---|---|---|---|---|\n");
            for (ApiModel a : r.apis) {
                sb.append("| `").append(a.key).append("` | ").append(a.httpMethod)
                        .append(" | `").append(a.path).append("` | ").append(a.role)
                        .append(" | ").append(a.fields.size())
                        .append(" | ").append(a.fields.isEmpty() ? "**未能识别**" : String.join(", ", a.fields.keySet()))
                        .append(" |\n");
            }
            sb.append('\n');

            List<ApiModel> empty = r.apis.stream().filter(a -> a.fields.isEmpty()).toList();
            if (!empty.isEmpty()) {
                sb.append("## 未能识别字段的接口\n\n");
                for (ApiModel a : empty) {
                    sb.append("- `").append(a.key).append("`（").append(a.file).append(":").append(a.line).append("）");
                    if (!a.notes.isEmpty()) {
                        sb.append(" — ").append(String.join("；", a.notes));
                    }
                    sb.append('\n');
                }
                sb.append('\n');
            }

            if (!r.mappers.isEmpty()) {
                sb.append("## MyBatis 语句\n\n");
                sb.append("| 语句 | 文件 | 字段数 | 归属 | 备注 |\n|---|---|---|---|---|\n");
                for (MapperStmt st : r.mappers) {
                    sb.append("| `").append(st.namespace).append("#").append(st.id).append("` | ")
                            .append(st.file).append(" | ").append(st.fields.size()).append(" | ")
                            .append(st.attributed ? "已归属" : "未归属").append(" | ")
                            .append(st.unknown ? st.reason : "-").append(" |\n");
                }
                sb.append('\n');
            }

            if (!r.unattributedSamples.isEmpty()) {
                sb.append("## 未归属字段（部分）\n\n");
                for (String s : r.unattributedSamples) {
                    sb.append("- ").append(s).append('\n');
                }
                sb.append('\n');
            }

            if (!r.diagnostics.isEmpty()) {
                sb.append("## 诊断信息\n\n");
                for (String d : r.diagnostics) {
                    sb.append("- ").append(d).append('\n');
                }
                sb.append('\n');
            }

            sb.append("## 下一步\n\n");
            sb.append("1. 打开 `mappeek-groundtruth.csv`，在 `expected` 列填写该接口真实存在的字段（用 `|` 分隔）\n");
            sb.append("2. 至少填 20 个涉及 Map 的接口\n");
            sb.append("3. 执行 `java -jar mappeek.jar --verify m0-out/mappeek-groundtruth.csv` 查看召回率与字段名错误率\n");
            return sb.toString();
        }

        String buildFieldsCsv(Report r) {
            StringBuilder sb = new StringBuilder("apiKey,role,field,origin,file,line,via\n");
            for (ApiModel a : r.apis) {
                for (FieldModel f : a.fields.values()) {
                    sb.append(csv(a.key)).append(',').append(csv(a.role)).append(',')
                            .append(csv(f.name)).append(',').append(csv(f.origin)).append(',')
                            .append(csv(f.file)).append(',').append(f.line).append(',')
                            .append(csv(f.via)).append('\n');
                }
            }
            return sb.toString();
        }

        String buildGroundTruth(Report r) throws IOException {
            Path gt = outDir.resolve("mappeek-groundtruth.csv");
            Map<String, String> previous = new LinkedHashMap<>();
            if (Files.isRegularFile(gt)) {
                List<String> oldLines = Files.readAllLines(gt, StandardCharsets.UTF_8);
                for (int i = 1; i < oldLines.size(); i++) {
                    List<String> cols = parseCsvLine(oldLines.get(i));
                    if (cols.size() >= 4 && !cols.get(3).isBlank()) {
                        previous.put(cols.get(0), cols.get(3).trim());
                    }
                }
            }
            StringBuilder sb = new StringBuilder("apiKey,role,detected,expected\n");
            for (ApiModel a : r.apis) {
                List<String> names = new ArrayList<>(a.fields.keySet());
                Collections.sort(names);
                sb.append(csv(a.key)).append(',').append(csv(a.role)).append(',')
                        .append(csv(String.join("|", names))).append(',')
                        .append(csv(previous.getOrDefault(a.key, ""))).append('\n');
            }
            return sb.toString();
        }

        void verify(Path groundTruth) throws IOException {
            if (!Files.isRegularFile(groundTruth)) {
                System.out.println("错误: 找不到 " + groundTruth);
                return;
            }
            Files.createDirectories(outDir);
            List<String> lines = Files.readAllLines(groundTruth, StandardCharsets.UTF_8);
            int hit = 0;
            int expectedTotal = 0;
            int detectedTotal = 0;
            int wrong = 0;
            int unfilled = 0;
            StringBuilder detail = new StringBuilder();
            detail.append("# M0 校验结果\n\n");
            detail.append("| 接口 | 期望 | 命中 | 漏掉 | 多出 |\n|---|---|---|---|---|\n");

            for (int i = 1; i < lines.size(); i++) {
                List<String> cols = parseCsvLine(lines.get(i));
                if (cols.size() < 4) {
                    continue;
                }
                String apiKey = cols.get(0);
                String detectedRaw = cols.get(2);
                String expectedRaw = cols.get(3);
                if (expectedRaw.isBlank()) {
                    unfilled++;
                    continue;
                }
                Set<String> detected = splitPipe(detectedRaw);
                Set<String> expected = splitPipe(expectedRaw);
                Set<String> hitSet = new LinkedHashSet<>(detected);
                hitSet.retainAll(expected);
                Set<String> missing = new LinkedHashSet<>(expected);
                missing.removeAll(detected);
                Set<String> extra = new LinkedHashSet<>(detected);
                extra.removeAll(expected);

                hit += hitSet.size();
                expectedTotal += expected.size();
                detectedTotal += detected.size();
                wrong += extra.size();
                detail.append("| `").append(apiKey).append("` | ").append(expected.size())
                        .append(" | ").append(hitSet.size())
                        .append(" | ").append(missing.isEmpty() ? "-" : String.join(", ", missing))
                        .append(" | ").append(extra.isEmpty() ? "-" : String.join(", ", extra))
                        .append(" |\n");
            }

            double recall = expectedTotal == 0 ? 0 : (hit * 100.0 / expectedTotal);
            double wrongRate = detectedTotal == 0 ? 0 : (wrong * 100.0 / detectedTotal);

            detail.append('\n');
            detail.append("## 结论\n\n");
            detail.append("- 已填写接口数：").append(lines.size() - 1 - unfilled).append("\n");
            detail.append("- 未填写接口数：").append(unfilled).append("\n");
            detail.append("- 召回率：").append(String.format("%.1f", recall)).append("%\n");
            detail.append("- 字段名错误率：").append(String.format("%.1f", wrongRate)).append("%\n\n");
            detail.append("判定：");
            if (recall >= 75 && wrongRate < 5) {
                detail.append("**达标，可以进入 M1**\n");
            } else if (recall < 60 || wrongRate >= 15) {
                detail.append("**不达标，按方案第 12 节放弃自动化**\n");
            } else {
                detail.append("**处于中间区间，建议只做 MyBatis 那部分**\n");
            }

            Files.writeString(outDir.resolve("mappeek-verify.md"), detail.toString(), StandardCharsets.UTF_8);
            System.out.println();
            System.out.println("==== M0 校验结果 ====");
            System.out.printf("召回率        : %.1f%%%n", recall);
            System.out.printf("字段名错误率  : %.1f%%%n", wrongRate);
            System.out.println("明细: " + outDir.resolve("mappeek-verify.md"));
        }

        static Set<String> splitPipe(String s) {
            Set<String> out = new LinkedHashSet<>();
            if (s == null || s.isBlank()) {
                return out;
            }
            for (String part : s.split("\\|")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
            return out;
        }

        static String csv(String s) {
            if (s == null) {
                return "";
            }
            if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
                return "\"" + s.replace("\"", "\"\"") + "\"";
            }
            return s;
        }

        static List<String> parseCsvLine(String line) {
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
}

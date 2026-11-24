package mappeek.idea;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.event.TableModelEvent;
import javax.swing.JTabbedPane;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.RowFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * MapPeek 工具窗口：上方编辑字段说明，下方实时生成文档预览，支持一键复制。
 */
final class FieldsPanel extends JPanel {

    /** JSON Schema 允许的标量/容器类型，类型列只能从这里选，避免手输错误 */
    private static final String[] SCHEMA_TYPES =
            {"string", "integer", "number", "boolean", "array", "object"};

    private final Project project;
    private final JComboBox<String> targetCombo = new JComboBox<>();
    private final DefaultTableModel model;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTable requestTable;
    private final JTable responseTable;
    private final JTextArea preview = new JTextArea();
    private final JLabel statusLabel = new JLabel();
    private final Timer autoSaveTimer;

    private List<String[]> rows = new ArrayList<>();
    private Path currentDir;
    private boolean loading;
    private boolean dirty;
    private boolean regenerating;
    private boolean regenPending;
    private boolean copyWhenReady;

    FieldsPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.model = new DefaultTableModel(
                new String[]{I18n.t("API", "接口"), I18n.t("Title", "标题"), I18n.t("Dir", "方向"),
                        I18n.t("Field", "字段"), I18n.t("Type", "类型"),
                        I18n.t("Conf.", "置信度"), I18n.t("Description", "说明")}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 1 || column == 4 || column == 6;
            }
        };
        this.requestTable = createTable("request");
        this.responseTable = createTable("response");
        model.addTableModelListener(this::onModelChanged);
        // 编辑停下后自动落盘并重新生成文档，避免手动点保存
        this.autoSaveTimer = new Timer(900, e -> autoSave());
        autoSaveTimer.setRepeats(false);

        preview.setEditable(false);
        preview.setLineWrap(false);
        preview.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));

        buildUi();
        refreshTargets();
    }

    private void buildUi() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(new JLabel(I18n.t("Target:", "目标：")));
        targetCombo.setPreferredSize(new Dimension(220, targetCombo.getPreferredSize().height));
        targetCombo.addActionListener(e -> {
            if (loading) {
                return;
            }
            Object selected = targetCombo.getSelectedItem();
            if (selected != null) {
                loadRows(baseDir().resolve(selected.toString()));
            }
        });
        top.add(targetCombo);
        top.add(button(I18n.t("Save", "保存"), this::save));
        top.add(button(I18n.t("Refresh", "刷新"), () -> reload(currentDir)));

        JPanel previewHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        previewHeader.add(new JLabel(I18n.t("Preview", "预览")));
        previewHeader.add(button(I18n.t("Copy Document", "复制文档"), this::copyOpenApi));
        previewHeader.add(button(I18n.t("Copy Request", "复制入参"),
                () -> copy(buildSchema("request"),
                        I18n.t("Request JSON Schema copied", "入参 JSON Schema 已复制"))));
        previewHeader.add(button(I18n.t("Copy Response", "复制出参"),
                () -> copy(buildSchema("response"),
                        I18n.t("Response JSON Schema copied", "出参 JSON Schema 已复制"))));
        statusLabel.setForeground(java.awt.Color.GRAY);
        previewHeader.add(statusLabel);

        JPanel previewPanel = new JPanel(new BorderLayout());
        previewPanel.add(previewHeader, BorderLayout.NORTH);
        previewPanel.add(new JScrollPane(preview), BorderLayout.CENTER);

        tabs.addTab(I18n.t("Request", "入参"), new JScrollPane(requestTable));
        tabs.addTab(I18n.t("Response", "出参"), new JScrollPane(responseTable));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                tabs, previewPanel);
        split.setResizeWeight(0.55);
        split.setBorder(BorderFactory.createEmptyBorder());

        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
    }

    /** 共享一个 model，用 RowFilter 按方向拆到两个表，编辑与保存只有一份数据 */
    private JTable createTable(String direction) {
        JTable t = new JTable(model);
        t.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        t.setRowHeight(24);
        t.getColumnModel().getColumn(0).setPreferredWidth(210);
        t.getColumnModel().getColumn(1).setPreferredWidth(150);
        t.getColumnModel().getColumn(2).setMinWidth(0);
        t.getColumnModel().getColumn(2).setMaxWidth(0);
        t.getColumnModel().getColumn(3).setPreferredWidth(170);
        t.getColumnModel().getColumn(4).setPreferredWidth(100);
        t.getColumnModel().getColumn(4).setCellEditor(new DefaultCellEditor(typeCombo()));
        t.getColumnModel().getColumn(5).setPreferredWidth(60);
        t.getColumnModel().getColumn(6).setPreferredWidth(260);
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
        sorter.setRowFilter(new RowFilter<DefaultTableModel, Integer>() {
            @Override
            public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                Object dir = entry.getModel().getValueAt(entry.getIdentifier(), 2);
                return dir != null && direction.equalsIgnoreCase(dir.toString());
            }
        });
        t.setRowSorter(sorter);
        return t;
    }

    /** 类型列的下拉编辑器：只能选 JSON Schema 支持的类型，从源头避免手输错误 */
    private static JComboBox<String> typeCombo() {
        JComboBox<String> combo = new JComboBox<String>(SCHEMA_TYPES) {
            @Override
            public void setSelectedItem(Object item) {
                super.setSelectedItem(normalizeType(item == null ? null : item.toString(), "string"));
            }
        };
        combo.setMaximumRowCount(SCHEMA_TYPES.length);
        combo.setToolTipText(I18n.t("JSON Schema type", "JSON Schema 类型"));
        return combo;
    }

    /** 类型列当前值归一后的结果，预览与保存共用 */
    private String typeAt(int index) {
        Object cell = model.getValueAt(index, 4);
        return normalizeType(cell == null ? "" : cell.toString(), "string");
    }

    /** 把 Java / 数据库类型归一成 JSON Schema 类型，空值回退到扫描结果 */
    static String normalizeType(String raw, String fallback) {
        String t = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty()) {
            return fallback;
        }
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

    private void updateTabTitles() {
        int req = 0;
        int resp = 0;
        for (String[] r : rows) {
            if ("request".equals(r[1])) {
                req++;
            } else {
                resp++;
            }
        }
        tabs.setTitleAt(0, I18n.t("Request", "入参") + " (" + req + ")");
        tabs.setTitleAt(1, I18n.t("Response", "出参") + " (" + resp + ")");
    }

    private JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    // ==================== 数据加载 ====================

    private Path baseDir() {
        return Paths.get(project.getBasePath() == null ? "." : project.getBasePath()).resolve(".mappeek");
    }

    void reload(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        refreshTargets();
        loading = true;
        targetCombo.setSelectedItem(dir.getFileName().toString());
        loading = false;
        loadRows(dir);
    }

    void reloadLatest() {
        Path pending = ScanAction.pendingDir(project);
        if (pending != null && Files.isDirectory(pending)) {
            reload(pending);
            return;
        }
        Path latest = newestTarget();
        if (latest != null) {
            reload(latest);
        }
    }

    private Path newestTarget() {
        Path base = baseDir();
        if (!Files.isDirectory(base)) {
            return null;
        }
        try (Stream<Path> dirs = Files.list(base)) {
            return dirs.filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("mappeek-edit.csv")))
                    .max(Comparator.comparingLong(this::lastModified))
                    .orElse(null);
        } catch (IOException ex) {
            return null;
        }
    }

    private long lastModified(Path dir) {
        try {
            return Files.getLastModifiedTime(dir.resolve("mappeek-edit.csv")).toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }

    private void refreshTargets() {
        Path base = baseDir();
        loading = true;
        targetCombo.removeAllItems();
        if (Files.isDirectory(base)) {
            try (Stream<Path> dirs = Files.list(base)) {
                dirs.filter(Files::isDirectory)
                        .filter(d -> Files.isRegularFile(d.resolve("mappeek-edit.csv")))
                        .sorted(Comparator.comparingLong(this::lastModified).reversed())
                        .forEach(d -> targetCombo.addItem(d.getFileName().toString()));
            } catch (IOException ignored) {
                // 目录不存在或不可读时保持空列表
            }
        }
        loading = false;
    }

    private void loadRows(Path dir) {
        currentDir = dir;
        autoSaveTimer.stop();
        dirty = false;
        statusLabel.setText("");
        rows = FieldCsv.readRows(dir.resolve("mappeek-edit.csv"));
        loading = true;
        model.setRowCount(0);
        for (String[] r : rows) {
            model.addRow(new Object[]{r[0], r.length > 7 ? r[7] : "", r[1], r[2],
                    normalizeType(r[3], "string"), r[5], r[6]});
        }
        loading = false;
        updateTabTitles();
        updatePreview();
    }

    private void onModelChanged(TableModelEvent event) {
        if (loading) {
            return;
        }
        updatePreview();
        if (currentDir == null) {
            return;
        }
        dirty = true;
        statusLabel.setText(I18n.t("Pending save...", "待保存…"));
        autoSaveTimer.restart();
    }

    // ==================== 保存与生成 ====================

    /** 保存按钮：立即落盘并重新生成文档 */
    private void save() {
        autoSaveTimer.stop();
        if (!commitAndSave(true)) {
            return;
        }
        regenerateDocs(true);
    }

    /** 停止编辑后自动落盘；正在生成文档时先记下，等本轮结束再补一次 */
    private void autoSave() {
        if (currentDir == null || !dirty) {
            return;
        }
        if (requestTable.isEditing() || responseTable.isEditing()) {
            autoSaveTimer.restart();
            return;
        }
        if (regenerating) {
            return; // 本轮生成结束后 onRegenerated 会按 dirty 再补一次
        }
        if (!commitAndSave(false)) {
            return;
        }
        regenerateDocs(false);
    }

    /** 用扫描参数重新生成 mappeek-openapi.json / mappeek-api.md */
    private void regenerateDocs(boolean interactive) {
        if (currentDir == null) {
            return;
        }
        Map<String, String> props = readProps(currentDir.resolve("mappeek-target.properties"));
        if (props.get("src") == null) {
            if (interactive) {
                notifyUser(I18n.t("Saved", "已保存"),
                        I18n.t("Scan parameters are missing, so the docs were not regenerated. "
                                        + "Please run a scan again.",
                                "缺少扫描参数，未重新生成文档，请重新扫描一次。"),
                        NotificationType.WARNING);
            }
            return;
        }
        if (regenerating) {
            regenPending = true;
            return;
        }
        regenerating = true;
        statusLabel.setText(I18n.t("Updating docs...", "正在更新文档…"));
        final Path dir = currentDir;
        ProgressManager.getInstance().run(new Task.Backgroundable(project,
                I18n.t("MapPeek: Updating docs", "MapPeek：更新文档"), false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText(I18n.t("Regenerating docs with the latest fields",
                        "正在按最新字段更新文档"));
                indicator.setIndeterminate(true);
                try {
                    ScanAction.RunResult result = ScanAction.runRaw(project, props.get("src"),
                            props.get("resolve"), props.get("only"), props.get("level"), dir);
                    ApplicationManager.getApplication().invokeLater(
                            () -> onRegenerated(dir, result, interactive));
                } catch (IOException | InterruptedException ex) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        regenerating = false;
                        statusLabel.setText(I18n.t("Doc update failed", "文档更新失败"));
                        notifyUser(I18n.t("Generation error", "生成异常"),
                                String.valueOf(ex.getMessage()), NotificationType.ERROR);
                    });
                }
            }
        });
    }

    private void onRegenerated(Path dir, ScanAction.RunResult result, boolean interactive) {
        regenerating = false;
        if (result.code() != 0) {
            copyWhenReady = false;
            statusLabel.setText(I18n.t("Doc update failed", "文档更新失败"));
            notifyUser(I18n.t("Doc generation failed (exit code " + result.code() + ")",
                            "生成文档失败（退出码 " + result.code() + "）"),
                    ScanAction.firstLines(result.output(), 8), NotificationType.ERROR);
        } else if (interactive) {
            loadRows(dir);
            notifyUser(I18n.t("Saved and docs updated", "已保存并更新文档"),
                    I18n.t("Regenerated mappeek-api.md and mappeek-openapi.json.",
                            "已重新生成 mappeek-api.md 和 mappeek-openapi.json。"),
                    NotificationType.INFORMATION);
        } else {
            statusLabel.setText(I18n.t("Auto-saved", "已自动保存") + " "
                    + java.time.LocalTime.now().withNano(0));
        }
        if (dirty || regenPending) {
            regenPending = false;
            autoSaveTimer.restart();
        } else if (copyWhenReady) {
            copyWhenReady = false;
            copyOpenApi();
        }
    }

    private boolean commitAndSave(boolean interactive) {
        if (currentDir == null) {
            if (interactive) {
                notifyUser(I18n.t("Nothing to save", "没有可保存的字段"),
                        I18n.t("Run a scan in the project first.", "请先在项目里执行扫描。"),
                        NotificationType.WARNING);
            }
            return false;
        }
        if ((requestTable.isEditing() && !requestTable.getCellEditor().stopCellEditing())
                || (responseTable.isEditing() && !responseTable.getCellEditor().stopCellEditing())) {
            if (interactive) {
                Messages.showWarningDialog(project,
                        I18n.t("Finish editing the current cell before saving.",
                                "请先完成当前单元格的编辑再保存。"), "MapPeek");
            }
            return false;
        }
        LinkedHashMap<String, String> summaries = new LinkedHashMap<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            Object title = model.getValueAt(i, 1);
            String text = title == null ? "" : title.toString().trim();
            if (!text.isEmpty()) {
                summaries.putIfAbsent(rows.get(i)[0], text);
            }
        }
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            String[] origin = rows.get(i);
            Object desc = model.getValueAt(i, 6);
            Object typeCell = model.getValueAt(i, 4);
            String type = normalizeType(typeCell == null ? "" : typeCell.toString(), origin[3]);
            String summary = summaries.get(origin[0]);
            out.add(new String[]{origin[0], origin[1], origin[2], type, origin[4], origin[5],
                    desc == null ? "" : desc.toString().trim(),
                    summary == null ? "" : summary});
        }
        try {
            FieldCsv.write(currentDir.resolve("mappeek-edit.csv"), out);
            rows = out;
            dirty = false;
            return true;
        } catch (IOException ex) {
            if (interactive) {
                Messages.showErrorDialog(project, String.valueOf(ex.getMessage()),
                        I18n.t("Save failed", "保存失败"));
            } else {
                statusLabel.setText(I18n.t("Auto-save failed", "自动保存失败"));
                notifyUser(I18n.t("Auto-save failed", "自动保存失败"),
                        String.valueOf(ex.getMessage()), NotificationType.ERROR);
            }
            return false;
        }
    }

    private Map<String, String> readProps(Path file) {
        Map<String, String> map = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return map;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int i = line.indexOf('=');
                if (i > 0) {
                    map.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
                }
            }
        } catch (IOException ignored) {
            // 缺少参数时由调用方提示
        }
        return map;
    }

    // ==================== 预览生成 ====================

    private void updatePreview() {
        preview.setText(buildDocument());
        preview.setCaretPosition(0);
    }

    private String buildDocument() {
        LinkedHashMap<String, LinkedHashMap<String, Node>> data = collectTree();
        LinkedHashMap<String, String> titles = new LinkedHashMap<>();
        for (int i = 0; i < model.getRowCount() && i < rows.size(); i++) {
            Object title = model.getValueAt(i, 1);
            String text = title == null ? "" : title.toString().trim();
            if (!text.isEmpty()) {
                titles.putIfAbsent(rows.get(i)[0], text);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, LinkedHashMap<String, Node>> api : data.entrySet()) {
            sb.append("## ").append(api.getKey());
            String title = titles.get(api.getKey());
            if (title != null) {
                sb.append(" — ").append(title);
            }
            sb.append("\n\n");
            for (Map.Entry<String, Node> dir : api.getValue().entrySet()) {
                sb.append("### ").append(dir.getKey()).append("\n\n```json\n");
                sb.append(renderRoot(dir.getValue())).append("\n```\n\n");
            }
        }
        return sb.toString().trim();
    }

    private static String directionOf(String raw) {
        return "request".equals(raw) ? I18n.t("Request", "入参") : I18n.t("Response", "出参");
    }

    private LinkedHashMap<String, LinkedHashMap<String, Node>> collectTree() {
        LinkedHashMap<String, LinkedHashMap<String, Node>> data = new LinkedHashMap<>();
        for (int i = 0; i < model.getRowCount() && i < rows.size(); i++) {
            String[] origin = rows.get(i);
            String direction = directionOf(origin[1]);
            Object desc = model.getValueAt(i, 6);
            Node root = data.computeIfAbsent(origin[0], k -> new LinkedHashMap<>())
                    .computeIfAbsent(direction, k -> new Node());
            root.insert(origin[2], typeAt(i), desc == null ? "" : desc.toString().trim(),
                    "true".equalsIgnoreCase(origin[4]));
        }
        return data;
    }

    /** direction 为 request / response 时只导出该方向的 JSON Schema，null 表示入参 + 出参 */
    private String buildSchema(String directionFilter) {
        LinkedHashMap<String, Node> sections = collectSections(directionFilter);
        if (sections.isEmpty()) {
            return "";
        }
        if (sections.size() == 1) {
            return schemaOf(sections.values().iterator().next(), 0);
        }
        StringBuilder sb = new StringBuilder("{\n");
        int index = 0;
        for (Map.Entry<String, Node> entry : sections.entrySet()) {
            index++;
            sb.append(pad(1)).append(jsonStr(entry.getKey())).append(": ")
                    .append(schemaOf(entry.getValue(), 1));
            if (index < sections.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append('}');
        return sb.toString();
    }

    /** 复制核心生成的 OpenAPI 文档；有未落盘的编辑时先保存并等文档更新完再复制 */
    private void copyOpenApi() {
        if (currentDir == null) {
            notifyUser(I18n.t("Nothing to copy", "没有可复制的文档"),
                    I18n.t("Run a scan in the project first.", "请先在项目里执行扫描。"),
                    NotificationType.WARNING);
            return;
        }
        if (dirty) {
            autoSaveTimer.stop();
            if (!commitAndSave(false)) {
                return;
            }
            regenerateDocs(false);
        }
        if (regenerating) {
            copyWhenReady = true;
            notifyUser(I18n.t("Updating docs", "正在更新文档"),
                    I18n.t("The document will be copied to the clipboard as soon as it is up to date.",
                            "文档更新完成后会自动复制到剪贴板。"),
                    NotificationType.INFORMATION);
            return;
        }
        Path file = currentDir.resolve("mappeek-openapi.json");
        if (!Files.isRegularFile(file)) {
            notifyUser(I18n.t("OpenAPI file not found", "未找到 OpenAPI 文件"),
                    I18n.t("Run a scan again to generate mappeek-openapi.json.",
                            "请重新扫描一次以生成 mappeek-openapi.json。"),
                    NotificationType.WARNING);
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            CopyPasteManager.getInstance().setContents(new StringSelection(text));
            notifyUser(I18n.t("OpenAPI copied", "OpenAPI 文档已复制"),
                    I18n.t("Save it as .json and import into Apifox, or paste it into an existing OpenAPI file.",
                            "保存为 .json 即可导入 Apifox，或粘贴进已有的 OpenAPI 文件。"),
                    NotificationType.INFORMATION);
        } catch (IOException ex) {
            notifyUser(I18n.t("Failed to read OpenAPI", "读取 OpenAPI 失败"),
                    String.valueOf(ex.getMessage()), NotificationType.ERROR);
        }
    }

    /** direction 为 request / response 时只收集该方向，null 表示全部；不受表格选中行影响 */
    private LinkedHashMap<String, Node> collectSections(String directionFilter) {
        LinkedHashMap<String, Node> sections = new LinkedHashMap<>();
        for (int i = 0; i < model.getRowCount() && i < rows.size(); i++) {
            String[] origin = rows.get(i);
            if (directionFilter != null && !directionFilter.equals(origin[1])) {
                continue;
            }
            String direction = directionOf(origin[1]);
            Object desc = model.getValueAt(i, 6);
            Node root = sections.computeIfAbsent(origin[0] + " " + direction, k -> new Node());
            root.insert(origin[2], typeAt(i), desc == null ? "" : desc.toString().trim(),
                    "true".equalsIgnoreCase(origin[4]));
        }
        return sections;
    }

    private static String schemaOf(Node node, int indent) {
        List<String> parts = new ArrayList<>();
        parts.add(jsonStr("type") + ": " + jsonStr("object"));
        if (!node.desc.isEmpty()) {
            parts.add(jsonStr("description") + ": " + jsonStr(node.desc));
        }
        List<String> required = new ArrayList<>();
        for (Map.Entry<String, Node> entry : node.children.entrySet()) {
            if (entry.getValue().required) {
                required.add(jsonStr(entry.getKey()));
            }
        }
        if (!required.isEmpty()) {
            parts.add(jsonStr("required") + ": [" + String.join(", ", required) + "]");
        }
        List<String> props = new ArrayList<>();
        for (Map.Entry<String, Node> entry : node.children.entrySet()) {
            props.add(pad(indent + 2) + jsonStr(entry.getKey()) + ": "
                    + valueOf(entry.getValue(), indent + 2));
        }
        if (props.isEmpty()) {
            parts.add(jsonStr("properties") + ": {}");
        } else {
            parts.add(jsonStr("properties") + ": {\n" + String.join(",\n", props) + "\n"
                    + pad(indent + 1) + "}");
        }
        return "{\n" + pad(indent + 1) + String.join(",\n" + pad(indent + 1), parts)
                + "\n" + pad(indent) + "}";
    }

    private static String valueOf(Node node, int indent) {
        if (!node.children.isEmpty()) {
            if (node.array) {
                return "{\n" + pad(indent + 1) + jsonStr("type") + ": " + jsonStr("array") + ",\n"
                        + pad(indent + 1) + jsonStr("items") + ": "
                        + schemaOf(node, indent + 1) + "\n" + pad(indent) + "}";
            }
            return schemaOf(node, indent);
        }
        String type = jsonType(node.type);
        StringBuilder sb = new StringBuilder("{\n").append(pad(indent + 1))
                .append(jsonStr("type")).append(": ").append(jsonStr(type));
        if (node.array || "array".equals(type)) {
            sb.append(",\n").append(pad(indent + 1)).append(jsonStr("items")).append(": {")
                    .append(jsonStr("type")).append(": ").append(jsonStr("string")).append('}');
        }
        if (!node.desc.isEmpty()) {
            sb.append(",\n").append(pad(indent + 1)).append(jsonStr("description")).append(": ")
                    .append(jsonStr(node.desc));
        }
        sb.append('\n').append(pad(indent)).append('}');
        return sb.toString();
    }

    private static String jsonType(String raw) {
        String type = raw == null ? "" : raw.toLowerCase();
        if (type.contains("int") || type.contains("long") || type.contains("short")) {
            return "integer";
        }
        if (type.contains("double") || type.contains("float") || type.contains("decimal")
                || type.contains("number")) {
            return "number";
        }
        if (type.contains("bool")) {
            return "boolean";
        }
        if (type.contains("list") || type.contains("array")) {
            return "array";
        }
        if (type.contains("map") || type.contains("object")) {
            return "object";
        }
        return "string";
    }

    private static String jsonStr(String value) {
        return "\"" + escape(value) + "\"";
    }

    private void copy(String text, String message) {
        if (text == null || text.isEmpty()) {
            notifyUser(I18n.t("Nothing to copy", "没有可复制的内容"),
                    I18n.t("Run a scan or add field descriptions first.",
                            "先扫描或补充字段说明。"), NotificationType.WARNING);
            return;
        }
        CopyPasteManager.getInstance().setContents(new StringSelection(text));
        notifyUser(message, I18n.t("Copied to clipboard; paste it into Apifox or any JSON Schema importer.",
                        "已复制到剪贴板，可直接导入 Apifox 等工具的 JSON Schema。"),
                NotificationType.INFORMATION);
    }

    private void notifyUser(String title, String content, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("MapPeek")
                .createNotification(title, content, type)
                .notify(project);
    }

    // ==================== 结构树 ====================

    private static final class Node {
        final LinkedHashMap<String, Node> children = new LinkedHashMap<>();
        String type = "";
        String desc = "";
        boolean required;
        boolean array;

        void insert(String path, String type, String desc, boolean required) {
            Node cur = this;
            for (String part : path.split("\\.")) {
                boolean isArray = part.endsWith("[]");
                String name = isArray ? part.substring(0, part.length() - 2) : part;
                Node child = cur.children.computeIfAbsent(name, k -> new Node());
                child.array = child.array || isArray;
                cur = child;
            }
            cur.type = type;
            cur.desc = desc;
            cur.required = required;
        }
    }

    private static String renderRoot(Node root) {
        List<String> lines = new ArrayList<>();
        lines.add("{");
        renderChildren(root, 1, lines);
        lines.add("}");
        return String.join("\n", lines);
    }

    private static void renderChildren(Node parent, int indent, List<String> out) {
        int count = parent.children.size();
        int index = 0;
        for (Map.Entry<String, Node> entry : parent.children.entrySet()) {
            index++;
            boolean last = index == count;
            Node node = entry.getValue();
            String prefix = pad(indent) + "\"" + entry.getKey() + "\": ";
            String comment = commentOf(node);
            if (node.children.isEmpty()) {
                out.add(prefix + "\"" + escape(node.desc) + "\"" + (last ? "" : ",") + comment);
            } else if (node.array) {
                out.add(prefix + "[" + comment);
                out.add(pad(indent + 1) + "{");
                renderChildren(node, indent + 2, out);
                out.add(pad(indent + 1) + "}" + (last ? "" : ","));
                out.add(pad(indent) + "]" + (last ? "" : ","));
            } else {
                out.add(prefix + "{" + comment);
                renderChildren(node, indent + 1, out);
                out.add(pad(indent) + "}" + (last ? "" : ","));
            }
        }
    }

    private static String commentOf(Node node) {
        String type = node.type == null ? "" : node.type;
        if (type.isEmpty()) {
            return "";
        }
        return "  // " + type + (node.required ? I18n.t(" required", " 必填") : "");
    }

    private static String pad(int indent) {
        return "  ".repeat(Math.max(0, indent));
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

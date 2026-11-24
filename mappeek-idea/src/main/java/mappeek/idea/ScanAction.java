package mappeek.idea;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.ContentManager;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 右键项目/目录或在 Tools 菜单触发，对选中目录跑一遍 MapPeek M0 扫描，并把报告直接打开。
 */
public class ScanAction extends AnAction {

    private static final Pattern NUM = Pattern.compile("(\\d+)");

    public ScanAction() {
        getTemplatePresentation().setText(
                I18n.t("Scan & Edit Fields (MapPeek)", "扫描并编辑字段（MapPeek）"));
        getTemplatePresentation().setDescription(I18n.t(
                "Scans Map fields, opens the editor, and regenerates docs on save",
                "扫描 Map 字段后直接弹出编辑页，保存即生成文档"));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }
        Target target = resolveTarget(event, project);
        if (target == null) {
            notifyUser(project, I18n.t("Nothing to scan", "没有可分析的目标"),
                    I18n.t("Select a directory, package or class in the project tree, "
                                    + "or place the caret inside an endpoint method.",
                            "请在项目树中选择目录、包或类，或把光标放在某个接口方法里再执行。"),
                    NotificationType.WARNING);
            return;
        }
        String level = detectLevel(project);

        ProgressManager.getInstance().run(new Task.Backgroundable(project,
                I18n.t("MapPeek: Scanning Map fields", "MapPeek：扫描 Map 字段"), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText(I18n.t("Scanning", "正在扫描") + " " + target.label());
                indicator.setIndeterminate(true);
                RunResult result;
                try {
                    result = runScan(project, target, level);
                } catch (IOException | InterruptedException ex) {
                    notifyUser(project, I18n.t("Scan error", "扫描异常"),
                            String.valueOf(ex.getMessage()), NotificationType.ERROR);
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> afterScan(project, target, result));
            }
        });
    }

    /** 扫描完成后打开工具窗口，在页面上直接编辑说明与复制文档 */
    static void afterScan(Project project, Target target, RunResult result) {
        if (result.code != 0) {
            notifyUser(project, I18n.t("Scan failed (exit code " + result.code + ")",
                            "扫描失败（退出码 " + result.code + "）"),
                    firstLines(result.output, 8), NotificationType.ERROR);
            return;
        }
        openToolWindow(project, result.outDir);
        long apiCount = FieldCsv.readRows(result.outDir.resolve("mappeek-edit.csv")).stream()
                .map(r -> r[0]).distinct().count();
        notifyUser(project, I18n.t("Map field scan finished", "Map 字段扫描完成"),
                I18n.t(apiCount + " endpoints found; opened in the MapPeek window, "
                                + "where you can edit descriptions and copy the docs.",
                        "共 " + apiCount + " 个接口，已在 MapPeek 窗口打开，可编辑说明并复制文档。"),
                NotificationType.INFORMATION);
    }

    private static final Map<Project, Path> PENDING = new ConcurrentHashMap<>();

    static Path pendingDir(Project project) {
        return PENDING.get(project);
    }

    static void openToolWindow(Project project, Path outDir) {
        if (outDir != null) {
            PENDING.put(project, outDir);
        }
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("MapPeek");
        if (window == null) {
            return;
        }
        window.activate(null);
        ApplicationManager.getApplication().invokeLater(() -> {
            ContentManager manager = window.getContentManager();
            if (manager.getContentCount() > 0) {
                java.awt.Component component = manager.getContent(0).getComponent();
                if (component instanceof FieldsPanel panel && outDir != null) {
                    panel.reload(outDir);
                }
            }
        });
    }

    record RunResult(int code, String output, Path outDir) {
    }

    static RunResult runScan(Project project, Target target, String level)
            throws IOException, InterruptedException {
        Path outDir = Paths.get(project.getBasePath() == null ? target.root() : project.getBasePath())
                .resolve(".mappeek").resolve(sanitize(target.label()));
        RunResult result = runRaw(project, target.path(), target.root(), target.only(), level, outDir);
        if (result.code() == 0) {
            String text = "src=" + target.path().replace('\\', '/') + "\n"
                    + "resolve=" + target.root().replace('\\', '/') + "\n"
                    + "only=" + (target.only() == null ? "" : target.only()) + "\n"
                    + "level=" + level + "\n";
            Files.writeString(outDir.resolve("mappeek-target.properties"), text, StandardCharsets.UTF_8);
        }
        return result;
    }

    /** 按显式参数执行一次扫描，供工具窗口重新生成时复用 */
    static RunResult runRaw(Project project, String src, String resolve, String only,
                            String level, Path outDir) throws IOException, InterruptedException {
        Path jar = locateJar();
        if (jar == null) {
            throw new IOException(I18n.t("lib/mappeek.jar is missing from the plugin directory; "
                    + "please reinstall the plugin",
                    "插件安装目录下缺少 lib/mappeek.jar，请重新安装插件"));
        }
        String javaExe = javaExecutable();
        if (javaExe == null) {
            throw new IOException(I18n.t("Cannot locate the JBR java executable",
                    "无法定位 JBR 的 java 可执行文件"));
        }
        Files.createDirectories(outDir);

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-jar");
        cmd.add(jar.toString());
        cmd.add("--src");
        cmd.add(src);
        cmd.add("--resolve");
        cmd.add(resolve);
        cmd.add("--out");
        cmd.add(outDir.toString());
        cmd.add("--level");
        cmd.add(level);
        cmd.add("--format");
        cmd.add(src.endsWith(".java") ? "openapi,md,dto" : "openapi,md");
        if (only != null && !only.isBlank()) {
            cmd.add("--only");
            cmd.add(only);
        }
        Path overrides = outDir.resolve("mappeek-edit.csv");
        if (Files.isRegularFile(overrides)) {
            cmd.add("--overrides");
            cmd.add(overrides.toString());
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = readAll(process);
        int code = process.waitFor();
        return new RunResult(code, output, outDir);
    }

    record Target(String path, String root, String only, String label) {
    }

    /**
     * 解析当前操作的目标：
     * 项目树选中目录/包 -> 只分析该目录；选中类 -> 只分析该类；
     * 光标在方法内 -> 只分析该接口；什么都没选 -> 整个项目。
     */
    private static Target resolveTarget(AnActionEvent event, Project project) {
        VirtualFile vf = firstSelected(event);
        if (vf == null) {
            vf = project.getBaseDir();
        }
        if (vf == null) {
            return null;
        }
        String only = methodAtCaret(event);
        String root = contentRootOf(project, vf);
        return new Target(vf.getPath(), root, only, labelOf(vf, only));
    }

    private static VirtualFile firstSelected(AnActionEvent event) {
        VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (files != null && files.length > 0) {
            return files[0];
        }
        PsiFile psiFile = event.getData(CommonDataKeys.PSI_FILE);
        if (psiFile != null) {
            return psiFile.getVirtualFile();
        }
        return event.getData(CommonDataKeys.VIRTUAL_FILE);
    }

    /** 用项目根作为解析范围，保证能找到其它模块里的 DTO / VO；范围外的接口不会被扫描 */
    private static String contentRootOf(Project project, VirtualFile vf) {
        String base = project.getBasePath();
        if (base != null) {
            return base;
        }
        try {
            VirtualFile root = ProjectRootManager.getInstance(project).getFileIndex().getContentRootForFile(vf);
            if (root != null) {
                return root.getPath();
            }
        } catch (Throwable ignored) {
            // 回退到选中路径
        }
        return vf.getPath();
    }

    private static String methodAtCaret(AnActionEvent event) {
        try {
            PsiFile psiFile = event.getData(CommonDataKeys.PSI_FILE);
            Editor editor = event.getData(CommonDataKeys.EDITOR);
            if (psiFile == null || editor == null) {
                return null;
            }
            PsiElement element = psiFile.findElementAt(editor.getCaretModel().getOffset());
            PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
            PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
            if (method == null || psiClass == null || psiClass.getQualifiedName() == null) {
                return null;
            }
            return psiClass.getQualifiedName() + "#" + method.getName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String labelOf(VirtualFile vf, String only) {
        String name = vf.getName().replaceAll("\\.java$", "");
        if (only == null) {
            return name;
        }
        int i = only.lastIndexOf('#');
        return name + "-" + (i >= 0 ? only.substring(i + 1) : only);
    }

    static Path locateJar() {
        try {
            IdeaPluginDescriptor desc = PluginManagerCore.getPlugin(PluginId.getId("mappeek.idea"));
            if (desc != null) {
                Path base = desc.getPluginPath();
                Path jar = base.resolve("lib").resolve("mappeek.jar");
                if (Files.isRegularFile(jar)) {
                    return jar;
                }
            }
        } catch (Throwable ignored) {
            // 回退到插件目录约定
        }
        Path fallback = Paths.get(PathManager.getPluginsPath(), "mappeek-idea", "lib", "mappeek.jar");
        return Files.isRegularFile(fallback) ? fallback : null;
    }

    static String javaExecutable() {
        String home = System.getProperty("java.home");
        if (home == null) {
            return null;
        }
        Path exe = Paths.get(home, "bin", isWindows() ? "java.exe" : "java");
        return Files.isExecutable(exe) ? exe.toString() : null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static String detectLevel(Project project) {
        try {
            Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
            if (sdk != null) {
                String mapped = majorOf(sdk.getVersionString());
                if (mapped != null) {
                    return mapped;
                }
            }
        } catch (Throwable ignored) {
            // 忽略，走默认值
        }
        return "17";
    }

    private static String majorOf(String version) {
        if (version == null) {
            return null;
        }
        if (version.startsWith("1.8") || version.startsWith("1.7")) {
            return "8";
        }
        Matcher m = NUM.matcher(version);
        if (m.find()) {
            int major = Integer.parseInt(m.group(1));
            if (major >= 8 && major <= 99) {
                return String.valueOf(major);
            }
        }
        return null;
    }

    static String summarize(Path outDir) {
        try {
            Path gt = outDir.resolve("mappeek-groundtruth.csv");
            Path fields = outDir.resolve("mappeek-fields.csv");
            long apis = Files.isRegularFile(gt) ? Math.max(0, Files.readAllLines(gt, StandardCharsets.UTF_8).size() - 1) : 0;
            long fieldCount = Files.isRegularFile(fields)
                    ? Math.max(0, Files.readAllLines(fields, StandardCharsets.UTF_8).size() - 1) : 0;
            return apis + " Map endpoints, " + fieldCount + " fields";
        } catch (IOException e) {
            return "Scan finished";
        }
    }

    static String readAll(Process process) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    static String firstLines(String text, int count) {
        String[] lines = text.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(count, lines.length); i++) {
            if (!lines[i].isBlank()) {
                sb.append(lines[i]).append('\n');
            }
        }
        return sb.toString();
    }

    static void openFile(Project project, Path path) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString());
        if (vf != null) {
            FileEditorManager.getInstance(project).openFile(vf, true);
        }
    }

    static String sanitize(String name) {
        return name == null ? "project" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    static void notifyUser(Project project, String title, String content, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("MapPeek")
                .createNotification(title, content, type)
                .notify(project);
    }
}

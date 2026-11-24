package mappeek.idea;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

/** 打开 MapPeek 工具窗口查看/编辑上次扫描结果，不重新扫描 */
public class EditFieldsAction extends AnAction {

    public EditFieldsAction() {
        getTemplatePresentation().setText(
                I18n.t("Edit Field Descriptions (MapPeek)", "编辑字段说明（MapPeek）"));
        getTemplatePresentation().setDescription(I18n.t(
                "Reviews parsed field structures and edits descriptions",
                "查看解析出的字段结构，手工完善字段说明"));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null || project.getBasePath() == null) {
            return;
        }
        Path dir = resolveTargetDir(project, firstSelected(event));
        if (dir == null) {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("MapPeek")
                    .createNotification(I18n.t("No scan result yet", "还没有扫描结果"),
                            I18n.t("Run \"Scan & Edit Fields (MapPeek)\" first.",
                                    "请先执行「扫描并编辑字段（MapPeek）」。"),
                            NotificationType.WARNING)
                    .notify(project);
            return;
        }
        ScanAction.openToolWindow(project, dir);
    }

    private static VirtualFile firstSelected(AnActionEvent event) {
        VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (files != null && files.length > 0) {
            return files[0];
        }
        return event.getData(CommonDataKeys.VIRTUAL_FILE);
    }

    /** 先按选中目标定位；对不上就取最近修改过的一份 */
    private static Path resolveTargetDir(Project project, VirtualFile selected) {
        Path base = Paths.get(project.getBasePath()).resolve(".mappeek");
        VirtualFile anchor = selected != null ? selected : project.getBaseDir();
        if (anchor != null) {
            String label = anchor.isDirectory() ? anchor.getName()
                    : anchor.getName().replaceAll("\\.java$", "");
            Path exact = base.resolve(ScanAction.sanitize(label));
            if (Files.isRegularFile(exact.resolve("mappeek-edit.csv"))) {
                return exact;
            }
        }
        if (!Files.isDirectory(base)) {
            return null;
        }
        try (Stream<Path> dirs = Files.list(base)) {
            return dirs.filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("mappeek-edit.csv")))
                    .max(Comparator.comparingLong(EditFieldsAction::lastModified))
                    .orElse(null);
        } catch (IOException ex) {
            return null;
        }
    }

    private static long lastModified(Path dir) {
        try {
            return Files.getLastModifiedTime(dir.resolve("mappeek-edit.csv")).toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }
}

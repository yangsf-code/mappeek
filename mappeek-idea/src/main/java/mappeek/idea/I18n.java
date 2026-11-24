package mappeek.idea;

/** 界面统一中英双语：中文在前，英文在后 */
final class I18n {

    private I18n() {
    }

    static String t(String en, String zh) {
        return zh + " / " + en;
    }
}

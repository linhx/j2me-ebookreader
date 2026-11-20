/**
 * Lightweight localization (i18n) for J2ME CLDC 1.1 / MIDP 2.0.
 * Supports Vietnamese (VI) and English (EN).
 */
public class L10n {
    public static final int VI = 0;
    public static final int EN = 1;

    private static int lang = -1;

    public static int getLang() {
        if (lang < 0) {
            lang = Store.getInt("lang", -1);
            if (lang < 0) {
                try {
                    String loc = System.getProperty("microedition.locale");
                    if (loc != null && loc.toLowerCase().startsWith("vi")) {
                        lang = VI;
                    } else {
                        lang = EN;
                    }
                } catch (Exception e) {
                    lang = VI;
                }
            }
        }
        return lang;
    }

    public static void setLang(int l) {
        lang = (l == EN) ? EN : VI;
        Store.putInt("lang", lang);
    }

    public static void toggleLang() {
        setLang((getLang() == VI) ? EN : VI);
    }

    public static String t(String vi, String en) {
        return (getLang() == EN) ? en : vi;
    }

    public static String langName() {
        return (getLang() == EN) ? "English" : "Tiếng Việt";
    }

    public static String[] fontSizes() {
        return (getLang() == EN)
                ? new String[]{"small", "medium", "large"}
                : new String[]{"nhỏ", "vừa", "lớn"};
    }

    public static String[] uiThemes() {
        return (getLang() == EN)
                ? new String[]{"light", "dark"}
                : new String[]{"sáng", "tối"};
    }

    public static String[] readThemes() {
        return (getLang() == EN)
                ? new String[]{"light", "dark", "sepia"}
                : new String[]{"sáng", "tối", "sepia"};
    }
}

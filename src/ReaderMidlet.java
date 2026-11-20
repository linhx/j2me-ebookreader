import java.io.IOException;
import java.util.Enumeration;
import java.util.Vector;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.io.file.FileSystemRegistry;
import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Gauge;
import javax.microedition.midlet.MIDlet;

public class ReaderMidlet extends MIDlet implements CommandListener, ListScreen.Listener {
    private Display display;
    private ReaderCanvas reader;

    private Command cBack = new Command(L10n.t("Quay lại", "Back"), Command.BACK, 1);
    private final Command cOk = new Command("OK", Command.OK, 1);

    private ListScreen menu, browser, toc;
    private Vector menuActions = new Vector();
    private ListScreen libMenu;
    private Vector libMenuActions = new Vector();
    private ListScreen settingsScreen;
    private Vector settingsActions = new Vector();
    private Displayable settingsBack = null;
    private Displayable backScreen = null;
    private Form gotoForm;
    private Gauge gotoGauge;

    // file browser state
    private static final int TAB_FAV = 0, TAB_ALL = 1;
    private int libraryTab = TAB_FAV;
    private boolean defaultTabPending = true;
    private String curDir = null; // null => roots
    private String library = null; // chosen books folder
    private boolean chooseMode = false; // true while picking the books folder
    private boolean hasUpItem = false;
    private Vector entries = new Vector(); // names (dirs end with '/')

    protected void startApp() {
        if (display != null) return;
        display = Display.getDisplay(this);
        reader = new ReaderCanvas(this);
        library = Store.get("lib", "");
        if (library.length() == 0) library = null;
        if (library != null) showLibrary();
        else showFolderPicker();
    }

    protected void pauseApp() {
        reader.saveState();
    }

    protected void destroyApp(boolean unconditional) {
        reader.saveState();
    }

    private void quit() {
        reader.saveState();
        destroyApp(true);
        notifyDestroyed();
    }

    // ------------------------------------------------------------ messages
    public void showError(String msg) {
        Alert a = new Alert("Lỗi", msg, null, AlertType.ERROR);
        a.setTimeout(Alert.FOREVER);
        Displayable back = display.getCurrent();
        if (back == null || back instanceof Alert) back = reader;
        display.setCurrent(a, back);
    }

    private void showWait(String msg) {
        Displayable cur = display.getCurrent();
        if (cur instanceof ListScreen) {
            ((ListScreen) cur).setLoading(msg);
            return;
        }
        ListScreen wait = browser != null ? browser : new ListScreen(this, "BookReader");
        wait.setLoading(msg);
        display.setCurrent(wait);
    }

    // ------------------------------------------------------------ open
    private void openFile(final String url) {
        Store.putLong("t:" + url, System.currentTimeMillis());
        showWait(L10n.t("Đang mở sách...", "Opening book..."));
        if (reader != null) reader.closeBook();
        toc = null;
        menu = null;
        entries.removeAllElements();
        System.gc();
        new Thread(new Runnable() {
            public void run() {
                try {
                    Book b = Book.open(url);
                    browser = null;
                    reader.openBook(url, b);
                    display.setCurrent(reader);
                } catch (Throwable e) {
                    System.gc();
                    String m = (e instanceof OutOfMemoryError)
                            ? L10n.t("Hết bộ nhớ khi mở sách.", "Out of memory while opening book.")
                            : (L10n.t("Không mở được: ", "Cannot open: ") + e);
                    showBrowser();
                    showError(m);
                }
            }
        }).start();
    }

    // ------------------------------------------------------------ menu
    public void showMenu() {
        reader.saveState();
        menu = new ListScreen(this, "Menu");
        menuActions = new Vector();

        menu.add(L10n.t("Tiếp tục đọc", "Resume reading"), Ui.I_BOOK);
        menuActions.addElement("resume");

        menu.add(L10n.t("Mục lục", "Contents"), Ui.I_PAGE);
        menuActions.addElement("toc");

        menu.add(L10n.t("Đi tới %", "Go to %"), Ui.I_PAGE);
        menuActions.addElement("goto");

        String curUrl = reader.getUrl();
        boolean isFav = curUrl != null && Store.isFavorite(curUrl);
        menu.add(isFav ? L10n.t("Bỏ yêu thích", "Remove from favorites")
                       : L10n.t("Thêm vào yêu thích", "Add to favorites"), Ui.I_STAR);
        menuActions.addElement("fav");

        menu.add(L10n.t("Mục ưa thích", "Favorites tab"), Ui.I_STAR);
        menuActions.addElement("tab_fav");

        menu.add(L10n.t("Tất cả sách", "All books tab"), Ui.I_BOOK);
        menuActions.addElement("tab_all");

        menu.add(L10n.t("Cài đặt", "Settings"), Ui.I_GEAR);
        menuActions.addElement("settings");

        menu.add(L10n.t("Hướng dẫn phím", "Key shortcuts"), Ui.I_PAGE);
        menuActions.addElement("help");

        menu.add(L10n.t("Giới thiệu", "About"), Ui.I_BOOK);
        menuActions.addElement("about");

        menu.add(L10n.t("Thoát", "Exit"), Ui.I_GEAR);
        menuActions.addElement("quit");

        menu.setSoftKeys(L10n.t("Chọn", "Select"), L10n.t("Quay lại", "Back"));
        display.setCurrent(menu);
    }

    private void handleMenuSelect(int idx) {
        if (idx < 0 || idx >= menuActions.size()) return;
        String action = (String) menuActions.elementAt(idx);
        if ("resume".equals(action)) {
            display.setCurrent(reader);
        } else if ("toc".equals(action)) {
            showToc();
        } else if ("goto".equals(action)) {
            showGoto();
        } else if ("fav".equals(action)) {
            String curUrl = reader.getUrl();
            if (curUrl != null) {
                boolean isFav = Store.isFavorite(curUrl);
                Store.setFavorite(curUrl, !isFav);
                showMenu();
                menu.setSelected(idx);
            }
        } else if ("tab_fav".equals(action)) {
            showLibraryTab(TAB_FAV);
        } else if ("tab_all".equals(action)) {
            showLibraryTab(TAB_ALL);
        } else if ("settings".equals(action)) {
            showSettings(menu);
        } else if ("help".equals(action)) {
            backScreen = menu;
            showHelp();
        } else if ("about".equals(action)) {
            backScreen = menu;
            showAbout();
        } else if ("quit".equals(action)) {
            quit();
        }
    }

    public void showToc() {
        Book b = reader.getBook();
        if (b == null) return;
        reader.saveState();
        toc = new ListScreen(this, L10n.t("Mục lục", "Contents"));
        for (int i = 0; i < b.count(); i++) toc.add(b.titles[i], Ui.I_PAGE);
        toc.setSelected(reader.getChapter());
        toc.setSoftKeys(L10n.t("Mở", "Open"), L10n.t("Quay lại", "Back"));
        display.setCurrent(toc);
    }

    private void showGoto() {
        gotoForm = new Form(L10n.t("Đi tới %", "Go to %"));
        gotoGauge = new Gauge(L10n.t("Vị trí (%)", "Position (%)"), true, 100, 0);
        gotoForm.append(gotoGauge);
        gotoForm.addCommand(cOk);
        gotoForm.addCommand(cBack);
        gotoForm.setCommandListener(this);
        display.setCurrent(gotoForm);
    }

    private void showHelp() {
        Form f = new Form(L10n.t("Phím tắt", "Key Shortcuts"));
        String help = (L10n.getLang() == L10n.EN)
                ? ("While reading:\n"
                + "Up/Down (2/8): scroll line\n"
                + "Left/Right (4/6), OK: prev/next page\n"
                + "1 / 3: prev / next chapter\n"
                + "7 / 9: prev / next footnote\n"
                + "Hold * + Up/Down: font size\n"
                + "#: reading theme (light / dark / sepia)\n\n"
                + "In book list:\n"
                + "Left/Right (4/6): Favorites / All books\n"
                + "*: Toggle favorite book\n"
                + "0: View full title\n"
                + "Left softkey: Menu\n"
                + "Right softkey: Exit / Back\n")
                : ("Khi đọc sách:\n"
                + "Lên/Xuống (2/8): cuộn dòng\n"
                + "Trái/Phải (4/6), OK: lùi/tiến trang\n"
                + "1 / 3: chương trước / sau\n"
                + "7 / 9: chú thích trước / sau\n"
                + "Giữ *, bấm Lên/Xuống: đổi cỡ chữ\n"
                + "#: đổi màu đọc (sáng / tối / sepia)\n\n"
                + "Trong danh sách sách:\n"
                + "Trái/Phải (4/6): đổi Ưa thích / Tất cả\n"
                + "*: Yêu thích / Bỏ yêu thích sách\n"
                + "0: Xem tên sách đầy đủ\n"
                + "Phím mềm trái: Menu\n"
                + "Phím mềm phải: Thoát / Quay lại\n");
        f.append(help);
        f.addCommand(cBack);
        f.setCommandListener(this);
        display.setCurrent(f);
    }

    private void showAbout() {
        Form f = new Form(L10n.t("Giới thiệu", "About"));
        String about = (L10n.getLang() == L10n.EN)
                ? ("BookReader v1.0\n"
                + "EPUB/HTML/TXT reader for Nokia C2-01 & Java J2ME devices.\n\n"
                + "Opera Mini 4.4 style.\n"
                + "Decoupled App UI and Reading themes.\n"
                + "Supports Favorites, text justify, footnotes and anti-OOM memory optimization.\n")
                : ("BookReader v1.0\n"
                + "Trình đọc sách EPUB/HTML/TXT cho Nokia C2-01 & thiết bị Java J2ME.\n\n"
                + "Thiết kế theo phong cách Opera Mini 4.4.\n"
                + "Tách biệt Giao diện ứng dụng và Màu đọc sách.\n"
                + "Hỗ trợ mục Ưa thích, căn đều 2 lề, xem chú thích và nén RAM thông minh.\n");
        f.append(about);
        f.addCommand(cBack);
        f.setCommandListener(this);
        display.setCurrent(f);
    }

    private String getSelectedBookUrl() {
        if (browser == null || chooseMode || curDir == null) return null;
        int idx = browser.getSelected();
        if (hasUpItem) idx--;
        if (idx < 0 || idx >= entries.size()) return null;
        String name = (String) entries.elementAt(idx);
        if (name == null || name.endsWith("/") || name.charAt(0) == '\u0001') return null;
        return curDir + name;
    }

    public void showLibraryMenu() {
        libMenu = new ListScreen(this, "Menu");
        libMenuActions = new Vector();

        if (reader != null && reader.getBook() != null) {
            libMenu.add(L10n.t("Tiếp tục đọc", "Resume reading"), Ui.I_BOOK);
            libMenuActions.addElement("resume");
        } else {
            String lastUrl = Store.get("last", "");
            if (lastUrl != null && lastUrl.length() > 0) {
                int sl = lastUrl.lastIndexOf('/');
                String n = sl >= 0 ? lastUrl.substring(sl + 1) : lastUrl;
                libMenu.add(L10n.t("Đọc tiếp: ", "Resume: ") + stripExt(n), Ui.I_BOOK);
                libMenuActions.addElement("last");
            }
        }

        String selBookUrl = getSelectedBookUrl();
        if (selBookUrl != null) {
            boolean isFav = Store.isFavorite(selBookUrl);
            libMenu.add(isFav ? L10n.t("Bỏ yêu thích", "Remove from favorites")
                              : L10n.t("Thêm vào yêu thích", "Add to favorites"), Ui.I_STAR);
            libMenuActions.addElement("fav");
        }

        libMenu.add(L10n.t("Cài đặt", "Settings"), Ui.I_GEAR);
        libMenuActions.addElement("settings");

        libMenu.add(L10n.t("Hướng dẫn phím", "Key shortcuts"), Ui.I_PAGE);
        libMenuActions.addElement("help");

        libMenu.add(L10n.t("Giới thiệu", "About"), Ui.I_BOOK);
        libMenuActions.addElement("about");

        libMenu.add(L10n.t("Thoát", "Exit"), Ui.I_GEAR);
        libMenuActions.addElement("quit");

        libMenu.setSoftKeys(L10n.t("Chọn", "Select"), L10n.t("Quay lại", "Back"));
        display.setCurrent(libMenu);
    }

    private void handleLibMenuSelect(int idx) {
        if (idx < 0 || idx >= libMenuActions.size()) return;
        String action = (String) libMenuActions.elementAt(idx);
        if ("resume".equals(action)) {
            display.setCurrent(reader);
        } else if ("last".equals(action)) {
            String lastUrl = Store.get("last", "");
            if (lastUrl != null && lastUrl.length() > 0) openFile(lastUrl);
        } else if ("fav".equals(action)) {
            String selBookUrl = getSelectedBookUrl();
            if (selBookUrl != null) {
                boolean isFav = Store.isFavorite(selBookUrl);
                Store.setFavorite(selBookUrl, !isFav);
                defaultTabPending = false;
                showLibraryTab(libraryTab);
            }
        } else if ("settings".equals(action)) {
            showSettings(libMenu);
        } else if ("help".equals(action)) {
            backScreen = libMenu;
            showHelp();
        } else if ("about".equals(action)) {
            backScreen = libMenu;
            showAbout();
        } else if ("quit".equals(action)) {
            quit();
        }
    }

    // ------------------------------------------------------------ settings
    public void showSettings(Displayable back) {
        if (back != null && back != settingsScreen) {
            settingsBack = back;
        }
        settingsScreen = new ListScreen(this, L10n.t("Cài đặt", "Settings"));
        settingsActions = new Vector();

        String[] uiTh = L10n.uiThemes();
        int curUi = Store.getInt("ui_theme", 1);
        if (curUi < 0 || curUi >= uiTh.length) curUi = 1;
        settingsScreen.add(L10n.t("Giao diện: ", "App theme: ") + uiTh[curUi], Ui.I_GEAR);
        settingsActions.addElement("ui_theme");

        String[] readTh = L10n.readThemes();
        int curTheme = (reader != null) ? reader.getTheme() : Store.getInt("theme", 0);
        if (curTheme < 0 || curTheme >= readTh.length) curTheme = 0;
        settingsScreen.add(L10n.t("Màu đọc: ", "Reading theme: ") + readTh[curTheme], Ui.I_GEAR);
        settingsActions.addElement("theme");

        String[] fs = L10n.fontSizes();
        int curFont = (reader != null) ? reader.getFontSize() : Store.getInt("font", 1);
        if (curFont < 0 || curFont >= fs.length) curFont = 1;
        settingsScreen.add(L10n.t("Cỡ chữ: ", "Font size: ") + fs[curFont], Ui.I_GEAR);
        settingsActions.addElement("font");

        settingsScreen.add(L10n.t("Ngôn ngữ: ", "Language: ") + L10n.langName(), Ui.I_GEAR);
        settingsActions.addElement("lang");

        settingsScreen.add(L10n.t("Đổi thư mục sách", "Change folder"), Ui.I_FOLDER);
        settingsActions.addElement("folder");

        settingsScreen.setSoftKeys(L10n.t("Chọn", "Select"), L10n.t("Quay lại", "Back"));
        display.setCurrent(settingsScreen);
    }

    private void handleSettingsSelect(int idx) {
        if (idx < 0 || idx >= settingsActions.size()) return;
        String action = (String) settingsActions.elementAt(idx);
        if ("ui_theme".equals(action)) {
            int curUi = Store.getInt("ui_theme", 1);
            int nextUi = (curUi + 1) % 2;
            Store.putInt("ui_theme", nextUi);
            if (reader != null) reader.repaint();
            showSettings(settingsBack);
            settingsScreen.setSelected(idx);
        } else if ("theme".equals(action)) {
            int curTheme = (reader != null) ? reader.getTheme() : Store.getInt("theme", 0);
            int nextTheme = (curTheme + 1) % 3;
            Store.putInt("theme", nextTheme);
            if (reader != null) reader.setTheme(nextTheme);
            showSettings(settingsBack);
            settingsScreen.setSelected(idx);
        } else if ("font".equals(action)) {
            int curFont = (reader != null) ? reader.getFontSize() : Store.getInt("font", 1);
            int nextFont = (curFont + 1) % 3;
            Store.putInt("font", nextFont);
            if (reader != null) reader.setFontSize(nextFont);
            showSettings(settingsBack);
            settingsScreen.setSelected(idx);
        } else if ("lang".equals(action)) {
            L10n.toggleLang();
            cBack = new Command(L10n.t("Quay lại", "Back"), Command.BACK, 1);
            showSettings(settingsBack);
            settingsScreen.setSelected(idx);
        } else if ("folder".equals(action)) {
            showFolderPicker();
        }
    }

    // ------------------------------------------------------------ browser
    private int browserGen = 0;

    /** Shows the books of the chosen library folder. */
    public void showLibrary() {
        if (library == null) { showFolderPicker(); return; }
        chooseMode = false;
        defaultTabPending = true;
        curDir = library;
        showBrowser();
    }

    public void showLibraryTab(int tab) {
        if (library == null) { showFolderPicker(); return; }
        chooseMode = false;
        libraryTab = tab;
        defaultTabPending = false;
        curDir = library;
        showBrowser();
    }

    /** Folder picker: navigate directories, then choose the current one. */
    public void showFolderPicker() {
        chooseMode = true;
        curDir = library;
        showBrowser();
    }

    private void showBrowser() {
        if (reader != null) reader.saveState();
        showWait(L10n.t("Đang đọc thư mục...", "Reading folder..."));
        final int bg = ++browserGen;
        new Thread(new Runnable() {
            public void run() {
                try {
                    listDir(bg);
                } catch (Throwable e) {
                    if (bg != browserGen) return;
                    curDir = null;
                    chooseMode = true;
                    try { listDir(bg); } catch (Throwable e2) { }
                    showError(L10n.t("Không đọc được thư mục: ", "Cannot read folder: ") + e);
                }
            }
        }).start();
    }

    private static boolean readable(String name) {
        String l = name.toLowerCase();
        return l.endsWith(".epub") || l.endsWith(".html") || l.endsWith(".htm") || l.endsWith(".xhtml")
                || l.endsWith(".txt") || l.endsWith(".xml");
    }

    private static String stripExt(String name) {
        int d = name.lastIndexOf('.');
        return d > 0 ? name.substring(0, d) : name;
    }

    private void listDir(final int bg) throws IOException {
        Vector names = new Vector();
        if (curDir == null) {
            try {
                Enumeration en = FileSystemRegistry.listRoots();
                while (en.hasMoreElements()) names.addElement(((String) en.nextElement()));
            } catch (Throwable t) { }
            String[] props = {"fileconn.dir.memorycard", "fileconn.dir.documents", "fileconn.dir.photos"};
            for (int i = props.length - 1; i >= 0; i--) {
                String v = System.getProperty(props[i]);
                if (v != null && v.length() > 0 && names.indexOf("\u0001" + v) < 0) names.insertElementAt("\u0001" + v, 0);
            }
            if (names.size() == 0) {
                String[] guess = {"E:/", "C:/", "root1/"};
                for (int i = 0; i < guess.length; i++) names.addElement("\u0001" + guess[i]);
            }
            addDesktopRoots(names);
        } else {
            FileConnection fc = null;
            try {
                fc = (FileConnection) Connector.open(curDir, Connector.READ);
            } catch (Throwable t) { }
            if (fc != null) {
                try {
                    Enumeration en = fc.list();
                    while (en.hasMoreElements()) {
                        String n = (String) en.nextElement();
                        boolean dir = n.endsWith("/");
                        if (chooseMode ? dir : (!dir && readable(n))) names.addElement(n);
                    }
                } finally {
                    fc.close();
                }
            } else {
                listDesktopDir(curDir, names);
            }
        }
        int n = names.size();
        String[] a = new String[n];
        names.copyInto(a);
        if (!chooseMode && curDir != null) {
            // Sort library by most recently accessed first
            long[] times = new long[n];
            String lastUrl = Store.get("last", "");
            java.util.Hashtable tMap = Store.getAllWithPrefix("t:");
            java.util.Hashtable pMap = Store.getAllWithPrefix("p:");
            for (int i = 0; i < n; i++) {
                String u = curDir + a[i];
                long t = 0;
                String tVal = (String) tMap.get("t:" + u);
                if (tVal != null) {
                    try { t = Long.parseLong(tVal); } catch (Exception e) { }
                }
                if (t == 0 && u.equals(lastUrl)) t = Long.MAX_VALUE;
                else if (t == 0 && pMap.containsKey("p:" + u)) t = 1;
                times[i] = t;
            }
            for (int i = 1; i < n; i++) {
                String x = a[i];
                long tx = times[i];
                int j = i - 1;
                while (j >= 0 && compareBooks(times[j], a[j], tx, x) > 0) {
                    a[j + 1] = a[j];
                    times[j + 1] = times[j];
                    j--;
                }
                a[j + 1] = x;
                times[j + 1] = tx;
            }
        } else {
            // sort: drives first, then dirs, then alphabetical
            for (int i = 1; i < n; i++) {
                String x = a[i];
                int j = i - 1;
                while (j >= 0 && compare(a[j], x) > 0) {
                    a[j + 1] = a[j];
                    j--;
                }
                a[j + 1] = x;
            }
        }
        if (bg != browserGen) return;
        String keepName = null;
        if (browser != null && !chooseMode && curDir != null) {
            int curSel = browser.getSelected();
            if (curSel >= 0 && curSel < entries.size()) {
                keepName = (String) entries.elementAt(curSel);
            }
        }
        entries = new Vector();
        String title;
        if (curDir == null) title = L10n.t("Chọn ổ đĩa", "Select drive");
        else if (chooseMode) title = shortPath(curDir);
        else title = L10n.t("Thư viện", "Library");
        ListScreen ls = new ListScreen(this, title);
        hasUpItem = chooseMode && curDir != null;
        if (hasUpItem) ls.add(L10n.t(".. (thư mục cha)", ".. (parent folder)"), Ui.I_UP);

        if (!chooseMode && curDir != null) {
            java.util.Hashtable favMap = Store.getAllWithPrefix("fav:");
            int favCount = 0;
            for (int i = 0; i < n; i++) {
                if (favMap.containsKey("fav:" + curDir + a[i])) favCount++;
            }
            if (defaultTabPending) {
                libraryTab = (favCount > 0) ? TAB_FAV : TAB_ALL;
                defaultTabPending = false;
            }

            int added = 0;
            for (int i = 0; i < n; i++) {
                boolean isFav = favMap.containsKey("fav:" + curDir + a[i]);
                if (libraryTab == TAB_FAV && !isFav) continue;

                entries.addElement(a[i]);
                String label = stripExt(a[i]);
                int icon = isFav ? Ui.I_STAR : Ui.I_BOOK;
                ls.add(label, icon);
                added++;
            }

            if (libraryTab == TAB_FAV && added == 0) {
                ls.add(L10n.t("(Chưa có sách ưa thích. Nhấn * để thêm)", "(No favorites yet. Press * to add)"), Ui.I_NONE);
            } else if (libraryTab == TAB_ALL && added == 0) {
                ls.add(L10n.t("(Không có sách. Bấm \"Menu\" -> Đổi thư mục)", "(No books found. Press \"Menu\" -> Change folder)"), Ui.I_NONE);
            }

            ls.setTabs(new String[] {"★ " + L10n.t("Ưa thích", "Favorites") + " (" + favCount + ")", L10n.t("Tất cả", "All") + " (" + n + ")"}, libraryTab);
            ls.setSoftKeys(L10n.t("Menu", "Menu"), (reader != null && reader.getBook() != null) ? L10n.t("Quay lại", "Back") : L10n.t("Thoát", "Exit"));
            if (keepName != null) {
                int newSel = entries.indexOf(keepName);
                if (newSel >= 0) ls.setSelected(newSel);
            }
        } else {
            for (int i = 0; i < n; i++) {
                entries.addElement(a[i]);
                String label = a[i];
                int icon;
                if (label.charAt(0) == '\u0001') {
                    label = label.substring(1);
                    icon = Ui.I_DRIVE;
                } else if (label.endsWith("/")) {
                    label = label.substring(0, label.length() - 1);
                    icon = curDir == null ? Ui.I_DRIVE : Ui.I_FOLDER;
                } else {
                    label = stripExt(label);
                    icon = Ui.I_BOOK;
                }
                ls.add(label, icon);
            }
            if (chooseMode) {
                ls.setSoftKeys(curDir != null ? L10n.t("Chọn", "Select") : "", library != null ? L10n.t("Hủy", "Cancel") : L10n.t("Thoát", "Exit"));
                if (hasUpItem && n > 0) ls.setSelected(1);
            }
        }
        if (bg != browserGen) return;
        browser = ls;
        display.setCurrent(browser);
    }

    private static int compareBooks(long t1, String name1, long t2, String name2) {
        if (t1 != t2) {
            return t1 < t2 ? 1 : -1;
        }
        return name1.toLowerCase().compareTo(name2.toLowerCase());
    }

    private static int compare(String x, String y) {
        boolean dx = x.endsWith("/") || x.charAt(0) == '\u0001', dy = y.endsWith("/") || y.charAt(0) == '\u0001';
        if (x.charAt(0) == '\u0001' && y.charAt(0) != '\u0001') return -1;
        if (y.charAt(0) == '\u0001' && x.charAt(0) != '\u0001') return 1;
        if (dx != dy) return dx ? -1 : 1;
        return x.toLowerCase().compareTo(y.toLowerCase());
    }

    private static String shortPath(String url) {
        return url.startsWith("file:///") ? url.substring(8) : url;
    }

    private static String toLocalPath(String u) {
        String p = u;
        if (p.startsWith("file:///")) p = p.substring(7);
        else if (p.startsWith("file://")) p = p.substring(6);
        if (p.length() >= 3 && p.charAt(0) == '/' && p.charAt(2) == ':') {
            p = p.substring(1);
        }
        return p;
    }

    private static void addDesktopRoots(Vector names) {
        try {
            Class cf = Class.forName("java.io.File");
            String home = System.getProperty("user.home");
            if (home != null && home.length() > 0) {
                if (!home.endsWith("/")) home += "/";
                addDirIfValid(names, home);
                addDirIfValid(names, home + "Downloads/");
            }
            addDirIfValid(names, "/mnt/data/");
            Object[] roots = (Object[]) cf.getMethod("listRoots", null).invoke(null, null);
            if (roots != null) {
                java.lang.reflect.Method mPath = cf.getMethod("getPath", null);
                for (int i = 0; i < roots.length; i++) {
                    String p = (String) mPath.invoke(roots[i], null);
                    if (!p.endsWith("/")) p += "/";
                    addDirIfValid(names, p);
                }
            }
        } catch (Throwable t) { }
    }

    private static void addDirIfValid(Vector names, String p) {
        try {
            Class cf = Class.forName("java.io.File");
            Object f = cf.getConstructor(new Class[] {String.class}).newInstance(new Object[] {p});
            Boolean ex = (Boolean) cf.getMethod("exists", null).invoke(f, null);
            if (ex.booleanValue()) {
                String entry = "\u0001" + p;
                if (names.indexOf(entry) < 0) names.addElement(entry);
            }
        } catch (Throwable t) { }
    }

    private void listDesktopDir(String dirUrl, Vector names) throws IOException {
        try {
            String p = toLocalPath(dirUrl);
            Class cf = Class.forName("java.io.File");
            Object dir = cf.getConstructor(new Class[] {String.class}).newInstance(new Object[] {p});
            Object[] files = (Object[]) cf.getMethod("listFiles", null).invoke(dir, null);
            if (files != null) {
                java.lang.reflect.Method mName = cf.getMethod("getName", null);
                java.lang.reflect.Method mIsDir = cf.getMethod("isDirectory", null);
                for (int i = 0; i < files.length; i++) {
                    Object f = files[i];
                    String n = (String) mName.invoke(f, null);
                    boolean isDir = ((Boolean) mIsDir.invoke(f, null)).booleanValue();
                    if (isDir) n += "/";
                    if (chooseMode ? isDir : (!dirUrl.endsWith("/") && readable(n) || readable(n))) {
                        names.addElement(n);
                    }
                }
            }
        } catch (Throwable t) {
            throw new IOException("Không thể đọc thư mục: " + dirUrl);
        }
    }

    private void browserUp() {
        if (curDir == null) return;
        String d = curDir;
        if (d.endsWith("/")) d = d.substring(0, d.length() - 1);
        int s = d.lastIndexOf('/');
        if (s < 0 || s < "file:///".length()) {
            curDir = null;
        } else {
            curDir = d.substring(0, s + 1);
            if (curDir.length() <= "file:///".length()) curDir = null;
        }
        showBrowser();
    }

    // ------------------------------------------------------------ list callbacks
    public void onSoft(ListScreen s, boolean leftKey) {
        if (s == browser) {
            if (chooseMode) {
                if (leftKey) {
                    if (curDir == null) return;
                    library = curDir;
                    Store.put("lib", library);
                    showLibrary();
                } else if (library != null) {
                    showLibrary();
                } else {
                    quit();
                }
            } else if (leftKey) {
                showLibraryMenu();
            } else {
                if (reader != null && reader.getBook() != null) {
                    display.setCurrent(reader);
                } else {
                    quit();
                }
            }
        } else if (s == menu || s == toc) {
            if (!leftKey) display.setCurrent(reader);
            else onSelect(s, s.getSelected());
        } else if (s == libMenu) {
            if (!leftKey) {
                if (browser != null) {
                    display.setCurrent(browser);
                    browser.repaint();
                } else {
                    showLibrary();
                }
            } else {
                handleLibMenuSelect(s.getSelected());
            }
        } else if (s == settingsScreen) {
            if (!leftKey) {
                if (settingsBack == menu) {
                    showMenu();
                } else if (settingsBack == libMenu) {
                    showLibraryMenu();
                } else if (settingsBack != null) {
                    display.setCurrent(settingsBack);
                    if (settingsBack instanceof Canvas) ((Canvas) settingsBack).repaint();
                } else {
                    showLibrary();
                }
            } else {
                handleSettingsSelect(s.getSelected());
            }
        }
    }

    public void onTab(ListScreen s, int newTab) {
        if (s == browser && !chooseMode && curDir != null) {
            libraryTab = newTab;
            defaultTabPending = false;
            showBrowser();
        }
    }

    public void onKey(ListScreen s, int keyCode, int idx) {
        if (s == browser && !chooseMode && curDir != null && keyCode == javax.microedition.lcdui.Canvas.KEY_STAR) {
            if (hasUpItem) idx--;
            if (idx < 0 || idx >= entries.size()) return;
            String name = (String) entries.elementAt(idx);
            if (name == null || name.endsWith("/") || name.charAt(0) == '\u0001') return;
            String u = curDir + name;
            boolean fav = Store.isFavorite(u);
            Store.setFavorite(u, !fav);
            defaultTabPending = false;
            showBrowser();
        }
    }

    public void onSelect(ListScreen s, int idx) {
        if (s == browser) {
            if (hasUpItem) {
                if (idx == 0) { browserUp(); return; }
                idx--;
            }
            if (idx < 0 || idx >= entries.size()) return;
            String name = (String) entries.elementAt(idx);
            if (name.charAt(0) == '\u0001') {
                String p = name.substring(1);
                if (p.startsWith("/")) p = p.substring(1);
                if (!p.startsWith("file:///")) p = "file:///" + p;
                if (!p.endsWith("/")) p += "/";
                curDir = p;
                showBrowser();
            } else if (name.endsWith("/")) {
                curDir = (curDir == null ? "file:///" : curDir) + name;
                showBrowser();
            } else {
                openFile(curDir + name);
            }
        } else if (s == libMenu) {
            handleLibMenuSelect(idx);
        } else if (s == menu) {
            handleMenuSelect(idx);
        } else if (s == settingsScreen) {
            handleSettingsSelect(idx);
        } else if (s == toc) {
            reader.gotoChapter(idx);
            display.setCurrent(reader);
        }
    }

    // ------------------------------------------------------------ forms (goto %, help)
    public void commandAction(Command c, Displayable d) {
        if (d == gotoForm && c == cOk) {
            reader.gotoPercent(gotoGauge.getValue());
            display.setCurrent(reader);
        } else if (backScreen != null) {
            display.setCurrent(backScreen);
            backScreen = null;
        } else if (reader != null && reader.getBook() != null) {
            showMenu();
        } else {
            showLibrary();
        }
    }
}

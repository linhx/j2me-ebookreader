import java.io.IOException;
import java.util.Enumeration;
import java.util.Vector;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.io.file.FileSystemRegistry;
import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
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

    private final Command cBack = new Command("Quay lại", Command.BACK, 1);
    private final Command cOk = new Command("OK", Command.OK, 1);

    private ListScreen menu, browser, toc;
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
        showWait("Đang mở sách...");
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
                            ? "Hết bộ nhớ khi mở sách."
                            : ("Không mở được: " + e);
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
        menu.add("Tiếp tục đọc", Ui.I_BOOK);
        String curUrl = reader.getUrl();
        boolean isFav = curUrl != null && Store.isFavorite(curUrl);
        menu.add(isFav ? "★ Bỏ ưa thích sách này" : "☆ Thêm vào ưa thích", Ui.I_STAR);
        menu.add("Mục lục", Ui.I_PAGE);
        menu.add("Đi tới %", Ui.I_PAGE);
        String[] fs = {"nhỏ", "vừa", "lớn"};
        String[] th = {"sáng", "tối", "sepia"};
        menu.add("Cỡ chữ: " + fs[reader.getFontSize()], Ui.I_GEAR);
        menu.add("Giao diện: " + th[reader.getTheme()], Ui.I_GEAR);
        menu.add("Mục ưa thích", Ui.I_STAR);
        menu.add("Tất cả sách", Ui.I_BOOK);
        menu.add("Hướng dẫn phím", Ui.I_GEAR);
        menu.add("Thoát", Ui.I_GEAR);
        menu.setSoftKeys("Chọn", "Quay lại");
        display.setCurrent(menu);
    }

    public void showToc() {
        Book b = reader.getBook();
        if (b == null) return;
        reader.saveState();
        toc = new ListScreen(this, "Mục lục");
        for (int i = 0; i < b.count(); i++) toc.add(b.titles[i], Ui.I_PAGE);
        toc.setSelected(reader.getChapter());
        toc.setSoftKeys("Mở", "Quay lại");
        display.setCurrent(toc);
    }

    private void showGoto() {
        gotoForm = new Form("Đi tới %");
        gotoGauge = new Gauge("Vị trí (%)", true, 100, 0);
        gotoForm.append(gotoGauge);
        gotoForm.addCommand(cOk);
        gotoForm.addCommand(cBack);
        gotoForm.setCommandListener(this);
        display.setCurrent(gotoForm);
    }

    private void showHelp() {
        Form f = new Form("Phím tắt");
        f.append("Khi đọc sách:\n"
                + "Lên/Xuống (2/8): cuộn dòng\n"
                + "Trái/Phải (4/6), OK: lùi/tiến trang\n"
                + "1 / 3: chương trước / sau\n"
                + "Giữ *, bấm Lên/Xuống: đổi cỡ chữ\n"
                + "#: đổi giao diện\n"
                + "Trong danh sách sách:\n"
                + "Trái/Phải (4/6): đổi Ưa thích / Tất cả\n"
                + "*: Yêu thích / Bỏ yêu thích sách\n"
                + "0: Xem tên sách đầy đủ\n"
                + "Phím mềm trái: Menu / Thư mục\n"
                + "Phím mềm phải: Mục lục / Thoát\n");
        f.addCommand(cBack);
        f.setCommandListener(this);
        display.setCurrent(f);
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
        showWait("Đang đọc thư mục...");
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
                    showError("Không đọc được thư mục: " + e);
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
        if (curDir == null) title = "Chọn ổ đĩa";
        else if (chooseMode) title = shortPath(curDir);
        else title = "Thư viện";
        ListScreen ls = new ListScreen(this, title);
        hasUpItem = chooseMode && curDir != null;
        if (hasUpItem) ls.add(".. (thư mục cha)", Ui.I_UP);

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
                ls.add("(Chưa có sách ưa thích. Nhấn * để thêm)", Ui.I_NONE);
            } else if (libraryTab == TAB_ALL && added == 0) {
                ls.add("(Không có sách. Bấm \"Thư mục\" để đổi)", Ui.I_NONE);
            }

            ls.setTabs(new String[] {"★ Ưa thích (" + favCount + ")", "Tất cả (" + n + ")"}, libraryTab);
            ls.setSoftKeys("Thư mục", (reader != null && reader.getBook() != null) ? "Quay lại" : "Thoát");
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
                ls.setSoftKeys(curDir != null ? "Chọn" : "", library != null ? "Hủy" : "Thoát");
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
                showFolderPicker();
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
        } else if (s == menu) {
            String curUrl = reader.getUrl();
            boolean isFav = curUrl != null && Store.isFavorite(curUrl);
            switch (idx) {
                case 0: display.setCurrent(reader); break;
                case 1:
                    if (curUrl != null) {
                        Store.setFavorite(curUrl, !isFav);
                        showMenu();
                        menu.setSelected(1);
                    }
                    break;
                case 2: showToc(); break;
                case 3: showGoto(); break;
                case 4: reader.cycleFont(); showMenu(); menu.setSelected(4); break;
                case 5: reader.setTheme((reader.getTheme() + 1) % 3); showMenu(); menu.setSelected(5); break;
                case 6: showLibraryTab(TAB_FAV); break;
                case 7: showLibraryTab(TAB_ALL); break;
                case 8: showHelp(); break;
                case 9: quit(); break;
                default: break;
            }
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
        } else {
            showMenu();
        }
    }
}

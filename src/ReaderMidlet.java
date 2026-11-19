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
        menu.add("Mục lục", Ui.I_PAGE);
        menu.add("Đi tới %", Ui.I_PAGE);
        String[] fs = {"nhỏ", "vừa", "lớn"};
        String[] th = {"sáng", "tối", "sepia"};
        menu.add("Cỡ chữ: " + fs[reader.getFontSize()], Ui.I_GEAR);
        menu.add("Giao diện: " + th[reader.getTheme()], Ui.I_GEAR);
        menu.add("Danh sách sách", Ui.I_FOLDER);
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
        f.append("Lên/Xuống (2/8): cuộn 3 dòng\n"
                + "Trái/Phải (4/6), OK: lùi/tiến trang\n"
                + "1 / 3: chương trước / sau\n"
                + "Giữ *, bấm Lên/Xuống: tăng/giảm cỡ chữ (bấm * nhanh: đổi cỡ)\n"
                + "#: đổi giao diện\n"
                + "Phím mềm trái: Menu\n"
                + "Phím mềm phải: Mục lục\n");
        f.addCommand(cBack);
        f.setCommandListener(this);
        display.setCurrent(f);
    }

    // ------------------------------------------------------------ browser
    /** Shows only the books of the chosen library folder. */
    public void showLibrary() {
        if (library == null) { showFolderPicker(); return; }
        chooseMode = false;
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
        new Thread(new Runnable() {
            public void run() {
                try {
                    listDir();
                } catch (Throwable e) {
                    curDir = null;
                    chooseMode = true;
                    try { listDir(); } catch (Throwable e2) { }
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

    private void listDir() throws IOException {
        Vector names = new Vector();
        if (curDir == null) {
            try {
                Enumeration en = FileSystemRegistry.listRoots();
                while (en.hasMoreElements()) names.addElement(((String) en.nextElement()));
            } catch (Throwable t) {
                // some emulators have a non-standard FileSystemRegistry; fall back to well-known roots
                String[] guess = {"E:/", "C:/", "root1/"};
                for (int i = 0; i < guess.length; i++) names.addElement("\u0001" + guess[i]);
            }
            String[] props = {"fileconn.dir.memorycard", "fileconn.dir.documents", "fileconn.dir.photos"};
            for (int i = props.length - 1; i >= 0; i--) {
                String v = System.getProperty(props[i]);
                if (v != null && v.length() > 0 && names.indexOf("\u0001" + v) < 0) names.insertElementAt("\u0001" + v, 0);
            }
        } else {
            FileConnection fc = (FileConnection) Connector.open(curDir, Connector.READ);
            if (fc == null) throw new IOException("Thiết bị/emulator không hỗ trợ truy cập file (JSR-75)");
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
        }
        // sort: drives first, then dirs, then alphabetical
        int n = names.size();
        String[] a = new String[n];
        names.copyInto(a);
        for (int i = 1; i < n; i++) {
            String x = a[i];
            int j = i - 1;
            while (j >= 0 && compare(a[j], x) > 0) {
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = x;
        }
        entries = new Vector();
        String title;
        if (curDir == null) title = "Chọn ổ đĩa";
        else if (chooseMode) title = shortPath(curDir);
        else title = "Thư viện";
        ListScreen ls = new ListScreen(this, title);
        hasUpItem = chooseMode && curDir != null;
        if (hasUpItem) ls.add(".. (thư mục cha)", Ui.I_UP);
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
        if (!chooseMode && n == 0) ls.add("(Không có sách. Bấm \"Thư mục\" để đổi)", Ui.I_NONE);
        if (chooseMode) {
            ls.setSoftKeys(curDir != null ? "Chọn" : "", library != null ? "Hủy" : "Thoát");
            if (hasUpItem && n > 0) ls.setSelected(1);
        } else {
            ls.setSoftKeys("Thư mục", "Thoát");
        }
        browser = ls;
        display.setCurrent(browser);
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
                quit();
            }
        } else if (s == menu || s == toc) {
            if (!leftKey) display.setCurrent(reader);
            else onSelect(s, s.getSelected());
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
            switch (idx) {
                case 0: display.setCurrent(reader); break;
                case 1: showToc(); break;
                case 2: showGoto(); break;
                case 3: reader.cycleFont(); showMenu(); menu.setSelected(3); break;
                case 4: reader.setTheme((reader.getTheme() + 1) % 3); showMenu(); menu.setSelected(4); break;
                case 5: showLibrary(); break;
                case 6: showHelp(); break;
                case 7: quit(); break;
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

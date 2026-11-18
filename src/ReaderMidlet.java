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
import javax.microedition.lcdui.Item;
import javax.microedition.lcdui.List;
import javax.microedition.midlet.MIDlet;

public class ReaderMidlet extends MIDlet implements CommandListener {
    private Display display;
    private ReaderCanvas reader;

    private final Command cBack = new Command("Quay lại", Command.BACK, 1);
    private final Command cOk = new Command("OK", Command.OK, 1);
    private final Command cExit = new Command("Thoát", Command.EXIT, 9);
    private final Command cSelect = new Command("Chọn thư mục này", Command.OK, 1);
    private final Command cChange = new Command("Đổi thư mục sách", Command.ITEM, 2);
    private final Command cUp = new Command("Lên thư mục cha", Command.ITEM, 2);

    private List menu, browser, toc;
    private Form gotoForm;
    private Gauge gotoGauge;

    // file browser state
    private String curDir = null; // null => roots
    private String library = null; // chosen books folder
    private boolean chooseMode = false; // true while picking the books folder
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
        Form f = new Form("Bookreader");
        f.append(new Gauge(msg, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING));
        display.setCurrent(f);
    }

    // ------------------------------------------------------------ open
    private void openFile(final String url) {
        showWait("Đang mở sách...");
        new Thread(new Runnable() {
            public void run() {
                try {
                    Book b = Book.open(url);
                    reader.openBook(url, b);
                    display.setCurrent(reader);
                } catch (Throwable e) {
                    Store.put("last", "");
                    String m = "Không mở được: " + e;
                    showBrowser();
                    showError(m);
                }
            }
        }).start();
    }

    // ------------------------------------------------------------ menu
    public void showMenu() {
        reader.saveState();
        menu = new List("Menu", List.IMPLICIT);
        menu.append("Tiếp tục đọc", null);
        menu.append("Mục lục", null);
        menu.append("Đi tới %", null);
        String[] fs = {"nhỏ", "vừa", "lớn"};
        String[] th = {"sáng", "tối", "sepia"};
        menu.append("Cỡ chữ: " + fs[reader.getFontSize()], null);
        menu.append("Giao diện: " + th[reader.getTheme()], null);
        menu.append("Danh sách sách", null);
        menu.append("Hướng dẫn phím", null);
        menu.append("Thoát", null);
        menu.setCommandListener(this);
        display.setCurrent(menu);
    }

    public void showToc() {
        Book b = reader.getBook();
        if (b == null) return;
        reader.saveState();
        toc = new List("Mục lục", List.IMPLICIT);
        for (int i = 0; i < b.count(); i++) toc.append(b.titles[i], null);
        toc.setSelectedIndex(reader.getChapter(), true);
        toc.addCommand(cBack);
        toc.setCommandListener(this);
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
                + "*: đổi cỡ chữ\n"
                + "#: đổi giao diện\n"
                + "Phím mềm trái / 0: Menu\n"
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

    /** Folder picker: navigate directories, then "Chọn thư mục này". */
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
        // sort: dirs first, then alphabetical
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
        browser = new List(curDir == null ? "Chọn ổ đĩa" : (chooseMode ? "Chọn thư mục sách: " : "Thư viện: ") + shortPath(curDir), List.IMPLICIT);
        for (int i = 0; i < n; i++) {
            entries.addElement(a[i]);
            String label = a[i];
            if (label.charAt(0) == '\u0001') label = "[Ổ] " + label.substring(1);
            else if (label.endsWith("/")) label = "[" + label.substring(0, label.length() - 1) + "]";
            browser.append(label, null);
        }
        if (chooseMode) {
            if (curDir != null) browser.addCommand(cSelect);
            if (curDir != null) browser.addCommand(cUp);
            if (library != null) browser.addCommand(cBack); // cancel
        } else {
            browser.addCommand(cChange);
            if (reader.getBook() != null) browser.addCommand(cBack); // continue reading
        }
        browser.addCommand(cExit);
        browser.setCommandListener(this);
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
        String p = url.startsWith("file:///") ? url.substring(8) : url;
        return p;
    }

    private void browserUp() {
        if (curDir == null) return;
        String d = curDir;
        if (d.endsWith("/")) d = d.substring(0, d.length() - 1);
        int s = d.lastIndexOf('/');
        if (s < 0 || d.length() <= "file:///".length() + 0 || s < "file:///".length()) {
            curDir = null;
        } else {
            curDir = d.substring(0, s + 1);
            if (curDir.length() <= "file:///".length()) curDir = null;
        }
        showBrowser();
    }

    // ------------------------------------------------------------ commands
    public void commandAction(Command c, Displayable d) {
        if (d == browser) {
            if (c == cExit) { quit(); return; }
            if (c == cBack) {
                if (chooseMode) showLibrary(); else display.setCurrent(reader);
                return;
            }
            if (c == cUp) { browserUp(); return; }
            if (c == cChange) { showFolderPicker(); return; }
            if (c == cSelect) {
                library = curDir;
                Store.put("lib", library);
                showLibrary();
                return;
            }
            if (entries.size() == 0) return;
            int idx = browser.getSelectedIndex();
            if (idx < 0) return;
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
        } else if (d == menu) {
            if (c == List.SELECT_COMMAND) {
                switch (menu.getSelectedIndex()) {
                    case 0: display.setCurrent(reader); break;
                    case 1: showToc(); break;
                    case 2: showGoto(); break;
                    case 3: reader.cycleFont(); showMenu(); break;
                    case 4: reader.setTheme((reader.getTheme() + 1) % 3); showMenu(); break;
                    case 5: showLibrary(); break;
                    case 6: showHelp(); break;
                    case 7: quit(); break;
                    default: break;
                }
            }
        } else if (d == toc) {
            if (c == cBack) { display.setCurrent(reader); return; }
            reader.gotoChapter(toc.getSelectedIndex());
            display.setCurrent(reader);
        } else if (d == gotoForm) {
            if (c == cOk) {
                reader.gotoPercent(gotoGauge.getValue());
                display.setCurrent(reader);
            } else {
                showMenu();
            }
        } else {
            // help form
            showMenu();
        }
    }
}

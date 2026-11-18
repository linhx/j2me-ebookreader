import java.util.Vector;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/** Custom-drawn list screen (title bar, icon rows, soft key bar) in the style of Opera Mini 4. */
public class ListScreen extends Canvas {
    public interface Listener {
        void onSelect(ListScreen s, int index);
        void onSoft(ListScreen s, boolean left);
    }

    private final Listener listener;
    private String title = "";
    private String left, right;
    private String loadingMsg;
    private final Vector labels = new Vector();
    private int[] icons = new int[16];
    private int sel = 0, first = 0;
    private Font font = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private Font bold = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_SMALL);

    public ListScreen(Listener l, String title) {
        listener = l;
        this.title = title;
        setFullScreenMode(true);
    }

    public void setSoftKeys(String l, String r) { left = l; right = r; }

    public void setLoading(String msg) {
        loadingMsg = msg;
        repaint();
    }

    public void add(String label, int icon) {
        if (labels.size() == icons.length) {
            int[] n = new int[icons.length * 2];
            System.arraycopy(icons, 0, n, 0, icons.length);
            icons = n;
        }
        icons[labels.size()] = icon;
        labels.addElement(label);
    }

    public int size() { return labels.size(); }
    public int getSelected() { return sel; }

    public void setSelected(int i) {
        if (labels.size() == 0) return;
        sel = Math.max(0, Math.min(labels.size() - 1, i));
        ensureVisible();
    }

    private int titleH() { return bold.getHeight() + 6; }
    private int barH() { return Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_SMALL).getHeight() + 6; }
    private int rowH() { return Math.max(font.getHeight(), 12) + 4; }
    private int visibleRows() { return Math.max(1, (getHeight() - titleH() - barH()) / rowH()); }

    private void ensureVisible() {
        int v = visibleRows();
        if (sel < first) first = sel;
        if (sel >= first + v) first = sel - v + 1;
        if (first < 0) first = 0;
    }

    protected void showNotify() { ensureVisible(); }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(0xFFFFFF);
        g.fillRect(0, 0, w, h);
        int th = titleH(), bh = barH(), rh = rowH();
        int v = visibleRows();
        int n = labels.size();
        g.setFont(font);
        int y = th;
        for (int k = 0; k < v && first + k < n; k++) {
            int i = first + k;
            boolean s = i == sel;
            if (s) {
                Ui.vgrad(g, 0, y, w, rh, Ui.RED1, Ui.RED2);
            } else if ((i & 1) == 1) {
                g.setColor(0xF3F3F3);
                g.fillRect(0, y, w, rh);
            }
            int x = 6;
            if (icons[i] != Ui.I_NONE) {
                Ui.icon(g, icons[i], x, y + (rh - 12) / 2, s);
                x += 19;
            }
            g.setFont(s ? bold : font);
            g.setColor(s ? 0xFFFFFF : 0x202020);
            String t = Ui.fit(s ? bold : font, (String) labels.elementAt(i), w - x - 10);
            g.drawString(t, x, y + (rh - font.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
            if (!s) {
                g.setColor(0xDCDCDC);
                g.drawLine(0, y + rh - 1, w, y + rh - 1);
            }
            y += rh;
        }
        Ui.scrollbar(g, w - 4, th, h - th - bh, n, first, v, 0xE6E6E6, 0xB0B0B0);
        Ui.titleBar(g, w, th, title, n > 0 ? (sel + 1) + "/" + n : null);
        Ui.softBar(g, h - bh, w, bh, left, right, 0xF0F0F0, 0xB4B4B4, 0x202020);
        if (loadingMsg != null) {
            Ui.loadingBox(g, w, h, loadingMsg, false);
        }
    }

    protected void keyPressed(int code) { handle(code); }

    protected void keyRepeated(int code) {
        int a = action(code);
        if (a == UP || a == DOWN || a == LEFT || a == RIGHT
                || code == KEY_NUM2 || code == KEY_NUM8 || code == KEY_NUM4 || code == KEY_NUM6)
            handle(code);
    }

    private int action(int code) {
        try { return getGameAction(code); } catch (Exception e) { return 0; }
    }

    private void handle(int code) {
        if (loadingMsg != null) return;
        if (code == -6 || code == -21) { listener.onSoft(this, true); return; }
        if (code == -7 || code == -22) { listener.onSoft(this, false); return; }
        int a = action(code);
        if (code == KEY_NUM2) a = UP;
        else if (code == KEY_NUM8) a = DOWN;
        else if (code == KEY_NUM4) a = LEFT;
        else if (code == KEY_NUM6) a = RIGHT;
        else if (code == KEY_NUM5) a = FIRE;
        int n = labels.size();
        if (n == 0) return;
        if (a == UP) {
            sel = sel == 0 ? n - 1 : sel - 1;
        } else if (a == DOWN) {
            sel = sel == n - 1 ? 0 : sel + 1;
        } else if (a == LEFT) {
            sel = Math.max(0, sel - visibleRows());
        } else if (a == RIGHT) {
            sel = Math.min(n - 1, sel + visibleRows());
        } else if (a == FIRE) {
            listener.onSelect(this, sel);
            return;
        } else {
            return;
        }
        ensureVisible();
        repaint();
    }
}

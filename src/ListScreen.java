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

    private volatile boolean running = false;
    private int marqueeOffset = 0;
    private int marqueeHold = 0;
    private Thread tickerThread = null;
    private boolean showPreview = false;

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

    protected void showNotify() {
        ensureVisible();
        startTicker();
    }

    protected void hideNotify() {
        stopTicker();
    }

    private synchronized void startTicker() {
        if (tickerThread != null) return;
        running = true;
        marqueeOffset = 0;
        marqueeHold = 0;
        tickerThread = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        Thread.sleep(60);
                    } catch (Exception e) {
                        break;
                    }
                    if (!running) break;
                    if (labels.size() == 0 || sel < 0 || sel >= labels.size() || showPreview) continue;
                    String text = (String) labels.elementAt(sel);
                    int tw = bold.stringWidth(text);
                    int availW = getWidth() - 36;
                    if (tw > availW) {
                        if (marqueeHold < 10) {
                            marqueeHold++;
                        } else {
                            marqueeOffset += 2;
                            if (marqueeOffset > tw - availW + 24) {
                                marqueeOffset = -8;
                                marqueeHold = 0;
                            }
                            repaint();
                        }
                    }
                }
            }
        });
        tickerThread.start();
    }

    private synchronized void stopTicker() {
        running = false;
        tickerThread = null;
    }

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
            String fullText = (String) labels.elementAt(i);
            int availW = w - x - 10;
            int tw = (s ? bold : font).stringWidth(fullText);
            if (s && tw > availW && marqueeOffset > 0) {
                g.setClip(x, y, availW, rh);
                g.drawString(fullText, x - marqueeOffset, y + (rh - font.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
                g.setClip(0, 0, w, h);
            } else {
                String t = Ui.fit(s ? bold : font, fullText, availW);
                g.drawString(t, x, y + (rh - font.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
            }
            if (!s) {
                g.setColor(0xDCDCDC);
                g.drawLine(0, y + rh - 1, w, y + rh - 1);
            }
            y += rh;
        }
        Ui.scrollbar(g, w - 4, th, h - th - bh, n, first, v, 0xE6E6E6, 0xB0B0B0);
        Ui.titleBar(g, w, th, title, n > 0 ? (sel + 1) + "/" + n : null);
        Ui.softBar(g, h - bh, w, bh, left, right, 0xF0F0F0, 0xB4B4B4, 0x202020);
        if (n > 0 && sel >= 0 && sel < n && !showPreview) {
            String selText = (String) labels.elementAt(sel);
            if (bold.stringWidth(selText) > w - 36) {
                g.setFont(font);
                g.setColor(0x707070);
                g.drawString("0: Xem tên", w / 2, h - bh + (bh - font.getHeight()) / 2 + 1, Graphics.TOP | Graphics.HCENTER);
            }
        }
        if (loadingMsg != null) {
            Ui.loadingBox(g, w, h, loadingMsg, false);
        }
        if (showPreview) {
            drawPreviewDialog(g, w, h);
        }
    }

    private void drawPreviewDialog(Graphics g, int w, int h) {
        if (sel < 0 || sel >= labels.size()) return;
        String text = (String) labels.elementAt(sel);

        // Semi-transparent / darkened overlay
        g.setColor(0x000000);
        for (int y = 0; y < h; y += 2) {
            g.drawLine(0, y, w, y);
        }

        int dw = w - 24;
        int dx = 12;
        Font pf = font;
        Font hf = bold;
        int maxTextW = dw - 16;
        Vector lines = wrapText(text, maxTextW, pf);

        int lineStep = pf.getHeight() + 3;
        int maxLines = (h - 90) / lineStep;
        int visibleCount = Math.min(lines.size(), maxLines);
        if (visibleCount < 1) visibleCount = 1;
        int boxH = visibleCount * lineStep + 54;
        int dy = (h - boxH) / 2;

        // Background
        g.setColor(0xFFFFFF);
        g.fillRoundRect(dx, dy, dw, boxH, 8, 8);
        g.setColor(0x9C1217);
        g.drawRoundRect(dx, dy, dw, boxH, 8, 8);

        // Header
        int barH = 22;
        Ui.vgrad(g, dx + 1, dy + 1, dw - 2, barH, Ui.RED1, Ui.RED2);
        g.setColor(0xFFFFFF);
        g.setFont(hf);
        g.drawString("Tên đầy đủ", dx + 8, dy + 3, Graphics.TOP | Graphics.LEFT);

        // Body lines
        g.setColor(0x1A1A1A);
        g.setFont(pf);
        int by = dy + barH + 8;
        for (int i = 0; i < visibleCount; i++) {
            g.drawString((String) lines.elementAt(i), dx + 8, by, Graphics.TOP | Graphics.LEFT);
            by += lineStep;
        }

        // Footer
        int fy = dy + boxH - 20;
        g.setColor(0xE0E0E0);
        g.drawLine(dx + 2, fy, dx + dw - 2, fy);
        g.setFont(font);
        g.setColor(0x707070);
        g.drawString("OK: Mở sách | Phím khác: Đóng", dx + dw / 2, fy + 4, Graphics.TOP | Graphics.HCENTER);
    }

    private Vector wrapText(String str, int maxW, Font f) {
        Vector res = new Vector();
        if (str == null || str.length() == 0) return res;
        int len = str.length();
        int pos = 0;
        while (pos < len) {
            int w = 0, lastSpace = -1, i = pos;
            while (i < len) {
                char c = str.charAt(i);
                if (c == '\n') break;
                int cw = f.charWidth(c);
                if (w + cw > maxW) break;
                w += cw;
                if (c == ' ') lastSpace = i;
                i++;
            }
            if (i >= len) {
                res.addElement(str.substring(pos, len));
                break;
            } else if (str.charAt(i) == '\n') {
                res.addElement(str.substring(pos, i));
                pos = i + 1;
            } else if (lastSpace > pos) {
                res.addElement(str.substring(pos, lastSpace));
                pos = lastSpace + 1;
            } else {
                if (i == pos) i++;
                res.addElement(str.substring(pos, i));
                pos = i;
            }
        }
        return res;
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
        if (showPreview) {
            int a = action(code);
            if (code == KEY_NUM5 || a == FIRE) {
                showPreview = false;
                listener.onSelect(this, sel);
                return;
            }
            showPreview = false;
            repaint();
            return;
        }
        if (code == KEY_NUM0 || code == KEY_STAR || code == KEY_POUND) {
            if (labels.size() > 0 && sel >= 0 && sel < labels.size()) {
                showPreview = true;
                repaint();
                return;
            }
        }
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
        marqueeOffset = 0;
        marqueeHold = 0;
        ensureVisible();
        repaint();
    }
}

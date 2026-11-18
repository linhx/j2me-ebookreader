import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/** Paged text reader. */
public class ReaderCanvas extends Canvas {
    private static final int START = 0, END = 1, OFFSET = 2;

    private final ReaderMidlet midlet;
    private Book book;
    private String url;
    private int chapter;
    private String text = "";
    private int[] ls = new int[256], le = new int[256];
    private int[] lh = new int[256], gap = new int[256]; // line height, gap above
    private byte[] kind = new byte[256]; // 0 body, 1 title, 2 subheading, 3 rule
    private boolean[] ind = new boolean[256], just = new boolean[256];
    private int nLines = 0;
    private int top = 0;

    private Font font, small, fTitle, fSub;
    private int fontSize, theme;
    private int lineH, linesPerPage, margin = 7, footerH, textW, availH;
    private volatile boolean busy = true;
    private String busyMsg = "Đang tải...";
    private int gen = 0;

    private static final int[][] THEMES = {
        // background, text, dim, accent
        {0xFFFFFF, 0x1A1A1A, 0x909090, 0x1F5A9E}, // light
        {0x101010, 0xCFCFCF, 0x686868, 0xE8A838}, // dark
        {0xF4ECD8, 0x3B2F1E, 0x9A8A6E, 0x8B3A1A}  // sepia
    };

    public ReaderCanvas(ReaderMidlet m) {
        midlet = m;
        setFullScreenMode(true);
        fontSize = Store.getInt("font", 1);
        theme = Store.getInt("theme", 0);
        applyFont();
    }

    public Book getBook() { return book; }
    public int getFontSize() { return fontSize; }
    public int getTheme() { return theme; }

    private void applyFont() {
        int sz = fontSize == 0 ? Font.SIZE_SMALL : fontSize == 2 ? Font.SIZE_LARGE : Font.SIZE_MEDIUM;
        font = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, sz);
        small = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        lineH = font.getHeight() + 2;
        fTitle = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD,
                fontSize == 0 ? Font.SIZE_MEDIUM : Font.SIZE_LARGE);
        fSub = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, sz);
        footerH = small.getHeight() + 5;
    }

    private void metrics() {
        int w = getWidth(), h = getHeight();
        textW = w - 2 * margin;
        availH = h - footerH - 6;
        linesPerPage = Math.max(1, availH / lineH);
    }

    // ------------------------------------------------------------ book loading
    public void openBook(String bookUrl, Book b) {
        saveState();
        Book old = book;
        url = bookUrl;
        book = b;
        if (old != null && old != b) old.close();
        int ch = 0, off = 0;
        String p = Store.get("p:" + url, "");
        int c = p.indexOf(',');
        if (c > 0) {
            try {
                ch = Integer.parseInt(p.substring(0, c));
                off = Integer.parseInt(p.substring(c + 1));
            } catch (Exception e) { ch = 0; off = 0; }
        }
        if (ch < 0 || ch >= b.count()) { ch = 0; off = 0; }
        Store.put("last", url);
        load(ch, OFFSET, off);
    }

    private void load(final int ch, final int mode, final int off) {
        saveState();
        busy = true;
        busyMsg = "Đang tải...";
        final int g = ++gen;
        repaint();
        new Thread(new Runnable() {
            public void run() {
                try {
                    String t = book.chapter(ch);
                    if (g != gen) return;
                    text = t;
                    chapter = ch;
                    layout();
                    if (mode == START) top = 0;
                    else if (mode == END) top = lastTop();
                    else top = lineAt(off);
                    busy = false;
                } catch (Throwable e) {
                    if (g != gen) return;
                    busy = false;
                    if (text == null) text = "";
                    midlet.showError("Lỗi đọc: " + e);
                }
                repaint();
            }
        }).start();
    }

    private int lastTop() {
        int used = 0, t = nLines - 1;
        for (int i = nLines - 1; i >= 0; i--) {
            int add = lh[i] + (i < nLines - 1 ? gap[i + 1] : 0);
            if (used + add > availH) break;
            used += add;
            t = i;
        }
        return Math.max(0, t);
    }

    private int lineAt(int off) {
        int lo = 0;
        for (int i = 0; i < nLines; i++) {
            if (ls[i] <= off) lo = i; else break;
        }
        return Math.min(lo, Math.max(0, nLines - 1));
    }

    private void grow() {
        int n = ls.length * 2;
        ls = grow(ls, n); le = grow(le, n); lh = grow(lh, n); gap = grow(gap, n);
        byte[] k = new byte[n];
        System.arraycopy(kind, 0, k, 0, nLines);
        kind = k;
        boolean[] c = new boolean[n], d = new boolean[n];
        System.arraycopy(ind, 0, c, 0, nLines);
        System.arraycopy(just, 0, d, 0, nLines);
        ind = c; just = d;
    }

    private int[] grow(int[] a, int n) {
        int[] r = new int[n];
        System.arraycopy(a, 0, r, 0, nLines);
        return r;
    }

    private void addLine(int s, int e, int knd, boolean in, boolean j, int height, int gp) {
        if (nLines == ls.length) grow();
        ls[nLines] = s; le[nLines] = e; kind[nLines] = (byte) knd; ind[nLines] = in; just[nLines] = j;
        lh[nLines] = height; gap[nLines] = gp;
        nLines++;
    }

    private void layout() {
        metrics();
        nLines = 0;
        int n = text.length();
        int p = 0;
        while (p < n) {
            int e = text.indexOf('\n', p);
            if (e < 0) e = n;
            int a = p, b = e;
            int knd = 0;
            if (a < b && text.charAt(a) < 4 && text.charAt(a) > 0) {
                knd = text.charAt(a);
                a++;
            }
            while (a < b && text.charAt(a) == ' ') a++;
            while (b > a && text.charAt(b - 1) == ' ') b--;
            if (knd == 3) {
                addLine(a, a, 3, false, false, 7, lineH / 2);
            } else if (a < b) {
                wrap(a, b, knd);
            }
            p = e + 1;
        }
        if (nLines == 0) addLine(0, 0, 0, false, false, lineH, 0);
    }

    private Font fontFor(int knd) {
        return knd == 1 ? fTitle : knd == 2 ? fSub : font;
    }

    private void wrap(int ps, int pe, int knd) {
        Font f = fontFor(knd);
        int indent = knd == 0 ? font.charWidth(' ') * 2 : 0;
        int fh = f.getHeight() + 2;
        int pos = ps;
        boolean first = true;
        while (pos < pe) {
            int w = first ? indent : 0;
            int lastSpace = -1;
            int i = pos;
            while (i < pe) {
                char c = text.charAt(i);
                int cw = f.charWidth(c);
                if (w + cw > textW) break;
                w += cw;
                if (c == ' ') lastSpace = i;
                i++;
            }
            int end, next;
            boolean j = false;
            if (i >= pe) {
                end = pe; next = pe;
            } else if (lastSpace > pos) {
                end = lastSpace; next = lastSpace + 1; j = knd == 0;
            } else {
                if (i == pos) i++;
                end = i; next = i;
            }
            int g = 0, height = fh;
            if (first) g = knd == 1 ? lineH : knd == 2 ? lineH / 2 : lineH / 3;
            if (next >= pe) height += knd == 1 ? 4 : knd == 2 ? 1 : 0; // space after headings
            addLine(pos, end, knd, first && knd == 0, j, height, g);
            first = false;
            pos = next;
        }
    }

    /** Number of lines that fit on a page starting at line 'from'. */
    private int fits(int from) {
        int used = 0, n = 0;
        for (int i = from; i < nLines; i++) {
            int hh = lh[i] + (i == from ? 0 : gap[i]);
            if (used + hh > availH && n > 0) break;
            used += hh;
            n++;
        }
        return Math.max(1, n);
    }

    // ------------------------------------------------------------ painting
    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        int[] t = THEMES[theme];
        g.setColor(t[0]);
        g.fillRect(0, 0, w, h);
        g.setColor(t[1]);
        if (busy) {
            g.setFont(font);
            g.drawString(busyMsg, w / 2, h / 2, Graphics.HCENTER | Graphics.BASELINE);
            return;
        }
        if (availH < 1) metrics();
        int y = 4;
        int cnt = fits(top);
        for (int i = top; i < nLines && i < top + cnt; i++) {
            if (i > top) y += gap[i];
            drawLine(g, i, y, w, t);
            y += lh[i];
        }
        // footer: progress bar + labels
        int fy = h - footerH;
        int total = book == null ? 1 : book.count();
        double frac = (chapter + (nLines > 1 ? (double) top / (nLines - 1) : 0)) / total;
        if (frac > 1) frac = 1;
        g.setColor(t[2]);
        g.drawLine(0, fy, w, fy);
        g.setColor(t[3]);
        g.fillRect(0, fy, (int) (w * frac), 2);
        g.setFont(small);
        g.setColor(t[2]);
        g.drawString("Menu", 3, fy + 3, Graphics.TOP | Graphics.LEFT);
        g.drawString("Mục lục", w - 3, fy + 3, Graphics.TOP | Graphics.RIGHT);
        g.drawString((chapter + 1) + "/" + total + "  " + (int) (frac * 100) + "%", w / 2, fy + 3,
                Graphics.TOP | Graphics.HCENTER);
    }

    private void drawLine(Graphics g, int i, int y, int w, int[] t) {
        int knd = kind[i];
        if (knd == 3) {
            g.setColor(t[2]);
            int ly = y + lh[i] / 2;
            g.drawLine(w / 4, ly, w - w / 4, ly);
            g.setColor(t[3]);
            g.fillRect(w / 2 - 2, ly - 1, 4, 3);
            return;
        }
        int len = le[i] - ls[i];
        if (len <= 0) return;
        Font f = fontFor(knd);
        g.setFont(f);
        if (knd == 0) {
            g.setColor(t[1]);
            int x = margin + (ind[i] ? font.charWidth(' ') * 2 : 0);
            if (just[i] && drawJustified(g, i, x, y, f)) return;
            g.drawSubstring(text, ls[i], len, x, y, Graphics.TOP | Graphics.LEFT);
        } else if (knd == 1) {
            g.setColor(t[3]);
            g.drawSubstring(text, ls[i], len, w / 2, y, Graphics.TOP | Graphics.HCENTER);
            if (i + 1 >= nLines || kind[i + 1] != 1) {
                // underline accent below the title block
                int uw = Math.min(textW / 3, 50);
                g.fillRect(w / 2 - uw / 2, y + lh[i] - 3, uw, 1);
            }
        } else {
            g.setColor(t[3]);
            g.drawSubstring(text, ls[i], len, margin, y, Graphics.TOP | Graphics.LEFT);
        }
    }

    /** Stretches inter-word spaces so the line fills the column. Returns false if it should not be justified. */
    private boolean drawJustified(Graphics g, int i, int x, int y, Font f) {
        int s = ls[i], e = le[i];
        int natural = f.substringWidth(text, s, e - s);
        int spaces = 0;
        for (int k = s; k < e; k++) if (text.charAt(k) == ' ') spaces++;
        int avail = textW - (x - margin);
        int extra = avail - natural;
        if (spaces == 0 || extra <= 0 || extra > avail / 5) return false;
        int pos = s, done = 0;
        int cx = x;
        for (int k = s; k <= e; k++) {
            if (k == e || text.charAt(k) == ' ') {
                if (k > pos) {
                    g.drawSubstring(text, pos, k - pos, cx, y, Graphics.TOP | Graphics.LEFT);
                    cx += f.substringWidth(text, pos, k - pos);
                }
                if (k < e) {
                    int add = extra / spaces + (done < extra % spaces ? 1 : 0);
                    cx += f.charWidth(' ') + add;
                    done++;
                }
                pos = k + 1;
            }
        }
        return true;
    }

    protected void sizeChanged(int w, int h) {
        if (!busy && text != null) {
            int off = nLines > 0 ? ls[top] : 0;
            layout();
            top = lineAt(off);
        }
    }

    // ------------------------------------------------------------ input
    protected void keyPressed(int code) { handle(code); }
    protected void keyRepeated(int code) {
        int a = action(code);
        if (a == UP || a == DOWN || code == KEY_NUM2 || code == KEY_NUM8) handle(code);
    }

    private int action(int code) {
        try {
            return getGameAction(code);
        } catch (Exception e) {
            return 0;
        }
    }

    private void handle(int code) {
        if (code == -6 || code == -21) { midlet.showMenu(); return; }
        if (code == -7 || code == -22) { midlet.showToc(); return; }
        if (busy || book == null) return;
        int a = action(code);
        if (code == KEY_NUM2) a = UP;
        else if (code == KEY_NUM8) a = DOWN;
        else if (code == KEY_NUM4) a = LEFT;
        else if (code == KEY_NUM6) a = RIGHT;
        else if (code == KEY_NUM5) a = FIRE;
        if (code == KEY_NUM1) { gotoChapter(chapter - 1, START); return; }
        if (code == KEY_NUM3) { gotoChapter(chapter + 1, START); return; }
        if (code == KEY_STAR) { cycleFont(); return; }
        if (code == KEY_POUND) { setTheme((theme + 1) % THEMES.length); return; }
        if (code == KEY_NUM0) { midlet.showMenu(); return; }
        switch (a) {
            case UP: scroll(-3); break;
            case DOWN: scroll(3); break;
            case LEFT: page(-1); break;
            case RIGHT:
            case FIRE: page(1); break;
            default: break;
        }
    }

    private void scroll(int n) {
        int max = lastTop();
        int nt = top + n;
        if (nt < 0) nt = 0;
        if (nt > max) nt = max;
        top = nt;
        repaint();
    }

    private void page(int dir) {
        if (dir > 0) {
            int cnt = fits(top);
            if (top + cnt >= nLines) {
                gotoChapter(chapter + 1, START);
            } else {
                top = Math.min(top + Math.max(1, cnt - 1), lastTop());
                repaint();
            }
        } else {
            if (top <= 0) {
                gotoChapter(chapter - 1, END);
            } else {
                top = Math.max(0, top - Math.max(1, fits(top) - 1));
                repaint();
            }
        }
    }

    public void gotoChapter(int ch, int mode) {
        if (book == null || ch < 0 || ch >= book.count()) return;
        load(ch, mode, 0);
    }

    public void gotoChapter(int ch) {
        gotoChapter(ch, START);
    }

    public void gotoPercent(int pct) {
        if (book == null) return;
        double pos = pct * book.count() / 100.0;
        int ch = (int) pos;
        if (ch >= book.count()) { ch = book.count() - 1; pos = ch; }
        double frac = pos - ch;
        if (ch == chapter && !busy) {
            top = Math.min(lastTop(), (int) (frac * nLines));
            repaint();
        } else {
            // approximate: load, then position by fraction of text via offset estimate
            final double f = frac;
            loadFraction(ch, f);
        }
    }

    private void loadFraction(final int ch, final double frac) {
        saveState();
        busy = true;
        busyMsg = "Đang tải...";
        final int g = ++gen;
        repaint();
        new Thread(new Runnable() {
            public void run() {
                try {
                    String t = book.chapter(ch);
                    if (g != gen) return;
                    text = t;
                    chapter = ch;
                    layout();
                    top = Math.min(lastTop(), (int) (frac * nLines));
                    busy = false;
                } catch (Throwable e) {
                    busy = false;
                    midlet.showError("Lỗi đọc: " + e);
                }
                repaint();
            }
        }).start();
    }

    public void cycleFont() {
        setFontSize((fontSize + 1) % 3);
    }

    public void setFontSize(int s) {
        if (s == fontSize) return;
        int off = nLines > 0 ? ls[top] : 0;
        fontSize = s;
        Store.putInt("font", s);
        applyFont();
        if (!busy && book != null) {
            layout();
            top = lineAt(off);
        }
        repaint();
    }

    public void setTheme(int t) {
        theme = t;
        Store.putInt("theme", t);
        repaint();
    }

    public int getChapter() { return chapter; }

    public void saveState() {
        if (book != null && url != null && !busy && nLines > 0) {
            Store.put("p:" + url, chapter + "," + ls[Math.min(top, nLines - 1)]);
        }
    }

    protected void showNotify() {
        metrics();
    }
}

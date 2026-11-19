import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/** Paged text reader engineered for low memory usage on CLDC 1.1. */
public class ReaderCanvas extends Canvas {
    private static final int START = 0, END = 1, OFFSET = 2, PART_END = 3;

    private final ReaderMidlet midlet;
    private Book book;
    private String url;
    private int chapter;
    private int partOff = 0;
    private boolean hasMorePart = false;
    private int nextPartOff = 0;
    private final int[] partHistory = new int[32];
    private int partIdx = 0;

    private String text = "";
    // Packed line layout: ls (start), le (end), meta (bits 0..7=lh, 8..15=gap, 16..17=kind, 18=ind, 19=just)
    private int[] ls = new int[256], le = new int[256], meta = new int[256];
    private int nLines = 0;
    private int top = 0;

    private Font font, small, fTitle, fSub, fItalic, fCaption, fFootnote;
    private int fontSize, theme;
    private int lineH, linesPerPage, margin = 7, footerH, textW, availH;
    private volatile boolean busy = true;
    private String busyMsg = "Đang tải...";
    private int gen = 0;
    private boolean starHeld = false, starUsed = false;
    private volatile int heldKey = 0, heldGen = 0;
    private String toast;
    private long toastUntil;
    private java.util.Hashtable imgCache = new java.util.Hashtable();
    private Book.Note[] notes;
    private int selectedNote = -1;
    private Book.Note activeNote = null;
    private int noteScroll = 0;

    private static final int[][] THEMES = {
        // background, text, dim, accent
        {0xFFFFFF, 0x1A1A1A, 0x909090, 0x1F5A9E}, // 0: sáng
        {0x141414, 0xD4D4D4, 0x707070, 0xE8E8E8}, // 1: tối (Opera Dark: nền than chì sâu, điểm xuyết đỏ)
        {0xF4ECD8, 0x3B2F1E, 0x9A8A6E, 0x8B3A1A}  // 2: sepia
    };

    public boolean isUiDark() { return Store.getInt("ui_theme", 1) == 1; }
    public boolean isPaperDark() { return theme == 1; }
    public boolean isDark() { return isUiDark(); }

    public ReaderCanvas(ReaderMidlet m) {
        midlet = m;
        setFullScreenMode(true);
        fontSize = Store.getInt("font", 1);
        theme = Store.getInt("theme", 0);
        if (theme >= THEMES.length) theme = 1;
        applyFont();
    }

    public Book getBook() { return book; }
    public String getUrl() { return url; }
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
        fItalic = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_ITALIC, sz);
        int capSize = fontSize == 2 ? Font.SIZE_MEDIUM : Font.SIZE_SMALL;
        fCaption = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_ITALIC, capSize);
        int noteSize = fontSize == 2 ? Font.SIZE_MEDIUM : Font.SIZE_SMALL;
        fFootnote = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, noteSize);
        footerH = small.getHeight() + 8;
    }

    private void metrics() {
        int w = getWidth(), h = getHeight();
        textW = w - 2 * margin;
        availH = h - footerH - 6;
        linesPerPage = Math.max(1, availH / lineH);
    }

    // ------------------------------------------------------------ book lifecycle
    /** Releases the current book and chapter memory before opening another book. */
    public void closeBook() {
        saveState();
        Book old = book;
        book = null;
        url = null;
        if (old != null) old.close();
        freeChapter();
    }

    public void openBook(String bookUrl, Book b) {
        saveState();
        Book old = book;
        url = bookUrl;
        book = b;
        if (old != null && old != b) old.close();
        int ch = 0, pOff = 0, off = 0, pIdx = 0;
        String p = Store.get("p:" + url, "");
        int c1 = p.indexOf(',');
        if (c1 > 0) {
            try {
                ch = Integer.parseInt(p.substring(0, c1));
                int c2 = p.indexOf(',', c1 + 1);
                if (c2 > 0) {
                    pOff = Integer.parseInt(p.substring(c1 + 1, c2));
                    int c3 = p.indexOf(',', c2 + 1);
                    if (c3 > 0) {
                        off = Integer.parseInt(p.substring(c2 + 1, c3));
                        pIdx = Integer.parseInt(p.substring(c3 + 1));
                    } else {
                        off = Integer.parseInt(p.substring(c2 + 1));
                    }
                } else {
                    off = Integer.parseInt(p.substring(c1 + 1));
                }
            } catch (Exception e) { ch = 0; pOff = 0; off = 0; pIdx = 0; }
        }
        if (ch < 0 || ch >= b.count()) { ch = 0; pOff = 0; off = 0; pIdx = 0; }
        partHistory[0] = 0;
        if (pOff > 0) {
            partIdx = Math.max(1, pIdx);
            if (partIdx < partHistory.length) partHistory[partIdx] = pOff;
        } else {
            partIdx = 0;
        }
        Store.put("last", url);
        Store.putLong("t:" + url, System.currentTimeMillis());
        load(ch, pOff, OFFSET, off);
    }

    private void load(final int ch, final int initPartOff, final int mode, final int off) {
        saveState();
        busy = true;
        busyMsg = "Đang tải...";
        final int g = ++gen;
        repaint();
        new Thread(new Runnable() {
            public void run() {
                try {
                    freeChapter();
                    int[] outMeta = new int[2];
                    int pOff = initPartOff;
                    int pIdx = partIdx;
                    String t = book.chapter(ch, pOff, outMeta);
                    while (mode == END && outMeta[0] == 1 && pIdx + 1 < partHistory.length) {
                        pOff = outMeta[1];
                        partHistory[++pIdx] = pOff;
                        t = null;
                        System.gc();
                        t = book.chapter(ch, pOff, outMeta);
                    }
                    if (g != gen) return;
                    text = t;
                    chapter = ch;
                    partOff = pOff;
                    partIdx = pIdx;
                    hasMorePart = outMeta[0] == 1;
                    nextPartOff = outMeta[1];
                    layout();
                    notes = book != null ? book.getNotes() : null;
                    selectedNote = -1;
                    activeNote = null;
                    noteScroll = 0;
                    if (mode == START) top = 0;
                    else if (mode == END || mode == PART_END) top = lastTop();
                    else top = lineAt(off);
                    busy = false;
                } catch (Throwable e) {
                    if (g != gen) return;
                    busy = false;
                    if (text == null) text = "";
                    midlet.showError(errText(e));
                }
                repaint();
            }
        }).start();
    }

    /** Drops the current chapter and shrinks layout buffers before loading the next one. */
    public void freeChapter() {
        text = "";
        nLines = 0;
        top = 0;
        imgCache.clear();
        notes = null;
        selectedNote = -1;
        activeNote = null;
        noteScroll = 0;
        if (ls.length > 256) {
            ls = new int[256];
            le = new int[256];
            meta = new int[256];
        }
        System.gc();
    }

    private Image getImageCached(String path) {
        Image img = (Image) imgCache.get(path);
        if (img == null && book != null) {
            try {
                img = book.getImage(path);
                if (img != null) {
                    if (imgCache.size() >= 3) imgCache.clear();
                    imgCache.put(path, img);
                }
            } catch (Throwable t) {
                imgCache.clear();
                System.gc();
            }
        }
        return img;
    }

    private static String errText(Throwable e) {
        if (e instanceof OutOfMemoryError) return "Hết bộ nhớ. Chương này quá lớn cho máy.";
        return "Lỗi đọc: " + e;
    }

    private int lh(int i) { return meta[i] & 0x1FF; }
    private int gap(int i) { return (meta[i] >> 9) & 0x3F; }
    private int kind(int i) { return (meta[i] >> 15) & 7; }
    private boolean ind(int i) { return (meta[i] & (1 << 18)) != 0; }
    private boolean just(int i) { return (meta[i] & (1 << 19)) != 0; }

    private int lastTop() {
        return prevPageTop(nLines);
    }

    /** Computes the top line index of the full page immediately preceding 'from'. */
    private int prevPageTop(int from) {
        int used = 0, t = from - 1;
        for (int i = from - 1; i >= 0; i--) {
            int add = lh(i) + (i < from - 1 ? gap(i + 1) : 0);
            if (used + add > availH && i < from - 1) break;
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
        ls = grow(ls, n);
        le = grow(le, n);
        meta = grow(meta, n);
    }

    private int[] grow(int[] a, int n) {
        int[] r = new int[n];
        System.arraycopy(a, 0, r, 0, nLines);
        return r;
    }

    private void addLine(int s, int e, int knd, boolean in, boolean j, int height, int gp) {
        if (nLines == ls.length) grow();
        ls[nLines] = s;
        le[nLines] = e;
        meta[nLines] = (height & 0x1FF) | ((gp & 0x3F) << 9) | ((knd & 7) << 15)
                | (in ? (1 << 18) : 0) | (j ? (1 << 19) : 0);
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
            if (a < b && text.charAt(a) <= 6 && text.charAt(a) > 0) {
                knd = text.charAt(a);
                a++;
            }
            while (a < b && text.charAt(a) == ' ') a++;
            while (b > a && text.charAt(b - 1) == ' ') b--;
            if (knd == 3) {
                addLine(a, a, 3, false, false, 7, lineH / 2);
            } else if (knd == 4) {
                String lineStr = text.substring(a, b);
                int tab = lineStr.indexOf('\t');
                String path = tab >= 0 ? lineStr.substring(0, tab) : lineStr;
                int[] dim = book != null ? book.getImageDimensions(path) : null;
                if (dim != null && dim[0] <= 240 && dim[1] <= 320) {
                    int ih = Math.min(dim[1], availH);
                    addLine(a, a + path.length(), 4, false, false, ih, lineH / 2);
                } else {
                    // Image too large or cannot load -> placeholder
                    addLine(a, b, 5, false, false, lineH, lineH / 3);
                }
            } else if (a < b) {
                wrap(a, b, knd);
            }
            p = e + 1;
        }
        if (nLines == 0) addLine(0, 0, 0, false, false, lineH, 0);
    }

    private Font fontFor(int knd) {
        if (knd == 1) return fTitle;
        if (knd == 2) return fSub;
        if (knd == 5) return fItalic;
        if (knd == 6) return fCaption;
        return font;
    }

    private void wrap(int ps, int pe, int knd) {
        Font f = fontFor(knd);
        int indent = knd == 0 ? font.charWidth(' ') * 2 : 0;
        int fh = f.getHeight() + (knd == 6 ? 1 : 2);
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
                end = lastSpace; next = lastSpace + 1; j = (knd == 0 || knd == 6);
            } else {
                if (i == pos) i++;
                end = i; next = i;
            }
            int g = 0, height = fh;
            if (first) {
                g = (knd == 1) ? lineH : (knd == 2) ? lineH / 2 : (knd == 6) ? lineH / 4 : lineH / 3;
            }
            if (next >= pe) {
                height += (knd == 1) ? 4 : (knd == 2) ? 1 : (knd == 6) ? lineH / 2 : 0;
            }
            addLine(pos, end, knd, first && knd == 0, j, height, g);
            first = false;
            pos = next;
        }
    }

    /** Number of lines that fit on a page starting at line 'from'. */
    private int fits(int from) {
        int used = 0, n = 0;
        for (int i = from; i < nLines; i++) {
            int hh = lh(i) + (i == from ? 0 : gap(i));
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
        int fy = h - footerH;
        boolean uiDark = isUiDark();
        int c1 = uiDark ? 0x282828 : 0xF0F0F0;
        int c2 = uiDark ? 0x161616 : 0xB4B4B4;
        if (busy) {
            Ui.softBar(g, fy, w, footerH, "Menu", "Mục lục", c1, c2, uiDark ? 0xD0D0D0 : 0x202020);
            Ui.loadingBox(g, w, h, busyMsg, uiDark);
            return;
        }
        if (availH < 1) metrics();
        int y = 4;
        int cnt = fits(top);
        for (int i = top; i < nLines && i < top + cnt; i++) {
            if (i > top) y += gap(i);
            drawLine(g, i, y, w, t);
            y += lh(i);
        }
        if (toast != null && System.currentTimeMillis() < toastUntil) {
            g.setFont(small);
            int tw = small.stringWidth(toast) + 16, tx = (w - tw) / 2, ty = h / 2 - 12;
            g.setColor(0x202020);
            g.fillRoundRect(tx, ty, tw, small.getHeight() + 10, 8, 8);
            g.setColor(0xFFFFFF);
            g.drawString(toast, w / 2, ty + 5, Graphics.TOP | Graphics.HCENTER);
        }
        // scrollbar + footer (gradient soft-key bar with progress strip)
        int total = book == null ? 1 : book.count();
        int totalParts = book != null ? Math.max(1, book.chapterParts(chapter)) : 1;
        if (partIdx >= totalParts) totalParts = partIdx + (hasMorePart ? 2 : 1);
        else if (hasMorePart && partIdx + 1 >= totalParts) totalParts = partIdx + 2;

        double partProg = nLines > 1 ? (double) top / (nLines - 1) : 0;
        if (partProg > 1) partProg = 1;
        if (partProg < 0) partProg = 0;
        double chProg = (partIdx + partProg) / totalParts;
        if (chProg > 1) chProg = 1;

        double frac = (chapter + chProg) / total;
        if (frac > 1) frac = 1;
        boolean paperDark = isPaperDark();
        int trackCol = paperDark ? 0x1A1A1A : (theme == 2 ? 0xE8DFC2 : 0xEAEAEA);
        int thumbCol = paperDark ? 0x484848 : t[2];
        Ui.scrollbar(g, w - 3, 2, fy - 4, nLines, top, cnt, trackCol, thumbCol);
        Ui.softBar(g, fy, w, footerH, "Menu", "Mục lục", c1, c2, uiDark ? 0xD0D0D0 : 0x202020);
        g.setColor(uiDark ? 0x363636 : 0x808080);
        g.drawLine(0, fy, w, fy);
        g.setColor(uiDark ? Ui.RED1 : t[3]);
        g.fillRect(0, fy + 1, (int) (w * frac), 2);
        g.setFont(small);
        int fty = fy + (footerH - small.getHeight()) / 2;
        if (selectedNote >= 0 && notes != null && selectedNote < notes.length) {
            String noteHint = "OK: Đọc [" + notes[selectedNote].label + "]";
            int maxMidW = w - 110;
            if (small.stringWidth(noteHint) > maxMidW) {
                noteHint = "OK: Đọc";
            }
            int nhw = small.stringWidth(noteHint) + 12;
            int nhx = (w - nhw) / 2;
            g.setColor(uiDark ? 0x9E2024 : t[3]);
            g.fillRoundRect(nhx, fty - 1, nhw, small.getHeight() + 2, 4, 4);
            g.setColor(0xFFFFFF);
            g.drawString(noteHint, w / 2, fty, Graphics.TOP | Graphics.HCENTER);
        } else {
            g.setColor(uiDark ? 0xD0D0D0 : 0x202020);
            g.drawString((chapter + 1) + "/" + total + "  " + (int) (frac * 100) + "%", w / 2, fty,
                    Graphics.TOP | Graphics.HCENTER);
        }
        if (activeNote != null) {
            drawNoteDialog(g, w, h, t);
        }
    }

    private void drawLine(Graphics g, int i, int y, int w, int[] t) {
        int knd = kind(i);
        int lhi = lh(i);
        boolean paperDark = isPaperDark();
        if (knd == 3) {
            g.setColor(t[2]);
            int ly = y + lhi / 2;
            g.drawLine(w / 4, ly, w - w / 4, ly);
            g.setColor(paperDark ? 0x9E2024 : t[3]);
            g.fillRect(w / 2 - 2, ly - 1, 4, 3);
            return;
        }
        if (knd == 4) {
            String path = text.substring(ls[i], le[i]);
            Image img = getImageCached(path);
            if (img != null) {
                int ix = (w - img.getWidth()) / 2;
                g.drawImage(img, ix, y, Graphics.TOP | Graphics.LEFT);
            }
            return;
        }
        if (knd == 5) {
            g.setFont(fItalic);
            g.setColor(t[2]);
            String full = text.substring(ls[i], le[i]);
            int tab = full.indexOf('\t');
            String alt = tab >= 0 ? full.substring(tab + 1) : null;
            if (alt == null || alt.length() == 0) {
                int sl = full.lastIndexOf('/');
                alt = sl >= 0 ? full.substring(sl + 1) : full;
            }
            String label = "[Ảnh: " + alt + " (quá lớn)]";
            while (label.length() > 6 && fItalic.stringWidth(label) > textW - 20) {
                label = label.substring(0, label.length() - 5) + "...]";
            }
            int ix = margin;
            int iy = y + (lhi - 8) / 2;
            g.drawRect(ix, iy, 11, 8);
            g.fillRect(ix + 2, iy - 2, 3, 2);
            g.drawArc(ix + 3, iy + 2, 4, 4, 0, 360);
            g.drawString(label, ix + 16, y, Graphics.TOP | Graphics.LEFT);
            return;
        }
        if (knd == 6) {
            // Image caption: smaller, italic, secondary dim color, justified on both sides
            g.setFont(fCaption);
            g.setColor(t[2]);
            int clen = le[i] - ls[i];
            if (clen <= 0) return;
            if (just(i) && drawJustified(g, i, margin, y, fCaption)) return;
            g.drawSubstring(text, ls[i], clen, margin, y, Graphics.TOP | Graphics.LEFT);
            return;
        }
        int len = le[i] - ls[i];
        if (len <= 0) return;
        Font f = fontFor(knd);
        g.setFont(f);
        if (knd == 0) {
            int x = margin + (ind(i) ? font.charWidth(' ') * 2 : 0);
            int firstNote = findFirstNoteOnLine(ls[i], le[i]);
            if (firstNote >= 0) {
                drawLineWithNotes(g, i, x, y, f, t, firstNote);
                return;
            }
            g.setColor(t[1]);
            if (just(i) && drawJustified(g, i, x, y, f)) return;
            g.drawSubstring(text, ls[i], len, x, y, Graphics.TOP | Graphics.LEFT);
        } else if (knd == 1) {
            g.setColor(t[3]);
            g.drawSubstring(text, ls[i], len, w / 2, y, Graphics.TOP | Graphics.HCENTER);
            if (i + 1 >= nLines || kind(i + 1) != 1) {
                int uw = Math.min(textW / 3, 50);
                g.setColor(paperDark ? 0x9E2024 : t[3]);
                g.fillRect(w / 2 - uw / 2, y + lhi - 3, uw, 1);
            }
        } else {
            g.setColor(t[3]);
            g.drawSubstring(text, ls[i], len, margin, y, Graphics.TOP | Graphics.LEFT);
        }
    }

    private int findFirstNoteOnLine(int s, int e) {
        if (notes == null || notes.length == 0) return -1;
        for (int k = 0; k < notes.length; k++) {
            int noff = notes[k].charOffset;
            if (noff >= s && noff < e) return k;
        }
        return -1;
    }

    private void drawLineWithNotes(Graphics g, int lineIdx, int x, int y, Font f, int[] t, int firstNote) {
        int s = ls[lineIdx], e = le[lineIdx];
        int cur = s;
        Font fNote = small;

        for (int k = firstNote; k < notes.length; k++) {
            Book.Note n = notes[k];
            if (n.charOffset >= e) break;
            if (n.charOffset < cur) continue;

            if (n.charOffset > cur) {
                g.setFont(f);
                g.setColor(t[1]);
                g.drawSubstring(text, cur, n.charOffset - cur, x, y, Graphics.TOP | Graphics.LEFT);
                x += f.substringWidth(text, cur, n.charOffset - cur);
            }

            int nlen = n.length > 0 ? n.length : 3;
            if (n.charOffset + nlen > e) nlen = e - n.charOffset;
            String nstr = text.substring(n.charOffset, n.charOffset + nlen);
            g.setFont(fNote);
            int nw = fNote.stringWidth(nstr);
            int ny = y - 2;

            boolean paperDark = isPaperDark();
            boolean isFocused = (selectedNote == k);
            if (isFocused) {
                int padX = 2;
                int padY = 1;
                int bx = x - padX;
                int by = ny - padY;
                int bw = nw + padX * 2;
                int bh = fNote.getHeight() + padY * 2;
                g.setColor(paperDark ? 0x9E2024 : t[3]);
                g.fillRoundRect(bx, by, bw, bh, 4, 4);
                g.setColor(0xFFFFFF);
                g.drawString(nstr, x, ny, Graphics.TOP | Graphics.LEFT);
            } else {
                g.setColor(paperDark ? 0xC05054 : t[3]);
                g.drawString(nstr, x, ny, Graphics.TOP | Graphics.LEFT);
            }
            x += nw;
            cur = n.charOffset + nlen;
        }

        if (cur < e) {
            g.setFont(f);
            g.setColor(t[1]);
            g.drawSubstring(text, cur, e - cur, x, y, Graphics.TOP | Graphics.LEFT);
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
    protected void keyPressed(final int code) {
        if (heldKey == code) return;
        handle(code, false);
        int a = navAction(code);
        if (!starHeld && (a == UP || a == DOWN || a == LEFT || a == RIGHT)) {
            heldKey = code;
            final int hg = ++heldGen;
            new Thread(new Runnable() {
                public void run() {
                    try { Thread.sleep(320); } catch (InterruptedException e) { return; }
                    while (heldKey == code && heldGen == hg && !busy) {
                        if (!stepHold(code)) break;
                        repaint();
                        serviceRepaints();
                        if (heldKey != code || heldGen != hg) break;
                        try { Thread.sleep(110); } catch (InterruptedException e) { return; }
                    }
                }
            }).start();
        }
    }

    protected void keyRepeated(int code) {
    }

    private int action(int code) {
        try {
            return getGameAction(code);
        } catch (Exception e) {
            return 0;
        }
    }

    private int navAction(int code) {
        if (code == KEY_NUM2) return UP;
        if (code == KEY_NUM8) return DOWN;
        if (code == KEY_NUM4) return LEFT;
        if (code == KEY_NUM6) return RIGHT;
        if (code == KEY_NUM5) return FIRE;
        return action(code);
    }

    protected void keyReleased(int code) {
        if (heldKey == code) {
            heldKey = 0;
            heldGen++;
        }
        if (code == KEY_STAR) {
            if (starHeld && !starUsed && !busy && book != null) cycleFont();
            starHeld = false;
            starUsed = false;
        }
    }

    protected void hideNotify() {
        heldKey = 0;
        heldGen++;
        starHeld = false;
    }

    private void showToast(String t) {
        toast = t;
        toastUntil = System.currentTimeMillis() + 1200;
        repaint();
        new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(1300); } catch (InterruptedException e) { }
                repaint();
            }
        }).start();
    }

    /** Performs one synchronous step while a directional key is held. Returns false if boundary reached. */
    private boolean stepHold(int code) {
        if (busy || book == null) return false;
        int a = navAction(code);
        if (a == UP) {
            if (top <= 0) return false;
            top = Math.max(0, top - 4);
            return true;
        } else if (a == DOWN) {
            int max = lastTop();
            if (top >= max) return false;
            top = Math.min(max, top + 4);
            return true;
        } else if (a == LEFT) {
            if (top <= 0) return false;
            top = prevPageTop(top);
            return true;
        } else if (a == RIGHT) {
            int cnt = fits(top);
            if (top + cnt >= nLines) return false;
            top = top + cnt;
            return true;
        }
        return false;
    }

    private void handle(int code, boolean repeat) {
        if (activeNote != null) {
            int a = navAction(code);
            if (a == UP || code == KEY_NUM2) {
                if (noteScroll > 0) { noteScroll--; repaint(); }
                return;
            }
            if (a == DOWN || code == KEY_NUM8) {
                noteScroll++;
                repaint();
                return;
            }
            if (a == LEFT || code == KEY_NUM4) {
                noteScroll = Math.max(0, noteScroll - 4);
                repaint();
                return;
            }
            if (a == RIGHT || code == KEY_NUM6) {
                noteScroll += 4;
                repaint();
                return;
            }
            activeNote = null;
            repaint();
            return;
        }
        if (code == -6 || code == -21) { midlet.showMenu(); return; }
        if (code == -7 || code == -22) { midlet.showToc(); return; }
        if (busy || book == null) return;
        int a = navAction(code);
        if (code == KEY_NUM1) { gotoChapter(chapter - 1, START); return; }
        if (code == KEY_NUM3) { gotoChapter(chapter + 1, START); return; }
        if (code == KEY_NUM7) { navNote(-1); return; }
        if (code == KEY_NUM9) { navNote(1); return; }
        if (code == KEY_STAR) { starHeld = true; return; }
        if (starHeld && (a == UP || a == DOWN)) {
            starUsed = true;
            int ns = Math.max(0, Math.min(2, fontSize + (a == UP ? 1 : -1)));
            if (ns != fontSize) setFontSize(ns);
            String[] nm = {"nhỏ", "vừa", "lớn"};
            showToast("Cỡ chữ: " + nm[fontSize]);
            return;
        }
        if (code == KEY_POUND) { setTheme((theme + 1) % THEMES.length); return; }
        switch (a) {
            case UP: selectedNote = -1; scroll(repeat ? -5 : -3); break;
            case DOWN: selectedNote = -1; scroll(repeat ? 5 : 3); break;
            case LEFT: selectedNote = -1; page(-1, repeat); break;
            case RIGHT: selectedNote = -1; page(1, repeat); break;
            case FIRE:
                if (selectedNote >= 0 && notes != null && selectedNote < notes.length) {
                    activeNote = notes[selectedNote];
                    noteScroll = 0;
                    repaint();
                    return;
                }
                selectedNote = -1;
                page(1, repeat);
                break;
            default: break;
        }
    }

    private void navNote(int dir) {
        if (notes == null || notes.length == 0 || nLines == 0) {
            showToast("Trang này không có chú thích");
            return;
        }
        int cnt = fits(top);
        int pageStart = ls[top];
        int pageEnd = (top + cnt < nLines) ? ls[top + cnt] : text.length();

        int first = -1, last = -1, count = 0;
        for (int i = 0; i < notes.length; i++) {
            int off = notes[i].charOffset;
            if (off >= pageStart && off < pageEnd) {
                if (first < 0) first = i;
                last = i;
                count++;
            }
        }

        if (count == 0) {
            showToast("Trang này không có chú thích");
            return;
        }

        if (selectedNote < first || selectedNote > last) {
            selectedNote = dir > 0 ? first : last;
        } else if (count > 1) {
            selectedNote += dir;
            if (selectedNote > last) selectedNote = first;
            else if (selectedNote < first) selectedNote = last;
        }

        Book.Note n = notes[selectedNote];
        showToast("Chú thích [" + n.label + "] - Bấm OK để đọc");
        repaint();
    }

    private void drawNoteDialog(Graphics g, int w, int h, int[] t) {
        // Semi-transparent / darkened overlay
        g.setColor(0x000000);
        for (int y = 0; y < h; y += 2) {
            g.drawLine(0, y, w, y);
        }

        int dw = w - 20;
        int dx = 10;
        Font bf = fFootnote;
        int barH = 24;
        int footH = Math.max(24, small.getHeight() + 8);
        int maxBodyH = h - barH - footH - 40;
        java.util.Vector bodyLines = wrapNoteBody(activeNote.body, dw - 24, bf);
        int lineStep = bf.getHeight() + 2;
        int visibleLines = Math.min(bodyLines.size(), maxBodyH / lineStep);
        if (visibleLines < 1) visibleLines = 1;
        int boxH = visibleLines * lineStep + barH + footH + 16;
        int dy = (h - boxH) / 2;

        boolean uiDark = isUiDark();

        // Dialog background
        g.setColor(uiDark ? 0x1C1C1C : 0xFFFFFF);
        g.fillRoundRect(dx, dy, dw, boxH, 8, 8);
        g.setColor(uiDark ? 0x383838 : Ui.RED1);
        g.drawRoundRect(dx, dy, dw, boxH, 8, 8);

        // Header bar
        if (uiDark) {
            Ui.vgrad(g, dx + 1, dy + 1, dw - 2, barH, 0x303030, 0x202020);
            g.setColor(0x444444);
            g.drawLine(dx + 1, dy + 1, dx + dw - 2, dy + 1);
            g.setColor(Ui.RED1);
            g.drawLine(dx + 1, dy + barH, dx + dw - 2, dy + barH);
        } else {
            Ui.vgrad(g, dx + 1, dy + 1, dw - 2, barH, Ui.RED1, Ui.RED2);
        }
        g.setColor(0xFFFFFF);
        Font fHeader = (fontSize == 2) ? fSub : Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_SMALL);
        g.setFont(fHeader);
        g.drawString("Chú thích [" + activeNote.label + "]", dx + 8, dy + (barH - fHeader.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);

        // Body text
        g.setFont(bf);
        g.setColor(uiDark ? 0xD0D0D0 : 0x1A1A1A);
        int by = dy + barH + 8;
        int maxScroll = Math.max(0, bodyLines.size() - visibleLines);
        if (noteScroll > maxScroll) noteScroll = maxScroll;
        if (noteScroll < 0) noteScroll = 0;

        int noteBodyW = dw - 24;
        for (int i = noteScroll; i < bodyLines.size() && i < noteScroll + visibleLines; i++) {
            String line = (String) bodyLines.elementAt(i);
            boolean isLast = (i == bodyLines.size() - 1);
            if (isLast || !drawJustifiedString(g, line, dx + 8, by, noteBodyW, bf)) {
                g.drawString(line, dx + 8, by, Graphics.TOP | Graphics.LEFT);
            }
            by += lineStep;
        }

        // Scrollbar if content exceeds visible area
        if (bodyLines.size() > visibleLines) {
            Ui.scrollbar(g, dx + dw - 7, dy + barH + 5, boxH - barH - footH - 10,
                    bodyLines.size(), noteScroll, visibleLines,
                    uiDark ? 0x242424 : 0xEAEAEA,
                    uiDark ? 0x484848 : 0xB0B0B0);
        }

        // Footer
        int fy = dy + boxH - footH;
        g.setColor(uiDark ? 0x303030 : 0xDCDCDC);
        g.drawLine(dx + 2, fy, dx + dw - 2, fy);
        g.setFont(small);
        g.setColor(uiDark ? 0x909090 : 0x707070);
        String footerText = maxScroll > 0 ? "2/8: Cuộn  |  OK: Đóng" : "Bấm OK để đóng";
        g.drawString(footerText, dx + dw / 2, fy + (footH - small.getHeight()) / 2, Graphics.TOP | Graphics.HCENTER);
    }

    private boolean drawJustifiedString(Graphics g, String str, int x, int y, int availW, Font f) {
        int natural = f.stringWidth(str);
        int spaces = 0;
        int len = str.length();
        for (int k = 0; k < len; k++) if (str.charAt(k) == ' ') spaces++;
        int extra = availW - natural;
        if (spaces == 0 || extra <= 0 || extra > availW / 5) return false;
        int pos = 0, done = 0;
        int cx = x;
        for (int k = 0; k <= len; k++) {
            if (k == len || str.charAt(k) == ' ') {
                if (k > pos) {
                    String word = str.substring(pos, k);
                    g.drawString(word, cx, y, Graphics.TOP | Graphics.LEFT);
                    cx += f.stringWidth(word);
                }
                if (k < len) {
                    int add = extra / spaces + (done < extra % spaces ? 1 : 0);
                    cx += f.charWidth(' ') + add;
                    done++;
                }
                pos = k + 1;
            }
        }
        return true;
    }

    private java.util.Vector wrapNoteBody(String text, int maxW, Font f) {
        java.util.Vector lines = new java.util.Vector();
        if (text == null || text.length() == 0) {
            lines.addElement("(Không có nội dung)");
            return lines;
        }
        int len = text.length();
        int pos = 0;
        while (pos < len) {
            int w = 0, lastSpace = -1, i = pos;
            while (i < len) {
                char c = text.charAt(i);
                if (c == '\n') break;
                int cw = f.charWidth(c);
                if (w + cw > maxW) break;
                w += cw;
                if (c == ' ') lastSpace = i;
                i++;
            }
            if (i >= len) {
                lines.addElement(text.substring(pos, len));
                break;
            } else if (text.charAt(i) == '\n') {
                lines.addElement(text.substring(pos, i));
                pos = i + 1;
            } else if (lastSpace > pos) {
                lines.addElement(text.substring(pos, lastSpace));
                pos = lastSpace + 1;
            } else {
                if (i == pos) i++;
                lines.addElement(text.substring(pos, i));
                pos = i;
            }
        }
        return lines;
    }

    private void scroll(int n) {
        int max = lastTop();
        int nt = top + n;
        if (nt < 0) nt = 0;
        if (nt > max) nt = max;
        top = nt;
        repaint();
    }

    private void page(int dir, boolean repeat) {
        if (dir > 0) {
            int cnt = fits(top);
            if (top + cnt >= nLines) {
                if (repeat) return; // stop at chapter boundary while fast-holding
                if (hasMorePart) {
                    if (partIdx + 1 < partHistory.length) partHistory[++partIdx] = nextPartOff;
                    load(chapter, nextPartOff, START, 0);
                } else {
                    gotoChapter(chapter + 1, START);
                }
            } else {
                top = top + cnt;
                repaint();
            }
        } else {
            if (top <= 0) {
                if (repeat) return; // stop at chapter boundary while fast-holding
                if (partOff > 0) {
                    int prev = partIdx > 0 ? partHistory[--partIdx] : Math.max(0, partOff - Html.MAX_PART_CHARS);
                    load(chapter, prev, PART_END, 0);
                } else {
                    gotoChapter(chapter - 1, END);
                }
            } else {
                top = prevPageTop(top);
                repaint();
            }
        }
    }

    public void gotoChapter(int ch, int mode) {
        if (book == null || ch < 0 || ch >= book.count()) return;
        partIdx = 0;
        partHistory[0] = 0;
        load(ch, 0, mode, 0);
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
        if (ch == chapter && partOff == 0 && !hasMorePart && !busy) {
            top = Math.min(lastTop(), (int) (frac * nLines));
            repaint();
        } else {
            loadFraction(ch, frac);
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
                    freeChapter();
                    int totalParts = book != null ? Math.max(1, book.chapterParts(ch)) : 1;
                    int targetPart = (int) (frac * totalParts);
                    if (targetPart >= totalParts) targetPart = totalParts - 1;
                    if (targetPart < 0) targetPart = 0;
                    double fracInPart = (frac * totalParts) - targetPart;

                    int[] outMeta = new int[2];
                    int pOff = 0;
                    int pIdx = 0;
                    String t = book.chapter(ch, 0, outMeta);
                    while (pIdx < targetPart && outMeta[0] == 1 && pIdx + 1 < partHistory.length) {
                        pOff = outMeta[1];
                        partHistory[++pIdx] = pOff;
                        t = null;
                        System.gc();
                        t = book.chapter(ch, pOff, outMeta);
                    }
                    if (g != gen) return;
                    text = t;
                    chapter = ch;
                    partOff = pOff;
                    partIdx = pIdx;
                    hasMorePart = outMeta[0] == 1;
                    nextPartOff = outMeta[1];
                    layout();
                    notes = book != null ? book.getNotes() : null;
                    selectedNote = -1;
                    activeNote = null;
                    noteScroll = 0;
                    top = Math.min(lastTop(), (int) (fracInPart * nLines));
                    busy = false;
                } catch (Throwable e) {
                    if (g != gen) return;
                    busy = false;
                    if (text == null) text = "";
                    midlet.showError(errText(e));
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
        theme = Math.max(0, Math.min(THEMES.length - 1, t));
        Store.putInt("theme", theme);
        String[] nm = {"sáng", "tối", "sepia"};
        showToast("Màu đọc: " + nm[theme]);
        repaint();
    }

    public int getChapter() { return chapter; }

    public void saveState() {
        if (book != null && url != null && !busy && nLines > 0) {
            Store.put("p:" + url, chapter + "," + partOff + "," + ls[Math.min(top, nLines - 1)] + "," + partIdx);
            Store.putLong("t:" + url, System.currentTimeMillis());
        }
    }

    protected void showNotify() {
        metrics();
    }
}

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/** Shared drawing helpers for the Opera-Mini-like look. */
public class Ui {
    public static final int RED1 = 0xE2262D, RED2 = 0x9C1217;

    public static void vgrad(Graphics g, int x, int y, int w, int h, int c1, int c2) {
        int r1 = (c1 >> 16) & 255, g1 = (c1 >> 8) & 255, b1 = c1 & 255;
        int r2 = (c2 >> 16) & 255, g2 = (c2 >> 8) & 255, b2 = c2 & 255;
        for (int i = 0; i < h; i++) {
            int d = h > 1 ? h - 1 : 1;
            int r = r1 + (r2 - r1) * i / d, gg = g1 + (g2 - g1) * i / d, b = b1 + (b2 - b1) * i / d;
            g.setColor((r << 16) | (gg << 8) | b);
            g.drawLine(x, y + i, x + w - 1, y + i);
        }
    }

    /** Truncates s with an ellipsis so it fits maxW pixels. */
    public static String fit(Font f, String s, int maxW) {
        if (f.stringWidth(s) <= maxW) return s;
        int n = s.length();
        while (n > 0 && f.stringWidth(s.substring(0, n)) + f.stringWidth("...") > maxW) n--;
        return s.substring(0, n) + "...";
    }

    /** Title bar: red gradient with white bold text and optional right-aligned info. */
    public static void titleBar(Graphics g, int w, int h, String title, String right) {
        titleBar(g, w, h, title, right, false);
    }

    public static void titleBar(Graphics g, int w, int h, String title, String right, boolean dark) {
        if (dark) {
            vgrad(g, 0, 0, w, h, 0x2A2A2A, 0x1A1A1A);
            g.setColor(0x3A3A3A);
            g.drawLine(0, 0, w, 0);
            g.setColor(0x101010);
            g.drawLine(0, h - 1, w, h - 1);
        } else {
            vgrad(g, 0, 0, w, h, RED1, RED2);
            g.setColor(0xFF6A6F);
            g.drawLine(0, 0, w, 0);
            g.setColor(0x6E0C10);
            g.drawLine(0, h - 1, w, h - 1);
        }
        Font f = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_SMALL);
        g.setFont(f);
        int rw = right == null ? 0 : f.stringWidth(right) + 6;
        g.setColor(dark ? 0x0A0A0A : 0x5A0A0D);
        String t = fit(f, title, w - 8 - rw);
        int ty = (h - f.getHeight()) / 2;
        g.drawString(t, 5, ty + 1, Graphics.TOP | Graphics.LEFT);
        g.setColor(0xFFFFFF);
        g.drawString(t, 4, ty, Graphics.TOP | Graphics.LEFT);
        if (right != null) g.drawString(right, w - 4, ty, Graphics.TOP | Graphics.RIGHT);
    }

    /** Soft key bar: light-grey gradient with bold labels. */
    public static void softBar(Graphics g, int y, int w, int h, String left, String right, int c1, int c2, int text) {
        vgrad(g, 0, y, w, h, c1, c2);
        g.setColor(0x808080);
        g.drawLine(0, y, w, y);
        Font f = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_SMALL);
        g.setFont(f);
        g.setColor(text);
        int ty = y + (h - f.getHeight()) / 2 + 1;
        if (left != null) g.drawString(left, 5, ty, Graphics.TOP | Graphics.LEFT);
        if (right != null) g.drawString(right, w - 5, ty, Graphics.TOP | Graphics.RIGHT);
    }

    public static void scrollbar(Graphics g, int x, int y, int h, int total, int first, int visible, int track, int thumb) {
        if (total <= visible) return;
        g.setColor(track);
        g.fillRect(x, y, 3, h);
        int th = Math.max(8, h * visible / total);
        int ty = y + (h - th) * first / Math.max(1, total - visible);
        g.setColor(thumb);
        g.fillRect(x, ty, 3, th);
    }

    /** Opera Mini 4.4 style centered loading dialog box. */
    public static void loadingBox(Graphics g, int w, int h, String msg, boolean dark) {
        Font bf = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_SMALL);
        Font pf = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        int bw = Math.min(w - 32, 204);
        int headH = bf.getHeight() + 6;
        int bh = headH + pf.getHeight() + 28;
        int bx = (w - bw) / 2;
        int by = (h - bh) / 2;

        // Drop shadow
        g.setColor(dark ? 0x050505 : 0xB8B8B8);
        g.fillRect(bx + 2, by + 2, bw, bh);

        // Box body
        vgrad(g, bx, by + headH, bw, bh - headH, dark ? 0x2A2A2A : 0xFFFFFF, dark ? 0x1A1A1A : 0xEAEAEA);

        // Header bar
        if (dark) {
            vgrad(g, bx, by, bw, headH, 0x323232, 0x202020);
            g.setColor(0x444444);
            g.drawLine(bx + 1, by + 1, bx + bw - 2, by + 1);
            g.setColor(RED1);
            g.drawLine(bx + 1, by + headH - 1, bx + bw - 2, by + headH - 1);
        } else {
            vgrad(g, bx, by, bw, headH, RED1, RED2);
            g.setColor(0xFF6A6F);
            g.drawLine(bx + 1, by + 1, bx + bw - 2, by + 1);
        }
        icon(g, I_BOOK, bx + 6, by + (headH - 12) / 2, true);
        g.setFont(bf);
        if (!dark) {
            g.setColor(0x5A0A0D);
            g.drawString("BookReader", bx + 23, by + 4, Graphics.TOP | Graphics.LEFT);
        }
        g.setColor(0xFFFFFF);
        g.drawString("BookReader", bx + 22, by + 3, Graphics.TOP | Graphics.LEFT);

        // Message text
        g.setFont(pf);
        g.setColor(dark ? 0xE0E0E0 : 0x202020);
        String t = fit(pf, msg, bw - 16);
        int ty = by + headH + 7;
        g.drawString(t, w / 2, ty, Graphics.TOP | Graphics.HCENTER);

        // Opera-style glossy progress track & bar
        int pw = bw - 24;
        int ph = 7;
        int px = bx + 12;
        int py = ty + pf.getHeight() + 7;
        g.setColor(dark ? 0x101010 : 0xD4D4D4);
        g.fillRect(px, py, pw, ph);
        g.setColor(dark ? 0x505050 : 0x9A9A9A);
        g.drawRect(px, py, pw - 1, ph - 1);

        int fillW = (pw - 2) * 3 / 5;
        vgrad(g, px + 1, py + 1, fillW, ph - 2, RED1, RED2);
        g.setColor(0xFF7A7F);
        g.drawLine(px + 1, py + 1, px + fillW, py + 1);

        // Outer border
        g.setColor(dark ? 0x404040 : 0x6E0C10);
        g.drawRect(bx, by, bw - 1, bh - 1);
    }

    /** Tab bar: two or more tabs with red gradient and active indicator. */
    public static void tabBar(Graphics g, int w, int h, String[] tabs, int active) {
        tabBar(g, w, h, tabs, active, false);
    }

    public static void tabBar(Graphics g, int w, int h, String[] tabs, int active, boolean dark) {
        if (dark) {
            vgrad(g, 0, 0, w, h, 0x202020, 0x141414);
            g.setColor(0x101010);
            g.drawLine(0, h - 1, w, h - 1);
        } else {
            vgrad(g, 0, 0, w, h, 0x851014, 0x5C0B0E);
            g.setColor(0x50080B);
            g.drawLine(0, h - 1, w, h - 1);
        }
        int n = tabs.length;
        int tw = w / n;
        Font fNorm = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        Font fBold = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_SMALL);
        for (int i = 0; i < n; i++) {
            int tx = i * tw;
            int curW = (i == n - 1) ? (w - tx) : tw;
            boolean act = (i == active);
            if (act) {
                if (dark) {
                    vgrad(g, tx, 0, curW, h - 1, 0x363636, 0x242424);
                    g.setColor(0x484848);
                    g.drawLine(tx, 0, tx + curW - 1, 0);
                    g.setColor(RED1);
                    g.fillRect(tx + 8, h - 2, curW - 16, 2);
                } else {
                    vgrad(g, tx, 0, curW, h - 1, RED1, RED2);
                    g.setColor(0xFF7A7F);
                    g.drawLine(tx, 0, tx + curW - 1, 0);
                    g.setColor(0xFFFFFF);
                    g.fillRect(tx + 6, h - 2, curW - 12, 2);
                }
            } else {
                g.setColor(dark ? 0x181818 : 0x6E0C10);
                g.drawLine(tx, 2, tx, h - 4);
            }
            Font f = act ? fBold : fNorm;
            g.setFont(f);
            int ty = (h - f.getHeight()) / 2;
            int cx = tx + curW / 2;
            if (act) {
                if (!dark) {
                    g.setColor(0x5A0A0D);
                    g.drawString(tabs[i], cx + 1, ty + 1, Graphics.TOP | Graphics.HCENTER);
                }
                g.setColor(0xFFFFFF);
                g.drawString(tabs[i], cx, ty, Graphics.TOP | Graphics.HCENTER);
            } else {
                g.setColor(dark ? 0x888888 : 0xE8B0B2);
                g.drawString(tabs[i], cx, ty, Graphics.TOP | Graphics.HCENTER);
            }
        }
    }

    // ---- small 14x12 icons, drawn at (x, y)
    public static final int I_NONE = 0, I_FOLDER = 1, I_BOOK = 2, I_UP = 3, I_DRIVE = 4, I_PAGE = 5, I_GEAR = 6, I_STAR = 7;

    public static void icon(Graphics g, int type, int x, int y, boolean selected) {
        switch (type) {
            case I_FOLDER:
                g.setColor(0xE8B83A);
                g.fillRect(x, y + 2, 6, 3);
                g.fillRect(x, y + 4, 14, 8);
                g.setColor(0xF7D86A);
                g.fillRect(x + 1, y + 6, 12, 5);
                g.setColor(0x9A7410);
                g.drawRect(x, y + 4, 13, 7);
                break;
            case I_STAR:
                g.setColor(selected ? 0xFFFFFF : 0xF5A623);
                g.fillTriangle(x + 6, y, x + 4, y + 5, x + 8, y + 5);
                g.fillTriangle(x, y + 4, x + 5, y + 4, x + 5, y + 7);
                g.fillTriangle(x + 12, y + 4, x + 7, y + 4, x + 7, y + 7);
                g.fillTriangle(x + 2, y + 11, x + 4, y + 6, x + 7, y + 8);
                g.fillTriangle(x + 10, y + 11, x + 8, y + 6, x + 5, y + 8);
                g.fillRect(x + 4, y + 4, 5, 4);
                break;
            case I_BOOK:
                g.setColor(selected ? 0xFFFFFF : 0x1F5A9E);
                g.fillRect(x + 1, y, 11, 12);
                g.setColor(selected ? 0xB0B0B0 : 0x0E3566);
                g.fillRect(x + 1, y, 2, 12);
                g.setColor(selected ? 0x9C1217 : 0xFFFFFF);
                g.fillRect(x + 5, y + 3, 5, 1);
                g.fillRect(x + 5, y + 6, 5, 1);
                g.fillRect(x + 5, y + 9, 3, 1);
                break;
            case I_UP:
                g.setColor(selected ? 0xFFFFFF : 0x606060);
                g.fillTriangle(x + 7, y, x, y + 7, x + 14, y + 7);
                g.fillRect(x + 5, y + 7, 5, 5);
                break;
            case I_DRIVE:
                g.setColor(0x909090);
                g.fillRect(x, y + 3, 14, 8);
                g.setColor(0xD0D0D0);
                g.fillRect(x + 1, y + 4, 12, 3);
                g.setColor(0x30C030);
                g.fillRect(x + 10, y + 8, 2, 2);
                break;
            case I_PAGE:
                g.setColor(selected ? 0xFFFFFF : 0xFFFFFF);
                g.fillRect(x + 2, y, 10, 12);
                g.setColor(selected ? 0x9C1217 : 0x808080);
                g.drawRect(x + 2, y, 9, 11);
                g.fillRect(x + 4, y + 3, 6, 1);
                g.fillRect(x + 4, y + 6, 6, 1);
                g.fillRect(x + 4, y + 9, 4, 1);
                break;
            case I_GEAR:
                g.setColor(selected ? 0xFFFFFF : Ui.RED1);
                g.fillArc(x + 1, y, 12, 12, 0, 360);
                g.setColor(selected ? Ui.RED2 : 0xFFFFFF);
                g.fillArc(x + 5, y + 4, 4, 4, 0, 360);
                break;
            default:
                break;
        }
    }
}

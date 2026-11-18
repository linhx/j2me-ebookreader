/**
 * Converts HTML/XHTML to plain text with newline-separated paragraphs.
 * Paragraph markers (first char of a paragraph): \u0001 = title (h1/h2), \u0002 = subheading (h3-h6),
 * \u0003 = horizontal rule.
 */
public class Html {

    private static final String[] BLOCK = {
        "p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr", "blockquote",
        "section", "article", "hr", "table", "ul", "ol", "dt", "dd", "pre", "title", "header", "footer"
    };

    public static String toText(String h) {
        int n = h.length();
        StringBuffer sb = new StringBuffer(n / 2 + 16);
        boolean space = false; // pending whitespace
        boolean lineStart = true;
        boolean marked = false; // a paragraph marker was just emitted with no text yet
        int i = 0;
        int blankRun = 0;
        while (i < n) {
            char c = h.charAt(i);
            if (c == '<') {
                if (h.startsWith("<!--", i)) {
                    int e = h.indexOf("-->", i + 4);
                    i = e < 0 ? n : e + 3;
                    continue;
                }
                int e = Util.tagEnd(h, i + 1);
                int ns = i + 1;
                boolean closing = false;
                if (ns < n && h.charAt(ns) == '/') { closing = true; ns++; }
                int ne = ns;
                while (ne < e && isNameChar(h.charAt(ne))) ne++;
                String name = h.substring(ns, ne).toLowerCase();
                i = e + 1;
                if (!closing && (name.equals("script") || name.equals("style") || name.equals("head"))) {
                    boolean selfClose = h.charAt(e > 0 ? e - 1 : 0) == '/';
                    if (!selfClose) {
                        int ce = Util.indexOfIgnoreCase(h, "</" + name, i);
                        if (ce < 0) { i = n; } else { i = Util.tagEnd(h, ce) + 1; }
                    }
                    continue;
                }
                if (isBlock(name)) {
                    if (name.equals("title")) continue;
                    if (marked && lineStart) { sb.setLength(sb.length() - 1); marked = false; }
                    if (!lineStart) {
                        sb.append('\n');
                        lineStart = true;
                        blankRun = 0;
                    }
                    space = false;
                    marked = false;
                    if (!closing) {
                        if (name.equals("h1") || name.equals("h2")) { sb.append('\u0001'); marked = true; }
                        else if (name.length() == 2 && name.charAt(0) == 'h' && name.charAt(1) >= '3' && name.charAt(1) <= '6') { sb.append('\u0002'); marked = true; }
                        else if (name.equals("hr")) { sb.append("\u0003\n"); }
                        else if (name.equals("li")) { sb.append("\u2022 "); lineStart = false; }
                    }
                }
                continue;
            }
            if (c == '&') {
                int semi = h.indexOf(';', i);
                if (semi > 0 && semi - i <= 10) {
                    String ent = h.substring(i + 1, semi);
                    int cp = entity(ent);
                    if (cp >= 0) {
                        i = semi + 1;
                        if (cp == 0xA0) cp = ' ';
                        if (cp == ' ') { space = true; continue; }
                        if (space && !lineStart) sb.append(' ');
                        space = false;
                        if (cp > 0xFFFF) cp = '?';
                        sb.append((char) cp);
                        lineStart = false;
                        marked = false;
                        continue;
                    }
                }
            }
            i++;
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f') {
                space = true;
                continue;
            }
            if (c < 32) continue;
            if (space && !lineStart) sb.append(' ');
            space = false;
            sb.append(c);
            lineStart = false;
            marked = false;
        }
        return sb.toString();
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == ':';
    }

    private static boolean isBlock(String name) {
        for (int i = 0; i < BLOCK.length; i++) if (BLOCK[i].equals(name)) return true;
        return false;
    }

    private static int entity(String e) {
        if (e.length() == 0) return -1;
        try {
            if (e.charAt(0) == '#') {
                if (e.length() > 1 && (e.charAt(1) == 'x' || e.charAt(1) == 'X'))
                    return Integer.parseInt(e.substring(2), 16);
                return Integer.parseInt(e.substring(1));
            }
        } catch (NumberFormatException ex) {
            return -1;
        }
        if (e.equals("amp")) return '&';
        if (e.equals("lt")) return '<';
        if (e.equals("gt")) return '>';
        if (e.equals("quot")) return '"';
        if (e.equals("apos")) return '\'';
        if (e.equals("nbsp")) return ' ';
        if (e.equals("mdash")) return 0x2014;
        if (e.equals("ndash")) return 0x2013;
        if (e.equals("hellip")) return 0x2026;
        if (e.equals("lsquo")) return 0x2018;
        if (e.equals("rsquo")) return 0x2019;
        if (e.equals("ldquo")) return 0x201C;
        if (e.equals("rdquo")) return 0x201D;
        if (e.equals("laquo")) return 0xAB;
        if (e.equals("raquo")) return 0xBB;
        if (e.equals("copy")) return 0xA9;
        if (e.equals("middot")) return 0xB7;
        if (e.equals("bull")) return 0x2022;
        if (e.equals("times")) return 0xD7;
        return -1;
    }
}

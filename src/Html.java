import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Hashtable;
import java.util.Vector;

/**
 * Converts HTML/XHTML to plain text with newline-separated paragraphs.
 * Paragraph markers (first char of a paragraph): \u0001 = title (h1/h2), \u0002 = subheading (h3-h6),
 * \u0003 = horizontal rule.
 *
 * Supports streaming directly from an InputStream so large XHTML/HTML files never need
 * to be loaded as a full byte[] or raw HTML String in memory.
 */
public class Html {

    public static final int MAX_PART_CHARS = 32000;

    private static final String[] BLOCK = {
        "p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr", "blockquote",
        "section", "article", "hr", "table", "ul", "ol", "dt", "dd", "pre", "title", "header", "footer"
    };

    private InputStream in;
    private byte[] bbuf = new byte[2048];
    private int bpos = 0, blen = 0;
    private int[] bstack = new int[4];
    private int bsp = 0;
    private int[] cstack = new int[16];
    private int csp = 0;
    private boolean firstByte = true;

    private String baseFile;
    private Vector outNotes;
    private char[] out;
    private int outLen = 0;
    private int pos = 0; // total logical chars emitted from start of stream
    private int skipChars;
    private int maxChars;

    /** Converts a short HTML String snippet to plain text. */
    public static String toText(String h) {
        try {
            byte[] b = h.getBytes("UTF-8");
            return toText(new ByteArrayInputStream(b), 0, h.length() + 16, null);
        } catch (Exception e) {
            return h;
        }
    }

    /**
     * Streams HTML from 'in', skips the first 'skipChars' plain-text characters (using 0 heap
     * for skipped text), and collects up to 'maxChars' plain-text characters.
     * If outMeta != null (length >= 2):
     *   outMeta[0] = 1 if there is more visible text after this part, 0 otherwise
     *   outMeta[1] = next skipChars offset
     */
    public static String toText(InputStream in, String baseFile, int skipChars, int maxChars, int[] outMeta, Vector outNotes) throws IOException {
        Html p = new Html();
        p.in = in;
        p.baseFile = baseFile;
        p.skipChars = skipChars;
        p.maxChars = maxChars;
        p.outNotes = outNotes;
        p.out = new char[Math.min(maxChars + 64, 4096)];
        return p.parse(outMeta);
    }

    public static String toText(InputStream in, String baseFile, int skipChars, int maxChars, int[] outMeta) throws IOException {
        return toText(in, baseFile, skipChars, maxChars, outMeta, null);
    }

    public static String toText(InputStream in, int skipChars, int maxChars, int[] outMeta) throws IOException {
        return toText(in, null, skipChars, maxChars, outMeta, null);
    }

    private int readByte() throws IOException {
        if (bsp > 0) return bstack[--bsp];
        if (bpos >= blen) {
            blen = in.read(bbuf, 0, bbuf.length);
            bpos = 0;
            if (blen <= 0) return -1;
        }
        return bbuf[bpos++] & 0xFF;
    }

    private void unreadByte(int b) {
        if (b >= 0) bstack[bsp++] = b;
    }

    private int nextChar() throws IOException {
        if (csp > 0) return cstack[--csp];
        int b0 = readByte();
        if (firstByte) {
            firstByte = false;
            if (b0 == 0xEF) {
                int b1 = readByte();
                int b2 = readByte();
                if (b1 == 0xBB && b2 == 0xBF) {
                    b0 = readByte();
                } else {
                    unreadByte(b2);
                    unreadByte(b1);
                }
            }
        }
        if (b0 < 0) return -1;
        if (b0 < 0x80) return b0;
        if (b0 >= 0xC2 && b0 <= 0xDF) {
            int b1 = readByte();
            if ((b1 & 0xC0) == 0x80) return ((b0 & 0x1F) << 6) | (b1 & 0x3F);
            unreadByte(b1);
            return b0;
        }
        if (b0 >= 0xE0 && b0 <= 0xEF) {
            int b1 = readByte();
            if ((b1 & 0xC0) == 0x80) {
                int b2 = readByte();
                if ((b2 & 0xC0) == 0x80) return ((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F);
                unreadByte(b2);
            }
            unreadByte(b1);
            return b0;
        }
        if (b0 >= 0xF0 && b0 <= 0xF4) {
            for (int k = 0; k < 3; k++) {
                int bx = readByte();
                if ((bx & 0xC0) != 0x80) { unreadByte(bx); break; }
            }
            return '?';
        }
        return b0;
    }

    private void unreadChar(int c) {
        if (c >= 0) cstack[csp++] = c;
    }

    private void emit(char c) {
        if (pos >= skipChars) {
            if (outLen >= out.length) {
                int n = Math.min(maxChars + 512, out.length * 2);
                if (n <= out.length) n = out.length + 512;
                char[] t = new char[n];
                System.arraycopy(out, 0, t, 0, outLen);
                out = t;
            }
            out[outLen++] = c;
        }
        pos++;
    }

    private void unemit() {
        if (pos > 0) {
            pos--;
            if (pos >= skipChars && outLen > 0) outLen--;
        }
    }

    private String parse(int[] outMeta) throws IOException {
        if (outMeta != null) {
            outMeta[0] = 0;
            outMeta[1] = skipChars;
        }
        int softMax = maxChars > 2400 ? maxChars - 1200 : maxChars;
        boolean space = false;
        boolean lineStart = true;
        boolean marked = false;
        boolean stopping = false;
        int stopLen = 0;
        char[] nameBuf = new char[16];
        char[] entBuf = new char[12];
        Hashtable noteBodies = new Hashtable();
        Vector noteRefs = new Vector();
        int noteSeq = 0;
        boolean inNoteRef = false;
        String currentNoteTarget = null;
        int noteRefStart = 0;

        for (;;) {
            int ci = nextChar();
            if (ci < 0) break;
            char c = (char) ci;
            if (c == '<') {
                int c1 = nextChar();
                if (c1 == '!') {
                    int c2 = nextChar(), c3 = nextChar();
                    if (c2 == '-' && c3 == '-') {
                        int d = 0;
                        while (true) {
                            int x = nextChar();
                            if (x < 0) break;
                            if (x == '-') d++;
                            else if (x == '>' && d >= 2) break;
                            else d = 0;
                        }
                        continue;
                    }
                    unreadChar(c3);
                    unreadChar(c2);
                }
                int t = c1;
                boolean closing = false;
                if (t == '/') {
                    closing = true;
                    t = nextChar();
                }
                int nlen = 0;
                while (t >= 0 && isNameChar((char) t)) {
                    if (nlen < nameBuf.length) nameBuf[nlen++] = Character.toLowerCase((char) t);
                    t = nextChar();
                }
                String name = nlen > 0 ? new String(nameBuf, 0, nlen) : "";
                String imgSrc = null, imgAlt = null, linkHref = null, tagId = null, epubType = null;
                boolean shouldParseAttrs = !closing && (name.equals("img") || name.equals("image") || name.equals("a") || name.equals("aside") || name.equals("section"));
                if (shouldParseAttrs) {
                    StringBuffer an = new StringBuffer();
                    StringBuffer av = new StringBuffer();
                    while (t >= 0 && t != '>') {
                        while (t >= 0 && (t == ' ' || t == '\t' || t == '\r' || t == '\n')) t = nextChar();
                        if (t < 0 || t == '>' || t == '/') break;
                        an.setLength(0);
                        while (t >= 0 && isNameChar((char) t)) {
                            an.append(Character.toLowerCase((char) t));
                            t = nextChar();
                        }
                        while (t >= 0 && (t == ' ' || t == '\t' || t == '\r' || t == '\n')) t = nextChar();
                        if (t == '=') {
                            t = nextChar();
                            while (t >= 0 && (t == ' ' || t == '\t' || t == '\r' || t == '\n')) t = nextChar();
                            av.setLength(0);
                            if (t == '"' || t == '\'') {
                                int quote = t;
                                t = nextChar();
                                while (t >= 0 && t != quote) {
                                    av.append((char) t);
                                    t = nextChar();
                                }
                                if (t == quote) t = nextChar();
                            } else {
                                while (t >= 0 && t > ' ' && t != '>' && t != '/') {
                                    av.append((char) t);
                                    t = nextChar();
                                }
                            }
                            String aName = an.toString();
                            String aVal = av.toString();
                            if (aName.equals("src") || (name.equals("image") && (aName.equals("href") || aName.endsWith(":href")))) {
                                if (imgSrc == null) imgSrc = aVal;
                            } else if (aName.equals("alt") || aName.equals("title")) {
                                if (imgAlt == null) imgAlt = aVal;
                            } else if (aName.equals("href") || aName.endsWith(":href")) {
                                if (linkHref == null) linkHref = aVal;
                            } else if (aName.equals("id")) {
                                if (tagId == null) tagId = aVal;
                            } else if (aName.equals("epub:type") || aName.equals("type") || aName.equals("class")) {
                                if (epubType == null) epubType = aVal.toLowerCase();
                            }
                        }
                    }
                }
                char q = 0;
                int lastNonSpace = closing ? '/' : 0;
                while (t >= 0) {
                    if (q != 0) {
                        if (t == q) q = 0;
                    } else if (t == '"' || t == '\'') {
                        q = (char) t;
                    } else if (t == '>') {
                        break;
                    } else if (t != ' ' && t != '\t' && t != '\r' && t != '\n') {
                        lastNonSpace = t;
                    }
                    t = nextChar();
                }
                boolean selfClose = !closing && (lastNonSpace == '/');
                if (imgSrc != null && imgSrc.length() > 0) {
                    if (stopping) continue;
                    String full = baseFile != null ? Util.resolve(baseFile, imgSrc) : imgSrc;
                    if (marked && lineStart) {
                        unemit();
                        marked = false;
                    }
                    if (!lineStart) {
                        emit('\n');
                        lineStart = true;
                    }
                    emit('\u0004');
                    for (int k = 0; k < full.length(); k++) emit(full.charAt(k));
                    if (imgAlt != null && imgAlt.trim().length() > 0) {
                        emit('\t');
                        String at = imgAlt.trim();
                        for (int k = 0; k < at.length(); k++) emit(at.charAt(k));
                    }
                    emit('\n');
                    lineStart = true;
                    space = false;
                    marked = false;
                    continue;
                }
                boolean isFootnoteContainer = !closing && epubType != null
                        && (epubType.indexOf("footnotes") >= 0 || epubType.indexOf("endnotes") >= 0);
                if (isFootnoteContainer) {
                    continue;
                }
                boolean isFootnoteTag = !closing && (name.equals("aside")
                        || (epubType != null && (epubType.equals("footnote") || epubType.equals("endnote"))));
                if (isFootnoteTag) {
                    String body = readTextUntilClose(name);
                    if (tagId != null && body.length() > 0) {
                        noteBodies.put(tagId, body);
                    }
                    if (stopping && (outNotes == null || allRefsFound(noteRefs, noteBodies))) {
                        break;
                    }
                    continue;
                }
                if (!stopping && !closing && name.equals("a")) {
                    if (linkHref != null && linkHref.startsWith("#")) {
                        String tid = linkHref.substring(1);
                        boolean isRef = (epubType != null && epubType.indexOf("noteref") >= 0)
                                || tid.startsWith("n") || tid.startsWith("fn") || tid.indexOf("note") >= 0;
                        if (isRef) {
                            inNoteRef = true;
                            currentNoteTarget = tid;
                            noteRefStart = outLen;
                        }
                    }
                }
                if (!stopping && closing && name.equals("a")) {
                    if (inNoteRef) {
                        inNoteRef = false;
                        int noteLen = outLen - noteRefStart;
                        String lbl;
                        if (noteLen == 0) {
                            lbl = "" + (noteSeq + 1);
                        } else {
                            lbl = new String(out, noteRefStart, noteLen).trim();
                            if (lbl.startsWith("[") && lbl.endsWith("]")) {
                                lbl = lbl.substring(1, lbl.length() - 1).trim();
                            }
                            if (lbl.length() == 0) lbl = "" + (noteSeq + 1);
                        }
                        outLen = noteRefStart;
                        emit('[');
                        for (int k = 0; k < lbl.length(); k++) emit(lbl.charAt(k));
                        emit(']');
                        int anchorLen = outLen - noteRefStart;
                        noteRefs.addElement(new Object[] {new Integer(noteRefStart), new Integer(anchorLen), currentNoteTarget, lbl});
                        noteSeq++;
                    }
                }
                if (!closing && !selfClose && (name.equals("script") || name.equals("style") || name.equals("head"))) {
                    skipUntilClose(name);
                    continue;
                }
                if (isBlock(name)) {
                    if (name.equals("title")) continue;
                    if (stopping) continue;
                    if (marked && lineStart) {
                        unemit();
                        marked = false;
                    }
                    if (!lineStart) {
                        emit('\n');
                        lineStart = true;
                    }
                    space = false;
                    marked = false;
                    if (!stopping && outLen >= softMax) {
                        stopping = true;
                        stopLen = outLen;
                    }
                    if (!closing) {
                        if (name.equals("h1") || name.equals("h2")) { emit('\u0001'); marked = true; }
                        else if (name.length() == 2 && name.charAt(0) == 'h' && name.charAt(1) >= '3' && name.charAt(1) <= '6') { emit('\u0002'); marked = true; }
                        else if (name.equals("hr")) { emit('\u0003'); emit('\n'); }
                        else if (name.equals("li")) { emit('\u2022'); emit(' '); lineStart = false; }
                    }
                }
                continue;
            }
            if (c == '&') {
                int elen = 0;
                int x = nextChar();
                while (x >= 0 && x != ';' && x != '<' && x != ' ' && x != '\n' && elen < entBuf.length) {
                    entBuf[elen++] = (char) x;
                    x = nextChar();
                }
                if (x == ';' && elen > 0) {
                    int cp = entity(new String(entBuf, 0, elen));
                    if (cp >= 0) {
                        if (cp == 0xA0) cp = ' ';
                        if (cp == ' ') { space = true; continue; }
                        if (stopping) {
                            if (outNotes == null || allRefsFound(noteRefs, noteBodies)) {
                                break;
                            }
                            continue;
                        }
                        if (space && !lineStart) emit(' ');
                        space = false;
                        if (cp > 0xFFFF) cp = '?';
                        emit((char) cp);
                        lineStart = false;
                        marked = false;
                        if (!stopping && outLen >= maxChars) {
                            stopping = true;
                            stopLen = outLen;
                        }
                        continue;
                    }
                }
                if (x >= 0) unreadChar(x);
                for (int k = elen - 1; k >= 0; k--) unreadChar(entBuf[k]);
            }
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f') {
                space = true;
                continue;
            }
            if (c < 32) continue;
            if (stopping) {
                if (outNotes == null || allRefsFound(noteRefs, noteBodies)) {
                    break;
                }
                continue;
            }
            if (space && !lineStart) emit(' ');
            space = false;
            emit(c);
            lineStart = false;
            marked = false;
            if (!stopping && outLen >= maxChars) {
                stopping = true;
                stopLen = outLen;
            }
        }
        if (stopping) {
            if (outMeta != null) {
                outMeta[0] = 1;
                outMeta[1] = skipChars + stopLen;
            }
            outLen = stopLen;
        }
        if (outNotes != null) {
            for (int i = 0; i < noteRefs.size(); i++) {
                Object[] r = (Object[]) noteRefs.elementAt(i);
                int off = ((Integer) r[0]).intValue();
                int len = ((Integer) r[1]).intValue();
                String tid = (String) r[2];
                String lbl = (String) r[3];
                String body = (String) noteBodies.get(tid);
                if (body != null && body.length() > 0) {
                    outNotes.addElement(new Book.Note(off, len, lbl, body));
                }
            }
        }
        char[] r = out;
        out = null;
        bbuf = null;
        return new String(r, 0, outLen);
    }

    private String readTextUntilClose(String tag) throws IOException {
        StringBuffer sb = new StringBuffer();
        char[] nb = new char[16];
        char[] eb = new char[12];
        for (;;) {
            int ci = nextChar();
            if (ci < 0) break;
            char c = (char) ci;
            if (c == '<') {
                int c1 = nextChar();
                boolean close = (c1 == '/');
                int t = close ? nextChar() : c1;
                int nlen = 0;
                while (t >= 0 && isNameChar((char) t)) {
                    if (nlen < nb.length) nb[nlen++] = Character.toLowerCase((char) t);
                    t = nextChar();
                }
                while (t >= 0 && t != '>') t = nextChar();
                String tn = nlen > 0 ? new String(nb, 0, nlen) : "";
                if (close && tn.equals(tag)) break;
                if (tn.equals("p") || tn.equals("br") || tn.equals("div")) {
                    if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
                }
                continue;
            }
            if (c == '&') {
                int elen = 0;
                int x = nextChar();
                while (x >= 0 && x != ';' && x != '<' && x != ' ' && x != '\n' && elen < eb.length) {
                    eb[elen++] = (char) x;
                    x = nextChar();
                }
                if (x == ';' && elen > 0) {
                    int cp = entity(new String(eb, 0, elen));
                    if (cp >= 0) c = (char) cp;
                }
            }
            if (c == '\r' || c == '\n' || c == '\t') c = ' ';
            if (c == ' ') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private void skipUntilClose(String name) throws IOException {
        int nlen = name.length();
        while (true) {
            int x = nextChar();
            if (x < 0) break;
            if (x == '<') {
                int y = nextChar();
                if (y == '/') {
                    int k = 0;
                    while (k < nlen) {
                        int z = nextChar();
                        if (z < 0 || Character.toLowerCase((char) z) != name.charAt(k)) break;
                        k++;
                    }
                    if (k == nlen) {
                        while (x >= 0 && x != '>') x = nextChar();
                        break;
                    }
                }
            }
        }
    }

    private static boolean allRefsFound(Vector refs, Hashtable bodies) {
        if (refs == null || refs.size() == 0) return true;
        for (int i = 0; i < refs.size(); i++) {
            Object[] r = (Object[]) refs.elementAt(i);
            String tid = (String) r[2];
            if (tid != null && !bodies.containsKey(tid)) return false;
        }
        return true;
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

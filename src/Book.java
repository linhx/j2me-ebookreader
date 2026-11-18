import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Hashtable;
import java.util.Vector;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;

/** A readable document split into chapters, engineered for low memory (CLDC 1.1 / ~2 MB heap). */
public abstract class Book {
    public String[] titles;

    public int count() {
        return titles.length;
    }

    /** Returns plain text of chapter i, paragraphs separated by '\n'. */
    public String chapter(int i) throws IOException {
        return chapter(i, 0, null);
    }

    /**
     * Returns plain text of chapter i starting at 'skipChars' plain-text offset.
     * If outMeta != null (length >= 2):
     *   outMeta[0] = 1 if chapter has more text after this chunk, 0 otherwise
     *   outMeta[1] = next skipChars offset
     */
    public abstract String chapter(int i, int skipChars, int[] outMeta) throws IOException;

    public void close() { }

    public static Book open(String url) throws IOException {
        String l = url.toLowerCase();
        if (l.endsWith(".epub") || l.endsWith(".zip")) return new EpubBook(url);
        boolean html = l.endsWith(".htm") || l.endsWith(".html") || l.endsWith(".xhtml") || l.endsWith(".xml");
        return new TextBook(url, html);
    }

    // ---------------------------------------------------------------- plain text & standalone HTML (streamed in windows)
    static class TextBook extends Book {
        private static final int CH = 36000;
        private static final int SLACK = 6144;
        private final FileConnection fc; // kept open: one permission prompt per book
        private final long size;
        private final boolean html;
        private boolean utf8 = true;

        TextBook(String url, boolean html) throws IOException {
            this.html = html;
            fc = (FileConnection) Connector.open(url, Connector.READ);
            if (fc == null) throw new IOException("No file access");
            size = fc.fileSize();
            int n = (int) ((size + CH - 1) / CH);
            if (n < 1) n = 1;
            titles = new String[n];
            for (int i = 0; i < n; i++) titles[i] = n == 1 ? "Nội dung" : "Phần " + (i + 1);
            byte[] head = window(0, 2048);
            utf8 = Util.isUtf8(head, 0, head.length);
        }

        private byte[] window(long from, int len) throws IOException {
            if (from + len > size) len = (int) (size - from);
            if (len <= 0) return new byte[0];
            InputStream in = fc.openInputStream();
            try {
                ZipReader.skipFully(in, from);
                byte[] b = new byte[len];
                int o = 0;
                while (o < len) {
                    int r = in.read(b, o, len - o);
                    if (r < 0) break;
                    o += r;
                }
                if (o < len) {
                    byte[] t = new byte[o];
                    System.arraycopy(b, 0, t, 0, o);
                    return t;
                }
                return b;
            } finally {
                in.close();
            }
        }

        /**
         * Deterministic split point at/after p within SLACK, consistent between adjacent windows.
         * In HTML mode, never splits inside a <...> tag or multi-byte UTF-8 sequence.
         */
        private int boundary(byte[] w, int p) {
            if (p >= w.length) return w.length;
            int lim = Math.min(w.length, p + SLACK);
            if (html) {
                int i = p;
                if (utf8) while (i < lim && (w[i] & 0xC0) == 0x80) i++;
                for (int k = i; k < lim; k++) {
                    byte b = w[k];
                    if (b == '<') return k;
                    if (b == '>') return k + 1;
                }
                for (int k = i; k < lim; k++) if (w[k] == '\n') return k + 1;
                return i;
            }
            for (int i = p; i < lim; i++) if (w[i] == '\n') return i + 1;
            int i = lim;
            if (utf8) while (i < w.length && (w[i] & 0xC0) == 0x80) i++;
            return i;
        }

        public void close() {
            try { fc.close(); } catch (Exception e) { }
        }

        public String chapter(int i, int skipChars, int[] outMeta) throws IOException {
            if (outMeta != null) {
                outMeta[0] = 0;
                outMeta[1] = 0;
            }
            long base = (long) i * CH;
            byte[] w = window(base, CH + SLACK + 8);
            int s = i == 0 ? 0 : boundary(w, 0);
            int e = (base + CH >= size) ? w.length : boundary(w, CH);
            if (e < s) e = s;
            if (html) {
                ByteArrayInputStream bin = new ByteArrayInputStream(w, s, e - s);
                w = null;
                return Html.toText(bin, skipChars, Html.MAX_PART_CHARS, outMeta);
            }
            String t;
            try {
                t = new String(w, s, e - s, utf8 ? "UTF-8" : "ISO-8859-1");
            } catch (Exception ex) {
                t = new String(w, s, e - s);
            }
            w = null;
            t = Util.replace(t, "\r\n", "\n");
            return t.replace('\r', '\n');
        }
    }

    // ---------------------------------------------------------------- EPUB (streamed)
    static class EpubBook extends Book {
        private ZipReader zip;
        private String[] files;

        EpubBook(String url) throws IOException {
            zip = new ZipReader(url);
            String opfPath = parseContainer();
            if (opfPath == null) throw new IOException("No OPF in container");

            Hashtable manifest = new Hashtable(); // id -> href
            String ncxHref = null, navHref = null;
            Vector list = new Vector();

            InputStream opfIn = zip.openStream(opfPath);
            try {
                XmlStream xs = new XmlStream(opfIn);
                String tag;
                while ((tag = xs.nextTag()) != null) {
                    if (tag.equals("item")) {
                        String id = xs.attr("id"), href = xs.attr("href");
                        if (id == null || href == null) continue;
                        String type = xs.attr("media-type");
                        String pr = xs.attr("properties");
                        if (type != null && type.indexOf("ncx") >= 0) ncxHref = href;
                        if (pr != null && pr.indexOf("nav") >= 0) navHref = href;
                        if (isContentFile(href, type)) manifest.put(id, href);
                    } else if (tag.equals("itemref")) {
                        if ("no".equals(xs.attr("linear"))) continue;
                        String idref = xs.attr("idref");
                        if (idref != null) {
                            String href = (String) manifest.get(idref);
                            if (href != null) list.addElement(Util.resolve(opfPath, href));
                        }
                    }
                }
            } finally {
                opfIn.close();
            }
            manifest.clear();
            manifest = null;

            if (list.size() == 0) throw new IOException("Empty spine");
            files = new String[list.size()];
            list.copyInto(files);
            list = null;

            titles = new String[files.length];
            Hashtable toc = new Hashtable();
            try {
                if (ncxHref != null) loadNcx(Util.resolve(opfPath, ncxHref), toc);
                else if (navHref != null) loadNav(Util.resolve(opfPath, navHref), toc);
            } catch (Exception e) {
                // titles are optional
            }
            for (int i = 0; i < files.length; i++) {
                String t = (String) toc.get(files[i]);
                titles[i] = (t != null && t.length() > 0) ? t : "Phần " + (i + 1);
            }
            toc.clear();
        }

        private static boolean isContentFile(String href, String type) {
            if (type != null) {
                if (type.startsWith("image/") || type.startsWith("audio/") || type.startsWith("video/")
                        || type.startsWith("font/") || type.equals("text/css"))
                    return false;
            }
            String l = href.toLowerCase();
            return !(l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".gif")
                    || l.endsWith(".svg") || l.endsWith(".css") || l.endsWith(".ttf") || l.endsWith(".otf")
                    || l.endsWith(".woff") || l.endsWith(".ncx"));
        }

        private String parseContainer() throws IOException {
            InputStream in = zip.openStream("META-INF/container.xml");
            try {
                XmlStream xs = new XmlStream(in);
                String tag;
                while ((tag = xs.nextTag()) != null) {
                    if (tag.equals("rootfile")) {
                        String fp = xs.attr("full-path");
                        if (fp != null) return Util.urlDecode(fp);
                    }
                }
            } finally {
                in.close();
            }
            return null;
        }

        private void loadNcx(String path, Hashtable toc) throws IOException {
            InputStream in = zip.openStream(path);
            try {
                XmlStream xs = new XmlStream(in);
                String lastTitle = null;
                String tag;
                while ((tag = xs.nextTag()) != null) {
                    if (tag.equals("text")) {
                        lastTitle = xs.readText("text");
                    } else if (tag.equals("content")) {
                        String src = xs.attr("src");
                        if (src != null && lastTitle != null && lastTitle.length() > 0) {
                            String full = Util.resolve(path, src);
                            if (!toc.containsKey(full)) toc.put(full, lastTitle);
                        }
                    }
                }
            } finally {
                in.close();
            }
        }

        private void loadNav(String path, Hashtable toc) throws IOException {
            InputStream in = zip.openStream(path);
            try {
                XmlStream xs = new XmlStream(in);
                String tag;
                while ((tag = xs.nextTag()) != null) {
                    if (tag.equals("a")) {
                        String href = xs.attr("href");
                        if (href != null) {
                            String title = xs.readText("a");
                            if (title.length() > 0) {
                                String full = Util.resolve(path, href);
                                if (!toc.containsKey(full)) toc.put(full, title);
                            }
                        }
                    }
                }
            } finally {
                in.close();
            }
        }

        public void close() {
            zip.close();
        }

        public String chapter(int i, int skipChars, int[] outMeta) throws IOException {
            InputStream in = zip.openStream(files[i]);
            try {
                return Html.toText(in, skipChars, Html.MAX_PART_CHARS, outMeta);
            } finally {
                in.close();
            }
        }
    }

    /**
     * Lightweight streaming XML parser for container.xml, content.opf, toc.ncx, and nav.xhtml.
     * Uses a 1 KB byte buffer and reuses a single Hashtable for attributes.
     */
    static class XmlStream {
        private final InputStream in;
        private final byte[] bbuf = new byte[1024];
        private int bpos = 0, blen = 0;
        private final int[] bstack = new int[4];
        private int bsp = 0;
        private int savedChar = -1;
        private boolean firstByte = true;
        private final Hashtable attrs = new Hashtable();

        XmlStream(InputStream in) {
            this.in = in;
        }

        String attr(String name) {
            return (String) attrs.get(name);
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
            if (savedChar >= 0) {
                int c = savedChar;
                savedChar = -1;
                return c;
            }
            int b0 = readByte();
            if (firstByte) {
                firstByte = false;
                if (b0 == 0xEF) {
                    int b1 = readByte(), b2 = readByte();
                    if (b1 == 0xBB && b2 == 0xBF) b0 = readByte();
                    else { unreadByte(b2); unreadByte(b1); }
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

        /** Advances to the next start tag, populates attrs, and returns the local lowercased tag name. */
        String nextTag() throws IOException {
            attrs.clear();
            StringBuffer sb = new StringBuffer(32);
            for (;;) {
                int c = nextChar();
                while (c >= 0 && c != '<') c = nextChar();
                if (c < 0) return null;
                c = nextChar();
                if (c < 0) return null;
                if (c == '?' || c == '/') {
                    while (c >= 0 && c != '>') c = nextChar();
                    continue;
                }
                if (c == '!') {
                    int c1 = nextChar(), c2 = nextChar();
                    if (c1 == '-' && c2 == '-') {
                        int d = 0;
                        while (true) {
                            int x = nextChar();
                            if (x < 0) return null;
                            if (x == '-') d++;
                            else if (x == '>' && d >= 2) break;
                            else d = 0;
                        }
                    } else {
                        while (c2 >= 0 && c2 != '>') c2 = nextChar();
                    }
                    continue;
                }
                sb.setLength(0);
                while (c >= 0 && !Util.isSpace((char) c) && c != '>' && c != '/') {
                    if (c == ':') sb.setLength(0); // strip namespace prefix (e.g. opf:item -> item)
                    else sb.append(Character.toLowerCase((char) c));
                    c = nextChar();
                }
                String name = sb.toString();
                while (c >= 0 && c != '>') {
                    while (c >= 0 && (Util.isSpace((char) c) || c == '/')) c = nextChar();
                    if (c < 0 || c == '>') break;
                    sb.setLength(0);
                    while (c >= 0 && c != '=' && !Util.isSpace((char) c) && c != '>' && c != '/') {
                        sb.append(Character.toLowerCase((char) c));
                        c = nextChar();
                    }
                    String an = sb.toString();
                    while (c >= 0 && Util.isSpace((char) c)) c = nextChar();
                    if (c == '=') {
                        c = nextChar();
                        while (c >= 0 && Util.isSpace((char) c)) c = nextChar();
                        sb.setLength(0);
                        if (c == '"' || c == '\'') {
                            int q = c;
                            c = nextChar();
                            while (c >= 0 && c != q) {
                                sb.append((char) c);
                                c = nextChar();
                            }
                            if (c == q) c = nextChar();
                        } else {
                            while (c >= 0 && !Util.isSpace((char) c) && c != '>' && c != '/') {
                                sb.append((char) c);
                                c = nextChar();
                            }
                        }
                        if (an.length() > 0) attrs.put(an, sb.toString());
                    }
                }
                if (name.length() > 0) return name;
            }
        }

        /** Reads and strips text up to </endTag>, collapsing spaces. */
        String readText(String endTag) throws IOException {
            StringBuffer sb = new StringBuffer(48);
            for (;;) {
                int c = nextChar();
                if (c < 0) break;
                if (c == '<') {
                    int c1 = nextChar();
                    if (c1 == '/') {
                        StringBuffer tb = new StringBuffer(12);
                        int x = nextChar();
                        while (x >= 0 && x != '>') {
                            if (x == ':') tb.setLength(0);
                            else if (!Util.isSpace((char) x)) tb.append(Character.toLowerCase((char) x));
                            x = nextChar();
                        }
                        if (tb.toString().equals(endTag)) break;
                    } else {
                        while (c1 >= 0 && c1 != '>') c1 = nextChar();
                    }
                    continue;
                }
                if (sb.length() < 200) sb.append((char) c);
            }
            return Util.plain(sb.toString());
        }
    }
}

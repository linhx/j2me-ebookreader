import java.io.IOException;
import java.io.InputStream;
import java.util.Hashtable;
import java.util.Vector;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;

/** A readable document split into chapters. */
public abstract class Book {
    public String[] titles;

    public int count() {
        return titles.length;
    }

    /** Returns plain text of chapter i, paragraphs separated by '\n'. */
    public abstract String chapter(int i) throws IOException;

    public void close() { }

    public static final int MAX_BYTES = 1200000;
    public static final int CHUNK_CHARS = 30000;

    public static Book open(String url) throws IOException {
        String l = url.toLowerCase();
        if (l.endsWith(".epub") || l.endsWith(".zip")) return new EpubBook(url);
        if (l.endsWith(".htm") || l.endsWith(".html") || l.endsWith(".xhtml") || l.endsWith(".xml"))
            return new MemBook(readAll(url), true);
        return new TextBook(url);
    }

    public static byte[] readAll(String url) throws IOException {
        FileConnection fc = (FileConnection) Connector.open(url, Connector.READ);
        try {
            long sz = fc.fileSize();
            if (sz > MAX_BYTES) throw new IOException("File too large (" + (sz / 1024) + " KB)");
            InputStream in = fc.openInputStream();
            try {
                byte[] b = new byte[(int) sz];
                int o = 0;
                while (o < b.length) {
                    int r = in.read(b, o, b.length - o);
                    if (r < 0) break;
                    o += r;
                }
                return b;
            } finally {
                in.close();
            }
        } finally {
            fc.close();
        }
    }

    // ---------------------------------------------------------------- in-memory (html)
    static class MemBook extends Book {
        private String text;
        private int[] starts;

        MemBook(byte[] data, boolean html) {
            String s = Util.decode(data, 0, data.length);
            text = html ? Html.toText(s) : s;
            Vector v = new Vector();
            int p = 0;
            while (p < text.length()) {
                v.addElement(new Integer(p));
                int e = p + CHUNK_CHARS;
                if (e >= text.length()) break;
                int nl = text.indexOf('\n', e);
                if (nl < 0 || nl - e > 4000) nl = e;
                p = nl + 1;
            }
            if (v.size() == 0) v.addElement(new Integer(0));
            starts = new int[v.size()];
            titles = new String[starts.length];
            for (int i = 0; i < starts.length; i++) {
                starts[i] = ((Integer) v.elementAt(i)).intValue();
                titles[i] = "Phần " + (i + 1);
            }
            if (starts.length == 1) titles[0] = "Nội dung";
        }

        public String chapter(int i) {
            int e = i + 1 < starts.length ? starts[i + 1] : text.length();
            return text.substring(starts[i], e);
        }
    }

    // ---------------------------------------------------------------- plain text (streamed)
    static class TextBook extends Book {
        private static final int CH = 40000;
        private static final int SLACK = 8192;
        private final FileConnection fc; // kept open: one permission prompt per book
        private final long size;
        private boolean utf8 = true;

        TextBook(String url) throws IOException {
            fc = (FileConnection) Connector.open(url, Connector.READ);
            if (fc == null) throw new IOException("No file access");
            size = fc.fileSize();
            int n = (int) ((size + CH - 1) / CH);
            if (n < 1) n = 1;
            titles = new String[n];
            for (int i = 0; i < n; i++) titles[i] = n == 1 ? "Nội dung" : "Phần " + (i + 1);
            byte[] head = window(0, 4096);
            utf8 = Util.isUtf8(head, 0, head.length);
        }

        private byte[] window(long from, int len) throws IOException {
            if (from + len > size) len = (int) (size - from);
            if (len <= 0) return new byte[0];
            InputStream in = fc.openInputStream();
            try {
                long n = from;
                while (n > 0) {
                    long s = in.skip(n);
                    if (s <= 0) {
                        if (in.read() < 0) break;
                        s = 1;
                    }
                    n -= s;
                }
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

        /** boundary(p): first position after a '\n' at/after p (within SLACK), consistent between windows. */
        private int boundary(byte[] w, int p) {
            if (p >= w.length) return w.length;
            int lim = Math.min(w.length, p + SLACK);
            for (int i = p; i < lim; i++) if (w[i] == '\n') return i + 1;
            int i = lim;
            if (utf8) while (i < w.length && (w[i] & 0xC0) == 0x80) i++;
            return i;
        }

        public void close() {
            try { fc.close(); } catch (Exception e) { }
        }

        public String chapter(int i) throws IOException {
            long base = (long) i * CH;
            byte[] w = window(base, CH + SLACK + 8);
            int s = i == 0 ? 0 : boundary(w, 0);
            int e = (base + CH >= size) ? w.length : boundary(w, CH);
            if (e < s) e = s;
            String t;
            try {
                t = new String(w, s, e - s, utf8 ? "UTF-8" : "ISO-8859-1");
            } catch (Exception ex) {
                t = new String(w, s, e - s);
            }
            t = Util.replace(t, "\r\n", "\n");
            return t.replace('\r', '\n');
        }
    }

    // ---------------------------------------------------------------- EPUB
    static class EpubBook extends Book {
        private ZipReader zip;
        private String[] files;

        EpubBook(String url) throws IOException {
            zip = new ZipReader(url);
            byte[] cb = zip.read("META-INF/container.xml", 65536);
            String container = Util.decode(cb, 0, cb.length);
            Vector rf = Util.tags(container, "rootfile");
            if (rf.size() == 0) throw new IOException("No OPF in container");
            String opfPath = Util.urlDecode((String) ((Hashtable) rf.elementAt(0)).get("full-path"));
            byte[] ob = zip.read(opfPath, 400000);
            String opf = Util.decode(ob, 0, ob.length);
            ob = null;

            Hashtable manifest = new Hashtable(); // id -> href
            Hashtable props = new Hashtable();
            String ncxHref = null, navHref = null;
            Vector items = Util.tags(opf, "item");
            for (int i = 0; i < items.size(); i++) {
                Hashtable a = (Hashtable) items.elementAt(i);
                String id = (String) a.get("id"), href = (String) a.get("href");
                if (id == null || href == null) continue;
                manifest.put(id, href);
                String type = (String) a.get("media-type");
                String pr = (String) a.get("properties");
                if (type != null && type.indexOf("ncx") >= 0) ncxHref = href;
                if (pr != null && pr.indexOf("nav") >= 0) navHref = href;
            }
            Vector refs = Util.tags(opf, "itemref");
            Vector list = new Vector();
            for (int i = 0; i < refs.size(); i++) {
                Hashtable a = (Hashtable) refs.elementAt(i);
                String idref = (String) a.get("idref");
                if (idref == null) continue;
                if ("no".equals(a.get("linear"))) continue;
                String href = (String) manifest.get(idref);
                if (href != null) list.addElement(Util.resolve(opfPath, href));
            }
            if (list.size() == 0) throw new IOException("Empty spine");
            files = new String[list.size()];
            list.copyInto(files);

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
        }

        private void loadNcx(String path, Hashtable toc) throws IOException {
            byte[] b = zip.read(path, 400000);
            String x = Util.decode(b, 0, b.length);
            int pos = 0;
            while (true) {
                int t = Util.indexOfIgnoreCase(x, "<text", pos);
                if (t < 0) break;
                int ts = x.indexOf('>', t) + 1;
                int te = Util.indexOfIgnoreCase(x, "</text", ts);
                if (te < 0) break;
                String title = Util.plain(x.substring(ts, te));
                int c = Util.indexOfIgnoreCase(x, "<content", te);
                if (c < 0) break;
                Hashtable a = Util.parseAttrs(x, c + 8, Util.tagEnd(x, c + 8));
                String src = (String) a.get("src");
                if (src != null) {
                    String full = Util.resolve(path, src);
                    if (!toc.containsKey(full)) toc.put(full, title);
                }
                pos = c + 8;
            }
        }

        private void loadNav(String path, Hashtable toc) throws IOException {
            byte[] b = zip.read(path, 400000);
            String x = Util.decode(b, 0, b.length);
            Vector as = Util.tags(x, "a");
            for (int i = 0; i < as.size(); i++) {
                Hashtable a = (Hashtable) as.elementAt(i);
                String href = (String) a.get("href");
                if (href == null) continue;
                int from = ((Integer) a.get("#pos")).intValue();
                int e = Util.indexOfIgnoreCase(x, "</a", from);
                if (e < 0) continue;
                String full = Util.resolve(path, href);
                if (!toc.containsKey(full)) toc.put(full, Util.plain(x.substring(from, e)));
            }
        }

        public void close() {
            zip.close();
        }

        public String chapter(int i) throws IOException {
            byte[] b = zip.read(files[i], MAX_BYTES);
            String s = Util.decode(b, 0, b.length);
            b = null;
            return Html.toText(s);
        }
    }
}

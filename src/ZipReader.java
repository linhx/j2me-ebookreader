import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Hashtable;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;

/** Random-access ZIP reader built on top of JSR-75 FileConnection. */
public class ZipReader {
    private final FileConnection fc; // kept open so the phone asks for permission only once
    private final long size;
    private final Hashtable entries = new Hashtable(); // name -> int[]{method, csize, usize, offset}

    public ZipReader(String url) throws IOException {
        fc = (FileConnection) Connector.open(url, Connector.READ);
        if (fc == null) throw new IOException("No file access");
        size = fc.fileSize();
        readDirectory();
    }

    private InputStream open(long skip) throws IOException {
        InputStream in = fc.openInputStream();
        skipFully(in, skip);
        return in;
    }

    static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) throw new IOException("EOF");
                s = 1;
            }
            n -= s;
        }
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    private int findEocd(int maxTail) throws IOException {
        int tailLen = (int) Math.min(size, (long) maxTail);
        byte[] tail = new byte[tailLen];
        InputStream in = open(size - tailLen);
        try {
            readFully(in, tail, 0, tailLen);
        } finally {
            in.close();
        }
        for (int i = tailLen - 22; i >= 0; i--) {
            if (tail[i] == 0x50 && tail[i + 1] == 0x4b && tail[i + 2] == 5 && tail[i + 3] == 6) {
                int count = u16(tail, i + 10);
                long cdOff = u32(tail, i + 16);
                tail = null;
                parseCentralDir(count, cdOff);
                return 0;
            }
        }
        return -1;
    }

    private void readDirectory() throws IOException {
        // Try 1 KB tail first to avoid allocating 66 KB on normal EPUBs with no zip comment
        if (findEocd(1024) == 0) return;
        if (findEocd(66000) == 0) return;
        throw new IOException("Not a ZIP/EPUB file");
    }

    private void parseCentralDir(int count, long cdOff) throws IOException {
        InputStream in = open(cdOff);
        try {
            DataInputStream din = new DataInputStream(in);
            byte[] h = new byte[46];
            byte[] nb = new byte[128];
            for (int i = 0; i < count; i++) {
                din.readFully(h, 0, 46);
                if (u32(h, 0) != 0x02014b50L) throw new IOException("Bad central directory");
                int method = u16(h, 10);
                long csize = u32(h, 20);
                long usize = u32(h, 24);
                int nl = u16(h, 28), el = u16(h, 30), cl = u16(h, 32);
                long off = u32(h, 42);
                if (nl > nb.length) nb = new byte[nl];
                din.readFully(nb, 0, nl);
                skipFully(din, el + cl);
                String name = new String(nb, 0, nl, "UTF-8");
                String ln = name.toLowerCase();
                // keep only text-like entries: skip images/fonts/css to save heap
                if (ln.endsWith(".xhtml") || ln.endsWith(".html") || ln.endsWith(".htm") || ln.endsWith(".xml")
                        || ln.endsWith(".opf") || ln.endsWith(".ncx") || ln.endsWith(".txt"))
                    entries.put(name, new int[] {method, (int) csize, (int) usize, (int) off});
            }
        } finally {
            in.close();
        }
    }

    private static void readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            int r = in.read(b, off, len);
            if (r < 0) throw new IOException("EOF");
            off += r;
            len -= r;
        }
    }

    public void close() {
        entries.clear();
        try { fc.close(); } catch (Exception e) { }
    }

    public boolean has(String name) {
        return entries.containsKey(name);
    }

    /** Find an entry ignoring case if exact name is missing; returns the real name or null. */
    public String find(String name) {
        if (entries.containsKey(name)) return name;
        java.util.Enumeration en = entries.keys();
        while (en.hasMoreElements()) {
            String k = (String) en.nextElement();
            if (k.equalsIgnoreCase(name)) return k;
        }
        return null;
    }

    /** Opens a streaming InputStream for the entry without loading the entire uncompressed entry into RAM. */
    public InputStream openStream(String name) throws IOException {
        String real = find(name);
        if (real == null) throw new IOException("Missing: " + name);
        int[] e = (int[]) entries.get(real);
        final InputStream in = open(e[3]);
        byte[] lh = new byte[30];
        try {
            readFully(in, lh, 0, 30);
            if (u32(lh, 0) != 0x04034b50L) throw new IOException("Bad local header");
            skipFully(in, u16(lh, 26) + u16(lh, 28));
        } catch (IOException ex) {
            try { in.close(); } catch (Exception ignore) { }
            throw ex;
        }
        if (e[0] == 0) {
            final int csize = e[1];
            return new InputStream() {
                private int left = csize;
                public int read() throws IOException {
                    if (left <= 0) return -1;
                    int v = in.read();
                    if (v >= 0) left--;
                    return v;
                }
                public int read(byte[] b, int off, int len) throws IOException {
                    if (left <= 0) return -1;
                    int r = in.read(b, off, Math.min(len, left));
                    if (r > 0) left -= r;
                    return r;
                }
                public void close() throws IOException {
                    in.close();
                }
            };
        } else if (e[0] == 8) {
            return new Inflate(in, e[1]);
        }
        try { in.close(); } catch (Exception ignore) { }
        throw new IOException("Unsupported compression");
    }

    public byte[] read(String name, int maxSize) throws IOException {
        String real = find(name);
        if (real == null) throw new IOException("Missing: " + name);
        int[] e = (int[]) entries.get(real);
        int usize = e[2];
        if (usize > maxSize) throw new IOException("Too large");
        InputStream in = openStream(real);
        try {
            byte[] buf = new byte[usize > 0 ? usize : 4096];
            int len = 0;
            while (true) {
                if (len == buf.length) {
                    if (len >= maxSize) throw new IOException("Too large");
                    byte[] n = new byte[Math.min(maxSize, buf.length * 2)];
                    System.arraycopy(buf, 0, n, 0, len);
                    buf = n;
                }
                int r = in.read(buf, len, buf.length - len);
                if (r < 0) break;
                len += r;
            }
            if (len == buf.length) return buf;
            byte[] r = new byte[len];
            System.arraycopy(buf, 0, r, 0, len);
            return r;
        } finally {
            in.close();
        }
    }
}

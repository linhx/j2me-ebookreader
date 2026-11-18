import java.util.Vector;

/** Text decoding and path/HTML helpers. */
public class Util {

    /** Validates UTF-8; tolerates a truncated sequence at the very end. */
    public static boolean isUtf8(byte[] b, int off, int len) {
        int end = off + len;
        int i = off;
        while (i < end) {
            int c = b[i] & 0xFF;
            int n;
            if (c < 0x80) { i++; continue; }
            else if (c >= 0xC2 && c <= 0xDF) n = 1;
            else if (c >= 0xE0 && c <= 0xEF) n = 2;
            else if (c >= 0xF0 && c <= 0xF4) n = 3;
            else return false;
            if (i + n >= end) return checkTail(b, i + 1, end);
            for (int k = 1; k <= n; k++) if ((b[i + k] & 0xC0) != 0x80) return false;
            i += n + 1;
        }
        return true;
    }

    private static boolean checkTail(byte[] b, int from, int end) {
        for (int k = from; k < end; k++) if ((b[k] & 0xC0) != 0x80) return false;
        return true;
    }

    public static String replace(String s, String a, String b) {
        int i = s.indexOf(a);
        if (i < 0) return s;
        StringBuffer sb = new StringBuffer();
        int p = 0;
        while (i >= 0) {
            sb.append(s.substring(p, i)).append(b);
            p = i + a.length();
            i = s.indexOf(a, p);
        }
        sb.append(s.substring(p));
        return sb.toString();
    }

    public static String urlDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '%' && i + 2 < s.length()) {
                    try {
                        bo.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                        i += 2;
                        continue;
                    } catch (NumberFormatException e) { }
                }
                if (c < 128) bo.write(c);
                else {
                    byte[] t = String.valueOf(c).getBytes("UTF-8");
                    bo.write(t, 0, t.length);
                }
            }
            byte[] r = bo.toByteArray();
            return new String(r, 0, r.length, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** Resolve href relative to the directory of base (zip path style), removing fragment/query. */
    public static String resolve(String baseFile, String href) {
        int h = href.indexOf('#');
        if (h >= 0) href = href.substring(0, h);
        href = urlDecode(href);
        String dir = "";
        int sl = baseFile.lastIndexOf('/');
        if (sl >= 0) dir = baseFile.substring(0, sl + 1);
        String path = href.startsWith("/") ? href.substring(1) : dir + href;
        Vector parts = new Vector();
        int p = 0;
        while (p <= path.length()) {
            int q = path.indexOf('/', p);
            if (q < 0) q = path.length();
            String seg = path.substring(p, q);
            if (seg.equals("..")) {
                if (parts.size() > 0) parts.removeElementAt(parts.size() - 1);
            } else if (seg.length() > 0 && !seg.equals(".")) {
                parts.addElement(seg);
            }
            p = q + 1;
        }
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append((String) parts.elementAt(i));
        }
        return sb.toString();
    }

    public static boolean isSpace(char c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f';
    }

    /** Strip tags and entities from a short snippet. */
    public static String plain(String s) {
        String t = Html.toText(s);
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\n') c = ' ';
            if (c >= 4 || c == ' ') sb.append(c);
        }
        return sb.toString().trim();
    }
}

import java.io.IOException;
import java.util.Hashtable;
import java.util.Vector;

/** Text decoding, tiny XML/HTML helpers. */
public class Util {

    /** Decode bytes: UTF-8 if valid, otherwise ISO-8859-1. */
    public static String decode(byte[] b, int off, int len) {
        if (len >= 3 && (b[off] & 0xFF) == 0xEF && (b[off + 1] & 0xFF) == 0xBB && (b[off + 2] & 0xFF) == 0xBF) {
            off += 3;
            len -= 3;
        }
        try {
            if (isUtf8(b, off, len)) return new String(b, off, len, "UTF-8");
            return new String(b, off, len, "ISO-8859-1");
        } catch (Exception e) {
            return new String(b, off, len);
        }
    }

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

    public static String lower(String s) {
        return s.toLowerCase();
    }

    public static int indexOfIgnoreCase(String s, String what, int from) {
        int n = what.length();
        int max = s.length() - n;
        char f = Character.toLowerCase(what.charAt(0));
        for (int i = from; i <= max; i++) {
            if (Character.toLowerCase(s.charAt(i)) != f) continue;
            int k = 1;
            while (k < n && Character.toLowerCase(s.charAt(i + k)) == Character.toLowerCase(what.charAt(k))) k++;
            if (k == n) return i;
        }
        return -1;
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

    /**
     * Find all start tags with the given name (case-insensitive). Each result is a Hashtable of
     * lower-cased attribute name -> value, plus key "#pos" (Integer, index after the tag).
     */
    public static Vector tags(String xml, String name) {
        Vector res = new Vector();
        int pos = 0;
        String key = "<" + name;
        while (true) {
            int i = indexOfIgnoreCase(xml, key, pos);
            if (i < 0) break;
            int after = i + key.length();
            if (after < xml.length()) {
                char c = xml.charAt(after);
                if (!(c == ' ' || c == '>' || c == '/' || c == '\n' || c == '\r' || c == '\t')) {
                    pos = after;
                    continue;
                }
            }
            int end = tagEnd(xml, after);
            Hashtable h = parseAttrs(xml, after, end);
            h.put("#pos", new Integer(end + 1));
            h.put("#start", new Integer(i));
            res.addElement(h);
            pos = end + 1;
        }
        return res;
    }

    public static int tagEnd(String s, int from) {
        char q = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) {
                if (c == q) q = 0;
            } else if (c == '"' || c == '\'') {
                q = c;
            } else if (c == '>') {
                return i;
            }
        }
        return s.length() - 1;
    }

    public static Hashtable parseAttrs(String s, int from, int to) {
        Hashtable h = new Hashtable();
        int i = from;
        while (i < to) {
            while (i < to && isSpace(s.charAt(i))) i++;
            int ns = i;
            while (i < to && s.charAt(i) != '=' && !isSpace(s.charAt(i)) && s.charAt(i) != '/') i++;
            if (i == ns) { i++; continue; }
            String an = s.substring(ns, i).toLowerCase();
            while (i < to && isSpace(s.charAt(i))) i++;
            if (i < to && s.charAt(i) == '=') {
                i++;
                while (i < to && isSpace(s.charAt(i))) i++;
                String val;
                if (i < to && (s.charAt(i) == '"' || s.charAt(i) == '\'')) {
                    char q = s.charAt(i++);
                    int vs = i;
                    while (i < to && s.charAt(i) != q) i++;
                    val = s.substring(vs, i);
                    i++;
                } else {
                    int vs = i;
                    while (i < to && !isSpace(s.charAt(i))) i++;
                    val = s.substring(vs, i);
                }
                h.put(an, val);
            } else {
                h.put(an, "");
            }
        }
        return h;
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

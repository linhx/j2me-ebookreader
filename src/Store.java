import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;

/** Tiny key/value persistence on top of RMS. Records are "key\tvalue". */
public class Store {
    private static final String NAME = "bookreader";

    public static synchronized String get(String key, String def) {
        RecordStore rs = null;
        RecordEnumeration en = null;
        try {
            rs = RecordStore.openRecordStore(NAME, true);
            en = rs.enumerateRecords(null, null, false);
            String prefix = key + "\t";
            while (en.hasNextElement()) {
                int id = en.nextRecordId();
                String s = new String(rs.getRecord(id), "UTF-8");
                if (s.startsWith(prefix)) return s.substring(prefix.length());
            }
        } catch (Exception e) {
        } finally {
            if (en != null) try { en.destroy(); } catch (Exception e) { }
            close(rs);
        }
        return def;
    }

    public static synchronized void put(String key, String value) {
        RecordStore rs = null;
        RecordEnumeration en = null;
        try {
            rs = RecordStore.openRecordStore(NAME, true);
            byte[] data = (key + "\t" + value).getBytes("UTF-8");
            en = rs.enumerateRecords(null, null, false);
            String prefix = key + "\t";
            while (en.hasNextElement()) {
                int id = en.nextRecordId();
                String s = new String(rs.getRecord(id), "UTF-8");
                if (s.startsWith(prefix)) {
                    rs.setRecord(id, data, 0, data.length);
                    return;
                }
            }
            rs.addRecord(data, 0, data.length);
        } catch (Exception e) {
        } finally {
            if (en != null) try { en.destroy(); } catch (Exception e) { }
            close(rs);
        }
    }

    public static synchronized java.util.Hashtable getAllWithPrefix(String prefix) {
        java.util.Hashtable map = new java.util.Hashtable();
        RecordStore rs = null;
        RecordEnumeration en = null;
        try {
            rs = RecordStore.openRecordStore(NAME, true);
            en = rs.enumerateRecords(null, null, false);
            while (en.hasNextElement()) {
                int id = en.nextRecordId();
                String s = new String(rs.getRecord(id), "UTF-8");
                if (s.startsWith(prefix)) {
                    int tab = s.indexOf('\t');
                    if (tab >= 0) {
                        map.put(s.substring(0, tab), s.substring(tab + 1));
                    }
                }
            }
        } catch (Exception e) {
        } finally {
            if (en != null) try { en.destroy(); } catch (Exception e) { }
            close(rs);
        }
        return map;
    }

    public static int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, ""));
        } catch (Exception e) {
            return def;
        }
    }

    public static void putInt(String key, int v) {
        put(key, String.valueOf(v));
    }

    public static long getLong(String key, long def) {
        try {
            return Long.parseLong(get(key, ""));
        } catch (Exception e) {
            return def;
        }
    }

    public static void putLong(String key, long v) {
        put(key, String.valueOf(v));
    }

    private static void close(RecordStore rs) {
        if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) { }
    }
}

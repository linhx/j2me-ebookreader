import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;

/** Tiny key/value persistence on top of RMS. Records are "key\tvalue". */
public class Store {
    private static final String NAME = "bookreader";

    public static synchronized String get(String key, String def) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(NAME, true);
            RecordEnumeration en = rs.enumerateRecords(null, null, false);
            String prefix = key + "\t";
            while (en.hasNextElement()) {
                int id = en.nextRecordId();
                String s = new String(rs.getRecord(id), "UTF-8");
                if (s.startsWith(prefix)) return s.substring(prefix.length());
            }
        } catch (Exception e) {
        } finally {
            close(rs);
        }
        return def;
    }

    public static synchronized void put(String key, String value) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(NAME, true);
            byte[] data = (key + "\t" + value).getBytes("UTF-8");
            RecordEnumeration en = rs.enumerateRecords(null, null, false);
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
            close(rs);
        }
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

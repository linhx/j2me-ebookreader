import java.io.IOException;
import java.io.InputStream;

/**
 * Lightweight helper to inspect image dimensions from JPEG, PNG, and GIF headers
 * without decoding pixel data into memory, preventing OutOfMemoryError on J2ME.
 */
public class ImageUtil {

    public static int[] getDimensions(InputStream in) {
        if (in == null) return null;
        try {
            int b0 = in.read(), b1 = in.read();
            if (b0 < 0 || b1 < 0) return null;

            // PNG signature: 89 50 4E 47 0D 0A 1A 0A
            if (b0 == 0x89 && b1 == 0x50) {
                byte[] hdr = new byte[22];
                readFully(in, hdr);
                int w = ((hdr[14] & 0xFF) << 24) | ((hdr[15] & 0xFF) << 16) | ((hdr[16] & 0xFF) << 8) | (hdr[17] & 0xFF);
                int h = ((hdr[18] & 0xFF) << 24) | ((hdr[19] & 0xFF) << 16) | ((hdr[20] & 0xFF) << 8) | (hdr[21] & 0xFF);
                return (w > 0 && h > 0) ? new int[] {w, h} : null;
            }

            // GIF signature: GIF87a or GIF89a
            if (b0 == 'G' && b1 == 'I') {
                byte[] hdr = new byte[8];
                readFully(in, hdr);
                int w = (hdr[4] & 0xFF) | ((hdr[5] & 0xFF) << 8);
                int h = (hdr[6] & 0xFF) | ((hdr[7] & 0xFF) << 8);
                return (w > 0 && h > 0) ? new int[] {w, h} : null;
            }

            // JPEG SOI: FF D8
            if (b0 == 0xFF && b1 == 0xD8) {
                while (true) {
                    int c = in.read();
                    while (c == 0xFF) c = in.read();
                    if (c < 0 || c == 0xD9 || c == 0xDA) break; // EOF, EOI, or SOS (scan data)
                    int lenHigh = in.read(), lenLow = in.read();
                    if (lenHigh < 0 || lenLow < 0) break;
                    int len = ((lenHigh & 0xFF) << 8) | (lenLow & 0xFF);
                    // SOF0 (0xC0), SOF1 (0xC1), SOF2 (0xC2)
                    if (c == 0xC0 || c == 0xC1 || c == 0xC2) {
                        int prec = in.read();
                        int hHigh = in.read(), hLow = in.read();
                        int wHigh = in.read(), wLow = in.read();
                        if (wLow < 0) break;
                        int h = ((hHigh & 0xFF) << 8) | (hLow & 0xFF);
                        int w = ((wHigh & 0xFF) << 8) | (wLow & 0xFF);
                        return (w > 0 && h > 0) ? new int[] {w, h} : null;
                    } else {
                        int toSkip = len - 2;
                        while (toSkip > 0) {
                            long s = in.skip(toSkip);
                            if (s <= 0) {
                                if (in.read() < 0) return null;
                                s = 1;
                            }
                            toSkip -= s;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return null;
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) throw new IOException("EOF");
            off += r;
        }
    }
}

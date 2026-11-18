import java.io.IOException;
import java.io.InputStream;

/**
 * Minimal raw-DEFLATE decoder (RFC 1951) for CLDC, which has no java.util.zip.
 * Works as a streaming InputStream using a fixed 32 KB sliding window, so peak
 * memory is ~34 KB regardless of the uncompressed entry size.
 */
public class Inflate extends InputStream {
    private static final int MAXBITS = 15;
    private static final int WSIZE = 32768;
    private static final int WMASK = WSIZE - 1;
    private static final short[] LBASE = {3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258};
    private static final short[] LEXT = {0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0};
    private static final short[] DBASE = {1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577};
    private static final short[] DEXT = {0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12,13,13};
    private static final byte[] ORDER = {16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15};

    private static final int S_BLOCK = 0, S_STORED = 1, S_CODES = 2, S_DONE = 3;

    private InputStream in;
    private byte[] ibuf = new byte[1024];
    private int ipos = 0, ilen = 0;
    private int bitBuf = 0, bitCnt = 0;
    private long inLeft;

    // 32 KB circular window: simultaneously holds lookback dictionary and unread output
    private byte[] win = new byte[WSIZE];
    private int wpos = 0; // total bytes written
    private int rpos = 0; // total bytes read by caller

    private int state = S_BLOCK;
    private boolean lastBlock = false;
    private int storedLeft = 0;

    private short[] lcount = new short[MAXBITS + 1], lsym = new short[288];
    private short[] dcount = new short[MAXBITS + 1], dsym = new short[30];

    public Inflate(InputStream in, long compressedSize) {
        this.in = in;
        this.inLeft = compressedSize;
    }

    public int read() throws IOException {
        if (rpos == wpos) {
            fill();
            if (rpos == wpos) return -1;
        }
        return win[rpos++ & WMASK] & 0xFF;
    }

    public int read(byte[] b, int off, int len) throws IOException {
        if (len <= 0) return 0;
        int total = 0;
        while (len > 0) {
            if (rpos == wpos) {
                fill();
                if (rpos == wpos) return total == 0 ? -1 : total;
            }
            int avail = wpos - rpos;
            int ri = rpos & WMASK;
            int chunk = Math.min(len, Math.min(avail, WSIZE - ri));
            System.arraycopy(win, ri, b, off, chunk);
            rpos += chunk;
            off += chunk;
            len -= chunk;
            total += chunk;
        }
        return total;
    }

    public void close() throws IOException {
        win = null;
        ibuf = null;
        if (in != null) {
            in.close();
            in = null;
        }
    }

    /** Decompresses until at least some bytes are available in win, or EOF. */
    private void fill() throws IOException {
        // One Huffman symbol outputs at most 258 bytes; keep 260 bytes free in win.
        while (state != S_DONE && (wpos - rpos) <= WSIZE - 260) {
            if (state == S_BLOCK) {
                if (lastBlock) {
                    state = S_DONE;
                    break;
                }
                lastBlock = bits(1) != 0;
                int type = bits(2);
                if (type == 0) {
                    bitBuf = 0;
                    bitCnt = 0;
                    int b0 = nextByte(), b1 = nextByte(), b2 = nextByte(), b3 = nextByte();
                    int len = b0 | (b1 << 8);
                    int nlen = b2 | (b3 << 8);
                    if ((len ^ 0xFFFF) != nlen) throw new IOException("Bad stored");
                    storedLeft = len;
                    state = S_STORED;
                } else if (type == 1) {
                    byte[] l = new byte[288];
                    int i;
                    for (i = 0; i < 144; i++) l[i] = 8;
                    for (; i < 256; i++) l[i] = 9;
                    for (; i < 280; i++) l[i] = 7;
                    for (; i < 288; i++) l[i] = 8;
                    construct(lcount, lsym, l, 0, 288);
                    byte[] d = new byte[30];
                    for (i = 0; i < 30; i++) d[i] = 5;
                    construct(dcount, dsym, d, 0, 30);
                    state = S_CODES;
                } else if (type == 2) {
                    int nlen = bits(5) + 257;
                    int ndist = bits(5) + 1;
                    int ncode = bits(4) + 4;
                    if (nlen > 286 || ndist > 30) throw new IOException("Bad counts");
                    byte[] lengths = new byte[320];
                    int i;
                    for (i = 0; i < ncode; i++) lengths[ORDER[i]] = (byte) bits(3);
                    for (; i < 19; i++) lengths[ORDER[i]] = 0;
                    short[] cc = new short[MAXBITS + 1], cs = new short[19];
                    if (construct(cc, cs, lengths, 0, 19) != 0) throw new IOException("Bad code lengths");
                    byte[] ll = new byte[320];
                    int idx = 0;
                    while (idx < nlen + ndist) {
                        int sym = decode(cc, cs);
                        if (sym < 16) {
                            ll[idx++] = (byte) sym;
                        } else {
                            int len = 0, rep;
                            if (sym == 16) {
                                if (idx == 0) throw new IOException("Bad repeat");
                                len = ll[idx - 1];
                                rep = 3 + bits(2);
                            } else if (sym == 17) {
                                rep = 3 + bits(3);
                            } else {
                                rep = 11 + bits(7);
                            }
                            if (idx + rep > nlen + ndist) throw new IOException("Bad repeat");
                            while (rep-- > 0) ll[idx++] = (byte) len;
                        }
                    }
                    construct(lcount, lsym, ll, 0, nlen);
                    construct(dcount, dsym, ll, nlen, ndist);
                    state = S_CODES;
                } else {
                    throw new IOException("Bad block");
                }
            } else if (state == S_STORED) {
                while (storedLeft > 0 && (wpos - rpos) < WSIZE) {
                    win[wpos++ & WMASK] = (byte) nextByte();
                    storedLeft--;
                }
                if (storedLeft == 0) state = S_BLOCK;
                else break;
            } else if (state == S_CODES) {
                int s = decode(lcount, lsym);
                if (s < 256) {
                    win[wpos++ & WMASK] = (byte) s;
                } else if (s == 256) {
                    state = S_BLOCK;
                } else {
                    s -= 257;
                    if (s >= 29) throw new IOException("Bad len");
                    int len = LBASE[s] + bits(LEXT[s]);
                    int ds = decode(dcount, dsym);
                    if (ds >= 30) throw new IOException("Bad dist");
                    int dist = DBASE[ds] + bits(DEXT[ds]);
                    if (dist > wpos) throw new IOException("Dist too far");
                    while (len-- > 0) {
                        win[wpos & WMASK] = win[(wpos - dist) & WMASK];
                        wpos++;
                    }
                }
            }
        }
    }

    private int bits(int need) throws IOException {
        int val = bitBuf;
        while (bitCnt < need) {
            if (ipos >= ilen) {
                if (inLeft <= 0) throw new IOException("EOF");
                int want = (int) Math.min((long) ibuf.length, inLeft);
                ilen = in.read(ibuf, 0, want);
                if (ilen <= 0) throw new IOException("EOF");
                inLeft -= ilen;
                ipos = 0;
            }
            val |= (ibuf[ipos++] & 0xFF) << bitCnt;
            bitCnt += 8;
        }
        bitBuf = val >>> need;
        bitCnt -= need;
        return val & ((1 << need) - 1);
    }

    private int nextByte() throws IOException {
        if (ipos >= ilen) {
            if (inLeft <= 0) throw new IOException("EOF");
            int want = (int) Math.min((long) ibuf.length, inLeft);
            ilen = in.read(ibuf, 0, want);
            if (ilen <= 0) throw new IOException("EOF");
            inLeft -= ilen;
            ipos = 0;
        }
        return ibuf[ipos++] & 0xFF;
    }

    private int decode(short[] count, short[] sym) throws IOException {
        int code = 0, first = 0, index = 0;
        for (int len = 1; len <= MAXBITS; len++) {
            code |= bits(1);
            int c = count[len];
            if (code - c < first) return sym[index + (code - first)];
            index += c;
            first += c;
            first <<= 1;
            code <<= 1;
        }
        throw new IOException("Bad code");
    }

    private static int construct(short[] count, short[] sym, byte[] length, int off, int n) {
        short[] offs = new short[MAXBITS + 1];
        int len, i;
        for (len = 0; len <= MAXBITS; len++) count[len] = 0;
        for (i = 0; i < n; i++) count[length[off + i]]++;
        if (count[0] == n) return 0;
        int left = 1;
        for (len = 1; len <= MAXBITS; len++) {
            left <<= 1;
            left -= count[len];
            if (left < 0) return left;
        }
        offs[1] = 0;
        for (len = 1; len < MAXBITS; len++) offs[len + 1] = (short) (offs[len] + count[len]);
        for (i = 0; i < n; i++) {
            if (length[off + i] != 0) sym[offs[length[off + i]]++] = (short) i;
        }
        return left;
    }
}

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.imageio.ImageIO;
import javax.microedition.lcdui.Graphics;
import org.recompile.mobile.PlatformImage;

/**
 * Renders an actual 240x320 Nokia C2-01 screen of any EPUB/HTML/TXT book directly on PC
 * to a PNG file, showing exact fonts, line wrap, justification, and rendered images.
 */
public class PreviewBook {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Sử dụng: ./tools/preview.sh <đường_dẫn_sách> [số_thứ_tự_chương] [file_kết_quả.png]");
            return;
        }
        String rawPath = args[0];
        File bookFile = new File(rawPath);
        if (!bookFile.exists()) {
            System.out.println("Lỗi: Không tìm thấy file " + rawPath);
            System.exit(1);
        }
        String url = "file://" + bookFile.getAbsolutePath();
        int chIdx = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        File outFile = new File(args.length > 2 ? args[2] : "preview.png");

        org.recompile.mobile.Mobile.lcdWidth = 240;
        org.recompile.mobile.Mobile.lcdHeight = 320;
        org.recompile.mobile.Mobile.isAAEnabled = true;
        org.recompile.mobile.Mobile.setPlatform(new org.recompile.mobile.MobilePlatform(240, 320), new Runnable() {
            public void run() {}
        });
        org.recompile.mobile.Mobile.setDisplay(new javax.microedition.lcdui.Display());

        System.out.println("Đang đọc: " + bookFile.getName());
        Book b = Book.open(url);
        System.out.println("- Tổng số chương: " + b.count());
        if (chIdx < 0 || chIdx >= b.count()) chIdx = 0;
        System.out.println("- Chương đang hiển thị (" + chIdx + "): " + b.titles[chIdx]);

        ReaderCanvas rc = new ReaderCanvas(null);
        rc.openBook(url, b);
        if (chIdx > 0) {
            rc.gotoChapter(chIdx);
        }

        // Wait for background loader thread
        Field fBusy = rc.getClass().getDeclaredField("busy");
        fBusy.setAccessible(true);
        Thread.sleep(200);
        for (int i = 0; i < 50; i++) {
            if (!fBusy.getBoolean(rc)) break;
            Thread.sleep(100);
        }

        Field fNotes = rc.getClass().getDeclaredField("notes");
        fNotes.setAccessible(true);
        Object[] notesArr = (Object[]) fNotes.get(rc);
        System.out.println("DEBUG: notesArr count = " + (notesArr == null ? "null" : "" + notesArr.length));

        if (args.length > 3) {
            String sim = args[3];
            Method mHandle = rc.getClass().getDeclaredMethod("handle", new Class[] { int.class, boolean.class });
            mHandle.setAccessible(true);
            if ("note".equalsIgnoreCase(sim)) {
                // Press 9 to navigate to footnote, then KEY_NUM5 (or OK) to open dialog
                mHandle.invoke(rc, new Object[] { new Integer(javax.microedition.lcdui.Canvas.KEY_NUM9), Boolean.FALSE });
                mHandle.invoke(rc, new Object[] { new Integer(javax.microedition.lcdui.Canvas.KEY_NUM5), Boolean.FALSE });
            } else if ("nav9".equalsIgnoreCase(sim)) {
                mHandle.invoke(rc, new Object[] { new Integer(javax.microedition.lcdui.Canvas.KEY_NUM9), Boolean.FALSE });
            }
        }

        PlatformImage img = new PlatformImage(240, 320);
        Graphics g = img.getMIDPGraphics();
        Method m = rc.getClass().getDeclaredMethod("paint", new Class[] { Graphics.class });
        m.setAccessible(true);
        m.invoke(rc, new Object[] { g });

        ImageIO.write(img.getCanvas(), "png", outFile);
        System.out.println("-> Đã lưu ảnh chụp màn hình điện thoại (240x320): " + outFile.getAbsolutePath());
        System.exit(0);
    }
}

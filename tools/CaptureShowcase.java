import java.io.File;
import java.lang.reflect.Method;
import javax.imageio.ImageIO;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Graphics;
import org.recompile.mobile.Mobile;
import org.recompile.mobile.MobilePlatform;
import org.recompile.mobile.PlatformImage;

/**
 * Headless 240x320 screenshot generator for BookReader showcase images.
 * Uses FreeJ2ME's PlatformImage/PlatformGraphics to render actual MIDlet screens into docs/*.png.
 */
public class CaptureShowcase {

    static class SampleBook extends Book {
        SampleBook() {
            titles = new String[] {
                "Chương 1: Khởi đầu",
                "Chương 2: Hành trình",
                "Chương 3: Bến bờ mới",
                "Chương 4: Hồi kết"
            };
        }

        public String chapter(int i, int skip, int[] meta) {
            if (meta != null) {
                meta[0] = 0;
                meta[1] = 0;
            }
            String html = "<h1>Chương 1: Khởi đầu</h1>"
                + "<p>Trăm năm trong cõi người ta, chữ tài chữ mệnh khéo là ghét nhau. "
                + "Trải qua một cuộc bể dâu, những điều trông thấy mà đau đớn lòng.</p>"
                + "<hr/>"
                + "<h3>Điểm nổi bật</h3>"
                + "<ul><li>Giải nén EPUB trực tiếp trên dòng máy Nokia C2-01.</li>"
                + "<li>Căn đều hai bên và tự động nhớ vị trí đọc.</li></ul>"
                + "<p>Lạ gì bỉ sắc tư phong, trời xanh quen thói má hồng đánh ghen. "
                + "Cảo thơm lần giở trước đèn, phong tình cổ lục còn truyền sử xanh.</p>";
            return Html.toText(html);
        }
    }

    static void dump(Canvas c, File outFile) throws Exception {
        PlatformImage img = new PlatformImage(240, 320);
        Graphics g = img.getMIDPGraphics();
        Method m = c.getClass().getDeclaredMethod("paint", new Class[] { Graphics.class });
        m.setAccessible(true);
        m.invoke(c, new Object[] { g });
        ImageIO.write(img.getCanvas(), "png", outFile);
        System.out.println("Saved: " + outFile.getPath());
    }

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : "docs");
        outDir.mkdirs();

        Mobile.lcdWidth = 240;
        Mobile.lcdHeight = 320;
        Mobile.isAAEnabled = true;
        MobilePlatform mp = new MobilePlatform(240, 320);
        java.util.HashMap map = new java.util.HashMap();
        map.put("MIDlet-Vendor", "linh");
        map.put("MIDlet-Name", "BookReader");
        mp.loader = new org.recompile.mobile.MIDletLoader(new File("dist/BookReader.jar").toURI().toURL(), map);
        Mobile.setPlatform(mp, new Runnable() {
            public void run() {}
        });
        Mobile.setDisplay(new Display());

        // 1. Library screen
        Store.putInt("ui_theme", 1);
        ListScreen lib = new ListScreen(null, "BookReader");
        lib.setTabs(new String[]{"Ưa thích", "Tất cả sách"}, 0);
        lib.add("Dế Mèn Phiêu Lưu Ký", Ui.I_STAR);
        lib.add("Truyện Kiều - Nguyễn Du", Ui.I_STAR);
        lib.add("Số Đỏ - Vũ Trọng Phụng", Ui.I_STAR);
        lib.add("Tắt Đèn - Ngô Tất Tố", Ui.I_STAR);
        lib.add("Chí Phèo - Nam Cao", Ui.I_STAR);
        lib.setSelected(1);
        lib.setSoftKeys("Menu", "Thoát");
        dump(lib, new File(outDir, "library.png"));

        // 2. Reader screens (Light, Sepia, Dark)
        ReaderCanvas rc = new ReaderCanvas(null);
        rc.openBook("file:///E:/Books/truyen-kieu.epub", new SampleBook());
        Thread.sleep(300);
        rc.setTheme(0);
        dump(rc, new File(outDir, "reader-light.png"));
        rc.setTheme(2);
        dump(rc, new File(outDir, "reader-sepia.png"));
        rc.setTheme(1);
        dump(rc, new File(outDir, "reader-dark.png"));

        // 3. Menu screen
        ListScreen menu = new ListScreen(null, "Menu");
        menu.add("Tiếp tục đọc", Ui.I_BOOK);
        menu.add("☆ Thêm vào ưa thích", Ui.I_STAR);
        menu.add("Mục lục", Ui.I_PAGE);
        menu.add("Đi tới %", Ui.I_PAGE);
        menu.add("Cỡ chữ: vừa", Ui.I_GEAR);
        menu.add("Giao diện: tối", Ui.I_GEAR);
        menu.add("Màu đọc: sáng", Ui.I_GEAR);
        menu.add("Mục ưa thích", Ui.I_STAR);
        menu.add("Tất cả sách", Ui.I_BOOK);
        menu.add("Hướng dẫn phím", Ui.I_GEAR);
        menu.add("Thoát", Ui.I_GEAR);
        menu.setSelected(5);
        menu.setSoftKeys("Chọn", "Quay lại");
        dump(menu, new File(outDir, "menu.png"));

        System.exit(0);
    }
}

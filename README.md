# BookReader (J2ME, Nokia C2-01)

Đọc **EPUB, HTML, TXT** trên CLDC 1.1 / MIDP 2.1 (+ JSR-75 FileConnection).

- Tự cài đặt giải nén DEFLATE (CLDC không có `java.util.zip`), đọc EPUB theo từng chương nên tiết kiệm RAM.
- Mục lục từ NCX / nav.xhtml, nhớ vị trí đọc từng sách (RMS), tự mở lại sách gần nhất.
- Tiếng Việt Unicode (UTF-8; file không phải UTF-8 sẽ đọc như ISO-8859-1).

## Build
`./build.sh` → `dist/BookReader.jar` + `.jad` (cần JDK 8 tại `/mnt/data/apps/jdk/jdk-8`, đặt `JDK8=` để đổi;
lib/ chứa stub CLDC/MIDP/JSR-75 của MicroEmu và ProGuard dùng để preverify).

## Phím
| Phím | Chức năng |
|---|---|
| Lên/Xuống (2/8) | cuộn 3 dòng |
| Trái/Phải (4/6), OK | lùi/tiến trang |
| 1 / 3 | chương trước / sau |
| * / # | đổi cỡ chữ / giao diện |
| Phím mềm trái hoặc 0 | Menu (mục lục, đi tới %, mở sách khác…) |
| Phím mềm phải | Mục lục |

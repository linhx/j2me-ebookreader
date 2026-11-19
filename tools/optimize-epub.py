#!/usr/bin/env python3
"""
optimize-epub.py - Tối ưu hóa file sách EPUB cho điện thoại Nokia C2-01 (J2ME)
- Tự động thu nhỏ (resize) tất cả ảnh vượt quá độ phân giải màn hình (mặc định tối đa 232x280).
- Giữ nguyên tỉ lệ ảnh (aspect ratio), dùng bộ lọc chất lượng cao Lanczos.
- Nén JPEG/PNG tối ưu dung lượng và bộ nhớ RAM khi giải mã trên điện thoại.
- Giữ nguyên cấu trúc EPUB chuẩn.
"""

import sys
import os
import io
import zipfile
import argparse
from PIL import Image

def optimize_epub(input_path, output_path=None, max_w=232, max_h=280, jpeg_quality=80):
    if not os.path.isfile(input_path):
        print(f"Lỗi: Không tìm thấy file {input_path}")
        return False

    if not output_path:
        base, ext = os.path.splitext(input_path)
        output_path = f"{base}_c201{ext}"

    print(f"Đang xử lý: {os.path.basename(input_path)}")
    print(f"Kích thước tối đa: {max_w}x{max_h} px | Chất lượng JPEG: {jpeg_quality}")

    orig_size = os.path.getsize(input_path)
    img_count = 0
    resized_count = 0

    with zipfile.ZipFile(input_path, 'r') as zin, \
         zipfile.ZipFile(output_path, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zout:

        namelist = zin.namelist()
        for name in namelist:
            data = zin.read(name)
            lower = name.lower()

            # mimetype must be first and uncompressed in standard EPUB
            if name == 'mimetype':
                zout.writestr(name, data, compress_type=zipfile.ZIP_STORED)
                continue

            if lower.endswith(('.jpg', '.jpeg', '.png', '.gif', '.bmp', '.webp')):
                img_count += 1
                try:
                    img = Image.open(io.BytesIO(data))
                    w, h = img.size
                    orig_img_bytes = len(data)

                    # Check if resizing or recompressing is needed
                    need_resize = (w > max_w or h > max_h)
                    
                    if need_resize:
                        img.thumbnail((max_w, max_h), Image.Resampling.LANCZOS)
                        resized_count += 1

                    out_buf = io.BytesIO()
                    fmt = img.format if img.format else ('PNG' if lower.endswith('.png') else 'JPEG')

                    if fmt == 'WEBP' or fmt == 'BMP':
                        # Convert to JPEG (or PNG if transparency)
                        if img.mode in ('RGBA', 'LA') or (img.mode == 'P' and 'transparency' in img.info):
                            fmt = 'PNG'
                        else:
                            fmt = 'JPEG'
                            if img.mode != 'RGB':
                                img = img.convert('RGB')

                    if fmt == 'JPEG':
                        if img.mode != 'RGB':
                            img = img.convert('RGB')
                        img.save(out_buf, format='JPEG', quality=jpeg_quality, optimize=True)
                    elif fmt == 'PNG':
                        img.save(out_buf, format='PNG', optimize=True)
                    elif fmt == 'GIF':
                        img.save(out_buf, format='GIF')
                    else:
                        img.save(out_buf, format=fmt)

                    new_data = out_buf.getvalue()
                    # Only replace if we actually resized or saved space
                    if need_resize or len(new_data) < orig_img_bytes:
                        data = new_data
                except Exception as e:
                    print(f"  [Cảnh báo] Không thể tối ưu ảnh {name}: {e}")

            zout.writestr(name, data)

    new_size = os.path.getsize(output_path)
    ratio = (1 - (new_size / orig_size)) * 100 if orig_size > 0 else 0

    print("Hoàn tất!")
    print(f"- Tổng số ảnh trong sách: {img_count}")
    print(f"- Số ảnh đã thu nhỏ: {resized_count}")
    print(f"- Dung lượng gốc: {orig_size / (1024*1024):.2f} MB")
    print(f"- Dung lượng sau tối ưu: {new_size / (1024*1024):.2f} MB (giảm {ratio:.1f}%)")
    print(f"- File xuất: {output_path}")
    return True

def main():
    parser = argparse.ArgumentParser(description="Tối ưu hóa file EPUB cho Nokia C2-01 (J2ME)")
    parser.add_argument("input", help="Đường dẫn file EPUB gốc")
    parser.add_argument("output", nargs="?", default=None, help="Đường dẫn file EPUB kết quả (mặc định: thêm _c201)")
    parser.add_argument("--max-width", type=int, default=232, help="Chiều rộng tối đa (mặc định: 232)")
    parser.add_argument("--max-height", type=int, default=280, help="Chiều cao tối đa (mặc định: 280)")
    parser.add_argument("--quality", type=int, default=80, help="Chất lượng nén JPEG 1-100 (mặc định: 80)")

    args = parser.parse_args()
    success = optimize_epub(args.input, args.output, args.max_width, args.max_height, args.quality)
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()

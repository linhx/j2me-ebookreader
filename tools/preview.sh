#!/bin/bash
# Xem trước một cuốn sách trên PC dưới dạng ảnh chụp màn hình 240x320 Nokia C2-01
set -e
cd "$(dirname "$0")/.."

FREEJ2ME_JAR=${FREEJ2ME_JAR:-/mnt/data/apps/freej2me-plus/build/freej2me.jar}
if [ ! -f "$FREEJ2ME_JAR" ]; then
  echo "Lỗi: Không tìm thấy freej2me.jar tại $FREEJ2ME_JAR"
  exit 1
fi

if [ ! -f "dist/BookReader.jar" ]; then
  ./build.sh
fi

mkdir -p build/tools
javac -encoding UTF-8 -cp "dist/BookReader.jar:$FREEJ2ME_JAR" -d build/tools tools/PreviewBook.java
java -Dfile.encoding=ISO_8859_1 -Djava.awt.headless=true \
  -cp "build/tools:dist/BookReader.jar:$FREEJ2ME_JAR" PreviewBook "$@"

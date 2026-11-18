#!/bin/bash
# Render 240x320 showcase screenshots into docs/*.png without opening an X11 window.
set -e
cd "$(dirname "$0")/.."

FREEJ2ME_JAR=${FREEJ2ME_JAR:-/mnt/data/apps/freej2me-plus/build/freej2me.jar}
if [ ! -f "$FREEJ2ME_JAR" ]; then
  echo "Error: freej2me.jar not found at $FREEJ2ME_JAR (set FREEJ2ME_JAR=/path/to/freej2me.jar)"
  exit 1
fi

if [ ! -f "dist/BookReader.jar" ]; then
  ./build.sh
fi

mkdir -p build/tools docs
javac -encoding UTF-8 -cp "dist/BookReader.jar:$FREEJ2ME_JAR" -d build/tools tools/CaptureShowcase.java
java -Dfile.encoding=ISO_8859_1 -Djava.awt.headless=true \
  -cp "build/tools:dist/BookReader.jar:$FREEJ2ME_JAR" CaptureShowcase docs

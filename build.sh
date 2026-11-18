#!/bin/bash
# Build BookReader.jar/.jad for CLDC 1.1 / MIDP 2.1 (Nokia C2-01).
set -e
cd "$(dirname "$0")"
JDK8=${JDK8:-/mnt/data/apps/jdk/jdk-8}
LIB=$PWD/lib
BOOT=$LIB/microemu-cldc-2.0.4.jar:$LIB/microemu-midp-2.0.4.jar:$LIB/microemu-jsr-75-2.0.4.jar
rm -rf build && mkdir -p build/classes build/pre build/jar dist
$JDK8/bin/javac -source 1.3 -target 1.3 -encoding UTF-8 -g:none -bootclasspath $BOOT:$JDK8/jre/lib/rt.jar -d build/classes src/*.java 2>&1 | grep -v '^warning' || true
# Preverify (CLDC StackMap) + shrink-less pass with ProGuard
cat > build/pg.conf <<EOF
-injars $PWD/build/classes
-outjars $PWD/build/pre
-libraryjars $BOOT
-libraryjars $JDK8/jre/lib/rt.jar
-microedition
-dontobfuscate
-dontoptimize
-dontshrink
-keep public class ReaderMidlet
EOF
$JDK8/bin/java -jar $LIB/proguard-base-6.2.2.jar @build/pg.conf >build/pg.log 2>&1 || { cat build/pg.log; exit 1; }
cp -r build/pre/* build/jar/
cp -r res/* build/jar/
cat > build/MANIFEST.MF <<EOF
Manifest-Version: 1.0
MIDlet-Name: BookReader
MIDlet-Version: 1.0.0
MIDlet-Vendor: linh
MIDlet-1: BookReader,/icon.png,ReaderMidlet
MIDlet-Icon: /icon.png
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.1
MIDlet-Permissions: javax.microedition.io.Connector.file.read
EOF
(cd build/jar && $JDK8/bin/jar cfm ../../dist/BookReader.jar ../MANIFEST.MF .)
SZ=$(stat -c %s dist/BookReader.jar)
cat > dist/BookReader.jad <<EOF
MIDlet-Name: BookReader
MIDlet-Version: 1.0.0
MIDlet-Vendor: linh
MIDlet-1: BookReader,/icon.png,ReaderMidlet
MIDlet-Icon: /icon.png
MIDlet-Jar-URL: BookReader.jar
MIDlet-Jar-Size: $SZ
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.1
MIDlet-Permissions: javax.microedition.io.Connector.file.read
EOF
echo "OK: dist/BookReader.jar ($SZ bytes)"

#!/bin/bash
# Build BookReader.jar/.jad for CLDC 1.1 / MIDP 2.1 (Nokia C2-01).
set -e
cd "$(dirname "$0")"
if [ -z "$JDK8" ]; then
  if [ -d "/mnt/data/apps/jdk/jdk-8" ]; then
    JDK8="/mnt/data/apps/jdk/jdk-8"
  elif [ -n "$JAVA_HOME" ]; then
    JDK8="${JAVA_HOME}"
  fi
fi

if [ -z "$JDK8" ] || [ ! -x "$JDK8/bin/javac" ]; then
  echo "Error: JDK8 not found. Please set JDK8 or JAVA_HOME to a Java 8 JDK."
  exit 1
fi

RT_JAR="$JDK8/jre/lib/rt.jar"
if [ ! -f "$RT_JAR" ]; then
  if [ -f "$JDK8/lib/rt.jar" ]; then
    RT_JAR="$JDK8/lib/rt.jar"
  else
    echo "Error: rt.jar not found in $JDK8"
    exit 1
  fi
fi

VERSION=${VERSION:-1.0.0}
VERSION=${VERSION#v}
LIB=$PWD/lib
BOOT=$LIB/microemu-cldc-2.0.4.jar:$LIB/microemu-midp-2.0.4.jar:$LIB/microemu-jsr-75-2.0.4.jar:$LIB/nokiaui-stub.jar
rm -rf build && mkdir -p build/classes build/pre build/jar dist
$JDK8/bin/javac -source 1.3 -target 1.3 -encoding UTF-8 -g:none -bootclasspath $BOOT:$RT_JAR -d build/classes src/*.java > build/javac.log 2>&1 || { cat build/javac.log; exit 1; }
# Preverify (CLDC StackMap) + shrink-less pass with ProGuard
cat > build/pg.conf <<EOF
-injars $PWD/build/classes
-outjars $PWD/build/pre
-libraryjars $BOOT
-libraryjars $RT_JAR
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
MIDlet-Version: $VERSION
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
MIDlet-Version: $VERSION
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

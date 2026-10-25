#!/usr/bin/env bash
# Builds Contents/mods/SplitscreenFixes/42/media/java/SplitscreenFixes.jar
# Needs JDK 25+ (game classes are Java 25): sudo pacman -S jdk25-openjdk
set -euo pipefail
cd "$(dirname "$0")"

PZ_DIR="${PZ_DIR:-/mnt/data/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid}"
JDK="${JDK:-/usr/lib/jvm/java-25-openjdk}"
OUT=Contents/mods/SplitscreenFixes/42/media/java/SplitscreenFixes.jar

[[ -x "$JDK/bin/javac" ]] || { echo "JDK 25 not found at $JDK (set JDK=...)"; exit 1; }

rm -rf build/classes
mkdir -p build/classes "$(dirname "$OUT")"
"$JDK/bin/javac" --release 25 -Xlint:all,-options \
    -cp "$PZ_DIR/projectzomboid.jar:$PZ_DIR/ZombieBuddy.jar" \
    -d build/classes $(find src/main/java -name '*.java')
"$JDK/bin/jar" --create --file "$OUT" -C build/classes .
echo "built $OUT"

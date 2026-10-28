#!/usr/bin/env bash
# Saves the installed game's vanilla code into decompiled/<version>/ so the next game update
# can be compared with diff -r (Steam keeps only the current version).
#   media/  copy of media/lua and media/shaders
#   java/   classes already in decompiled/ (the ones this mod reads) plus any given ones
# Usage: tools/snapshot.sh [zombie/ui/Clock ...]       --all decompiles the whole jar (slow)
# Compare: diff -r decompiled/42.21 decompiled/42.22
set -euo pipefail
cd "$(dirname "$0")/.."

PZ_DIR="${PZ_DIR:-/mnt/data/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid}"
JDK="${JDK:-/usr/lib/jvm/java-25-openjdk}"
JAR="$PZ_DIR/projectzomboid.jar"
VF=tools/vineflower.jar
[[ -f $VF ]] || curl -fsSL -o $VF https://repo1.maven.org/maven2/org/vineflower/vineflower/1.11.1/vineflower-1.11.1.jar

# Core.gameVersion = new GameVersion(major, minor, suffix) in the static initializer.
version=$("$JDK/bin/javap" -c -p -cp "$JAR" zombie.core.Core | awk '
    /new .*class zombie\/core\/GameVersion$/ { n = 0 }
    /(bipush|sipush|iconst_)/ { n++; num[n] = ($2 ~ /^iconst_/) ? substr($2, 8) : $3 }
    /putstatic.*gameVersion:/ { print num[1] "." num[2]; exit }')
[[ $version =~ ^[0-9]+\.[0-9]+$ ]] || { echo "can't read the game version" >&2; exit 1; }

out="decompiled/$version"
if [[ -e $out ]]; then
    echo "$out already exists; delete it to redo the snapshot" >&2
    exit 1
fi
mkdir -p "$out/media" "$out/java"
cp -r "$PZ_DIR/media/lua" "$PZ_DIR/media/shaders" "$out/media/"
echo "game $version: media/lua, media/shaders copied"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
if [[ ${1:-} == --all ]]; then
    "$JDK/bin/java" -jar $VF -log=WARN "$JAR" "$out/java/" >/dev/null
else
    # Top-level classes of decompiled/ (version folders skipped), nested ones come with them.
    mapfile -t classes < <(find decompiled -path 'decompiled/[0-9]*' -prune -o -name '*.java' -printf '%P\n' \
        | sed 's|\.java$||; s|\$.*||' | sort -u)
    for cls in "${classes[@]}" "$@"; do
        unzip -qo "$JAR" "$cls.class" "$cls\$*.class" -d "$tmp" 2>/dev/null || true
    done
    "$JDK/bin/java" -jar $VF -log=WARN "$tmp" "$out/java/" >/dev/null
fi
echo "decompiled into $out/java/"

#!/usr/bin/env bash
# Decompiles game classes into decompiled/ for reading.
# Usage: tools/decompile.sh zombie/core/physics/CarController [zombie/input/JoypadManager ...]
# (nested classes like CarController$ClientControls are included automatically)
set -euo pipefail
cd "$(dirname "$0")/.."

PZ_DIR="${PZ_DIR:-/mnt/data/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid}"
VF=tools/vineflower.jar
[[ -f $VF ]] || curl -fsSL -o $VF https://repo1.maven.org/maven2/org/vineflower/vineflower/1.11.1/vineflower-1.11.1.jar

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for cls in "$@"; do
    unzip -qo "$PZ_DIR/projectzomboid.jar" "$cls.class" "$cls\$*.class" -d "$tmp" 2>/dev/null || true
done
java -jar $VF -log=WARN "$tmp" decompiled/ >/dev/null
echo "decompiled into decompiled/"

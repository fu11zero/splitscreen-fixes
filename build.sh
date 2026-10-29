#!/usr/bin/env bash
# Builds Contents/mods/SplitscreenFixes/42/media/java/SplitscreenFixes.jar
# Needs JDK 25+ (game classes are Java 25): sudo pacman -S jdk25-openjdk
set -euo pipefail
cd "$(dirname "$0")"

PZ_DIR="${PZ_DIR:-/mnt/data/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid}"
JDK="${JDK:-/usr/lib/jvm/java-25-openjdk}"
# ZombieBuddy ZBS signature (doc/ModSigning.md): Ed25519 key + SteamID64 of the Workshop uploader.
# Public key is published as JavaModZBS:<hex> in the Steam profile summary.
ZBS_KEY="${ZBS_KEY:-$HOME/.ssh/zbs-ed25519.pem}"
ZBS_STEAMID="${ZBS_STEAMID:-76561198084489589}"
OUT=Contents/mods/SplitscreenFixes/42/media/java/SplitscreenFixes.jar

[[ -x "$JDK/bin/javac" ]] || { echo "JDK 25 not found at $JDK (set JDK=...)"; exit 1; }

rm -rf build/classes
mkdir -p build/classes "$(dirname "$OUT")"
"$JDK/bin/javac" --release 25 -Xlint:all,-options \
    -cp "$PZ_DIR/projectzomboid.jar:$PZ_DIR/ZombieBuddy.jar" \
    -d build/classes $(find src/main/java -name '*.java')
"$JDK/bin/jar" --create --file "$OUT" -C build/classes .
echo "built $OUT"

# ZB verifies Ed25519 over "ZBS:<SteamID64>:<lowercase sha256 of jar>" (ZBSVerifier.verify).
if [[ -f "$ZBS_KEY" ]]; then
    hash=$(sha256sum "$OUT" | cut -d' ' -f1)
    # pkeyutl -rawin needs a sized input, a pipe fails with "Could not allocate 0 bytes"
    printf 'ZBS:%s:%s' "$ZBS_STEAMID" "$hash" > build/zbs-payload
    sig=$(openssl pkeyutl -sign -inkey "$ZBS_KEY" -rawin -in build/zbs-payload | od -An -tx1 | tr -d ' \n')
    printf 'ZBS\nSteamID64:%s\nSignature:%s\n' "$ZBS_STEAMID" "$sig" > "$OUT.zbs"
    echo "signed $OUT.zbs"
else
    rm -f "$OUT.zbs"
    echo "no signing key at $ZBS_KEY, jar left unsigned"
fi

# Other game versions (stable / beta branch): keep this one's jar, compile against the kept ones
# (~/Zomboid/tools/pz-versions.sh). The jar above is for the installed version either way.
if [[ -f ../../tools/pz-versions.sh ]]; then
    . ../../tools/pz-versions.sh
    pz_save_jar
    pz_check_versions $(find src/main/java -name '*.java')
fi

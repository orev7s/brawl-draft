#!/data/data/com.termux/files/usr/bin/bash
# Copies the staged sources into the tablet project and rebuilds.
set -e
PKG=app/src/main/java/com/brawldraft/app
PROJ=/data/data/com.termux/files/home/brawl-draft
cd "$PROJ"

cp /data/local/tmp/bd7_NamesRepo.kt        "$PKG/NamesRepo.kt"
cp /data/local/tmp/bd7_Prefs.kt            "$PKG/Prefs.kt"
cp /data/local/tmp/bd7_Api.kt              "$PKG/Api.kt"
cp /data/local/tmp/bd7_OverlayService.kt   "$PKG/OverlayService.kt"
cp /data/local/tmp/bd7_MainActivity.kt     "$PKG/MainActivity.kt"
rm -f "$PKG/BrawlData.kt"
cp /data/local/tmp/bd7_app_build.gradle.kts app/build.gradle.kts
cp /data/local/tmp/bd7_brawl_data.json     app/src/main/assets/brawl_data.json

chown 10309:10309 "$PKG"/*.kt app/build.gradle.kts app/src/main/assets/brawl_data.json
echo "sources in place:"
ls -l "$PKG"

/data/data/com.termux/files/usr/bin/bash /data/data/com.termux/files/home/bd_build2.sh > /dev/null 2>&1 || true
tail -n 14 /data/data/com.termux/files/home/bd_build_v11.log

#!/data/data/com.termux/files/usr/bin/bash
# Stages v1.6 sources + assets into the tablet project and rebuilds.
set -e
PKG=/data/data/com.termux/files/home/brawl-draft/app/src/main/java/com/brawldraft/app
PROJ=/data/data/com.termux/files/home/brawl-draft

cp /data/local/tmp/bdA_KnowledgeRepo.kt   "$PKG/KnowledgeRepo.kt"
cp /data/local/tmp/bdA_Providers.kt       "$PKG/Providers.kt"
cp /data/local/tmp/bdA_Prefs.kt           "$PKG/Prefs.kt"
cp /data/local/tmp/bdA_OverlayService.kt  "$PKG/OverlayService.kt"
cp /data/local/tmp/bdA_MainActivity.kt    "$PKG/MainActivity.kt"
cp /data/local/tmp/bdA_app_build.gradle.kts "$PROJ/app/build.gradle.kts"
cp /data/local/tmp/bdA_coach_knowledge.md "$PROJ/app/src/main/assets/coach_knowledge.md"

chown 10309:10309 "$PKG"/*.kt "$PROJ/app/build.gradle.kts" "$PROJ/app/src/main/assets/"*
echo "assets:"; ls -l "$PROJ/app/src/main/assets/"

/data/data/com.termux/files/usr/bin/bash /data/data/com.termux/files/home/bd_build2.sh > /dev/null 2>&1 || true
grep -E "BUILD|GRADLE_EXIT|APK_EXISTS|^e: " /data/data/com.termux/files/home/bd_build_v11.log | head -20

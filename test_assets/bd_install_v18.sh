#!/data/data/com.termux/files/usr/bin/bash
# Stages the logs/fallback/persistence build and rebuilds.
set -e
PKG=/data/data/com.termux/files/home/brawl-draft/app/src/main/java/com/brawldraft/app
PROJ=/data/data/com.termux/files/home/brawl-draft

for f in Prefs LogStore ProviderStore LlmChain LogsActivity ProvidersActivity OverlayService MainActivity; do
  cp "/data/local/tmp/bdC_$f.kt" "$PKG/$f.kt"
done
cp /data/local/tmp/bdC_AndroidManifest.xml "$PROJ/app/src/main/AndroidManifest.xml"
cp /data/local/tmp/bdC_app_build.gradle.kts "$PROJ/app/build.gradle.kts"

chown 10309:10309 "$PKG"/*.kt "$PROJ/app/src/main/AndroidManifest.xml" "$PROJ/app/build.gradle.kts"
echo "sources in place"
/data/data/com.termux/files/usr/bin/bash /data/data/com.termux/files/home/bd_build2.sh > /dev/null 2>&1 || true
grep -E "^e: |BUILD|APK_EXISTS" /data/data/com.termux/files/home/bd_build_v11.log | head -30

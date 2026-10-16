#!/data/data/com.termux/files/usr/bin/bash
# Points the app at the stronger coach model and drops any cached prompt so the bundled
# asset prompt (the one without the hardcoded ban list) is used.
PKG=com.brawldraft.app
F=/data/data/$PKG/shared_prefs/brawl_draft_prefs.xml
T=/data/local/tmp/bd_p3.xml

if [ ! -f "$F" ]; then echo "RESULT: no prefs"; exit 1; fi
if pidof $PKG >/dev/null 2>&1; then echo "RESULT: app running, abort"; exit 1; fi

sed 's|<string name="llm_model">[^<]*</string>|<string name="llm_model">openai/gpt-oss-120b</string>|' "$F" \
  | grep -v 'name="cached_prompt"' > "$T"
cat "$T" > "$F"
rm -f "$T"

echo "RESULT: updated"
for k in llm_model stt_model cached_prompt segment_ms stt_prompt; do
  v=$(grep -o "name=\"$k\">[^<]*" "$F" | head -1 | sed 's/.*>//')
  echo "$k len=${#v}"
done

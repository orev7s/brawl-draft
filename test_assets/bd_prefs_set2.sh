#!/data/data/com.termux/files/usr/bin/bash
# Rewrites the app's settings file with the configuration that was verified by test.
# The API keys are carried over in place and never printed.
PKG=com.brawldraft.app
F=/data/data/$PKG/shared_prefs/brawl_draft_prefs.xml
T=/data/local/tmp/bd_prefs_new2.xml
HINTFILE=/data/local/tmp/bdt/hint_roster.txt

if [ ! -f "$F" ]; then echo "RESULT: no prefs"; exit 1; fi
if pidof $PKG >/dev/null 2>&1; then echo "RESULT: app running, abort"; exit 1; fi

STTKEY=$(grep -o 'name="stt_key">[^<]*' "$F" | head -1 | sed 's/.*>//')
LLMKEY=$(grep -o 'name="llm_key">[^<]*' "$F" | head -1 | sed 's/.*>//')
HINT=$(cat "$HINTFILE")
if [ -z "$STTKEY" ] || [ -z "$LLMKEY" ]; then echo "RESULT: missing stored keys"; exit 1; fi
if [ -z "$HINT" ]; then echo "RESULT: missing hint file"; exit 1; fi

cp "$F" "$F.bak"
{
  printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
  printf '<map>\n'
  printf '<string name="stt_url">https://api.groq.com/openai/v1</string>\n'
  printf '<string name="stt_key">%s</string>\n' "$STTKEY"
  printf '<string name="stt_model">whisper-large-v3</string>\n'
  printf '<string name="stt_prompt">%s</string>\n' "$HINT"
  printf '<string name="llm_url">https://api.groq.com/openai/v1</string>\n'
  printf '<string name="llm_key">%s</string>\n' "$LLMKEY"
  printf '<string name="llm_model">openai/gpt-oss-20b</string>\n'
  printf '<string name="auto_hide_ms">7500</string>\n'
  printf '<string name="segment_ms">10000</string>\n'
  printf '<string name="stream_upload">true</string>\n'
  printf '<string name="speak_replies">true</string>\n'
  printf '</map>\n'
} > "$T"
cat "$T" > "$F"
rm -f "$T" "$F.bak"
echo "RESULT: settings written"
for k in stt_url stt_key stt_model stt_prompt llm_url llm_key llm_model auto_hide_ms segment_ms stream_upload speak_replies; do
  v=$(grep -o "name=\"$k\">[^<]*" "$F" | head -1 | sed 's/.*>//')
  echo "$k len=${#v}"
done

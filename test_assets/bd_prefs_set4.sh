#!/data/data/com.termux/files/usr/bin/bash
# Adds the knowledge settings to the existing v1.6 config. Keys are carried over, never printed.
PKG=com.brawldraft.app
F=/data/data/$PKG/shared_prefs/brawl_draft_prefs.xml
T=/data/local/tmp/bd_prefs_new4.xml
HINT=$(cat /data/local/tmp/bdt/hint_roster.txt)

if [ ! -f "$F" ]; then echo "RESULT: no prefs"; exit 1; fi
if pidof $PKG >/dev/null 2>&1; then echo "RESULT: app running, abort"; exit 1; fi

STTKEY=$(grep -o 'name="stt_key">[^<]*' "$F" | head -1 | sed 's/.*>//')
LLMKEY=$(grep -o 'name="llm_key">[^<]*' "$F" | head -1 | sed 's/.*>//')
if [ -z "$STTKEY" ] || [ -z "$LLMKEY" ]; then echo "RESULT: missing stored keys"; exit 1; fi

{
  printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
  printf '<map>\n'
  printf '<string name="stt_url">https://api.groq.com/openai/v1</string>\n'
  printf '<string name="stt_key">%s</string>\n' "$STTKEY"
  printf '<string name="stt_model">whisper-large-v3</string>\n'
  printf '<string name="stt_prompt">%s</string>\n' "$HINT"
  printf '<string name="llm_url">https://api.groq.com/openai/v1</string>\n'
  printf '<string name="llm_key">%s</string>\n' "$LLMKEY"
  printf '<string name="llm_model">openai/gpt-oss-120b</string>\n'
  printf '<string name="clean_enabled">true</string>\n'
  printf '<string name="clean_model">qwen/qwen3.8-27b</string>\n'
  printf '<string name="names_url">https://raw.githubusercontent.com/orev7s/brawl-draft/main/app/src/main/assets/brawl_data.json</string>\n'
  printf '<string name="knowledge_enabled">true</string>\n'
  printf '<string name="knowledge_url">https://raw.githubusercontent.com/orev7s/brawl-draft/main/app/src/main/assets/coach_knowledge.md</string>\n'
  printf '<string name="auto_hide_ms">7500</string>\n'
  printf '<string name="segment_ms">10000</string>\n'
  printf '<string name="stream_upload">true</string>\n'
  printf '<string name="speak_replies">true</string>\n'
  printf '</map>\n'
} > "$T"
cat "$T" > "$F"
rm -f "$T"

echo "RESULT: knowledge settings written"
for k in knowledge_enabled knowledge_url cached_knowledge llm_url llm_model clean_model; do
  v=$(grep -o "name=\"$k\">[^<]*" "$F" | head -1 | sed 's/.*>//')
  echo "$k len=${#v}"
done

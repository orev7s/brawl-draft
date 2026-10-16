#!/data/data/com.termux/files/usr/bin/bash
# Points the chat settings back at Groq so the coach works again. The Groq key is still on the
# device in the transcription field (the provider presets never touch speech-to-text), and the same
# key serves both Groq chat and Groq Whisper, so nothing new has to be pasted.
PKG=com.brawldraft.app
F=/data/data/$PKG/shared_prefs/brawl_draft_prefs.xml
T=/data/local/tmp/bd_restore.xml

if [ ! -f "$F" ]; then echo "RESULT: no prefs"; exit 1; fi
if pidof $PKG >/dev/null 2>&1; then echo "RESULT: app running, abort"; exit 1; fi

STTKEY=$(grep -o 'name="stt_key">[^<]*' "$F" | head -1 | sed 's/.*>//')
LLMKEY=$(grep -o 'name="llm_key">[^<]*' "$F" | head -1 | sed 's/.*>//')
echo "stt_key prefix=[$(echo "$STTKEY" | cut -c1-4)] length=${#STTKEY}"
echo "llm_key prefix=[$(echo "$LLMKEY" | cut -c1-4)] length=${#LLMKEY}"

case "$STTKEY" in
  gsk_*) : ;;
  *) echo "RESULT: transcription key is not a Groq key, not touching anything"; exit 1 ;;
esac

sed -e "s|<string name=\"llm_key\">[^<]*</string>|<string name=\"llm_key\">$STTKEY</string>|" \
    -e 's|<string name="llm_url">[^<]*</string>|<string name="llm_url">https://api.groq.com/openai/v1</string>|' \
    -e 's|<string name="llm_model">[^<]*</string>|<string name="llm_model">openai/gpt-oss-120b</string>|' \
    -e 's|<string name="clean_model">[^<]*</string>|<string name="clean_model">qwen/qwen3.8-27b</string>|' \
    "$F" | grep -v 'name="cached_prompt"' > "$T"
cat "$T" > "$F"
rm -f "$T" /data/local/tmp/ar_key.txt /data/local/tmp/ar_req.json /data/local/tmp/ar_out.txt /data/local/tmp/ar_err.txt

echo "RESULT: chat settings restored to Groq"
for k in llm_url llm_model clean_model; do
  v=$(grep -o "name=\"$k\">[^<]*" "$F" | head -1 | sed 's/.*>//')
  echo "$k = $v"
done
echo "llm_key prefix now=[$(grep -o 'name="llm_key">[^<]*' "$F" | head -1 | sed 's/.*>//' | cut -c1-4)]"

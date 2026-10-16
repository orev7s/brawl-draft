#!/data/data/com.termux/files/usr/bin/bash
# Which model corrects a mangled transcript fastest and correctly?
export PATH=/data/data/com.termux/files/usr/bin:/system/bin

F=/data/data/com.brawldraft.app/shared_prefs/brawl_draft_prefs.xml
KEY=$(grep -o 'name="llm_key">[^<]*' "$F" | head -1 | sed 's/.*>//')
URL=$(grep -o 'name="llm_url">[^<]*' "$F" | head -1 | sed 's/.*>//')
NAMES=/data/local/tmp/bdt/brawl_data.json

RAW="Okay, so the map is Rings of Fire. Ems is banned, Gray is banned, Chug is banned, and Squeak is banned. My team picked Gull, Edgar, and they picked Sirius and like the enemy team picked Sirius and Kaze and Cordelius. What should they pick? Shelly? Or Colt, or Bull, or Artie."

SYSP="You fix speech-to-text mistakes in Brawl Stars voice chat transcripts. You are given a list of known names and a raw transcript. Rewrite the transcript so that any word which is clearly a mangled version of a known name is replaced by that exact name, including names in the observed mistakes map. Output ONLY the corrected transcript - no commentary, no quotes, no markdown. Keep every other word, the order and the tense exactly as they are. Never add information, never answer questions, never explain. If nothing is clearly wrong, repeat the transcript unchanged."

NAMES_ESC=$(tr '\n' ' ' < "$NAMES" | sed 's/"/\\"/g')
USER="KNOWN NAMES AND MAPS: $NAMES_ESC  RAW TRANSCRIPT: $RAW"

REQ=/data/local/tmp/bd_correct.json
RES=/data/local/tmp/bd_correct_out.txt

echo "RAW: $RAW"
echo
echo "expected fixes: Ems->Emz  Chug->Chuck  Gull->Gus  Artie->R-T"
echo

run() { # label model extrajson
  printf '{"model":"%s","messages":[{"role":"system","content":"%s"},{"role":"user","content":"%s"}],"temperature":0,"max_tokens":2048%s}' \
    "$2" "$SYSP" "$USER" "$3" > "$REQ"
  local out
  out=$(curl -sS -o "$RES" -w '%{http_code} %{time_total}' -m 120 \
    -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
    --data-binary @"$REQ" "$URL/chat/completions")
  echo "### $1 | http/time=$out"
  head -c 700 "$RES"
  echo
  echo
  sleep 2
}

run "gpt-oss-20b default"        openai/gpt-oss-20b ""
run "qwen3.8-27b default"        qwen/qwen3.8-27b ""
run "qwen3.8-27b reasoning=low"  qwen/qwen3.8-27b ',"reasoning_effort":"low"'
run "allam-2-7b (no reasoning)"  allam-2-7b ""
echo "CORRECT TEST DONE"

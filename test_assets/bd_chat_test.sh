#!/data/data/com.termux/files/usr/bin/bash
# Reproduces the "coach never replies" problem on the real key, with the real transcript.
export PATH=/data/data/com.termux/files/usr/bin:/system/bin

F=/data/data/com.brawldraft.app/shared_prefs/brawl_draft_prefs.xml
KEY=$(grep -o 'name="llm_key">[^<]*' "$F" | head -1 | sed 's/.*>//')
URL=$(grep -o 'name="llm_url">[^<]*' "$F" | head -1 | sed 's/.*>//')

SYSP="You are a live draft coach for Brawl Stars ranked mode. The player talks to you by voice. Reply in English in at most 3 short sentences because your reply is read aloud. If key information is missing, ask one short question."
USERMSG="okay the map is rings of fire emz is banned gray is banned chuck is banned and squeak is banned my team picked gus and edgar and the enemy team picked sirius and kaze and cordelius what should i pick shelly or colt or bull or RT"

REQ=/data/local/tmp/bd_chat.json
RES=/data/local/tmp/bd_chat_out.txt

echo "endpoint=$URL/chat/completions"
echo

chat() { # model max_tokens extrajson label
  printf '{"model":"%s","messages":[{"role":"system","content":"%s"},{"role":"user","content":"%s"}],"temperature":0.3,"max_tokens":%s%s}' \
    "$1" "$SYSP" "$USERMSG" "$2" "$3" > "$REQ"
  local code
  code=$(curl -sS -o "$RES" -w '%{http_code}' -m 120 \
    -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
    --data-binary @"$REQ" "$URL/chat/completions")
  echo "### $4 | HTTP=$code"
  head -c 1500 "$RES"
  echo
  echo
  sleep 4
}

chat openai/gpt-oss-20b 300 "" "D1 gpt-oss-20b max_tokens=300  (exactly what the app sends today)"
chat openai/gpt-oss-20b 1024 "" "D2 gpt-oss-20b max_tokens=1024"
chat openai/gpt-oss-20b 1024 ',"reasoning_effort":"low"' "D3 gpt-oss-20b max_tokens=1024 reasoning_effort=low"
chat llama-3.3-70b-versatile 300 "" "D4 llama-3.3-70b-versatile max_tokens=300"

echo "CHAT TEST DONE"

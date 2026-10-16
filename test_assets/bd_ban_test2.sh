#!/data/data/com.termux/files/usr/bin/bash
# Simulates the failure mode where speech to text drops the word "not":
# the coach sees "edgar is banned. should i pick him on a close map?" - a self contradiction.
export PATH=/data/data/com.termux/files/usr/bin:/system/bin

F=/data/data/com.brawldraft.app/shared_prefs/brawl_draft_prefs.xml
KEY=$(grep -o 'name="llm_key">[^<]*' "$F" | head -1 | sed 's/.*>//')
URL=$(grep -o 'name="llm_url">[^<]*' "$F" | head -1 | sed 's/.*>//')

U1="okay the map is rings of fire. emz is banned, gray is banned, chuck is banned, squeak is banned. my team picked gus and edgar, the enemy picked sirius, kaze and cordelius. what should i pick, shelly or colt or bull or R-T?"
A1="Pick Colt. He can shoot from the ring and gives you long-range pressure next to Gus and Edgar."
U2="edgar is banned. should i pick him on a close map?"

REQ=/data/local/tmp/bd_ban2.json
RES=/data/local/tmp/bd_ban2_out.txt

run() { # model promptfile label
  local sysp
  sysp=$(tr '\n' ' ' < "$1" | sed 's/"/\\"/g')
  printf '{"model":"%s","messages":[{"role":"system","content":"%s"},{"role":"user","content":"%s"},{"role":"assistant","content":"%s"},{"role":"user","content":"%s"}],"temperature":0.3,"max_tokens":2048}' \
    "$2" "$sysp" "$U1" "$A1" "$U2" > "$REQ"
  local code
  code=$(curl -sS -o "$RES" -w '%{http_code}' -m 150 \
    -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
    --data-binary @"$REQ" "$URL/chat/completions")
  echo "### $3 | HTTP=$code"
  head -c 700 "$RES"
  echo
  echo
  sleep 3
}

run /data/local/tmp/bdt/prompt_current.txt openai/gpt-oss-20b "Q1 CURRENT prompt + 20b, transcript lost the 'not'"
run /data/local/tmp/bdt/prompt_fixed.txt   openai/gpt-oss-20b "Q2 FIXED prompt + 20b, transcript lost the 'not'"
run /data/local/tmp/bdt/prompt_fixed.txt   openai/gpt-oss-120b "Q3 FIXED prompt + 120b, transcript lost the 'not'"

echo "SECOND BAN TEST DONE"

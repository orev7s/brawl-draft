#!/data/data/com.termux/files/usr/bin/bash
# Points the app at Gemini models that are actually serving, then runs a realistic coach request
# built from the real bundled prompt + knowledge files to prove it answers end to end.
export PATH=/data/data/com.termux/files/usr/bin:/system/bin

PKG=com.brawldraft.app
F=/data/data/$PKG/shared_prefs/brawl_draft_prefs.xml
T=/data/local/tmp/bd_gm_fix.xml
PROJ=/data/data/com.termux/files/home/brawl-draft

if pidof $PKG >/dev/null 2>&1; then echo "RESULT: app running, stop it first"; exit 1; fi

sed -e 's|<string name="llm_model">[^<]*</string>|<string name="llm_model">gemini-3.5-flash</string>|' \
    -e 's|<string name="clean_model">[^<]*</string>|<string name="clean_model">gemini-2.5-flash-lite</string>|' \
    "$F" > "$T"
cat "$T" > "$F"
rm -f "$T"

echo "RESULT: models updated"
for k in llm_url llm_model clean_model; do
  echo "  $k = $(grep -o "name=\"$k\">[^<]*" "$F" | head -1 | sed 's/.*>//')"
done
echo

KEY=$(grep -o 'name="llm_key">[^<]*' "$F" | head -1 | sed 's/.*>//')
URL=$(grep -o 'name="llm_url">[^<]*' "$F" | head -1 | sed 's/.*>//')
MODEL=$(grep -o 'name="llm_model">[^<]*' "$F" | head -1 | sed 's/.*>//')

SYS=$( { tr '\n' ' ' < "$PROJ/app/src/main/assets/default_prompt.txt"
         echo " "
         tr '\n' ' ' < "$PROJ/app/src/main/assets/coach_knowledge.md"; } | sed 's/"/\\"/g' )
echo "system prompt chars = ${#SYS}"
echo

ask() { # user prompt label
  printf '{"model":"%s","messages":[{"role":"system","content":"%s"},{"role":"user","content":"%s"}],"temperature":0.3,"max_tokens":2048}' \
    "$MODEL" "$SYS" "$1" > /data/local/tmp/gm_req.json
  local res
  res=$(curl -sS -o /data/local/tmp/gm_ans.txt -w '%{http_code}|%{time_total}' -m 90 \
    -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
    --data-binary @/data/local/tmp/gm_req.json "$URL/chat/completions" 2>/dev/null)
  echo "### $2  -> HTTP ${res%%|*}  ${res##*|}s"
  if [ "${res%%|*}" = "200" ]; then
    sed -n 's/.*"content":"\([^"]*\)".*/\1/p' /data/local/tmp/gm_ans.txt | head -c 600
    echo
  else
    head -c 300 /data/local/tmp/gm_ans.txt
    echo
  fi
  echo
  sleep 2
}

ask "enemy picked edgar and mortis, my team has sprout and dynamike. what should i pick?" "coach: counter two assassins into two throwers"
ask "the map is ring of fire, should i pick colt? give me one pick and one reason." "coach: hot zone pick"
echo "GEMINI FIX DONE"

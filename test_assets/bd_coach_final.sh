#!/data/data/com.termux/files/usr/bin/bash
# End to end check: the transcript the app now produces, fed to the exact coach request the app
# sends (reasoning model, 2048 token budget, roster addendum on the system prompt).
export PATH=/data/data/com.termux/files/usr/bin:/system/bin

F=/data/data/com.brawldraft.app/shared_prefs/brawl_draft_prefs.xml
KEY=$(grep -o 'name="llm_key">[^<]*' "$F" | head -1 | sed 's/.*>//')
URL=$(grep -o 'name="llm_url">[^<]*' "$F" | head -1 | sed 's/.*>//')
MODEL=$(grep -o 'name="llm_model">[^<]*' "$F" | head -1 | sed 's/.*>//')

SYSP=$(sed 's/"/\\"/g' /data/local/tmp/bdt/sysprompt.txt)
USERMSG="Okay, so the map is Rings of Fire. Ems is banned, Gray is banned, Chuck is banned, and Squeak is banned. My team picked Gus, Edgar, and And they picked Sirius and like the enemy team picked Sirius and Kaze and Cordelius. What should they pick? Shelly? Or Colt, or Bull, or Archie."

REQ=/data/local/tmp/bd_coach.json
RES=/data/local/tmp/bd_coach_out.txt
printf '{"model":"%s","messages":[{"role":"system","content":"%s"},{"role":"user","content":"%s"}],"temperature":0.3,"max_tokens":2048}' \
  "$MODEL" "$SYSP" "$USERMSG" > "$REQ"

echo "model=$MODEL max_tokens=2048"
echo "system prompt bytes=$(wc -c < /data/local/tmp/bdt/sysprompt.txt)"
echo "user message: $USERMSG"
echo
code=$(curl -sS -o "$RES" -w '%{http_code}' -m 150 \
  -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  --data-binary @"$REQ" "$URL/chat/completions")
echo "HTTP=$code"
head -c 2500 "$RES"
echo
echo "COACH TEST DONE"

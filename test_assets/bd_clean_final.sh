#!/data/data/com.termux/files/usr/bin/bash
# Runs the name-repair pass with the exact context string the app builds.
# Note the \\n inside the printf format: printf turns that into the two characters \ and n, which
# is what JSON needs. A plain \n would emit a raw newline and the request would be rejected.
export PATH=/data/data/com.termux/files/usr/bin:/system/bin

F=/data/data/com.brawldraft.app/shared_prefs/brawl_draft_prefs.xml
KEY=$(grep -o 'name="llm_key">[^<]*' "$F" | head -1 | sed 's/.*>//')
URL=$(grep -o 'name="llm_url">[^<]*' "$F" | head -1 | sed 's/.*>//')
MODEL=$(grep -o 'name="clean_model">[^<]*' "$F" | head -1 | sed 's/.*>//')

SYSP="You fix speech-to-text mistakes in Brawl Stars voice chat transcripts. You are given a list of known brawlers, map names and game terms, and a raw transcript. Rewrite the transcript so that any word which is clearly a mangled version of a known name becomes that exact name, including every entry of the observed mistakes list. Output ONLY the corrected transcript: no commentary, no quotes, no markdown, no explanation. Keep every other word, the word order and the tense exactly as they are. Never add information, never answer the question and never change the meaning. If nothing is clearly wrong, repeat the transcript unchanged."

CTX=$(tr '\n' ' ' < /data/local/tmp/bdt/ctx.txt | sed 's/"/\\"/g')
RAW="we are playing open map hot zone on rings of fire. Ems is banned, Gray is banned, Chug is banned and Squeak is banned. my team picked Gull and Edgar, the enemy picked Sirius, Kaze and Cordelius. should they pick Shelly or Colt or Bull or Artie"

REQ=/data/local/tmp/bd_c2.json
RES=/data/local/tmp/bd_c2_out.txt

printf '{"model":"%s","messages":[{"role":"system","content":"%s"},{"role":"user","content":"KNOWN NAMES\\n%s\\n\\nRAW TRANSCRIPT\\n%s"}],"temperature":0,"max_tokens":1024}' \
  "$MODEL" "$SYSP" "$CTX" "$RAW" > "$REQ"

echo "model: $MODEL"
echo "RAW:   $RAW"
echo
out=$(curl -sS -o "$RES" -w '%{http_code} %{time_total}s' -m 120 \
  -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  --data-binary @"$REQ" "$URL/chat/completions")
echo "http/time: $out"
echo "---"
head -c 1200 "$RES"
echo

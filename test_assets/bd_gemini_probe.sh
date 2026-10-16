#!/data/data/com.termux/files/usr/bin/bash
# Lists every model the configured key can see, then actually calls each one with a tiny request
# and reports which are serving traffic right now. "High demand" is per model, so this finds the
# ones that are up instead of guessing.
export PATH=/data/data/com.termux/files/usr/bin:/system/bin

F=/data/data/com.brawldraft.app/shared_prefs/brawl_draft_prefs.xml
KEY=$(grep -o 'name="llm_key">[^<]*' "$F" | head -1 | sed 's/.*>//')
URL=$(grep -o 'name="llm_url">[^<]*' "$F" | head -1 | sed 's/.*>//')
CUR=$(grep -o 'name="llm_model">[^<]*' "$F" | head -1 | sed 's/.*>//')

echo "base url      = $URL"
echo "current model = $CUR"
echo "key length    = ${#KEY}"
echo

L=/data/local/tmp/gm_models.json
O=/data/local/tmp/gm_out.txt

echo "=== models visible to this key ==="
code=$(curl -sS -o "$L" -w '%{http_code}' -m 40 -H "Authorization: Bearer $KEY" "$URL/models")
echo "GET /models -> HTTP $code"
if [ "$code" != "200" ]; then head -c 400 "$L"; echo; exit 1; fi

IDS=$(grep -o '"id"[[:space:]]*:[[:space:]]*"[^"]*"' "$L" | sed 's/.*:[[:space:]]*"//; s/"$//' | sort -u)
echo "$IDS" | sed 's/^/  /'
echo

echo "=== live call test, one tiny request each ==="
for m in $IDS; do
  case "$m" in
    *embedding*|*aqa*|*imagen*|*veo*|*tts*|*native-audio*|*learnlm*|*gemma*) continue ;;
  esac
  printf '{"model":"%s","messages":[{"role":"user","content":"ok"}],"max_tokens":8}' "$m" > /data/local/tmp/gm_req.json
  res=$(curl -sS -o "$O" -w '%{http_code}|%{time_total}' -m 40 \
    -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
    --data-binary @/data/local/tmp/gm_req.json "$URL/chat/completions" 2>/dev/null)
  http=${res%%|*}
  secs=${res##*|}
  if [ "$http" = "200" ]; then
    printf '  %-34s OK   %ss\n' "$m" "$secs"
  else
    msg=$(head -c 150 "$O" | tr -d '\n' | sed 's/  */ /g')
    printf '  %-34s FAIL %s  %ss  %s\n' "$m" "$http" "$secs" "$msg"
  fi
  sleep 1
done
echo "GEMINI PROBE DONE"

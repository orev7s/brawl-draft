#!/data/data/com.termux/files/usr/bin/bash
# Lists every model this account can actually call, so no 404 model ever ships again.
export PATH=/data/data/com.termux/files/usr/bin:/system/bin

F=/data/data/com.brawldraft.app/shared_prefs/brawl_draft_prefs.xml
KEY=$(grep -o 'name="llm_key">[^<]*' "$F" | head -1 | sed 's/.*>//')
URL=$(grep -o 'name="llm_url">[^<]*' "$F" | head -1 | sed 's/.*>//')

OUT=/data/local/tmp/bd_models.json
code=$(curl -sS -o "$OUT" -w '%{http_code}' -m 60 \
  -H "Authorization: Bearer $KEY" "$URL/models")
echo "HTTP=$code"
tr ',' '\n' < "$OUT" | grep '"id"' | sed 's/.*"id"[: ]*"//; s/".*//' | sort

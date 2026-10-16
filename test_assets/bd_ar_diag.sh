#!/data/data/com.termux/files/usr/bin/bash
# Diagnoses an OpenAI-compatible gateway. Prints the URL and model (not secrets), the key only as
# a length, and the raw status + body for each probe so the gateway's own words are visible.
export PATH=/data/data/com.termux/files/usr/bin:/system/bin

F=/data/data/com.brawldraft.app/shared_prefs/brawl_draft_prefs.xml
KEY=$(grep -o 'name="llm_key">[^<]*' "$F" | head -1 | sed 's/.*>//')
URL=$(grep -o 'name="llm_url">[^<]*' "$F" | head -1 | sed 's/.*>//')
MODEL=$(grep -o 'name="llm_model">[^<]*' "$F" | head -1 | sed 's/.*>//')
CLEAN=$(grep -o 'name="clean_model">[^<]*' "$F" | head -1 | sed 's/.*>//')

echo "configured llm_url    = [$URL]"
echo "configured llm_model  = [$MODEL]"
echo "configured clean_model= [$CLEAN]"
echo "key length            = ${#KEY}"
echo "key prefix looks like = $(echo "$KEY" | cut -c1-4)..."
echo

O=/data/local/tmp/ar_out.txt

probe() { # label method url extra_curl_args...
  local label="$1"; shift
  local method="$1"; shift
  local url="$1"; shift
  local code
  code=$(curl -sS -o "$O" -w '%{http_code}' -m 45 -X "$method" \
    -H "Authorization: Bearer $KEY" "$@" "$url" 2>/data/local/tmp/ar_err.txt)
  echo "### $label -> HTTP $code"
  head -c 500 "$O"; echo
  if [ -s /data/local/tmp/ar_err.txt ]; then echo "(curl: $(head -c 200 /data/local/tmp/ar_err.txt))"; fi
  echo
}

echo "=== 1. does the key work at all: list models ==="
probe "GET /models (no user-agent)" GET "$URL/models"
probe "GET /models (app user-agent)" GET "$URL/models" -H "User-Agent: BrawlDraft/1.6 (Android)"

echo "=== 2. chat with the configured model, exactly as stored ==="
printf '{"model":"%s","messages":[{"role":"user","content":"say ok"}],"max_tokens":16}' "$MODEL" > /data/local/tmp/ar1.json
probe "POST /chat/completions model=[$MODEL]" POST "$URL/chat/completions" \
  -H "Content-Type: application/json" --data-binary @/data/local/tmp/ar1.json

echo "=== 3. same request with the hyphenated model name from the docs ==="
printf '{"model":"deepseek-v4-flash","messages":[{"role":"user","content":"say ok"}],"max_tokens":16}' > /data/local/tmp/ar2.json
probe "POST /chat/completions model=[deepseek-v4-flash]" POST "$URL/chat/completions" \
  -H "Content-Type: application/json" --data-binary @/data/local/tmp/ar2.json

echo "=== 4. a second documented model, to separate key problems from model problems ==="
printf '{"model":"glm-5.3","messages":[{"role":"user","content":"say ok"}],"max_tokens":16}' > /data/local/tmp/ar3.json
probe "POST /chat/completions model=[glm-5.3]" POST "$URL/chat/completions" \
  -H "Content-Type: application/json" --data-binary @/data/local/tmp/ar3.json

echo "=== 5. Responses API instead of chat completions ==="
printf '{"model":"glm-5.3","input":"say ok","max_output_tokens":16}' > /data/local/tmp/ar4.json
probe "POST /responses model=[glm-5.3]" POST "${URL%/v1}/v1/responses" \
  -H "Content-Type: application/json" --data-binary @/data/local/tmp/ar4.json

echo "AR DIAG DONE"

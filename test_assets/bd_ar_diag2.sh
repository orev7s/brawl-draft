#!/data/data/com.termux/files/usr/bin/bash
# Probes the AgentRouter gateway with a key read from a device temp file (never from the repo).
# Prints URLs, model names and raw response bodies. The key itself is never echoed.
export PATH=/data/data/com.termux/files/usr/bin:/system/bin

KEY=$(tr -d '\r\n' < /data/local/tmp/ar_key.txt)
BASE=https://agentrouter.org
O=/data/local/tmp/ar_out.txt
E=/data/local/tmp/ar_err.txt

F=/data/data/com.brawldraft.app/shared_prefs/brawl_draft_prefs.xml
APPURL=$(grep -o 'name="llm_url">[^<]*' "$F" | head -1 | sed 's/.*>//')
APPMODEL=$(grep -o 'name="llm_model">[^<]*' "$F" | head -1 | sed 's/.*>//')
APPCLEAN=$(grep -o 'name="clean_model">[^<]*' "$F" | head -1 | sed 's/.*>//')

echo "app configured: url=[$APPURL] model=[$APPMODEL] cleaner=[$APPCLEAN]"
echo "test key: length=${#KEY} starts=$(echo "$KEY" | cut -c1-6)..."
echo

probe() { # label method url curl-args...
  local label="$1"; shift
  local method="$1"; shift
  local url="$1"; shift
  local code
  code=$(curl -sS -o "$O" -w '%{http_code}' -m 45 -X "$method" \
    -H "Authorization: Bearer $KEY" "$@" "$url" 2>"$E")
  echo "### $label"
  echo "    -> HTTP $code"
  echo "    body: $(head -c 320 "$O" | tr '\n' ' ')"
  [ -s "$E" ] && echo "    curl: $(head -c 150 "$E")"
  echo
}

echo "=== auth check ==="
probe "GET /v1/models" GET "$BASE/v1/models"

echo "=== chat completions, documented model names ==="
for M in deepseek-v4-flash glm-5.3 gpt-5.6-sol claude-opus-5; do
  printf '{"model":"%s","messages":[{"role":"user","content":"say ok"}],"max_tokens":16}' "$M" > /data/local/tmp/ar_req.json
  probe "POST /v1/chat/completions model=[$M]" POST "$BASE/v1/chat/completions" \
    -H "Content-Type: application/json" --data-binary @/data/local/tmp/ar_req.json
done

echo "=== the exact string from the error message (space instead of hyphen) ==="
printf '{"model":"deepseek-v4 flash","messages":[{"role":"user","content":"say ok"}],"max_tokens":16}' > /data/local/tmp/ar_req.json
probe "POST /v1/chat/completions model=[deepseek-v4 flash]" POST "$BASE/v1/chat/completions" \
  -H "Content-Type: application/json" --data-binary @/data/local/tmp/ar_req.json

echo "=== responses api ==="
printf '{"model":"glm-5.3","input":"say ok","max_output_tokens":16}' > /data/local/tmp/ar_req.json
probe "POST /v1/responses model=[glm-5.3]" POST "$BASE/v1/responses" \
  -H "Content-Type: application/json" --data-binary @/data/local/tmp/ar_req.json

echo "AR DIAG 2 DONE"

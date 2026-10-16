#!/data/data/com.termux/files/usr/bin/bash
# Distinguishes "bad key" from "this client is not allowed" by varying only the client fingerprint
# and the auth header style, never the credentials.
export PATH=/data/data/com.termux/files/usr/bin:/system/bin

KEY=$(tr -d '\r\n' < /data/local/tmp/ar_key.txt)
BASE=https://agentrouter.org
O=/data/local/tmp/ar_out.txt

probe() { # label method url curl-args...
  local label="$1"; shift
  local method="$1"; shift
  local url="$1"; shift
  local code
  code=$(curl -sS -o "$O" -w '%{http_code}' -m 45 -X "$method" "$@" "$url" 2>/dev/null)
  echo "### $label -> HTTP $code"
  echo "    $(head -c 240 "$O" | tr '\n' ' ')"
  echo
}

echo "=== same key, different auth header style ==="
probe "GET /v1/models with x-api-key" GET "$BASE/v1/models" -H "x-api-key: $KEY"
probe "POST /v1/messages (Anthropic shape) with x-api-key" POST "$BASE/v1/messages" \
  -H "x-api-key: $KEY" -H "anthropic-version: 2023-06-01" -H "Content-Type: application/json" \
  -d '{"model":"claude-opus-5","max_tokens":16,"messages":[{"role":"user","content":"say ok"}]}'

echo "=== same key, an ordinary app user agent instead of the Java default ==="
probe "GET /v1/models with User-Agent BrawlDraft/1.6" GET "$BASE/v1/models" \
  -H "Authorization: Bearer $KEY" -H "User-Agent: BrawlDraft/1.6"

echo "=== no credentials at all, to see the baseline error shape ==="
probe "GET /v1/models with no auth" GET "$BASE/v1/models"

echo "=== what user agent does curl send by default ==="
curl -sS -o /dev/null -w 'curl default UA: %{header_json}' -m 20 "$BASE/v1/models" 2>/dev/null | head -c 0
echo "curl version: $(curl --version | head -1)"

echo "AR DIAG 3 DONE"

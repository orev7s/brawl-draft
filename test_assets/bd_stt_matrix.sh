#!/data/data/com.termux/files/usr/bin/bash
# Transcription accuracy matrix. Reads the key from the app's own prefs so it is never printed.
export PATH=/data/data/com.termux/files/usr/bin:/system/bin

D=/data/local/tmp/bdt
F=/data/data/com.brawldraft.app/shared_prefs/brawl_draft_prefs.xml
KEY=$(grep -o 'name="stt_key">[^<]*' "$F" | head -1 | sed 's/.*>//')
URL=$(grep -o 'name="stt_url">[^<]*' "$F" | head -1 | sed 's/.*>//')
OUT=/data/local/tmp/bd_out.txt
ERR=/data/local/tmp/bd_err.txt

if [ -z "$KEY" ]; then echo "no key"; exit 1; fi
echo "endpoint=$URL/audio/transcriptions"
echo

one() { # file model hintfile label
  local hint=""
  if [ -n "$3" ] && [ -f "$3" ]; then hint=$(cat "$3"); fi
  local -a extra=()
  if [ -n "$hint" ]; then extra=(-F "prompt=$hint"); fi
  local code
  code=$(curl -sS -o "$OUT" -w '%{http_code}' -m 150 \
    -H "Authorization: Bearer $KEY" \
    -F "file=@$1" -F "model=$2" -F "language=en" -F "response_format=text" \
    "${extra[@]}" \
    "$URL/audio/transcriptions" 2>"$ERR")
  echo "### $4 | HTTP=$code | bytes=$(wc -c < "$OUT")"
  if [ "$code" = "200" ]; then cat "$OUT"; echo; else head -c 250 "$OUT"; echo; fi
  echo
  sleep 4
}

segments() { # prefix model hintfile label
  local joined=""
  local f
  for f in $D/$1_*.wav; do
    local hint=""
    if [ -n "$3" ] && [ -f "$3" ]; then hint=$(cat "$3"); fi
    local -a extra=()
    if [ -n "$hint" ]; then extra=(-F "prompt=$hint"); fi
    local code
    code=$(curl -sS -o "$OUT" -w '%{http_code}' -m 150 \
      -H "Authorization: Bearer $KEY" \
      -F "file=@$f" -F "model=$2" -F "language=en" -F "response_format=text" \
      "${extra[@]}" \
      "$URL/audio/transcriptions" 2>"$ERR")
    if [ "$code" = "200" ]; then
      joined="$joined $(cat "$OUT")"
    else
      joined="$joined [HTTP$code $(head -c 80 "$OUT")]"
    fi
    sleep 4
  done
  echo "### $4 JOINED:"
  echo "$joined"
  echo
}

# ---- baseline: whole file in one request
one $D/full.wav whisper-large-v3-turbo "" "A1 full, turbo, no hint"
one $D/full.wav whisper-large-v3 "" "A2 full, large-v3, no hint"
one $D/full.wav whisper-large-v3-turbo $D/hint_roster.txt "A3 full, turbo, +roster hint"
one $D/full.wav whisper-large-v3 $D/hint_roster.txt "A4 full, large-v3, +roster hint"

# ---- the shipped pipeline: 4 second segments
segments seg4 whisper-large-v3 $D/hint_roster.txt "B1 4s segments, large-v3, +roster hint"
segments seg4 whisper-large-v3-turbo $D/hint_roster.txt "B2 4s segments, turbo, +roster hint"

# ---- longer segments
segments seg10 whisper-large-v3 $D/hint_roster.txt "C1 10s segments, large-v3, +roster hint"

echo "MODEL TIMER DONE"

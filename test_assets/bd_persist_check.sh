#!/data/data/com.termux/files/usr/bin/bash
# Presence check only: multi-line values such as the log buffer cannot be measured with grep, and
# nothing here should ever print a key.
F=/data/data/com.brawldraft.app/shared_prefs/brawl_draft_prefs.xml

echo "prefs file: $([ -f "$F" ] && echo present || echo MISSING)"
[ -f "$F" ] || exit 0
echo "prefs size: $(wc -c < "$F") bytes"
echo

for k in providers_json history_json log_lines cached_knowledge cached_names clean_enabled knowledge_enabled bubble_dp reset_hold_ms; do
  if grep -q "name=\"$k\"" "$F"; then echo "  $k : stored"; else echo "  $k : not set yet"; fi
done

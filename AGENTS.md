# AGENTS.md: Building Brawl Draft APKs on the tablet

This file records the build process that was verified to work on 2026-10-16. Follow it exactly. Don't re-derive it.

## Proven setup (tablet)

- Device: Xiaomi tablet `25091RP04G`, Android 16, adb serial `59aff3fb`, KernelSU root.
- Termux user (uid 10309): `/data/data/com.termux/files/home`
- Termux prefix: `/data/data/com.termux/files/usr`
- JDK: `/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk`
- Android SDK: `/data/data/com.termux/files/home/android-sdk` (platforms android-35, build-tools 34.0.0 and 35.0.0)
- Gradle 8.7: `/data/data/com.termux/files/home/gradle-8.7/bin/gradle`
- Project: `/data/data/com.termux/files/home/brawl-draft`
- `local.properties` must contain: `sdk.dir=/data/data/com.termux/files/home/android-sdk`
- **Proven AGP version: `com.android.application` 8.5.2** (with Kotlin 1.9.24). This built `BUILD SUCCESSFUL`.

## Build procedure

1. Write the build script on the PC as a `.sh` file with LF endings. Never inline a multi-line script in an `adb shell` string. PowerShell and the device shell mangle the quotes.
2. Push and run it as root, because root has network access and uid 10309 does not:

```
adb push <local>.sh /data/local/tmp/<name>.sh
adb shell "su -c 'cp /data/local/tmp/<name>.sh /data/data/com.termux/files/home/<name>.sh && chmod 700 /data/data/com.termux/files/home/<name>.sh && /data/data/com.termux/files/usr/bin/bash /data/data/com.termux/files/home/<name>.sh'"
```

3. The script itself should export:

```
export HOME=/data/data/com.termux/files/home
export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME=/data/data/com.termux/files/home/android-sdk
export PATH=/data/data/com.termux/files/home/gradle-8.7/bin:/data/data/com.termux/files/usr/bin:/system/bin
cd $HOME/brawl-draft
gradle :app:assembleDebug
```

4. Check the result by looking for `BUILD SUCCESSFUL` in the log. The APK is at `app/build/outputs/apk/debug/app-debug.apk`. Do not claim success without that line and the APK file.
5. Install: `adb install -r <apk>` from the PC, after pulling it with `adb pull`.

## Known pitfalls (all hit during this session)

- `git` is not on PATH when run as root with a bare environment. Export the Termux PATH first.
- Running Gradle as uid 10309 fails plugin resolution with "Plugin not found", because that user has no network. Run as root.
- Gradle's cache can keep a failed plugin lookup. Delete `~/.gradle/caches/modules-2/files-2.1/com.android.tools.build` before retrying.
- PowerShell does not have `grep` or `head`. Use `Select-String` and `Select-Object -First N`.
- Do not use `&&` in Windows PowerShell 5.1. Use `;`.
- Background `adb shell` processes lose their handle after a timeout. Use a script file and check its log file instead.

## Repo state (do not assume)

- The GitHub repo `orev7s/brawl-draft` main branch has `build.gradle.kts` set to AGP **8.2.2** (commit `a9d44a2`). That version has NOT been built. Only 8.5.2 is proven.
- The tablet's working copy is still at AGP 8.5.2, because its `git reset` never ran.
- Before pushing a version change, build it on the tablet first.
- `codemagic.yaml` on main (commit `81b5582`) is for Codemagic cloud builds. It has not been run yet.

## Rules for future agents

- Never paste API keys into files, commits, or chat. The Whisper key that was pasted in chat should be treated as compromised and rotated.
- Do not install large SDKs or Gradle versions when the tablet already has what's needed.
- If a command fails twice with the same error, stop and read the full log. Don't retry the same command.

## Session 2 (2026-10-16, later): streaming transcription, UI pass (v1.1, verified)

### Tablet state that is now proven
- The tablet's root `build.gradle.kts` is **AGP 8.5.2**. The repo copy was aligned to 8.5.2 as well,
  because 8.2.2 was never actually built. Do not move it back to 8.2.2.
- The tablet's `gradle.properties` adds
  `android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2`.
  The aapt2 pulled from Maven is a glibc binary and cannot run under Termux. Keep this line.
- Build script: `/data/data/com.termux/files/home/bd_build2.sh`, log
  `/data/data/com.termux/files/home/bd_build_v11.log`. It logs itself, so run it and then read the
  log with `adb shell "su -c 'cat /data/data/com.termux/files/home/bd_build_v11.log'"`.
  An incremental build takes 2-7 s; look for `BUILD SUCCESSFUL` and the APK line.
- Push files with LF endings. `adb push` plus `cp` into the project keeps the *pushed* filename,
  so copy with an explicit destination name (`cp /data/local/tmp/x.kt OverlayService.kt`).
  Copying without a destination name leaves two copies of the same class and the build fails.

### What v1.1 changed
- Transcription goes to any OpenAI-compatible endpoint. Defaults: Groq
  `https://api.groq.com/openai/v1` with `whisper-large-v3-turbo`. Prefs keys are now `stt_url`,
  `stt_key`, `stt_model`, `stt_prompt`; the old `whisper_key` / `whisper_model` keys are unused.
- Audio capture is raw 16 kHz mono PCM through `AudioRecord` (not MediaRecorder). Audio is cut into
  4 second segments, each wrapped in a WAV header by hand and uploaded/transcribed **while the
  player is still talking**, on a 3 thread pool, joined in order at Send time. Send therefore only
  waits for the last short tail. Prefs toggle `stream_upload` (default true) turns this off.
  Trade-off to remember: with streaming on, a cancelled take still pays for segments already sent,
  and a word split across a segment boundary can be clipped.
- Overlay: auto-close after `auto_hide_ms` (default 7500) with a live countdown, tap panel to keep,
  X to close, Cancel button while recording, live partial transcript while recording, and phone vs
  tablet sizing driven by `screenWidthDp >= 600` with re-layout on `onConfigurationChanged`.

### Pitfalls hit in this session
- `TextView.setSingleLine(true)` called *after* `setInputType(...TYPE_TEXT_VARIATION_PASSWORD)`
  resets the variation to normal, so the API key fields rendered in clear text on screen.
  Always call `setSingleLine` first. This shipped in 1.0 and was fixed in 1.1.
- The first v1.1 build failed on `col.addView(hint("..."))` in MainActivity: `hint()` takes the
  column as its first parameter.

### Verifying transcription without a human in the loop
`/data/local/tmp/bd_stt_test.sh <audiofile>` pulls the key from the app's prefs on device, POSTs the
file to the configured endpoint and prints the HTTP status plus the transcript. It never prints the
key. `adb shell "su -c 'bash /data/local/tmp/bd_stt_test.sh /data/local/tmp/clip.wav'"`.
A 200 with real words proves key, model, endpoint and container are all good.

### Security
- The v1.0 key-display bug means the Groq key was visible on screen; the owner should rotate it.
- `shared_prefs/brawl_draft_prefs.xml` holds the keys in clear text, as Android normally does.
  Delete any `.bak` copies of that file after editing it.
- Prefs can be edited while the app is stopped by rewriting the file in place
  (`cat new > "$F"`) so ownership and the SELinux label are preserved.

## Session 3 (2026-10-16, later): measured transcription + coach fixes (v1.2)

Full evidence lives in `transcription-test-report.md`. Reusable harness is in `test_assets/`.

### The two real bugs found by measurement, not guessing
1. **4 second live-upload clips destroyed accuracy.** Cutting audio every 4 s splits words and strips
   context, so Whisper both mangled names ("Emz is banned" -> "Ems is back", "Gus" -> "Gull") and
   invented words that were never spoken ("Gryffindor", "Sander", "Anime Team Big Series").
   10 second clips matched single-request accuracy with no invented words. Default is now 10000 ms,
   exposed as `segment_ms` (clamped 2-30 s).
2. **The coach returned an empty reply.** `openai/gpt-oss-20b` is a reasoning model and the app sent
   `max_tokens=300`; the reasoning pass ate the entire budget, so `content` came back `""` with
   `finish_reason="length"`. The app showed an empty string, logged nothing and spoke nothing.
   Budget is now 2048 AND an empty reply is reported with finish_reason and reasoning token count.
   Also: the old default `llama-3.3-70b-versatile` now answers **404 model_not_found** on Groq.

### Vocabulary
`BrawlData.kt` holds the verified 108 brawler roster (October 2026). Sent as Whisper's `prompt`
(the only supported way to bias a hosted Whisper - the model cannot be fine tuned through the API)
and appended to the coach system prompt so it can repair mangled names. The hint is about 200 of the
224 allowed prompt tokens, so there is almost no headroom to add more.

### Testing pattern worth reusing
`test_assets/` holds the scripts. They run **on the tablet** and read the key from the app's own
`shared_prefs`, so the key never leaves the device or appears in a transcript:
- `bd_stt_matrix.sh` - accuracy matrix over whole-file vs segmented transcription.
- `bd_chat_test.sh` - coach request variants, exposes empty content and 404s.
- `bd_coach_final.sh` - end to end with the shipped prompt shape.
- `hint_roster.txt` / `sysprompt.txt` - the exact strings used.

Decode audio on the PC with `python -m pip install soundfile numpy` (no ffmpeg available anywhere),
write 16 kHz mono WAVs, push them to `/data/local/tmp/bdt/`. Ask an LLM to transcribe game
vocabulary and always keep a known ground-truth transcript to score against, otherwise "it sounds
better" is unfalsifiable.

### Housekeeping
- Delete recordings after testing: `/data/local/tmp/bdt/*.wav` and the app's cache. They contain
  the owner's voice.
- `dumpsys package` / `logcat -s BrawlDraft:*` show the version actually installed and the per
  segment transcript lengths. The logs proved the owner had been testing the 4 s build.

## Session 4 (2026-10-16, later): the "Edgar is still banned" complaint (v1.3)

### Cause, with evidence
The bundled `assets/default_prompt.txt` carried a hand written draft state listing
`Banned brawlers: Edgar, Emz, Gray, Chuck, El Primo` **inside the system prompt**, plus a rule
"Never recommend a banned brawler". A spoken correction therefore had to beat a system level
instruction. Tests (`test_assets/bd_ban_test.sh`, `bd_ban_test2.sh`):
- Correction with the transcript intact: the model accepts it, even on the old prompt.
- Same turn with the word "not" missing from the transcript: the coach flatly repeats the ban.
  **That is the reported symptom**, so the transcript is the prime suspect, not the chat history.
- `openai/gpt-oss-20b` ignored the added contradiction rule; `openai/gpt-oss-120b` reasoned about
  the contradiction and moved on. 120b is now the default coach.

### Rules for this project
- **Never put mutable draft state in the system prompt.** State comes from the conversation. The
  prompt file is polled every 5 s and is for RULES only. If state must be preloaded, mark it
  clearly as superseded by anything the player says.
- A `cached_prompt` pref can silently override the bundled asset. Clear it when changing the
  shipped prompt, or the new text never reaches the device.
- When a complaint is "the model ignores what I said", check the transcript first. During
  debugging, always ask what the `You: ...` line in the overlay showed.

### Testing nuance
`bd_ban_test.sh` needs a three message history (setup, coach reply, correction). Build the JSON on
device with `printf` and collapse the system prompt to one line
(`tr '\n' ' ' | sed 's/"/\\"/g'`) so it stays valid JSON. Do not try to nest the API's own reply
back into a follow up request from shell - hardcode the assistant turn instead.

## Session 5 (2026-10-16, later): published names + repair pass (v1.4)

### The names file is the single source of truth
`app/src/main/assets/brawl_data.json` holds brawlers, ranked map lists per mode, game terms and
observed speech-to-text mistakes. It is committed to GitHub (the repo is **public**, which matters
because the app fetches it unauthenticated) and pulled at startup plus every 5 minutes by
`NamesRepo`, cached in prefs, with the bundled asset as fallback. **Never hardcode a brawler or map
list in Kotlin again** - add it to the JSON. `BrawlData.kt` was deleted for this reason.

To add a map or brawler: edit the file on GitHub, save, and the tablet picks it up within 5 minutes.
No rebuild, no reinstall.

### The repair pass
Pipeline is now: 16 kHz PCM segments -> Whisper -> **name repair** -> coach.
- Model: `qwen/qwen3.8-27b`, `temperature=0`, `max_tokens=1024`, measured 0.57-0.76 s.
  It beat gpt-oss-20b (2.31 s) and destroyed allam-2-7b (missed 3 of 4 fixes and invented a name).
- The reply budget for reasoning models must cover thinking as well as the answer. `gpt-oss-20b`
  with `max_tokens=300` returned `content:""` with `finish_reason:"length"`, which the app used to
  display as total silence.
- On any exception the raw transcript is used. Never let the repair pass block the coach.
- The overlay prints `(heard: ...)` whenever the repair changed the text, so a bad correction is
  visible rather than silent.

### Gotchas found the hard way
- `printf` needs `\\n` to emit a JSON `\n`. A plain `\n` puts a raw newline inside the JSON string
  and Groq answers `400 failed to unmarshal JSON`. Two wasted test rounds on this.
- Inside `object : Runnable { override fun run() { ... } }`, `this` is the Runnable, not the
  Service. Use `this@OverlayService` when passing a Context.
- Check model availability with `GET /openai/v1/models` before shipping a model name. Only these
  were callable: gpt-oss-120b, gpt-oss-20b, gpt-oss-safeguard-20b, qwen3.8-27b, allam-2-7b,
  whisper-large-v3(-turbo), plus the TTS and prompt-guard models. `llama-3.3-70b-versatile` is gone.
- Do not put `android.aapt2FromMavenOverride` in `gradle.properties`: it would break any build
  outside Termux, including GitHub Actions. `bd_build2.sh` passes it with `-P` instead, so the
  repository stays clean and a `git reset --hard` on the tablet cannot break the build.
- `adb shell "su -c '...'"` mangles `$VAR`, braces and pipes. Write the script to a file, push it,
  run it. This bit three times in one session.

## Session 6 (2026-10-16, later): provider choice and the token wall (v1.5)

### The constraint that drives everything
Groq's free tier caps **every** chat model at **8K tokens per minute** (200K/day, 30 RPM). A large
system prompt cannot work there: one coach turn with a 5K token prompt plus a cleaner turn is
~8.5K tokens, so it immediately rate limits. Token budget, not model quality, is what limits how
much game knowledge the coach can hold.

### Verified free options (checked against provider docs on 2026-10-16)
| Provider | Free chat allowance | Notes |
|---|---|---|
| Groq | 8K TPM, 200K TPD, 30 RPM | permanently free, renewing, no card. Same models as Cerebras. |
| Google Gemini | not published per model; read it in AI Studio | highest TPM of the free options and a huge context, no card. Free tier data feeds Google's products. |
| Cerebras | 30K uncached TPM, 1M TPD, 5 RPM, ~3000 tok/s | **NOT permanently free**: $5 credit, expires after 30 days, needs a verified payment method. Only `gpt-oss-120b` and `qwen-3.8-27b`. |

All three speak the OpenAI chat completions shape, so switching is a base URL and a model name:
- Groq            `https://api.groq.com/openai/v1` -> `openai/gpt-oss-120b`, cleaner `qwen/qwen3.8-27b`
- Google Gemini   `https://generativelanguage.googleapis.com/v1beta/openai` -> `gemini-3.8-flash`
- Cerebras        `https://api.cerebras.ai/v1` -> `gpt-oss-120b`, cleaner `qwen-3.8-27b`

`Providers.kt` holds these as one-tap presets. A preset changes the chat URL and both model fields
only - never the keys, never the speech-to-text settings. Paste the new key afterwards.

### Still to check when a key exists
- Whether Cerebras accepts `max_tokens`. Its docs push `max_completion_tokens`, and its rate
  limiter reserves quota from that value before running the request. If it 400s, switch the app to
  send `max_completion_tokens`.
- Gemini 3.x cannot disable thinking, and thinking tokens come out of the same `max_tokens` budget.
  If replies come back empty, raise `MAX_REPLY_TOKENS` - this is the same failure that made
  gpt-oss-20b return nothing at 300 tokens.

# Brawl Draft - transcription and coach test report

Date: 2026-10-16. All tests were run **on the tablet** through `adb`, against the real Groq key,
read from the app's own `shared_prefs` so the key was never copied to a PC or printed.

## Test asset

`C:\Users\v80q\Downloads\thetest.mp3` - 26.40 s, 48 kHz mono, decoded to 16 kHz mono 16-bit PCM
(what the app records). Cut into 7 x 4 s and 3 x 10 s clips for the segmented runs.

Ground truth, as dictated by the owner:

> okay the map is rings of fire emz is banned gray is banned chuck is banned and squeak is banned
> my team picked gus and edgar and they picked siruis like the enemy team picked siruis and kaze
> and cordelius what should i pick shelly or colt or bull or RT

Hard words to get right: **Chuck**, **Emz**, **R-T**, plus the Ultra Legendary pair **Sirius** and
**Kaze** (both real brawlers, confirmed against the current 108 brawler roster).

## A. Whole file in one request

| Run | Model | Hint | Result |
|---|---|---|---|
| A1 | whisper-large-v3-turbo | none | Rings of Fire, Gray, Squeak, Gus, Edgar, Sirius, Kaze, Cordelius, Shelly, Colt, Bull all correct. **"Chug"** (Chuck), **"Artie"** (R-T), "Ems" (Emz), "should **they** pick" |
| A2 | whisper-large-v3 | none | same names correct, **"Choc"** (Chuck), **"Arty"** (R-T), "Ems", "they pick" |
| A3 | whisper-large-v3-turbo | + roster | same, **"Tug"** (Chuck), **"R-T" correct**, "Ems", "they pick" |
| A4 | whisper-large-v3 | + roster | same, **"Chuck" correct**, "Arty", "Ems", "they pick" |

## B. 4 second segments (what v1.1 shipped)

| Run | Model | Result |
|---|---|---|
| B1 | whisper-large-v3 + roster | "Ems is **back**", "**Sander**, Gray is banned", "**Gull**", "they picked Sirius and **Gryffindor**", "Like the **Anime Team Big Series**", "Kalt", "Boll", "**Paisa**", "Archie" |
| B2 | whisper-large-v3-turbo + roster | "Ems is **Bound**", "**Choc**", "My team picked **Gah**. Ss", "**Big Series**", "Kalt", "Boll" |

**This is the accuracy bug.** Four-second cuts split words across boundaries and strip the context
Whisper needs, which both mangles names and makes it invent words that were never spoken
("Gryffindor", "Sander", "Anime Team Big Series"). "Ems is back" is exactly the failure reported.

## C. 10 second segments

| Run | Result |
|---|---|
| C1 | "Ems is banned, Gray is banned, **Chuck** is banned, and Squeak is banned. My team picked **Gus**, Edgar, and they picked Sirius and like the enemy team picked Sirius and Kaze and Cordelius. What should they pick? Shelly? Or Colt, or Bull, or **Archie**" |

No invented words, and it matched or beat the single-request runs on name accuracy.

## D. Coach LLM

| Run | Request | Result |
|---|---|---|
| D1 | `openai/gpt-oss-20b`, `max_tokens=300` (**what the app sent**) | HTTP 200, **`content: ""`**, `finish_reason: "length"` - the reasoning pass consumed the whole budget and nothing was left to say |
| D2 | same, `max_tokens=1024` | proper 3 sentence recommendation |
| D3 | same, `max_tokens=1024`, `reasoning_effort=low` | replied in 0.06 s but asked a clarifying question instead of recommending |
| D4 | `llama-3.3-70b-versatile` (the app's **default** model) | **HTTP 404 `model_not_found`** - Groq retired it |

This is why the coach "never replied": the app displayed an empty reply string, logged no error, and
spoke nothing.

## E. End-to-end after the fixes

Config: `whisper-large-v3`, 10 s clips, roster hint, `openai/gpt-oss-20b`, `max_tokens=2048`,
system prompt + roster addendum. Input: the C1 transcript verbatim (including "Ems" and "Archie").

> **Pick Colt. He can shoot from the ring and help you score quickly. That gives you a long-range
> threat to complement Gus and Edgar.**

HTTP 200, real answer, names understood.

## Changes made because of these results

1. Default clip length **4000 -> 10000 ms**, now a setting (`segment_ms`), clamped 2-30 s.
2. Default transcription model **whisper-large-v3-turbo -> whisper-large-v3** (WER 12% -> 10.3%).
3. New `BrawlData.kt`: verified 108 name roster. Sent as Whisper's `prompt` (domain biasing is the
   only supported way to teach a hosted Whisper; the model cannot be fine tuned through the API)
   and appended to the coach system prompt so it can repair mangled names.
4. Reply budget **300 -> 2048 tokens**, and an empty reply is now reported instead of shown as
   silence (`finish_reason`, reasoning token count).
5. Default coach model **llama-3.3-70b-versatile -> openai/gpt-oss-20b** (the old one 404s).

## Not verified, and honest limits

- No live tap-to-talk run through the app's own mic. Everything above used the same encoder settings
  on a file, not the tablet's microphone in a game.
- `should I pick` -> `should they pick` survived every variant including single-request large-v3.
  That is acoustic, not vocabulary; the roster addendum lets the coach repair the intent, but the
  raw transcript will still say "they".
- The whole roster hint is roughly 200 of the 224 allowed prompt tokens. It was accepted and picked
  up names from both ends of the list, but there is little headroom to add more.
- One recording, one speaker, one accent. Treat the win/loss between turbo and large-v3 as
  directional, not conclusive.

---

# Addendum: "it says Edgar is still banned"

## What the coach was actually being told

The bundled system prompt contained a hand-written draft state that was stamped into **every**
request:

```
CURRENT DRAFT STATE (edited live by the owner of this prompt)
- Banned brawlers (nobody can pick these): Edgar, Emz, Gray, Chuck, El Primo
...
- Never recommend a banned brawler.
```

Edgar was hard-coded as banned at system-prompt level, so a spoken correction had to beat an
instruction that outranks the player. **Not a chat-history bug.**

## Two scenarios, two different outcomes

| Scenario | Prompt | Result |
|---|---|---|
| P1: "edgar is **not** banned. should i pick him on a close map?" | current | Accepted the correction: "Edgar is fine on a close map..." |
| Q1: transcript **lost** the "not" -> "edgar is banned. should i pick him..." | current | "**No, Edgar is banned** so you can't pick him." - the reported symptom |
| Q2: same lost-not transcript | fixed prompt | Still repeated the ban; **20b ignored the contradiction rule** |
| Q3: same lost-not transcript | fixed prompt, **120b** | Reasoned about the contradiction and moved on to a recommendation |

So with an intact transcript the old prompt already worked. The reported failure reproduces only
when speech to text drops the word "not" - which is a known ASR weak spot, and the reason the
clarifying-question rule was added.

## Changes shipped in v1.3

1. `assets/default_prompt.txt` rewritten: **no hardcoded draft state**, the player's latest
   statement is authoritative, bans exist only if spoken, plus a contradiction rule that asks
   one short question when the player calls a brawler banned and then asks to pick them.
2. Default coach **openai/gpt-oss-20b -> openai/gpt-oss-120b**, because 20b ignored the
   contradiction rule and 120b followed it. About one second slower per reply.
3. Any cached prompt is cleared so the bundled asset is definitely the one in use.

## Still open / honest limits

- The contradiction rule did **not** reliably produce the clarifying question in the Q runs. 120b
  handled the contradiction by moving on rather than asking. Treat it as mitigation, not a fix.
- Negation loss in transcription cannot be patched in the app. The way to tell which side failed
  is the `You: ...` line in the overlay, which shows exactly what the transcriber heard.
- Not yet tested: whether another ASR provider (Deepgram Nova, AssemblyAI Universal-2) drops
  "not" less often on this speaker. That needs a key and one recording of the failing sentence.

---

# Addendum 2: name-repair pass and the published name lists (v1.4)

## The reference file

`app/src/main/assets/brawl_data.json` - 108 brawlers, every ranked mode's map list
(Gem Grab 25, Brawl Ball 25, Hot Zone 20, Heist 19, Bounty 16, Knockout 26, Brawl Hockey 15
after de-duplicating names that appear twice in the source), 26 game terms, and 21
observed speech-to-text mistakes collected from the transcripts in this report.

Published at
`https://raw.githubusercontent.com/orev7s/brawl-draft/main/app/src/main/assets/brawl_data.json`
and fetched by the app at startup and every 5 minutes, cached on device, with the bundled copy as
the offline fallback. Verified on the tablet: `I BrawlDraft: names refreshed, 4802 chars`.

## Model choice for the repair pass

Same mangled transcript, four candidates:

| Model | Time | Result |
|---|---|---|
| **qwen/qwen3.8-27b** | **0.76 s** | Emz, Chuck, Gus, R-T and Ring of Fire all correct |
| openai/gpt-oss-20b | 2.31 s | all correct, but 3x slower |
| qwen/qwen3.8-27b, reasoning_effort=low | 1.24 s | correct, slower than default |
| allam-2-7b | 0.45 s | fixed only "Ems", invented "Cordelia" - rejected |

## End-to-end with the exact context the app builds

Input, containing every observed failure:

> we are playing open map hot zone on rings of fire. Ems is banned, Gray is banned, Chug is banned
> and Squeak is banned. my team picked Gull and Edgar, the enemy picked Sirius, Kaze and Cordelius.
> should they pick Shelly or Colt or Bull or Artie

Output, HTTP 200 in **0.57 s**:

> we are playing open map hot zone on **Ring of Fire**. **Emz** is banned, Gray is banned,
> **Chuck** is banned and Squeak is banned. my team picked **Gus** and Edgar, the enemy picked
> Sirius, Kaze and Cordelius. should they pick Shelly or Colt or Bull or **R-T**

Five repairs, no other word changed, question not answered, meaning intact.

## How it behaves in the app

- Runs after transcription and before the coach, only when a transcript exists.
- On any failure, timeout or empty output the raw transcript is used instead. The coach is never blocked.
- The overlay shows `You: <repaired>` and, when the repair changed something, a second line
  `(heard: <raw>)`, so a wrong repair is visible instead of silent.
- Toggle and model are in settings (`clean_enabled`, `clean_model`). Cost is one extra request per
  turn against the free tier.

## Limits

- The repair pass fixes names, not grammar or meaning. It will not recover a word the transcriber
  dropped entirely, e.g. a lost "not", which is the open issue from Addendum 1.
- It can over-correct: "back" is mapped to "banned" in the observed list, so a legitimate "back"
  would be rewritten. Review the `(heard: ...)` line and trim that entry in the JSON if it misfires.
- Every name list comes from brawlify.com, not from Supercell. A new brawler or a rotated map will
  be missing until the JSON is edited. Editing the file on GitHub is enough, no rebuild needed.

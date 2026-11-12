# KAVACH 20-call human pilot: recording kit

This is a **locked feasibility pilot**: 20 role-played calls with real human voices, recorded on the
real one-phone path, scored once with a frozen build. Twenty calls show whether KAVACH works on
human speech. They are **not** a safety certification. With 10 genuine calls, even 0 false STOPs
only means the true false-STOP rate is probably below about 28 % (95 % Wilson upper bound).

Files in this folder:

| File | What it is |
|---|---|
| `README.md` | this protocol |
| `consent.md` | consent note (English and Hindi): every person who speaks signs it first |
| `scenario_cards.md` | 4 practice cards + 20 test cards: role goals, not scripts |
| `sessions.csv` | one row per call: filled in during recording |
| `annotation_template.csv` | cue and request times: filled in **separately** by two people, without looking at KAVACH's results |
| `../../bench/pilot20_report.py` | turns the files into the results table (counts, rates, Wilson intervals) |

## 1. Rules that keep the result honest

1. **Freeze first.** Before the first test call, write down the build (app version, commit, APK SHA-256)
   and the model files in `sessions.csv`. Do not change the app, thresholds or models until all 20 test
   calls are done. If something has to be fixed after a test call, the calls already made become
   *development* data and must be reported that way.
2. **Practice calls are separate.** Use cards P1–P4 to fix placement, volume and setup. They are never
   counted as results.
3. **Each card is recorded once.** Repeated takes, noise variants or transcript pieces are not extra calls.
   If a call fails technically (it dropped, or KAVACH was not running), mark it `unavailable` and keep it in the table.
   Do not silently re-record until it works.
4. **Paraphrase.** Callers read the role goal on the card, then speak in their own words. Do not read
   the training scripts in `roleplay_scripts.md` or `data/synth`.
5. **Fictional everything.** Fictional names, Aadhaar/account/card numbers, amounts, links. No real
   transaction, no real link opened, no real app installed.
6. **Balance voices.** Every caller voice appears in both scam and genuine calls (the cards assign
   speakers). Otherwise KAVACH could learn "this voice = scam".

## 2. Allocation (fixed in advance)

| | Hindi | English | Hinglish | Total |
|---|---|---|---|---|
| Scam | 4 | 3 | 3 | 10 |
| Genuine (several look like scams) | 4 | 3 | 3 | 10 |

Noise: 6 scam and 6 genuine cards are "quiet room", 4 and 4 are "ordinary noise" (fan, TV at low
volume, people talking in the next room). Speakers: aim for 4–6 different callers. With fewer, say so.

## 3. Audio path and setup

* **Test path (preferred, what the app really uses):** the caller phones the test phone (Xiaomi,
  KAVACH installed) over the **normal cellular network** from a number **not** in its contacts. The
  "victim" answers in the phone's own dialer and taps **Speaker**. KAVACH starts by itself (Auto language).
* **Reference recording for annotation:** KAVACH never stores audio, and Android silences other
  recorders on the test phone during a call. So place a **second device** (laptop or another phone) about 30 cm
  from the test phone and record the room (both voices) from **before the call starts** to after it ends.
  The two annotators listen to this file. Say "start" aloud at the moment the call connects, so its time can be
  marked.
* Keep research audio on the team drive only, never in git or in the app's storage. Delete after the
  challenge as promised in the consent note.

Checklist before every call:
- [ ] Readiness screen: call listening **"On and running"**, Hindi and English big models, Llama loaded
- [ ] Language **Auto**; phone not charging; screen on; volume at the level written in `sessions.csv`
- [ ] Caller number not in contacts; reference recorder running; card number and speaker IDs written down

After every call, from the test phone:
- [ ] Note what the screen showed (nothing / yellow / red, scam type, explanation source) in `sessions.csv`
- [ ] At the end of the session pull the logs: `MSYS_NO_PATHCONV=1 adb pull /sdcard/Android/data/io.kavach.guardian/files/bench ./pilot_logs`
  (each call is one `session` … `session_end` block in a `guardian_*.csv` file: alert times are inside)

## 4. Annotation (two people, independently)

Each annotator copies `annotation_template.csv`, listens to the reference recordings and fills in, **without
seeing KAVACH's screen or logs**:
* `first_cue_s`: seconds from "start" to the first clearly scam-like sentence (an authority claim with a threat or
  identity hook, a prize, a fake emergency). Empty for genuine calls with none.
* `first_request_s`: seconds to the first **risky request**: OTP, PIN, card number, link, app install,
  screen share, or payment. Empty if there is none.
* `notes`: anything unclear.

Then the two compare. Keep both original files and write the agreed value in `annotation_final.csv`
(same columns, plus `resolved_by`).

## 5. Scoring

```bash
python bench/pilot20_report.py --sessions demo/pilot20/sessions.csv \
   --annot demo/pilot20/annotation_final.csv --logs ./pilot_logs --out bench/results/pilot20.json
```

It reports integer counts and Wilson 95 % intervals for: scam STOP, scam CAUTION-or-STOP, scam calls
warned **before** the first risky request (over **all** 10 scam calls, missed calls count as not warned),
lead time (among warned calls only), false STOP and false CAUTION on genuine calls, unavailable
sessions, explanation source (Llama / playbook text), and results per language and per speaker.

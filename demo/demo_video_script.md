# Demo video script (about 3 minutes)

PS3 asks for a demonstration video. This script shows every PS3 requirement once, in the order a
judge thinks about them, on **one Android phone** plus a teammate's phone for the scammer.
Record the phone screen (Android screen recorder; turn on *Show touches*) and, separately, a
second camera or phone filming the two phones on the table for the call parts.

| Time | What the viewer sees | What you say (short) | Judging criterion |
|---|---|---|---|
| 0:00–0:15 | Title card: KAVACH, on-device scam shield, plus one line on scam losses in India with a source you can cite (e.g. I4C / NCRP data) | "Scams in India now happen on a live call. KAVACH stops them on the phone itself, with no internet." | Problem understanding |
| 0:15–0:30 | App info → Permissions of KAVACH: **no Internet** in the list | "The app that hears your calls cannot send anything off the phone." | Privacy, alignment |
| 0:30–1:25 | Teammate calls from a number not in your contacts; you answer in the normal phone app and tap Speaker. KAVACH Calls tab: transcript scrolls, turns yellow, then red; phone vibrates; Stop card; then the "What KAVACH noticed" card fills in with **Llama 3.2 1B on this phone** | "It listens on the same phone… the rules and classifier react in milliseconds… then Llama 3.2 1B, running with ExecuTorch on the Arm CPU, explains the trick." | Novelty, technical correctness, use of Arm tools |
| 1:25–1:40 | A genuine bank-style call: stays calm | "A real bank call is not flagged. We count false alarms as failures." | Correctness |
| 1:40–2:00 | Check tab: paste `sbi-kyc-update.xyz` SMS → red, reasons listed. Activity tab | "Messages and links are checked on the phone too, and joined with the call." | Coverage (SMS, phishing) |
| 2:00–2:25 | Network Watch: Overview on; Wi-Fi tab → Check my Wi-Fi → a device with remote debugging open, with the fix | "Network Watch finds apps talking to fake bank sites and risky devices on your home Wi-Fi." | Coverage (network intrusion) |
| 2:25–2:50 | Calls → Setup & tools → Measure performance results screen | "Measured on this phone: both speech models together keep up five times faster than real time, Llama writes about 12 words a second, and an hour of listening costs under 8 % battery." (numbers from report §7.5) | Performance and optimisation |
| 2:50–3:00 | End card: GitHub link, team names, faculty mentor | "Source, report and benchmarks are on GitHub." | Documentation |

Rules for the video:

- Say **demo** when a call is scripted. Never claim KAVACH is "100 % safe"; say "no warnings so far is not a guarantee".
- Use the real numbers from the benchmark screen; do not round up.
- If Llama copies its example answer on camera, keep it: the screen labels its wording honestly.
- Keep the phone 10–30 cm from the speaker-phone audio; check the placement chip says *Hearing the call clearly*.

# Finish Phase 1: four to five live calls and the demo

Agreed scope on 22 November: **4–5 live human role-play calls and a demo video only**. The 20-call pilot, new battery runs, standalone model tests and further ablations are deferred to Phase 2. Keep the frozen installed build. Results from the supplied addendum are already incorporated as tester-reported observations.

## 1. Prepare the first call
1. Ask one consenting adult with a second phone to read the caller lines below. A teammate is not required: a friend or family member can help. The person must speak live through an actual call; playback or reading beside the test phone is a different test type.
2. On the KAVACH phone, open **KAVACH → Calls → Setup & tools**.
3. Scroll to the **Readiness** heading. Check microphone permission, call-listening service and both speech models. Keep language **Auto** and **Protect my calls** on. If anything is missing, record the exact message before proceeding.
4. Open the laptop camera and frame the phone so the screen is legible. Record the phone and speaker audio if both participants consent; this avoids competing with KAVACH for the phone microphone. Do not show phone numbers or real personal information in the final video.
5. Have the other person call normally. Answer in the phone's dialer, tap **Speaker**, then return to KAVACH without ending the call. Let KAVACH start automatically. If it does not start, record that as an observed failure; do not silently replace the call with manual listening.
6. Caller reads the chosen script naturally. Use no real OTP, bank account or transaction. Do not tap Call 1930, make payments or follow links.
7. Capture the warning and explanation, if one appears. After the caller finishes, allow time for any pending explanation and record the actual wait. A rule-based warning is not evidence that Llama ran.
8. End the call. Capture **Activity** and fill one row of `demo/live_calls_phase1.csv`. Record failures and false alerts too. Start a fresh call for the next scenario.

## 2. Four calls; fifth optional

### C1 — English scam
“I am calling from your bank security department. Your KYC has expired and your account will be blocked today. Tell me the OTP that just arrived so I can fix it. Do it now. Do not tell anyone about this call. Transfer the money to our safe account.”

### C2 — English benign
“This is a bank safety reminder. Never share an OTP or PIN with anyone, including bank staff. We are not asking for a code or payment. If you are concerned, hang up and contact the bank using the number on your card.”

### C3 — Hindi scam
“मैं साइबर क्राइम विभाग से बोल रहा हूँ। आपके आधार पर एक पार्सल मिला है जिसमें गैरकानूनी सामान है। आपके खिलाफ गिरफ्तारी का वारंट है। कॉल मत काटिए और अपने परिवार को मत बताइए। अभी अपना पैसा हमारे सुरक्षित खाते में ट्रांसफर कीजिए।”

### C4 — Hindi benign
“यह बैंक की सुरक्षा सूचना है। अपना ओटीपी या पिन कभी किसी को मत बताइए। हम आपसे कोई कोड या पैसे नहीं माँग रहे हैं। किसी भी संदेह में कॉल काटिए और अपने बैंक के आधिकारिक नंबर पर संपर्क कीजिए।”

### C5 — Optional Hinglish scam
“Main courier office se bol raha hoon. Aapke Aadhaar par illegal parcel mila hai. Police case se bachne ke liye abhi safe account mein payment karo. Call mat kaatna aur kisi ko mat batana.”

These are a small, scripted live-human demonstration, not representative field accuracy or a 20-call pilot. Record observed results rather than predicted results. If C5 is not run, leave it pending and report four attempted calls.

## 3. What to record
Use anonymous caller labels only. Record date/time, language, whether listening started automatically, observed warning level, Llama explanation/source if shown, and evidence filename. NONE = no warning; INFO = worth watching; CAUTION = suspicious pattern; STOP = scam warning. “Too noisy” is an audio-quality hint, not a scam verdict.

If the continuous recording clearly shows the first sensitive request and warning, record both video timestamps. Otherwise leave timing blank and make no “before request” claim. Do not discard an unsuccessful attempt or repeat it silently until it passes. Mark retries separately.

Send the call screenshots/recording and completed rows for report compilation. No extra experiments are needed before these calls.

## 4. Demo video from those same calls
Target roughly 4–5 minutes unless the submission form sets a different limit. Reuse the recorded calls; do not conduct a separate test campaign.

- 0:00–0:25: problem, KAVACH, tested Arm phone and on-device approach.
- 0:25–0:45: readiness and model presence. Explain speakerphone requirement.
- 0:45–2:00: one continuous live scam-call sequence: incoming call, speaker, transcript, warning, explanation if produced. Do not splice the moment between request and warning to imply faster detection.
- 2:00–2:45: benign call outcome, including any false alarm.
- 2:45–3:30: Hindi/Hinglish call and its actual outcome.
- 3:30–4:15: concise architecture and measured results; distinguish rule detection from Llama explanation, and synthetic benchmarks from the four/five calls.
- 4:15–4:40: limitations and repository/report links.

Trim dead time outside the continuous demonstration and add truthful captions. Hide personal caller details. Export as MP4. Upload to your chosen video host and verify that judges can view the link without requesting access.

## 5. Finish submission
The report is updated after the call outcomes arrive. Check the PDF, APK/model instructions, source repository and video link against the actual submission form. Submit and save the receipt. The small call demonstration supports feasibility; it cannot establish broad accuracy or guarantee selection.

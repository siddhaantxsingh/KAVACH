Fill-in guide for sessions.csv

* call_id: T01..T20 in recording order; card: S01..S10 / B01..B10
* label: scam | benign;  language: hi | en | hinglish;  noise: quiet | ordinary
* audio_path: cellular_speaker (preferred) | acoustic_playback | other (describe in notes)
* start_time_ist: wall-clock time the call connected, e.g. 14:05:30; seconds required and observer/phone clocks aligned (used to find the call in the app log)
* status: pending (not attempted) | ok (completed, explicit end_state required) | unavailable (call dropped, KAVACH not running, etc.: keep the row)
* end_state: what the screen showed at the end: none | info | caution | stop
* explanation_source: llama | playbook | none (the label under the explanation)
* llm_timeout: yes | no;  readiness_ok: yes | no (Readiness screen before the call)
* request_kind (annotation): otp | pin | card | link | app_install | screen_share | payment

Scorer correction: missing request time is unknown, never an early-warning success. Both evaluable-call and attempted-call detection denominators are reported. Ambiguous/reused log matches are rejected. Timing is converted from app-session start to call connection; complete logs are required. Run regression tests with `python -m unittest discover -s tests -p test_pilot20_report.py`.

# Completed Phase 1 live-call tests

We tested KAVACH using four live human role-play calls from another phone. Both simulated scams turned red and both genuine calls produced no warnings.

| ID | Language | Scenario | Outcome |
|---|---|---|---|
| LC-01 | English | Expected parcel delivery, payment and delivery code | No warning |
| LC-02 | Hindi | Family conversation, hostel fee and scam awareness | No warning |
| LC-03 | English | Fake bank KYC, account-block threat and OTP request | Red warning |
| LC-04 | Hindi | Fake police, arrest threat, secrecy and safe-account demand | Red warning |

All calls were recorded and have been supplied in our submitted demo video, uploaded to this repository.

**[Watch the demo video](../KAVACH_VIDEO.mp4)**

- [Full scripts](live_call_scripts.md)
- [Completed call record](live_calls_phase1.csv)
- [Final report](../docs/KAVACH_Phase1_Report.pdf), Annexure F
- [Frozen-build checks](../docs/TEST_RESULTS_ADDENDUM.md)

These four scripted cases are a demonstration on the test phone. The earlier three-call demonstration remains separately documented. Host synthetic tests, TTS experiments and the podcast battery workload retain their original methods.

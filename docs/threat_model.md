# Threat model and privacy

## Assets
The user's money and credentials (OTP, PIN, card data), the conversation content, and trust in the warnings.

## Adversaries and what KAVACH does
| Adversary move | Effect on KAVACH | Mitigation |
|---|---|---|
| Avoid trigger words, paraphrase | lexicon misses | classifier n-grams + cue combinations + Llama reasoning; actions (link/APK/screen share) are caught on other channels |
| Talk calmly after the threat to "reset" the guard | — | levels are sticky within a session; evidence decays over minutes, not seconds |
| Mention "never share OTP" to look genuine | safety advice lowers the score | blocks only the hook+action rule; isolation+authority, the authority+identity+threat triad and cross-channel rules still fire |
| Prompt injection in speech ("ignore your instructions") | Llama sees it in the transcript | Llama can only *raise* the level; the validator enforces the schema; rule STOPs cannot be lowered |
| Background noise / bad audio | ASR errors | ASR-noisy training, fail-safe rules, Guardian placement guidance |
| Malicious APK disables KAVACH | — | out of scope for v0; Phase 2: device-admin-free self-check and family alert on uninstall |

## False alarms
False STOPs cost trust, so they carry 5× weight in threshold tuning. Measured false-STOP rate:
0 % on every evaluation set. False CAUTIONs are higher and are the target for the Llama layer.

## Privacy by design
* Neither app declares `INTERNET`; models and all processing are on the device.
* Audio is processed in 100 ms buffers and never written to storage; transcripts live in memory for the session.
* Messages are read from notifications (no `READ_SMS`); nothing is stored except the last 50 alert summaries in memory.
* The bench log stores only numbers (levels, timings, clocks, battery), never text.

## Platform policy (production path)
The prototype is side-loaded and uses notification access, usage access, `QUERY_ALL_PACKAGES`
and a special-use foreground service. A Play-compliant version would use a narrow package
`<queries>` list (payment and remote-access apps only), the SMS Retriever / default-SMS-app
role instead of notification reading, and integration as an OEM or pre-loaded dialer feature
for call audio, as Google does on Pixel.

// KAVACH Phase 1 report: front matter + chapters 1-2
const M = '/home/claude/kavach/docs/media/';
module.exports = {
abstract: [
  "Phone fraud in India has moved from single messages to live, orchestrated conversations. In a typical “digital arrest” call the fraudster claims to be from a police cyber-crime branch, says that a parcel booked on the victim’s Aadhaar contains drugs, forbids the victim to tell anyone, and finally asks for a transfer to a so-called RBI safe account. Every step is spoken, and the payment is made while the call is still running. Protection that reads only SMS or links arrives after the money has gone, and protection that uploads call audio to a server creates a new privacy risk of its own.",
  "This report presents KAVACH (Sanskrit for armour), a single Android application that listens to a call the user has put on speaker, recognises Hindi, Hinglish and English speech on the phone itself, and warns the user during the call, with no internet permission at all. Audio is cut into sentences by a voice-activity detector and recognised by two int8 CTC speech models: AI4Bharat IndicConformer for Hindi and NVIDIA Parakeet 110M for English. An Auto mode runs both models on every sentence and keeps the transcript whose words are real words of its language, so a caller may switch languages mid-call. A detector that takes about 4 ms per sentence, made of a cue lexicon, a hashed linear classifier and a multi-channel kill chain, raises CAUTION or STOP. A Llama 3.2 1B model, fine-tuned with LoRA on KAVACH’s scam playbook, quantised to 4 bits and run with ExecuTorch on the Arm CPU, then explains the alert in the caller’s language. Two further on-device pipelines, Shield (messages, links, apps, screen sharing and payments) and the optional Network Watch app (app traffic, DNS look-ups and risky devices on the home Wi-Fi), feed the same kill chain.",
  "On a mid-range Xiaomi phone with a Snapdragon 7s Gen 2 processor, both speech models together run about five times faster than real time, an hour of listening costs about 7.6 % of the battery with no rise in temperature, and Llama decodes 11.5 to 11.9 tokens per second. In three real cellular calls from an unknown number, a Hindi digital-arrest scam and an English fake-KYC scam reached STOP during the call and a genuine Hindi bank fraud-check call raised no warning. On host evaluation the detector reached 90.6 % STOP on 1,000 unseen synthetic calls with 0 % false STOPs on every evaluated set. Replacing the small streaming Hindi model with IndicConformer raised telephone-band Hindi detection from 0 % to 58 % STOP. LoRA fine-tuning raised Llama’s valid-verdict rate from 71 % to 100 % and its scam accuracy from 38 % to 89 %.",
  "Tools used: Kotlin and the Android SDK, Python 3, PyTorch, Hugging Face Transformers and PEFT, ExecuTorch 1.4 with XNNPACK and Arm KleidiAI, sherpa-onnx, Silero VAD, Piper TTS, scikit-learn, GitHub Actions, Google Colab (T4 GPU)."
],
abbreviations: [
  ["ASR","Automatic Speech Recognition"],["CTC","Connectionist Temporal Classification"],["VAD","Voice Activity Detection"],
  ["LLM","Large Language Model"],["LoRA","Low-Rank Adaptation"],["RTF","Real-Time Factor (processing time / audio time)"],
  ["TTFT","Time To First Token"],["KV cache","Key-Value cache of a transformer"],["int8 / 4-bit","8-bit / 4-bit integer quantisation"],
  ["8da4w","8-bit dynamic activations, 4-bit weights"],["NPU","Neural Processing Unit"],["SoC","System on Chip"],
  ["OTP","One-Time Password"],["KYC","Know Your Customer"],["UPI","Unified Payments Interface"],["RBI","Reserve Bank of India"],
  ["I4C","Indian Cyber Crime Coordination Centre"],["NCRP","National Cyber Crime Reporting Portal"],["SNR","Signal-to-Noise Ratio"],
  ["WER","Word Error Rate"],["DGA","Domain Generation Algorithm"],["ADB","Android Debug Bridge"],["CI","Continuous Integration"],
  ["JSON","JavaScript Object Notation"],["PS3","Problem Statement 3 of the challenge"]
],
chapters: [
// ---------------------------------------------------------------- CHAPTER 1
{ title: "INTRODUCTION", blocks: [
 {h2:"Introduction"},
 {p:"This chapter introduces the problem of live phone scams in India, explains why the protection must run on the phone itself, and states the objectives and target specifications of KAVACH. It ends with an outline of how the rest of the report is organised."},
 {h2:"Background: Phone Scams in India"},
 {p:"Cyber fraud has become one of the largest sources of financial loss for ordinary Indian families. Data shared by the Ministry of Home Affairs in Parliament reports that Indians lost about ₹22,845 crore to cyber fraud in 2024, a 206 % rise over the previous year [1]. “Digital arrest” scams grew especially fast on the National Cyber Crime Reporting Portal: incidents rose from 39,925 in 2022 to 1,23,672 in 2024, and reported losses grew from about ₹91 crore to ₹1,935 crore [2]."},
 {p:"What makes these scams hard to stop is that they are conversations, not single messages. The fraudster speaks with authority, creates fear, isolates the victim from family and keeps the call running until the money has been sent. Warnings that appear only after an SMS arrives, or banks that flag a transaction after it is made, are too late for a victim who has already been persuaded on the phone."},
 {h2:"Anatomy of a Phone Scam"},
 {p:"Across the twelve scam types in KAVACH’s playbook (Table 1.1), the same sequence of stages appears again and again. KAVACH models this sequence as a kill chain, borrowing the idea from network intrusion analysis [3]: a single suspicious word is weak evidence, but a sequence of stages is strong evidence."},
 {bullets:[
  "Contact: an unknown number calls, often claiming to be police, CBI, customs, a bank, a courier company, TRAI or a utility.",
  "Hook: an authority claim combined with an identity hook or threat (“your Aadhaar is linked to a money-laundering case”, “your SIM will be blocked in two hours”).",
  "Isolation: the victim is told not to disconnect, not to tell anyone and to stay on a video call.",
  "Action: the victim is asked for an OTP, to open a link, to install a remote-access app or to share the screen.",
  "Payment: the victim is told to transfer money, approve a UPI collect request or move savings to a “safe account”."]},
 {table:{caption:"Scam types in the KAVACH playbook", widths:[2600,6400], header:["Identifier","Scam type"], rows:[
  ["digital_arrest","Digital arrest / fake police"],["courier_parcel","Courier / parcel with illegal items"],["kyc_bank","Fake KYC / bank account block"],
  ["upi_collect_refund","UPI collect request / fake refund"],["investment_trading","Investment / trading group scam"],["job_task","Part-time job / task scam"],
  ["family_emergency","Family emergency / “your son is in trouble”"],["electricity_bill","Electricity disconnection scam"],["sim_telecom","TRAI / SIM disconnection scam"],
  ["lottery_prize","Lottery / prize / KBC scam"],["remote_support","Fake customer care / remote-access takeover"],["loan_app","Instant loan app / harassment"]]}},
 {h2:"Why the Protection Must Run on the Phone"},
 {p:"A system that hears phone calls hears some of the most private moments of a person’s life. Sending that audio to a server, even for a good purpose, creates a store of sensitive recordings that can leak, be misused or be demanded by third parties. It also fails when the network is weak, which is common exactly where many vulnerable users live. KAVACH therefore follows a strict on-device design: the main application does not even request Android’s INTERNET permission, so it cannot send anything off the phone, and audio is processed in memory and never written to storage."},
 {p:"Running everything on a phone is only practical because modern Arm processors can now run compact neural networks in real time. The Arm Bharat AI-SoC Challenge asks exactly this question: what useful AI can run on the Arm system-on-chip in a device people already own? KAVACH answers it for Problem Statement 3, Cybersecurity and Digital Safety, by combining speech recognition, a fast detector and a small large language model on one mid-range phone."},
 {h2:"Problem Statement"},
 {p:"Problem Statement 3 of the challenge asks for on-device AI that protects users against scams, phishing and local network threats and explains the risk in plain language. KAVACH addresses it with three on-device pipelines that feed one decision engine:"},
 {bullets:[
  "Call listening: hears a scam call on speaker, in Hindi, Hinglish or English, and warns during the call.",
  "Shield: checks messages, links, installed apps, screen sharing and payment-app use, and knows when a call with an unknown number is in progress.",
  "Network Watch: watches which app talks to which website, flags suspicious look-ups and checks the home Wi-Fi for risky devices."]},
 {h2:"Objectives"},
 {pb:[["Main objective: ",true],["to build an Android application that detects a scam call while it is happening, on one ordinary phone, in Hindi and English, explains the trick with an on-device Llama model, and never sends any data off the phone.",false]]},
 {p:"Secondary objectives:", bold:true},
 {bullets:[
  "Recognise Hindi, Hinglish and English speech on the phone, including callers who switch language mid-call.",
  "Raise CAUTION or STOP early, before the payment step, with zero false STOPs on genuine calls as a hard target.",
  "Explain every alert in the caller’s language with a fine-tuned Llama 3.2 1B model running with ExecuTorch on the Arm CPU.",
  "Join evidence across channels: a suspicious call followed by a scam SMS, a dangerous link or a remote-access app must escalate.",
  "Measure speed, memory, battery use and temperature on a real phone, and publish every result and the code that produced it."]},
 {h2:"Target Specifications"},
 {table:{caption:"Target specifications of KAVACH", widths:[3200,5800], header:["Parameter","Target"], rows:[
  ["Platform","One Android phone (Android 10 or later), mid-range Arm SoC"],
  ["Network use","None: no INTERNET permission in the main app"],
  ["Audio storage","None: audio kept in memory only for the length of a sentence"],
  ["Languages","Hindi, Hinglish and English, chosen automatically per sentence"],
  ["Detector latency","Under 10 ms per sentence on the phone"],
  ["Speech speed","Faster than real time with both models running"],
  ["Time to STOP","Median under 30 s from the first scam cue"],
  ["False STOP rate","At most 2 % on genuine calls (target 0 %)"],
  ["Explanation","Llama 3.2 1B, 4-bit, on the Arm CPU; never delays the warning"],
  ["Battery","Under 10 % per hour of listening"],
  ["User action","Call 1930 (national cyber-crime helpline) offered on every STOP"]]}},
 {h2:"Organisation of the Report"},
 {bullets:[
  "Chapter 2 reviews the background technologies: on-device speech recognition, small language models and their quantisation, LoRA fine-tuning, text classification and Android’s rules for call audio.",
  "Chapter 3 describes the methodology and system design: architecture, one-phone call listening, the speech front-end with Auto language, the detector, the Llama advisor, Shield, Network Watch and the privacy design.",
  "Chapter 4 describes the implementation: software structure, the Python reference and Kotlin port, continuous integration, data generation, model training and deployment.",
  "Chapter 5 presents the results: host evaluations, speech experiments, the phone benchmark, replayed and real phone calls, and Llama before and after fine-tuning.",
  "Chapter 6 discusses limitations, ethics and the threat model, and Chapter 7 concludes and sets out the Phase 2 plan.",
  "The annexures give installation steps, the repository structure, the Llama output contract, the training configuration and the benchmark fields."]}
]},
// ---------------------------------------------------------------- CHAPTER 2
{ title: "BACKGROUND AND RELATED WORK", blocks: [
 {h2:"Introduction"},
 {p:"KAVACH combines several fields: on-device speech recognition, small large language models and their compression, parameter-efficient fine-tuning, lightweight text classification, and the security rules of the Android operating system. This chapter summarises the background needed to follow the design, and explains where KAVACH differs from existing approaches."},
 {h2:"On-Device Speech Recognition"},
 {p:"Modern speech recognisers are neural networks that turn audio features into text. The Conformer architecture [4] combines self-attention, which captures long-range context, with convolution, which captures local patterns in the sound, and is the basis of most current recognisers. Fast Conformer [5] reduces the cost of attention so that long audio can be processed efficiently, which matters on a phone."},
 {p:"Connectionist Temporal Classification (CTC) [6] is a training and decoding method that lets a network output characters or word pieces frame by frame without needing an exact alignment between audio and text. A CTC model is decoded greedily by picking the most likely symbol at each frame and removing repeats and blanks, which is very cheap compared with beam search. KAVACH uses two CTC models:"},
 {bullets:[
  "AI4Bharat IndicConformer for Hindi: a Conformer model trained on large Indian-language speech corpora such as IndicVoices [7]. It writes Devanagari and also handles English words spoken inside Hindi sentences.",
  "NVIDIA Parakeet TDT-CTC 110M for English [8]: a Fast Conformer model with 110 million parameters that produces punctuated, cased English."]},
 {p:"Both models are run through sherpa-onnx [9], an open-source runtime that executes int8-quantised ONNX models on Arm CPUs, together with the Silero voice-activity detector [10], which decides which parts of the audio contain speech."},
 {h2:"Small Language Models on Phones"},
 {p:"Large language models such as the Llama 3 family [11] can read a transcript and explain, in natural language, what kind of scam is happening and what the user should do. Llama 3.2 1B has about 1.2 billion parameters; stored at 16 bits per weight it needs about 2.5 GB, which is too much for comfortable use on a phone. Quantisation stores weights with fewer bits. Groupwise 4-bit quantisation keeps one scale for every small group of weights (32 in KAVACH), which preserves accuracy far better than one scale per row. Methods such as SpinQuant [12] go further by rotating weights before quantisation."},
 {p:"ExecuTorch [13] is PyTorch’s runtime for running exported models on phones and embedded devices. Its XNNPACK backend executes the model on the CPU, and on Arm processors it uses Arm KleidiAI micro-kernels [14], which are hand-optimised routines for the matrix multiplications at the heart of a transformer, including 4-bit weight formats. KAVACH exports its fine-tuned Llama to a single .pte file that ExecuTorch loads on the phone."},
 {h2:"Parameter-Efficient Fine-Tuning with LoRA"},
 {p:"A base language model does not know KAVACH’s output format, the twelve scam types or the rule that every quoted piece of evidence must appear word for word in the transcript. Retraining all 1.2 billion parameters would be slow and expensive. Low-Rank Adaptation (LoRA) [15] freezes the original weights and learns two small matrices for each adapted layer whose product is added to the frozen weight. With rank r = 16 on all attention and feed-forward projections, only about 11 million parameters (0.9 % of the model) are trained, which fits on a free Colab T4 GPU. After training, the LoRA matrices are merged into the base weights, so the exported model has no extra cost at run time."},
 {h2:"Lightweight Text Classification"},
 {p:"Not every decision needs a large model. A linear classifier over word and character features can score a sentence in microseconds. Feature hashing [16] maps every feature to one of a fixed number of buckets with a hash function, so the model has a fixed, small size and needs no vocabulary file. KAVACH uses hashed linear classifiers for call windows, SMS text and URLs, and keeps the always-on path free of neural networks so that it is fast, predictable and easy to port exactly between Python and Kotlin."},
 {h2:"Android Rules for Call Audio"},
 {p:"Since Android 10, ordinary apps cannot record the audio of a phone call: during a call Android gives the microphone to the call and feeds silence to every other app that records. Android documents one exception for apps that need to keep hearing the user during a call: an app with an enabled accessibility service may continue to capture the microphone [17]. KAVACH uses this exception with a deliberately minimal service that reads no screen content and performs no actions. Because the microphone hears the room, the call must be on speaker for KAVACH to hear the caller."},
 {h2:"Existing Approaches and the Gap"},
 {p:"Existing protections fall into a few broad categories. Table 2.1 compares them qualitatively with KAVACH; it describes categories of solution, not specific products."},
 {table:{caption:"Categories of scam protection compared with KAVACH", widths:[2600,2200,2100,2100], header:["Approach","Sees the conversation?","Works offline?","Explains in Hindi?"], rows:[
  ["Number-based spam labels","No: only the caller’s number","Partly","No"],
  ["SMS and link filters","No: only text that arrives","Often","Rarely"],
  ["Cloud call screening","Yes, but audio leaves the phone","No","Varies"],
  ["Bank-side transaction checks","No: only after payment","No","No"],
  ["KAVACH","Yes, on the phone, during the call","Yes, fully","Yes, Hindi and English"]]}},
 {p:"The gap KAVACH fills is the combination: hearing the conversation itself, during the call, in Indian languages, with an explanation, on a phone people already own, with no audio or text leaving the device."},
 {h2:"Summary"},
 {p:"Compact CTC speech models, 4-bit language models running on Arm CPUs through ExecuTorch and KleidiAI, LoRA fine-tuning and hashed linear classifiers together make it possible to understand and explain a phone call on the phone itself. Android’s accessibility audio exception makes it possible to do so on the same phone that receives the call. The next chapter shows how KAVACH puts these pieces together."}
]}
]};

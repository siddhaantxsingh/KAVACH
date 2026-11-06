"""Utterance pools for synthetic call transcripts (training data only).

Three renderings per pool:
  en   - Indian English
  hing - romanised Hinglish (what an English/Hinglish ASR or a chat log yields)
  hi   - Devanagari Hindi, English loan words written in Devanagari (what a
         Devanagari Hindi ASR model outputs for code-switched speech)

Every pool is split by item index into a TRAIN part and a held-out TEST part
(see generate_calls.py), so the test split only contains phrasings the models
never saw. Scripts are paraphrased from public advisories (I4C, RBI, banks,
cybercrime.gov.in) and news descriptions of scam calls; no real victim audio
or transcript is used.

Slots: {officer} {city} {agency} {bank} {amount} {hours} {courier} {app}
       {relation} {company} {name} {item}
"""

SLOTS = {
    "officer": ["Inspector Vikram Singh", "Sub Inspector Rathore", "DCP Sharma", "officer Pradeep Kumar", "Inspector Rajesh Yadav", "SI Mehta", "Inspector Khan"],
    "city": ["Mumbai", "Delhi", "Lucknow", "Kolkata", "Bengaluru", "Hyderabad", "Jaipur", "Patna", "Ranchi", "Pune"],
    "agency": ["cyber crime branch", "CBI", "narcotics department", "crime branch", "customs department", "enforcement directorate", "cyber police"],
    "bank": ["SBI", "HDFC", "ICICI", "Axis bank", "PNB", "Bank of Baroda", "Canara bank", "Kotak"],
    "amount": ["50,000", "1 lakh", "2,50,000", "4 lakh", "15,000", "9,999", "75,000", "3 lakh"],
    "hours": ["2", "two", "24", "3"],
    "courier": ["FedEx", "DHL", "Blue Dart", "DTDC", "India Post"],
    "app": ["AnyDesk", "TeamViewer QuickSupport", "Secure KYC app", "RustDesk", "support app"],
    "otp": ["four eight two nine one three", "4 8 2 9 1 3", "482913", "seven seven one zero", "5 0 3 8", "nine one six two four eight", "306127", "two five five eight"],
    "acct": ["3 2 1 0 9 8 7 6 5 4", "five six seven eight nine zero one two", "30219876543", "1 1 0 0 4 5 6 7"],
    "relation": ["beta", "son", "grandson", "nephew", "daughter"],
    "company": ["Amazon", "Flipkart", "Meesho", "Myntra"],
    "name": ["Ramesh ji", "Sunita ji", "Mr Verma", "aunty", "uncle ji", "madam", "sir"],
    "item": ["5 fake passports and 200 grams MDMA", "drugs and 3 credit cards", "illegal documents", "4 kg ganja and foreign currency"],
}

# Hindi renderings of slot values (used by the 'hi' language)
SLOTS_HI = {
    "officer": ["इंस्पेक्टर विक्रम सिंह", "सब इंस्पेक्टर राठौर", "डीसीपी शर्मा", "ऑफिसर प्रदीप कुमार", "इंस्पेक्टर राजेश यादव", "एसआई मेहता"],
    "city": ["मुंबई", "दिल्ली", "लखनऊ", "कोलकाता", "बेंगलुरु", "हैदराबाद", "जयपुर", "पटना", "रांची", "पुणे"],
    "agency": ["साइबर क्राइम ब्रांच", "सीबीआई", "नारकोटिक्स विभाग", "क्राइम ब्रांच", "कस्टम विभाग", "साइबर पुलिस"],
    "bank": ["एसबीआई", "एचडीएफसी", "आईसीआईसीआई", "एक्सिस बैंक", "पीएनबी", "बैंक ऑफ बड़ौदा", "केनरा बैंक"],
    "amount": ["पचास हजार", "एक लाख", "ढाई लाख", "चार लाख", "पंद्रह हजार", "पचहत्तर हजार", "तीन लाख"],
    "hours": ["दो", "चौबीस", "तीन"],
    "courier": ["फेडएक्स", "डीएचएल", "ब्लू डार्ट", "डीटीडीसी", "इंडिया पोस्ट"],
    "app": ["एनीडेस्क", "टीमव्यूअर क्विक सपोर्ट", "सिक्योर केवाईसी ऐप", "सपोर्ट ऐप"],
    "otp": ["चार आठ दो नौ एक तीन", "482913", "सात सात एक शून्य", "5038", "नौ एक छह दो चार आठ"],
    "acct": ["तीन दो एक शून्य नौ आठ सात छह", "30219876543"],
    "relation": ["बेटा", "पोता", "भतीजा", "बेटी"],
    "company": ["अमेज़न", "फ्लिपकार्ट", "मीशो"],
    "name": ["रमेश जी", "सुनीता जी", "वर्मा जी", "आंटी", "अंकल जी", "मैडम", "सर"],
    "item": ["पांच नकली पासपोर्ट और ड्रग्स", "ड्रग्स और तीन क्रेडिट कार्ड", "गैरकानूनी कागज़ात"],
}

P = {}  # pool name -> {"en": [...], "hing": [...], "hi": [...]}

# ----------------------------------------------------------------- shared
P["greet_caller"] = {
    "en": ["Hello, am I speaking with {name}?", "Hello, good afternoon.", "Hello, is this {name}?", "Hi, can you hear me?", "Hello, good morning {name}.", "Yes hello, one minute please."],
    "hing": ["Hello, kya main {name} se baat kar raha hoon?", "Hello namaste {name}.", "Haan hello, awaaz aa rahi hai?", "Hello ji, namaskar.", "Hello, {name} bol rahe hain?", "Hello ji, ek minute line pe rahiye."],
    "hi": ["हेलो, क्या मैं {name} से बात कर रहा हूं?", "हेलो नमस्ते {name}।", "हां हेलो, आवाज़ आ रही है?", "हेलो जी, नमस्कार।", "हेलो, {name} बोल रहे हैं?", "हेलो जी, एक मिनट लाइन पर रहिए।"],
}
P["victim_ack"] = {
    "en": ["Yes, speaking.", "Yes, tell me.", "Who is this?", "Okay.", "Yes yes.", "Hmm.", "Sorry, who is calling?", "Okay, go on."],
    "hing": ["Haan ji bol raha hoon.", "Haan boliye.", "Kaun bol raha hai?", "Achha.", "Haan haan.", "Ji.", "Aap kaun?", "Theek hai, boliye."],
    "hi": ["हां जी बोल रहा हूं।", "हां बोलिए।", "कौन बोल रहा है?", "अच्छा।", "हां हां।", "जी।", "आप कौन?", "ठीक है, बोलिए।"],
}
P["victim_worried"] = {
    "en": ["What? I have not done anything.", "Sir I don't know anything about this.", "Oh god, what should I do?", "Is this true? I am scared.", "Please sir, I am a retired person.", "But how is that possible?", "I never booked any parcel."],
    "hing": ["Kya? Maine kuch nahi kiya.", "Sir mujhe iske baare mein kuch nahi pata.", "Hey bhagwan, ab main kya karoon?", "Sach mein? Mujhe dar lag raha hai.", "Sir please, main retired aadmi hoon.", "Par aisa kaise ho sakta hai?", "Maine koi parcel book nahi kiya."],
    "hi": ["क्या? मैंने कुछ नहीं किया।", "सर मुझे इसके बारे में कुछ नहीं पता।", "हे भगवान, अब मैं क्या करूं?", "सच में? मुझे डर लग रहा है।", "सर प्लीज़, मैं रिटायर्ड आदमी हूं।", "पर ऐसा कैसे हो सकता है?", "मैंने कोई पार्सल बुक नहीं किया।"],
}
P["victim_comply"] = {
    "en": ["Okay sir, I will do what you say.", "Okay, tell me what to do.", "Fine, I am listening.", "Yes sir, I understand.", "Alright, one minute.", "Okay I am opening it."],
    "hing": ["Theek hai sir, jaisa aap bolo.", "Achha batao kya karna hai.", "Theek hai, main sun raha hoon.", "Ji sir, samajh gaya.", "Achha ek minute.", "Haan khol raha hoon."],
    "hi": ["ठीक है सर, जैसा आप बोलो।", "अच्छा बताइए क्या करना है।", "ठीक है, मैं सुन रहा हूं।", "जी सर, समझ गया।", "अच्छा एक मिनट।", "हां खोल रहा हूं।"],
}

# victim-side lines that the Guardian mic hears best (the victim is next to it, the caller is
# on the speaker). Scam-side and benign-side versions overlap on purpose (reading an OTP to a
# delivery agent, sending money to a relative) so the models cannot treat them as scam alone.
P["victim_otp_read"] = {
    "en": ["My OTP is {otp}.", "Okay the OTP is {otp}.", "I got the code, it is {otp}.", "Yes the OTP came, {otp}.", "The number on the message is {otp}.",
           "Wait, the OTP is {otp}, did you get it?", "It says {otp}, is that the OTP?", "The code is {otp}.", "I am reading the OTP, {otp}."],
    "hing": ["Mera OTP hai {otp}.", "OTP aaya hai, {otp}.", "Haan code aa gaya, {otp} hai.", "OTP {otp} hai sir.", "Message mein {otp} likha hai.",
             "Ruko OTP bata raha hoon, {otp}.", "Sir OTP aaya {otp}, mil gaya?", "Code hai {otp}.", "OTP padh raha hoon, {otp}."],
    "hi": ["मेरा ओटीपी है {otp}।", "ओटीपी आया है, {otp}।", "हां कोड आ गया, {otp} है।", "ओटीपी {otp} है सर।", "मैसेज में {otp} लिखा है।",
           "रुकिए ओटीपी बता रहा हूं, {otp}।", "सर ओटीपी आया {otp}, मिल गया?", "कोड है {otp}।", "ओटीपी पढ़ रहा हूं, {otp}।"],
}
P["victim_paying"] = {
    "en": ["Okay I am transferring the money now.", "I am sending the amount, one minute.", "I have transferred it, please check.", "Which account should I transfer to?",
           "How much should I send?", "I am doing the payment now.", "Okay I will transfer {amount} now.", "Sending it now sir, please don't do anything.", "I have sent the money."],
    "hing": ["Main transfer kar raha hoon.", "Paise bhej raha hoon, ek minute.", "Transfer kar diya sir, check kijiye.", "Kis account mein bhejna hai?",
             "Kitna bhejna hai sir?", "Payment kar raha hoon abhi.", "Theek hai {amount} transfer karta hoon.", "Bhej raha hoon sir, kuch mat karna.", "Maine paise bhej diye."],
    "hi": ["मैं ट्रांसफर कर रहा हूं।", "पैसे भेज रहा हूं, एक मिनट।", "ट्रांसफर कर दिया सर, देख लीजिए।", "किस खाते में भेजना है?",
           "कितना भेजना है सर?", "पेमेंट कर रहा हूं अभी।", "ठीक है {amount} ट्रांसफर करता हूं।", "भेज रहा हूं सर, कुछ मत करना।", "मैंने पैसे भेज दिए।"],
}
P["victim_fear"] = {
    "en": ["Please don't arrest me.", "Sir please, I will cooperate, don't arrest me.", "I am not a criminal, please believe me.", "Please don't send the police to my house.",
           "I will do anything, just don't put me in jail.", "Please sir, my family will be ruined.", "I promise I didn't do it, please help me.", "Don't file a case against me please.", "I am very scared, what will happen to me?"],
    "hing": ["Mujhe arrest mat karo.", "Sir please main cooperate karunga, arrest mat karna.", "Main criminal nahi hoon, yakeen kijiye.", "Please police ghar mat bhejna.",
             "Main kuch bhi karunga, bas jail mat bhejo.", "Sir please, mera parivaar barbaad ho jayega.", "Kasam se maine nahi kiya, meri madad kijiye.", "Mere upar case mat karo please.", "Mujhe bahut dar lag raha hai, mera kya hoga?"],
    "hi": ["मुझे गिरफ्तार मत करो।", "सर प्लीज़ मैं सहयोग करूंगा, गिरफ्तार मत करना।", "मैं अपराधी नहीं हूं, यकीन कीजिए।", "प्लीज़ पुलिस घर मत भेजना।",
           "मैं कुछ भी करूंगा, बस जेल मत भेजो।", "सर प्लीज़, मेरा परिवार बर्बाद हो जाएगा।", "कसम से मैंने नहीं किया, मेरी मदद कीजिए।", "मेरे ऊपर केस मत करो प्लीज़।", "मुझे बहुत डर लग रहा है, मेरा क्या होगा?"],
}
P["victim_account"] = {
    "en": ["My account number is {acct}.", "The account number is {acct}.", "Card number is {acct}.", "My debit card number is {acct}.", "The IFSC and account number, {acct}.", "My net banking password is ..."],
    "hing": ["Mera account number hai {acct}.", "Account number {acct} hai.", "Card number hai {acct}.", "Debit card number {acct} hai.", "IFSC aur account number, {acct}.", "Net banking password hai ..."],
    "hi": ["मेरा खाता नंबर है {acct}।", "खाता नंबर {acct} है।", "कार्ड नंबर है {acct}।", "डेबिट कार्ड नंबर {acct} है।", "आईएफएससी और खाता नंबर, {acct}।", "नेट बैंकिंग पासवर्ड है ..."],
}
P["victim_benign_otp"] = {
    "en": ["The delivery OTP is {otp}.", "Okay the OTP is {otp}.", "Yes the code is {otp}.", "It says {otp} in the app.", "OTP is {otp}, please leave it at the gate.", "Code is {otp}."],
    "hing": ["Delivery OTP hai {otp}.", "OTP {otp} hai.", "Haan code hai {otp}.", "App mein {otp} likha hai.", "OTP {otp} hai, gate pe rakh dena.", "Code hai {otp}."],
    "hi": ["डिलीवरी ओटीपी है {otp}।", "ओटीपी {otp} है।", "हां कोड है {otp}।", "ऐप में {otp} लिखा है।", "ओटीपी {otp} है, गेट पर रख देना।", "कोड है {otp}।"],
}
P["victim_benign_pay"] = {
    "en": ["Okay beta, I am transferring the money now.", "I will send it on UPI in five minutes.", "I have sent the fees, check your account.", "How much do you need for the books?", "Sending it now, tell me when you get it.", "I transferred it to your account."],
    "hing": ["Theek hai beta, abhi transfer kar raha hoon.", "Paanch minute mein UPI kar deta hoon.", "Fees bhej di, account check karo.", "Kitaabon ke liye kitna chahiye?", "Bhej raha hoon, mil jaye toh batana.", "Tumhare account mein transfer kar diya."],
    "hi": ["ठीक है बेटा, अभी ट्रांसफर कर रहा हूं।", "पांच मिनट में यूपीआई कर देता हूं।", "फीस भेज दी, खाता देख लो।", "किताबों के लिए कितना चाहिए?", "भेज रहा हूं, मिल जाए तो बताना।", "तुम्हारे खाते में ट्रांसफर कर दिया।"],
}

# ----------------------------------------------------------------- contact (scam)
P["c_police"] = {
    "en": ["I am {officer} from {city} {agency}.", "This call is from {city} police headquarters, {agency}.", "I am calling from the {agency}, {city}. This is an official call.", "Myself {officer}, {agency} {city}, your case file is in front of me.", "This is the {city} cyber cell, I am {officer}.", "You are speaking to {officer} of the {agency}."],
    "hing": ["Main {city} {agency} se {officer} bol raha hoon.", "Yeh call {city} police headquarter se hai, {agency}.", "Main {agency} {city} se call kar raha hoon, yeh official call hai.", "Main {officer}, {agency} {city}, aapki case file mere saamne hai.", "{city} cyber cell se bol raha hoon, main {officer}.", "Aap {agency} ke {officer} se baat kar rahe hain."],
    "hi": ["मैं {city} {agency} से {officer} बोल रहा हूं।", "यह कॉल {city} पुलिस हेडक्वार्टर से है, {agency}।", "मैं {agency} {city} से कॉल कर रहा हूं, यह ऑफिशियल कॉल है।", "मैं {officer}, {agency} {city}, आपकी केस फाइल मेरे सामने है।", "{city} साइबर सेल से बोल रहा हूं, मैं {officer}।", "आप {agency} के {officer} से बात कर रहे हैं।"],
}
P["c_courier"] = {
    "en": ["This is an automated call from {courier}. Your parcel has been held. Press 1 to speak to our executive.", "I am calling from {courier} international courier, regarding a parcel booked on your Aadhaar.", "Sir, {courier} customer service here. A parcel sent in your name to Taiwan was stopped by customs.", "Hello, {courier} logistics. Your shipment has been returned by customs.", "Madam this is {courier}, your parcel to Cambodia is stuck at {city} airport."],
    "hing": ["Yeh {courier} ki automated call hai. Aapka parcel rok diya gaya hai. Executive se baat karne ke liye 1 dabaiye.", "Main {courier} courier se bol raha hoon, aapke aadhaar par ek parcel book hua hai.", "Sir {courier} customer service se, aapke naam se Taiwan bheja gaya parcel customs ne rok liya hai.", "Hello {courier} logistics se, aapka shipment customs ne wapas kar diya hai.", "Madam {courier} se bol rahe hain, aapka parcel {city} airport par atka hai."],
    "hi": ["यह {courier} की ऑटोमेटेड कॉल है। आपका पार्सल रोक दिया गया है। एग्जीक्यूटिव से बात करने के लिए एक दबाइए।", "मैं {courier} कूरियर से बोल रहा हूं, आपके आधार पर एक पार्सल बुक हुआ है।", "सर {courier} कस्टमर सर्विस से, आपके नाम से ताइवान भेजा गया पार्सल कस्टम ने रोक लिया है।", "हेलो {courier} लॉजिस्टिक्स से, आपका शिपमेंट कस्टम ने वापस कर दिया है।"],
}
P["c_telecom"] = {
    "en": ["This call is from TRAI, the telecom regulatory authority. Your mobile number will be disconnected in {hours} hours.", "I am calling from the telecom department. Your SIM card has been used for illegal activities.", "Press 9 to know why your number is being blocked by TRAI.", "This is the DoT department, there is a complaint against your number."],
    "hing": ["Yeh call TRAI se hai. Aapka mobile number {hours} ghante mein band kar diya jayega.", "Main telecom department se bol raha hoon, aapke sim card ka illegal kaam mein use hua hai.", "TRAI dwara aapka number block kyon ho raha hai jaanne ke liye 9 dabaiye.", "DoT department se call hai, aapke number ke khilaf complaint aayi hai."],
    "hi": ["यह कॉल ट्राई से है। आपका मोबाइल नंबर {hours} घंटे में बंद कर दिया जाएगा।", "मैं टेलीकॉम विभाग से बोल रहा हूं, आपके सिम कार्ड का अवैध काम में इस्तेमाल हुआ है।", "ट्राई द्वारा आपका नंबर ब्लॉक क्यों हो रहा है जानने के लिए नौ दबाइए।", "दूरसंचार विभाग से कॉल है, आपके नंबर के खिलाफ शिकायत आई है।"],
}
P["c_bank"] = {
    "en": ["I am calling from {bank} head office, KYC department.", "Sir this is {bank} customer care, your account needs urgent verification.", "Madam I am the branch manager from {bank}, calling about your KYC.", "This is {bank} card department, regarding your debit card.", "Good morning, {bank} RBI compliance team here."],
    "hing": ["Main {bank} head office ke KYC department se bol raha hoon.", "Sir {bank} customer care se, aapke account ka urgent verification karna hai.", "Madam main {bank} ka branch manager bol raha hoon, aapke KYC ke baare mein.", "{bank} card department se call hai, aapke debit card ke baare mein.", "Namaste, {bank} RBI compliance team se bol rahe hain."],
    "hi": ["मैं {bank} हेड ऑफिस के केवाईसी विभाग से बोल रहा हूं।", "सर {bank} कस्टमर केयर से, आपके अकाउंट का अर्जेंट वेरिफिकेशन करना है।", "मैडम मैं {bank} का ब्रांच मैनेजर बोल रहा हूं, आपके केवाईसी के बारे में।", "{bank} कार्ड डिपार्टमेंट से कॉल है, आपके डेबिट कार्ड के बारे में।"],
}
P["c_electricity"] = {
    "en": ["I am calling from the electricity office. Your bill is not updated in our system.", "This is the electricity board. Your power connection will be cut tonight at 9:30.", "Sir, bijli vibhag here, your last month bill payment is not showing."],
    "hing": ["Main bijli vibhag se bol raha hoon, aapka bill system mein update nahi hai.", "Electricity board se call hai, aaj raat 9:30 baje aapki bijli kat jayegi.", "Sir bijli office se, aapka pichhle mahine ka bill payment show nahi ho raha."],
    "hi": ["मैं बिजली विभाग से बोल रहा हूं, आपका बिल सिस्टम में अपडेट नहीं है।", "बिजली दफ्तर से कॉल है, आज रात साढ़े नौ बजे आपकी बिजली कट जाएगी।", "सर बिजली ऑफिस से, आपका पिछले महीने का बिल पेमेंट नहीं दिख रहा।"],
}
P["c_support"] = {
    "en": ["Hello, this is {company} customer support, you had raised a refund request.", "I am calling from {company} refund department regarding your cancelled order.", "Sir I am from {bank} technical support, your UPI is showing an error."],
    "hing": ["Hello, {company} customer support se, aapne refund request daali thi.", "Main {company} refund department se, aapke cancel order ke baare mein.", "Sir main {bank} technical support se, aapka UPI error dikha raha hai."],
    "hi": ["हेलो, {company} कस्टमर सपोर्ट से, आपने रिफंड रिक्वेस्ट डाली थी।", "मैं {company} रिफंड डिपार्टमेंट से, आपके कैंसिल ऑर्डर के बारे में।", "सर मैं {bank} टेक्निकल सपोर्ट से, आपका यूपीआई एरर दिखा रहा है।"],
}
P["c_relative"] = {
    "en": ["Hello, is this {relation}'s father? I am calling from the police station.", "Uncle, this is your {relation}'s friend, there has been a problem.", "Papa it's me, please help me, I am in trouble."],
    "hing": ["Hello, kya aap {relation} ke papa bol rahe hain? Main thane se bol raha hoon.", "Uncle main aapke {relation} ka dost bol raha hoon, ek problem ho gayi hai.", "Papa main hoon, please meri help karo, main mushkil mein hoon."],
    "hi": ["हेलो, क्या आप {relation} के पापा बोल रहे हैं? मैं थाने से बोल रहा हूं।", "अंकल मैं आपके {relation} का दोस्त बोल रहा हूं, एक प्रॉब्लम हो गई है।", "पापा मैं हूं, प्लीज़ मेरी मदद करो, मैं मुसीबत में हूं।"],
}
P["c_investment"] = {
    "en": ["Hello, I am calling from a SEBI registered advisory, we give stock tips with guaranteed returns.", "Madam, our trading group members earned 300 percent profit this month.", "Sir I am from the IPO allotment desk, you are eligible for a special institutional account."],
    "hing": ["Hello, main SEBI registered advisory se bol raha hoon, hum guaranteed return wale stock tips dete hain.", "Madam hamare trading group ke members ne is mahine 300 percent profit kamaya.", "Sir main IPO allotment desk se, aap special institutional account ke liye eligible hain."],
    "hi": ["हेलो, मैं सेबी रजिस्टर्ड एडवाइजरी से बोल रहा हूं, हम गारंटीड रिटर्न वाले स्टॉक टिप्स देते हैं।", "मैडम हमारे ट्रेडिंग ग्रुप के मेंबर्स ने इस महीने तीन सौ परसेंट प्रॉफिट कमाया।", "सर मैं आईपीओ अलॉटमेंट डेस्क से, आप स्पेशल अकाउंट के लिए एलिजिबल हैं।"],
}
P["c_job"] = {
    "en": ["Hi, I am HR from a digital marketing company, we have a part time job, work from home.", "Hello, you can earn 3000 to 5000 daily by just liking YouTube videos.", "We are hiring for hotel rating tasks, very simple work from mobile."],
    "hing": ["Hi main ek digital marketing company se HR bol rahi hoon, hamare paas part time job hai, work from home.", "Hello, aap sirf YouTube videos like karke roz 3000 se 5000 kama sakte hain.", "Hum hotel rating task ke liye hiring kar rahe hain, mobile se bahut simple kaam."],
    "hi": ["हाय मैं एक डिजिटल मार्केटिंग कंपनी से एचआर बोल रही हूं, हमारे पास पार्ट टाइम जॉब है, वर्क फ्रॉम होम।", "हेलो, आप सिर्फ यूट्यूब वीडियो लाइक करके रोज़ तीन से पांच हज़ार कमा सकते हैं।", "हम होटल रेटिंग टास्क के लिए हायरिंग कर रहे हैं, मोबाइल से बहुत आसान काम।"],
}
P["c_lottery"] = {
    "en": ["Congratulations! You have won 25 lakh rupees in the KBC lucky draw.", "Sir your number has been selected in the Jio lucky draw, you won a car.", "This is the lottery department, you have won a prize of {amount}."],
    "hing": ["Badhai ho! Aapne KBC lucky draw mein 25 lakh rupaye jeete hain.", "Sir aapka number Jio lucky draw mein select hua hai, aapne car jeeti hai.", "Lottery department se call hai, aapne {amount} ka inaam jeeta hai."],
    "hi": ["बधाई हो! आपने केबीसी लकी ड्रा में पच्चीस लाख रुपये जीते हैं।", "सर आपका नंबर जियो लकी ड्रा में सेलेक्ट हुआ है, आपने कार जीती है।", "लॉटरी विभाग से कॉल है, आपने {amount} का इनाम जीता है।"],
}

# ----------------------------------------------------------------- hook (scam)
P["h_identity"] = {
    "en": ["Your Aadhaar card has been linked to a money laundering case.", "A parcel booked on your Aadhaar contains {item}.", "Your name has come up in a money laundering case of 68 crore rupees.", "Your Aadhaar is being used to open bank accounts for illegal transactions.", "Seventeen complaints are registered against a SIM issued on your Aadhaar.", "We have found suspicious transactions linked to your PAN card."],
    "hing": ["Aapka aadhaar card ek money laundering case se linked hai.", "Aapke aadhaar par book hue parcel mein {item} mila hai.", "Aapka naam 68 crore ke money laundering case mein aaya hai.", "Aapke aadhaar ka use karke illegal transaction ke liye bank account khole gaye hain.", "Aapke aadhaar par issue hui sim ke against 17 complaint darj hain.", "Aapke PAN card se jude suspicious transaction mile hain."],
    "hi": ["आपका आधार कार्ड एक मनी लॉन्ड्रिंग केस से लिंक है।", "आपके आधार पर बुक हुए पार्सल में {item} मिला है।", "आपका नाम अड़सठ करोड़ के मनी लॉन्ड्रिंग केस में आया है।", "आपके आधार का इस्तेमाल करके अवैध लेनदेन के लिए बैंक अकाउंट खोले गए हैं।", "आपके आधार पर जारी सिम के खिलाफ सत्रह शिकायतें दर्ज हैं।", "आपके पैन कार्ड से जुड़े संदिग्ध लेनदेन मिले हैं।"],
}
P["h_threat_police"] = {
    "en": ["An arrest warrant has been issued in your name.", "If you do not cooperate you will be arrested within {hours} hours.", "This is a non bailable offence, you will go to jail.", "Your bank accounts will be frozen today itself.", "You are now under digital arrest until the investigation is complete.", "The court has ordered your immediate arrest."],
    "hing": ["Aapke naam se arrest warrant nikla hai.", "Agar aapne cooperate nahi kiya to {hours} ghante mein aapko giraftar kar liya jayega.", "Yeh non bailable offence hai, aapko jail jaana padega.", "Aaj hi aapke saare bank account freeze kar diye jayenge.", "Investigation poori hone tak aap digital arrest mein hain.", "Court ne aapki turant giraftari ka order diya hai."],
    "hi": ["आपके नाम से अरेस्ट वारंट निकला है।", "अगर आपने सहयोग नहीं किया तो {hours} घंटे में आपको गिरफ्तार कर लिया जाएगा।", "यह गैर जमानती अपराध है, आपको जेल जाना पड़ेगा।", "आज ही आपके सारे बैंक अकाउंट फ्रीज़ कर दिए जाएंगे।", "जांच पूरी होने तक आप डिजिटल अरेस्ट में हैं।", "कोर्ट ने आपकी तुरंत गिरफ्तारी का ऑर्डर दिया है।"],
}
P["h_threat_bank"] = {
    "en": ["Your account will be blocked within {hours} hours if KYC is not updated.", "Your debit card has been suspended due to incomplete KYC.", "Your net banking will be stopped today.", "Your account is showing suspicious activity and will be frozen."],
    "hing": ["Agar KYC update nahi hua to {hours} ghante mein aapka account block ho jayega.", "KYC poora na hone ki wajah se aapka debit card suspend ho gaya hai.", "Aaj aapki net banking band ho jayegi.", "Aapke account mein suspicious activity dikh rahi hai, account freeze ho jayega."],
    "hi": ["अगर केवाईसी अपडेट नहीं हुआ तो {hours} घंटे में आपका अकाउंट ब्लॉक हो जाएगा।", "केवाईसी पूरा न होने की वजह से आपका डेबिट कार्ड सस्पेंड हो गया है।", "आज आपकी नेट बैंकिंग बंद हो जाएगी।", "आपके अकाउंट में संदिग्ध गतिविधि दिख रही है, अकाउंट फ्रीज़ हो जाएगा।"],
}
P["h_threat_power"] = {
    "en": ["If you do not update it now your electricity will be disconnected tonight.", "The officer will come and cut your connection in two hours."],
    "hing": ["Agar abhi update nahi kiya to aaj raat bijli kat jayegi.", "Do ghante mein officer aake aapka connection kaat dega."],
    "hi": ["अगर अभी अपडेट नहीं किया तो आज रात बिजली कट जाएगी।", "दो घंटे में ऑफिसर आकर आपका कनेक्शन काट देगा।"],
}
P["h_family"] = {
    "en": ["Your {relation} has been caught by the police in a rape case.", "Your {relation} had an accident and is admitted in hospital, we need money urgently.", "Your {relation} is in police custody, we can settle it if you pay now."],
    "hing": ["Aapka {relation} police ne ek case mein pakad liya hai.", "Aapke {relation} ka accident ho gaya hai, hospital mein admit hai, turant paise chahiye.", "Aapka {relation} police custody mein hai, abhi paise doge to mamla nipat jayega."],
    "hi": ["आपके {relation} को पुलिस ने एक केस में पकड़ लिया है।", "आपके {relation} का एक्सीडेंट हो गया है, अस्पताल में भर्ती है, तुरंत पैसे चाहिए।", "आपका {relation} पुलिस हिरासत में है, अभी पैसे दोगे तो मामला निपट जाएगा।"],
}
P["h_reward"] = {
    "en": ["You will get a refund of {amount} today itself.", "We will double your money in 15 days, guaranteed returns.", "To release your prize, a small processing fee is needed.", "You get commission for every task, first task bonus is 200 rupees.", "Our VIP members get 5 percent daily profit."],
    "hing": ["Aapko aaj hi {amount} ka refund mil jayega.", "15 din mein aapka paisa double, guaranteed return.", "Prize release karne ke liye chhoti si processing fee lagegi.", "Har task par commission milega, pehle task ka bonus 200 rupaye.", "Hamare VIP members ko roz 5 percent profit milta hai."],
    "hi": ["आपको आज ही {amount} का रिफंड मिल जाएगा।", "पंद्रह दिन में आपका पैसा दोगुना, गारंटीड रिटर्न।", "इनाम रिलीज़ करने के लिए छोटी सी प्रोसेसिंग फीस लगेगी।", "हर टास्क पर कमीशन मिलेगा, पहले टास्क का बोनस दो सौ रुपये।", "हमारे वीआईपी मेंबर्स को रोज़ पांच परसेंट मुनाफा मिलता है।"],
}
P["h_urgency"] = {
    "en": ["You have to do this immediately.", "This is your last chance.", "We do not have much time, do it right now.", "Within 30 minutes otherwise I cannot help you."],
    "hing": ["Yeh aapko turant karna hoga.", "Yeh aapka aakhri mauka hai.", "Hamare paas time nahi hai, abhi ke abhi karo.", "30 minute ke andar, warna main help nahi kar paunga."],
    "hi": ["यह आपको तुरंत करना होगा।", "यह आपका आखिरी मौका है।", "हमारे पास टाइम नहीं है, अभी के अभी करो।", "तीस मिनट के अंदर, वरना मैं मदद नहीं कर पाऊंगा।"],
}

# ----------------------------------------------------------------- isolation (scam)
P["iso"] = {
    "en": ["Do not disconnect this call, it is being recorded.", "Do not tell anyone about this, not even your family. This is a confidential investigation.", "Stay on video call, keep your camera on, you are under surveillance.", "Go to a room and lock the door, you must be alone.", "If you tell your family they will also be arrested.", "This is a matter of national secrecy, do not inform anyone."],
    "hing": ["Yeh call mat kaatna, yeh record ho rahi hai.", "Iske baare mein kisi ko mat batana, ghar walon ko bhi nahi. Yeh confidential investigation hai.", "Video call pe raho, camera on rakho, aap surveillance mein ho.", "Ek kamre mein jaake darwaza band karo, aapko akele rehna hai.", "Agar ghar walon ko bataya to woh bhi arrest honge.", "Yeh national secret ka mamla hai, kisi ko kuch mat batana."],
    "hi": ["यह कॉल मत काटना, यह रिकॉर्ड हो रही है।", "इसके बारे में किसी को मत बताना, घर वालों को भी नहीं। यह गोपनीय जांच है।", "वीडियो कॉल पर रहिए, कैमरा चालू रखिए, आप निगरानी में हैं।", "एक कमरे में जाकर दरवाज़ा बंद कीजिए, आपको अकेले रहना है।", "अगर घर वालों को बताया तो वे भी अरेस्ट होंगे।", "यह राष्ट्रीय गोपनीयता का मामला है, किसी को कुछ मत बताइए।"],
}

# ----------------------------------------------------------------- action (scam)
P["a_credential"] = {
    "en": ["You will receive an OTP now, please tell me the OTP for verification.", "Read out the six digit code you just got.", "Tell me your card number and expiry date to verify.", "Enter your UPI PIN to complete the verification.", "Please share the OTP, it is only for the bank system."],
    "hing": ["Abhi aapke paas OTP aayega, verification ke liye OTP bata dijiye.", "Jo 6 digit ka code aaya hai woh padh ke sunaiye.", "Verify karne ke liye card number aur expiry date bataiye.", "Verification complete karne ke liye UPI PIN daaliye.", "OTP share kar dijiye, yeh sirf bank system ke liye hai."],
    "hi": ["अभी आपके पास ओटीपी आएगा, वेरिफिकेशन के लिए ओटीपी बता दीजिए।", "जो छह अंकों का कोड आया है वह पढ़ कर सुनाइए।", "वेरिफाई करने के लिए कार्ड नंबर और एक्सपायरी डेट बताइए।", "वेरिफिकेशन पूरा करने के लिए यूपीआई पिन डालिए।", "ओटीपी शेयर कर दीजिए, यह सिर्फ बैंक सिस्टम के लिए है।"],
}
P["a_remote"] = {
    "en": ["Please download {app} from the link I am sending.", "Install the {app} so that our technical team can help you.", "Open the app and share your screen with me.", "Download the APK file I sent on WhatsApp and install it.", "Give the screen sharing permission and tell me the code."],
    "hing": ["Jo link bhej raha hoon usse {app} download kar lijiye.", "{app} install kar lijiye taaki hamari technical team help kar sake.", "App kholiye aur apni screen mere saath share kijiye.", "WhatsApp par jo APK file bheji hai woh download karke install kijiye.", "Screen share ki permission dijiye aur code bataiye."],
    "hi": ["जो लिंक भेज रहा हूं उससे {app} डाउनलोड कर लीजिए।", "{app} इंस्टॉल कर लीजिए ताकि हमारी टेक्निकल टीम मदद कर सके।", "ऐप खोलिए और अपनी स्क्रीन मेरे साथ शेयर कीजिए।", "व्हाट्सएप पर जो एपीके फाइल भेजी है वह डाउनलोड करके इंस्टॉल कीजिए।", "स्क्रीन शेयर की परमिशन दीजिए और कोड बताइए।"],
}
P["a_link"] = {
    "en": ["I have sent you a link by SMS, click on it and fill the form.", "Open the link on WhatsApp and update your details.", "Click the link and enter your account details."],
    "hing": ["Maine aapko SMS par link bheja hai, us par click karke form bhariye.", "WhatsApp par jo link hai woh kholiye aur details update kijiye.", "Link par click karke account details daaliye."],
    "hi": ["मैंने आपको एसएमएस पर लिंक भेजा है, उस पर क्लिक करके फॉर्म भरिए।", "व्हाट्सएप पर जो लिंक है वह खोलिए और डिटेल्स अपडेट कीजिए।", "लिंक पर क्लिक करके अकाउंट डिटेल्स डालिए।"],
}

# ----------------------------------------------------------------- payment (scam)
P["pay_safe"] = {
    "en": ["Transfer all your money to the RBI safe account for verification, it will be returned in 24 hours.", "You must transfer {amount} to this government account to prove you are innocent.", "Send the money to the secure account I give you, it is fully refundable.", "Pay a security deposit of {amount} so the court can release you."],
    "hing": ["Verification ke liye apna saara paisa RBI ke safe account mein transfer kijiye, 24 ghante mein wapas aa jayega.", "Aapko apni begunahi saabit karne ke liye {amount} is sarkari account mein transfer karne honge.", "Main jo secure account de raha hoon usme paise bhej dijiye, poora refundable hai.", "{amount} ka security deposit bhariye taaki court aapko chhod de."],
    "hi": ["वेरिफिकेशन के लिए अपना सारा पैसा आरबीआई के सेफ अकाउंट में ट्रांसफर कीजिए, चौबीस घंटे में वापस आ जाएगा।", "आपको अपनी बेगुनाही साबित करने के लिए {amount} इस सरकारी अकाउंट में ट्रांसफर करने होंगे।", "मैं जो सिक्योर अकाउंट दे रहा हूं उसमें पैसे भेज दीजिए, पूरा रिफंडेबल है।", "{amount} का सिक्योरिटी डिपॉजिट भरिए ताकि कोर्ट आपको छोड़ दे।"],
}
P["pay_fee"] = {
    "en": ["Pay the processing fee of 4,999 by UPI to release it.", "Scan this QR code and pay the customs duty.", "Pay 10 rupees to update the bill, then everything will be fine.", "For the next task you have to prepay {amount}, you will get it back with commission.", "Pay the withdrawal tax first, then you can take out your profit."],
    "hing": ["Release karne ke liye 4999 ki processing fee UPI se bhariye.", "Yeh QR code scan karke customs duty pay kijiye.", "Bill update karne ke liye 10 rupaye pay kijiye, phir sab theek ho jayega.", "Agle task ke liye {amount} pehle pay karne honge, commission ke saath wapas milenge.", "Pehle withdrawal tax bhariye, phir aap apna profit nikaal sakte hain."],
    "hi": ["रिलीज़ करने के लिए उनचास सौ निन्यानवे की प्रोसेसिंग फीस यूपीआई से भरिए।", "यह क्यूआर कोड स्कैन करके कस्टम ड्यूटी पेमेंट कीजिए।", "बिल अपडेट करने के लिए दस रुपये पेमेंट कीजिए, फिर सब ठीक हो जाएगा।", "अगले टास्क के लिए {amount} पहले पेमेंट करने होंगे, कमीशन के साथ वापस मिलेंगे।", "पहले विड्रॉल टैक्स भरिए, फिर आप अपना मुनाफा निकाल सकते हैं।"],
}
P["pay_collect"] = {
    "en": ["I am sending a collect request, accept it and enter your PIN to receive the refund.", "Approve the request on your UPI app, the money will come to you.", "I sent the money by mistake, please return it, I am sending a request."],
    "hing": ["Main collect request bhej raha hoon, accept karke PIN daaliye to refund aa jayega.", "Apne UPI app mein request approve kijiye, paisa aapke paas aayega.", "Galti se paisa aapko chala gaya, please wapas kar dijiye, main request bhej raha hoon."],
    "hi": ["मैं कलेक्ट रिक्वेस्ट भेज रहा हूं, एक्सेप्ट करके पिन डालिए तो रिफंड आ जाएगा।", "अपने यूपीआई ऐप में रिक्वेस्ट अप्रूव कीजिए, पैसा आपके पास आएगा।", "गलती से पैसा आपको चला गया, प्लीज़ वापस कर दीजिए, मैं रिक्वेस्ट भेज रहा हूं।"],
}
P["pay_family"] = {
    "en": ["Send {amount} immediately to this number on Google Pay.", "Transfer the money now or he will be sent to jail."],
    "hing": ["Is number par Google Pay se turant {amount} bhejiye.", "Abhi paise transfer karo warna use jail bhej denge."],
    "hi": ["इस नंबर पर गूगल पे से तुरंत {amount} भेजिए।", "अभी पैसे ट्रांसफर करो वरना उसे जेल भेज देंगे।"],
}

# ----------------------------------------------------------------- benign
P["b_bank_alert"] = {
    "en": ["Hello, this is the fraud monitoring team of {bank}. We noticed a card transaction of 8,450 rupees at an online store. Was this done by you?",
           "Good evening, I am calling from {bank} regarding a transaction on your credit card ending 4417. Did you make this purchase?",
           "This is {bank} calling to confirm a large transaction of {amount} from your account."],
    "hing": ["Hello, main {bank} ki fraud monitoring team se bol rahi hoon. Aapke card se online store par 8450 ka transaction hua hai. Kya yeh aapne kiya tha?",
             "Namaste, main {bank} se aapke credit card ke ek transaction ke baare mein call kar raha hoon. Kya yeh purchase aapne ki thi?",
             "{bank} se call hai, aapke account se {amount} ke bade transaction ko confirm karna tha."],
    "hi": ["हेलो, मैं {bank} की फ्रॉड मॉनिटरिंग टीम से बोल रही हूं। आपके कार्ड से ऑनलाइन स्टोर पर आठ हज़ार चार सौ पचास का ट्रांजैक्शन हुआ है। क्या यह आपने किया था?",
           "नमस्ते, मैं {bank} से आपके क्रेडिट कार्ड के एक ट्रांजैक्शन के बारे में कॉल कर रहा हूं। क्या यह खरीदारी आपने की थी?"],
}
P["b_bank_alert_safety"] = {
    "en": ["If not, we will block the card and send a new one. Please never share your OTP or PIN with anyone, the bank will never ask for it.",
           "Thank you. Remember, do not share your OTP with anyone. For any doubt call the number on the back of your card.",
           "No problem, I have blocked the card for your safety. You can also visit your branch. We never ask for OTP on calls."],
    "hing": ["Agar nahi, to hum card block karke naya bhej denge. Kripya apna OTP ya PIN kisi ko mat bataiye, bank kabhi nahi maangta.",
             "Dhanyavaad. Yaad rakhiye, OTP kisi ko mat dijiye. Koi doubt ho to card ke peeche wale number par call kijiye.",
             "Koi baat nahi, aapki safety ke liye card block kar diya hai. Aap branch bhi ja sakte hain. Hum call par kabhi OTP nahi maangte."],
    "hi": ["अगर नहीं, तो हम कार्ड ब्लॉक करके नया भेज देंगे। कृपया अपना ओटीपी या पिन किसी को मत बताइए, बैंक कभी नहीं मांगता।",
           "धन्यवाद। याद रखिए, ओटीपी किसी को मत दीजिए। कोई शक हो तो कार्ड के पीछे वाले नंबर पर कॉल कीजिए।",
           "कोई बात नहीं, आपकी सुरक्षा के लिए कार्ड ब्लॉक कर दिया है। आप शाखा भी जा सकते हैं। हम कॉल पर कभी ओटीपी नहीं मांगते।"],
}
P["b_delivery"] = {
    "en": ["Hello, I am the delivery boy from {company}, I am outside your building. Which floor?", "Sir your {company} order is out for delivery, are you at home?", "Madam, I have your parcel, cash on delivery 649 rupees.", "Please tell me the delivery OTP shown in your app so I can mark it delivered."],
    "hing": ["Hello, main {company} se delivery boy bol raha hoon, aapki building ke bahar hoon. Kaunsa floor?", "Sir aapka {company} ka order out for delivery hai, ghar par hain?", "Madam aapka parcel hai, cash on delivery 649 rupaye.", "App mein jo delivery OTP dikh raha hai woh bata dijiye, delivered mark karna hai."],
    "hi": ["हेलो, मैं {company} से डिलीवरी बॉय बोल रहा हूं, आपकी बिल्डिंग के बाहर हूं। कौन सा फ्लोर?", "सर आपका {company} का ऑर्डर आउट फॉर डिलीवरी है, घर पर हैं?", "मैडम आपका पार्सल है, कैश ऑन डिलीवरी छह सौ उनचास रुपये।", "ऐप में जो डिलीवरी ओटीपी दिख रहा है वह बता दीजिए, डिलीवर्ड मार्क करना है।"],
}
P["b_relative_money"] = {
    "en": ["Papa, my college fees is due on Friday, can you send 12,000 on Google Pay?", "Hi mummy, I reached the hostel. Can you transfer some money for the mess?", "Bhaiya, please send 500 for the recharge, I will return it tomorrow.", "Dad, the hospital bill for grandma is 18,000, I paid half, can you send the rest?"],
    "hing": ["Papa meri college fees Friday ko due hai, Google Pay pe 12000 bhej doge?", "Hi mummy, main hostel pahunch gaya. Mess ke liye thode paise transfer kar do.", "Bhaiya 500 bhej do recharge ke liye, kal wapas kar dunga.", "Papa dadi ka hospital bill 18000 hai, aadha maine de diya, baaki bhej doge?"],
    "hi": ["पापा मेरी कॉलेज फीस शुक्रवार को ड्यू है, गूगल पे पर बारह हज़ार भेज दोगे?", "हाय मम्मी, मैं हॉस्टल पहुंच गया। मेस के लिए थोड़े पैसे ट्रांसफर कर दो।", "भैया पांच सौ भेज दो रिचार्ज के लिए, कल वापस कर दूंगा।", "पापा दादी का अस्पताल का बिल अठारह हज़ार है, आधा मैंने दे दिया, बाकी भेज दोगे?"],
}
P["b_family_chat"] = {
    "en": ["Did you eat? How is your knee pain now?", "We will come on Sunday for lunch, make rajma.", "Your cousin's wedding is in December, we have to book tickets.", "The electrician came today, the fan is working now."],
    "hing": ["Khana kha liya? Ab ghutne ka dard kaisa hai?", "Sunday ko lunch pe aayenge, rajma banana.", "Cousin ki shaadi December mein hai, ticket book karni hai.", "Aaj electrician aaya tha, pankha chal raha hai ab."],
    "hi": ["खाना खा लिया? अब घुटने का दर्द कैसा है?", "रविवार को लंच पर आएंगे, राजमा बनाना।", "कज़िन की शादी दिसंबर में है, टिकट बुक करनी है।", "आज इलेक्ट्रीशियन आया था, पंखा चल रहा है अब।"],
}
P["b_scam_gossip"] = {
    "en": ["Did you see the news? A retired doctor in {city} lost 2 crore in a digital arrest scam.", "Somebody called Sharma uncle saying he is from police, uncle just disconnected.", "The bank sent a message that they never ask for OTP, good that they keep reminding.", "My friend got a fake call about a FedEx parcel, he reported it on 1930."],
    "hing": ["News dekhi? {city} mein ek retired doctor ne digital arrest scam mein 2 crore gawa diye.", "Sharma uncle ko kisi ne police bankar call kiya, uncle ne turant phone kaat diya.", "Bank ka message aaya ki woh kabhi OTP nahi maangte, achha hai yaad dilate rehte hain.", "Mere dost ko FedEx parcel wali fake call aayi thi, usne 1930 par report kar diya."],
    "hi": ["न्यूज़ देखी? {city} में एक रिटायर्ड डॉक्टर ने डिजिटल अरेस्ट स्कैम में दो करोड़ गंवा दिए।", "शर्मा अंकल को किसी ने पुलिस बनकर कॉल किया, अंकल ने तुरंत फोन काट दिया।", "बैंक का मैसेज आया कि वे कभी ओटीपी नहीं मांगते, अच्छा है याद दिलाते रहते हैं।", "मेरे दोस्त को फेडएक्स पार्सल वाली फेक कॉल आई थी, उसने 1930 पर रिपोर्ट कर दिया।"],
}
P["b_doctor"] = {
    "en": ["This is City Clinic, confirming your appointment with Dr Gupta tomorrow at 11.", "Your blood test report is ready, you can collect it after 5.", "Please come fasting for the test, and bring the old prescription."],
    "hing": ["City Clinic se call hai, kal 11 baje Dr Gupta ke saath aapka appointment confirm karna tha.", "Aapki blood test report ready hai, 5 baje ke baad le sakte hain.", "Test ke liye khaali pet aaiye, aur purani parchi le aaiye."],
    "hi": ["सिटी क्लिनिक से कॉल है, कल ग्यारह बजे डॉक्टर गुप्ता के साथ आपका अपॉइंटमेंट कन्फर्म करना था।", "आपकी ब्लड टेस्ट रिपोर्ट तैयार है, पांच बजे के बाद ले सकते हैं।", "टेस्ट के लिए खाली पेट आइए, और पुरानी पर्ची ले आइए।"],
}
P["b_insurance"] = {
    "en": ["Sir, your car insurance is expiring on the 15th. You can renew it on our official app or at the branch.", "This is a reminder from LIC, your premium is due next week, you can pay at the branch or on the LIC website.", "We have a new credit card offer with zero joining fee, would you be interested? I can send details by email."],
    "hing": ["Sir aapka car insurance 15 tareekh ko expire ho raha hai, aap official app ya branch par renew kar sakte hain.", "LIC se reminder hai, aapka premium agle hafte due hai, branch ya LIC website par bhar sakte hain.", "Hamare paas zero joining fee wala naya credit card offer hai, interest hai? Main email par details bhej sakti hoon."],
    "hi": ["सर आपका कार इंश्योरेंस पंद्रह तारीख को एक्सपायर हो रहा है, आप ऑफिशियल ऐप या ब्रांच पर रिन्यू कर सकते हैं।", "एलआईसी से रिमाइंडर है, आपका प्रीमियम अगले हफ्ते ड्यू है, ब्रांच या एलआईसी वेबसाइट पर भर सकते हैं।", "हमारे पास ज़ीरो जॉइनिंग फीस वाला नया क्रेडिट कार्ड ऑफर है, इंटरेस्ट है? मैं ईमेल पर डिटेल्स भेज सकती हूं।"],
}
P["b_office"] = {
    "en": ["The client meeting moved to 4 pm, please send the slides before that.", "Can you approve the leave request in the portal today?", "The server was down in the morning, it is fixed now.", "Please transfer the vendor payment after finance approves the invoice."],
    "hing": ["Client meeting 4 baje shift ho gayi hai, usse pehle slides bhej dena.", "Aaj portal mein leave request approve kar doge?", "Subah server down tha, ab theek ho gaya.", "Finance invoice approve kar de to vendor payment transfer kar dena."],
    "hi": ["क्लाइंट मीटिंग चार बजे शिफ्ट हो गई है, उससे पहले स्लाइड्स भेज देना।", "आज पोर्टल में लीव रिक्वेस्ट अप्रूव कर दोगे?", "सुबह सर्वर डाउन था, अब ठीक हो गया।", "फाइनेंस इनवॉइस अप्रूव कर दे तो वेंडर पेमेंट ट्रांसफर कर देना।"],
}
P["b_police_genuine"] = {
    "en": ["Hello, I am calling from the local police station about the passport verification. Please come to the station with your original documents on Monday.", "This is the traffic police, your vehicle was towed, you can collect it from the yard with the RC."],
    "hing": ["Hello, main local thane se passport verification ke liye bol raha hoon. Monday ko original documents lekar thane aa jaiye.", "Traffic police se call hai, aapki gaadi tow hui hai, RC lekar yard se le sakte hain."],
    "hi": ["हेलो, मैं लोकल थाने से पासपोर्ट वेरिफिकेशन के लिए बोल रहा हूं। सोमवार को ओरिजिनल डॉक्यूमेंट लेकर थाने आ जाइए।", "ट्रैफिक पुलिस से कॉल है, आपकी गाड़ी टो हुई है, आरसी लेकर यार्ड से ले सकते हैं।"],
}
P["b_close"] = {
    "en": ["Okay, thank you, bye.", "Alright, talk to you later.", "Thank you, have a nice day.", "Okay fine, I will do it. Bye."],
    "hing": ["Theek hai, dhanyavaad, bye.", "Achha, baad mein baat karte hain.", "Shukriya, aapka din achha ho.", "Theek hai kar dunga. Bye."],
    "hi": ["ठीक है, धन्यवाद, बाय।", "अच्छा, बाद में बात करते हैं।", "शुक्रिया, आपका दिन अच्छा हो।", "ठीक है कर दूंगा। बाय।"],
}
P["victim_benign"] = {
    "en": ["Yes, that was me.", "No, I did not do that.", "Okay, thanks for informing.", "Yes I am at home.", "Second floor, flat 204.", "Sure, I will come.", "Okay I will send it."],
    "hing": ["Haan, woh maine kiya tha.", "Nahi, maine nahi kiya.", "Achha, batane ke liye shukriya.", "Haan ghar par hoon.", "Doosra floor, flat 204.", "Haan aa jaunga.", "Theek hai bhej deta hoon."],
    "hi": ["हां, वह मैंने किया था।", "नहीं, मैंने नहीं किया।", "अच्छा, बताने के लिए शुक्रिया।", "हां घर पर हूं।", "दूसरा फ्लोर, फ्लैट दो सौ चार।", "हां आ जाऊंगा।", "ठीक है भेज देता हूं।"],
}

# ----------------------------------------------------------------- scripts
# Each script: list of steps. A step is (pool, probability) or a list of alternatives
# [(pool, p), ...] from which one is chosen. 'stage' marks the kill-chain stage of
# caller utterances (for annotations); victim pools are stage None.
SCAM_SCRIPTS = {
    "digital_arrest": [("greet_caller", 0.7), ("victim_ack", 0.8), ("c_police", 1), ("victim_ack", 0.6), ("h_identity", 1), ("victim_worried", 0.9), ("h_threat_police", 1), ("victim_fear", 0.5), ("iso", 1), ("victim_comply", 0.7), ("h_urgency", 0.5), ("pay_safe", 1), ("victim_paying", 0.5), ("victim_account", 0.2)],
    "courier_parcel": [("c_courier", 1), ("victim_ack", 0.8), ("h_identity", 1), ("victim_worried", 0.9), ("c_police", 0.8), ("h_threat_police", 0.9), ("victim_fear", 0.4), ("iso", 0.7), ("victim_comply", 0.6), ("pay_safe", 0.6), ("pay_fee", 0.4), ("victim_paying", 0.4)],
    "sim_telecom": [("c_telecom", 1), ("victim_ack", 0.7), ("h_identity", 0.9), ("victim_worried", 0.8), ("c_police", 0.7), ("h_threat_police", 0.8), ("iso", 0.7), ("pay_safe", 0.6)],
    "kyc_bank": [("greet_caller", 0.6), ("c_bank", 1), ("victim_ack", 0.8), ("h_threat_bank", 1), ("victim_worried", 0.6), ("h_urgency", 0.6), ("a_link", 0.6), ("a_remote", 0.4), ("victim_comply", 0.6), ("a_credential", 1), ("victim_otp_read", 0.6), ("victim_account", 0.3)],
    "remote_support": [("c_support", 1), ("victim_ack", 0.8), ("h_reward", 0.8), ("a_remote", 1), ("victim_comply", 0.7), ("a_credential", 0.7), ("victim_otp_read", 0.4), ("pay_collect", 0.4)],
    "upi_collect_refund": [("greet_caller", 0.5), ("c_support", 0.6), ("victim_ack", 0.6), ("h_reward", 1), ("victim_ack", 0.5), ("pay_collect", 1), ("victim_comply", 0.5), ("a_credential", 0.5)],
    "family_emergency": [("c_relative", 1), ("victim_worried", 0.9), ("h_family", 1), ("victim_worried", 0.8), ("iso", 0.5), ("h_urgency", 0.7), ("pay_family", 1), ("victim_paying", 0.5)],
    "electricity_bill": [("greet_caller", 0.5), ("c_electricity", 1), ("victim_ack", 0.8), ("h_threat_power", 1), ("victim_worried", 0.6), ("a_remote", 0.8), ("a_link", 0.4), ("pay_fee", 1)],
    "investment_trading": [("greet_caller", 0.6), ("c_investment", 1), ("victim_ack", 0.7), ("h_reward", 1), ("a_link", 0.7), ("a_remote", 0.3), ("victim_comply", 0.5), ("pay_fee", 1)],
    "job_task": [("c_job", 1), ("victim_ack", 0.8), ("h_reward", 1), ("a_link", 0.7), ("victim_comply", 0.5), ("pay_fee", 1)],
    "lottery_prize": [("c_lottery", 1), ("victim_ack", 0.8), ("h_reward", 0.8), ("h_urgency", 0.5), ("pay_fee", 1)],
}
BENIGN_SCRIPTS = {
    "bank_fraud_alert": [("b_bank_alert", 1), ("victim_benign", 1), ("b_bank_alert_safety", 1), ("b_close", 0.8)],
    "delivery": [("b_delivery", 1), ("victim_benign", 1), ("b_delivery", 0.8), ("victim_benign_otp", 0.7), ("victim_benign", 0.6), ("b_close", 0.6)],
    "relative_money": [("greet_caller", 0.4), ("b_family_chat", 0.7), ("b_relative_money", 1), ("victim_benign", 1), ("victim_benign_pay", 0.6), ("b_family_chat", 0.5), ("b_close", 0.8)],
    "family_chat": [("greet_caller", 0.5), ("b_family_chat", 1), ("victim_benign", 0.8), ("b_family_chat", 1), ("b_close", 0.7)],
    "scam_gossip": [("greet_caller", 0.4), ("b_scam_gossip", 1), ("victim_benign", 0.7), ("b_scam_gossip", 0.8), ("b_family_chat", 0.5), ("b_close", 0.6)],
    "doctor": [("b_doctor", 1), ("victim_benign", 1), ("b_doctor", 0.7), ("b_close", 0.8)],
    "insurance_offer": [("greet_caller", 0.6), ("b_insurance", 1), ("victim_benign", 0.9), ("b_insurance", 0.4), ("b_close", 0.8)],
    "office": [("b_office", 1), ("victim_benign", 0.8), ("b_office", 1), ("b_close", 0.7)],
    "police_genuine": [("greet_caller", 0.5), ("b_police_genuine", 1), ("victim_benign", 1), ("b_close", 0.8)],
}
STAGE_OF_POOL = {
    "c_police": "contact", "c_courier": "contact", "c_telecom": "contact", "c_bank": "contact", "c_electricity": "contact", "c_support": "contact",
    "c_relative": "contact", "c_investment": "contact", "c_job": "contact", "c_lottery": "contact",
    "h_identity": "hook", "h_threat_police": "hook", "h_threat_bank": "hook", "h_threat_power": "hook", "h_family": "hook", "h_reward": "hook", "h_urgency": "hook",
    "iso": "isolation", "a_credential": "action", "a_remote": "action", "a_link": "action",
    "pay_safe": "payment", "pay_fee": "payment", "pay_collect": "payment", "pay_family": "payment",
}

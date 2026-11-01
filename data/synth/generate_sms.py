"""Synthetic Indian SMS / WhatsApp-forward messages (scam and genuine).

Complements the UCI SMS Spam Collection (UK English) with the patterns that
matter in India: KYC/PAN block, electricity cut, RTO e-challan APK, parcel
redelivery fee, UPI refund, part-time jobs, loan apps, plus genuine bank OTPs,
debit alerts, delivery and operator messages as hard negatives (a genuine OTP
SMS says "do not share", a debit alert names an amount and a bank).
Template-disjoint split exactly like generate_calls.py. Output CSV: split,label,source,text
"""
from __future__ import annotations

import csv
import random
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_calls import split_pool  # noqa: E402

BANKS = ["SBI", "HDFC Bank", "ICICI Bank", "Axis Bank", "PNB", "BoB", "Kotak", "Canara Bank"]
BANK_SHORT = ["sbi", "hdfc", "icici", "axis", "pnb", "bob", "kotak", "canara", "yono"]
GEN_DOMAINS = ["sbi.bank.in", "hdfc.bank.in", "icici.bank.in", "axis.bank.in", "kotak.bank.in", "pnb.bank.in", "incometax.gov.in", "parivahan.gov.in",
               "indiapost.gov.in", "amazon.in", "flipkart.com", "irctc.co.in", "uidai.gov.in", "jio.com", "airtel.in"]
SCAM_TLDS = ["xyz", "top", "click", "online", "site", "info", "live", "shop", "icu", "in", "com", "co", "cc", "vip", "support"]


def scam_url(rng: random.Random) -> str:
    b = rng.choice(BANK_SHORT + ["rto", "echallan", "parivahan", "indiapost", "bijli", "kyc", "pmkisan", "jio", "income-tax", "refund", "bluedart"])
    w = rng.choice(["kyc", "update", "verify", "secure", "online", "help", "care", "rewards", "pay", "login", "service", "apk", ""])
    sep = rng.choice(["-", "", "."])
    host = f"{b}{sep}{w}".strip("-.") + "." + rng.choice(SCAM_TLDS)
    if rng.random() < 0.15:
        host = rng.choice(["bit.ly", "tinyurl.com", "cutt.ly", "rb.gy"])
        return f"https://{host}/{''.join(rng.choice('abcdefghjkmnpqrstuvwxyz23456789') for _ in range(6))}"
    path = rng.choice(["", "/kyc", "/login", "/update", "/verify.php", "/app.apk", "/challan.apk", "/" + "".join(rng.choice("abcdef0123456789") for _ in range(5))])
    return rng.choice(["https://", "http://", ""]) + host + path


SCAM = {
    "en": [
        "Dear Customer, your {bank} account will be BLOCKED today due to incomplete KYC. Update PAN immediately: {url}",
        "Your {bank} YONO account is suspended. Click {url} to verify Aadhaar and reactivate within 24 hrs.",
        "Dear consumer, your electricity power will be disconnected tonight at 9:30 PM because your previous month bill was not updated. Please contact electricity officer {phone}",
        "Traffic e-challan of Rs.500 is pending against your vehicle. Download the RTO challan app to pay: {url}",
        "India Post: Your parcel could not be delivered due to incomplete address. Update within 12 hours and pay Rs 25 redelivery fee: {url}",
        "Congratulations! You have won Rs 25,00,000 in KBC lucky draw. To claim call {phone} and pay processing fee.",
        "Income Tax Refund of Rs 15,490 approved. Submit your account details to receive refund: {url}",
        "Earn Rs 3000-8000 daily from home! Part time job, just like YouTube videos. WhatsApp HR {phone}",
        "Your SIM will be deactivated in 2 hours as per TRAI. To stop press 9 or call {phone}",
        "Instant loan up to 5 lakh approved without documents. Download app {url} and get money in 5 minutes.",
        "Dear user, Rs 4,999 cashback credited to your PhonePe. Accept the request and enter UPI PIN to receive.",
        "ALERT: Suspicious login on your {bank} netbanking. If not you, verify immediately at {url}",
        "Your PAN card is deactivated. Link PAN with Aadhaar now at {url} to avoid penalty of Rs 10,000.",
        "Your account has been credited with reward points worth Rs 7,850 expiring today. Redeem now: {url}",
        "Mumbai Cyber Police: A case is registered against your Aadhaar. Call officer {phone} immediately to avoid arrest.",
    ],
    "hing": [
        "Priya grahak, aapka {bank} account aaj band ho jayega. KYC update karne ke liye turant click karein: {url}",
        "Aapki bijli aaj raat 9:30 baje kaat di jayegi kyunki pichhle mahine ka bill update nahi hua. Turant bijli adhikari se sampark karein {phone}",
        "Aapke vehicle par Rs 500 ka e-challan pending hai. Bharne ke liye RTO app download karein {url}",
        "Badhai ho! Aapne Rs 25 lakh ki lottery jeeti hai. Claim karne ke liye {phone} par call karein.",
        "Ghar baithe roz Rs 5000 kamayein, sirf video like karna hai. Abhi join karein {url}",
        "Aapka parcel address galat hone se ruka hua hai. Rs 25 dekar address update karein {url}",
        "Aapka SIM 2 ghante mein band ho jayega. Rokne ke liye {phone} par call karein.",
        "Bina document 5 lakh ka loan turant. App download karein {url}",
        "Aapke PhonePe mein Rs 2000 cashback aaya hai, request accept karke PIN daalein.",
        "Aapka PAN card band ho gaya hai, abhi Aadhaar se link karein {url} warna Rs 10000 jurmana.",
    ],
    "hi": [
        "प्रिय ग्राहक, आपका {bank} खाता आज बंद हो जाएगा। केवाईसी अपडेट करने के लिए तुरंत क्लिक करें: {url}",
        "आपकी बिजली आज रात साढ़े नौ बजे काट दी जाएगी क्योंकि पिछले महीने का बिल अपडेट नहीं हुआ। तुरंत बिजली अधिकारी से संपर्क करें {phone}",
        "आपके वाहन पर 500 रुपये का ई-चालान बाकी है। भरने के लिए आरटीओ ऐप डाउनलोड करें {url}",
        "बधाई हो! आपने 25 लाख की लॉटरी जीती है। इनाम पाने के लिए {phone} पर कॉल करें और प्रोसेसिंग फीस भरें।",
        "घर बैठे रोज़ 5000 रुपये कमाएं, सिर्फ वीडियो लाइक करना है। अभी जुड़ें {url}",
        "आपका पार्सल गलत पते के कारण रुका है। 25 रुपये देकर पता अपडेट करें {url}",
        "ट्राई के अनुसार आपकी सिम दो घंटे में बंद हो जाएगी। रोकने के लिए {phone} पर कॉल करें।",
        "आपका पैन कार्ड बंद हो गया है, अभी आधार से लिंक करें {url} वरना 10000 रुपये जुर्माना।",
        "आयकर रिफंड 15490 रुपये स्वीकृत। रिफंड पाने के लिए अपने खाते की जानकारी भेजें {url}",
        "साइबर पुलिस: आपके आधार पर केस दर्ज है। गिरफ्तारी से बचने के लिए तुरंत {phone} पर कॉल करें।",
    ],
}
GENUINE = {
    "en": [
        "{otp} is your OTP for transaction of INR {amt} at AMAZON. Valid for 10 mins. Do not share it with anyone. - {bank}",
        "Dear Customer, INR {amt} debited from A/c XX{acct} on {date} by UPI ref {ref}. Not you? Call 1800-xxx-xxxx or visit {gurl} - {bank}",
        "Your order of 2 items from Flipkart is out for delivery today. Share OTP {otp4} with the delivery agent only at the time of delivery.",
        "Recharge of Rs 299 successful for your Jio number. Validity 28 days. Download MyJio from Play Store.",
        "Rs {amt} credited to your A/c XX{acct} by NEFT from EMPLOYER PVT LTD. Avl Bal Rs xxx. - {bank}",
        "Your electricity bill of Rs {amt} for Oct is generated. Due date 15-Nov. Pay on the official app or {gurl}.",
        "Dear Customer, your KYC is due for periodic update. Please visit your nearest branch or update via official app. Bank never asks for OTP/PIN. - {bank}",
        "Your appointment with Dr. Sharma is confirmed for tomorrow 11:00 AM at City Clinic.",
        "PNR 4521367890: Train 12302 confirmed, Coach B2 Seat 34. Check status on {gurl}",
        "{otp} is the OTP to login to your DigiLocker account. Do not share.",
        "Hi, the parcel you ordered will arrive between 2 PM and 6 PM. Track at {gurl}",
        "Mummy I reached hostel safely, will call at night.",
        "Your SBI Credit Card statement is ready. Total due Rs {amt}, min due Rs 400 by 20-Nov.",
        "Thank you for paying your LIC premium of Rs {amt}. Receipt available on {gurl}",
        "Meeting moved to 4 pm today, please bring the printed report.",
    ],
    "hing": [
        "Bhaiya kal subah 9 baje aa jana, gaadi saaf karni hai.",
        "Beta fees ke 5000 bhej diye hain GPay pe, check kar lena.",
        "Aaj shaam ko mandir chalna hai, 6 baje tayaar rehna.",
        "{otp} aapka OTP hai {bank} transaction ke liye. Kisi ke saath share na karein.",
        "Aapke A/c XX{acct} se Rs {amt} UPI dwara debit hue. Aapne nahi kiya? Bank se sampark karein. - {bank}",
        "Doodh wale ka hisaab 1200 hua is mahine, de dena.",
        "Aapka Flipkart order aaj deliver hoga. Delivery ke samay OTP {otp4} batayein.",
    ],
    "hi": [
        "{otp} आपका ओटीपी है। यह 10 मिनट के लिए मान्य है। किसी के साथ साझा न करें। - {bank}",
        "आपके खाते XX{acct} से {amt} रुपये यूपीआई द्वारा डेबिट हुए। आपने नहीं किया? बैंक से संपर्क करें। - {bank}",
        "आपका बिजली बिल {amt} रुपये बन गया है। अंतिम तिथि 15 नवंबर। आधिकारिक ऐप से भुगतान करें।",
        "बेटा खाना खा लेना, रात को फोन करना।",
        "आपकी पेंशन की राशि खाते में जमा कर दी गई है। - {bank}",
        "कल सुबह दस बजे डॉक्टर के पास चलना है, तैयार रहना।",
        "आपका गैस सिलेंडर बुक हो गया है, दो दिन में डिलीवरी होगी।",
    ],
}


def fill(t: str, rng: random.Random) -> str:
    vals = {
        "bank": rng.choice(BANKS), "url": scam_url(rng), "gurl": "https://" + rng.choice(GEN_DOMAINS),
        "phone": rng.choice(["+91 ", ""]) + str(rng.randint(6, 9)) + "".join(str(rng.randint(0, 9)) for _ in range(9)),
        "otp": "".join(str(rng.randint(0, 9)) for _ in range(6)), "otp4": "".join(str(rng.randint(0, 9)) for _ in range(4)),
        "amt": f"{rng.randint(50, 50000):,}", "acct": str(rng.randint(1000, 9999)), "date": f"{rng.randint(1, 28):02d}-10-26",
        "ref": str(rng.randint(10**11, 10**12 - 1)),
    }
    return re.sub(r"\{(\w+)\}", lambda m: vals[m.group(1)], t)


def generate(n_per_split: dict[str, int], seed: int = 11) -> list[tuple[str, int, str, str]]:
    rng = random.Random(seed)
    rows = []
    for split, n in n_per_split.items():
        pool_split = split
        for _ in range(n):
            label = 1 if rng.random() < 0.5 else 0
            src = SCAM if label else GENUINE
            lang = rng.choices(["en", "hing", "hi"], [0.45, 0.3, 0.25])[0]
            items = split_pool(src[lang], pool_split)
            rows.append((split, label, f"synth-{lang}", fill(rng.choice(items), rng)))
    return rows


def main() -> None:
    out = Path(__file__).resolve().parent / "out"
    out.mkdir(exist_ok=True)
    rows = generate({"train": 3000, "dev": 500, "test": 800})
    with open(out / "sms_synth.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["split", "label", "source", "text"])
        w.writerows(rows)
    print(f"{len(rows)} synthetic SMS -> {out / 'sms_synth.csv'}")


if __name__ == "__main__":
    main()

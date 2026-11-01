"""Link Check: phishing and look-alike URL detection, fully offline.

Decision order
  1. Genuine allow-list (assets/genuine_domains.json) -> SAFE, unless the host
     is a free user-content host (sites.google.com, *.web.app, ...).
  2. Deterministic look-alike rules (reasons the user can understand):
       brand_in_unofficial_domain  "sbi-kyc-update.xyz"
       typosquat                   "hdfcbnak.com"   (edit distance 1-2 to a genuine label)
       homoglyph                   "pаytm.com" (Cyrillic а) / "paytrn.com" / "sb1.co.in"
       ip_host, at_sign, shortener, apk_download, suspicious_tld, user_content_brand
  3. A hashed character n-gram linear model over host + path (trained on
     public phishing domain lists vs popular domains + generated Indian
     look-alikes) gives a probability for everything the rules do not cover.
Final risk = max(rule risk, model probability) with SAFE short-circuit.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path

from .features import LinearModel, fnv1a32  # noqa: F401  (fnv1a32 re-exported for tests)

ASSETS = Path(__file__).resolve().parent.parent / "assets"

# ASCII-only constructs (no \b, no Unicode \s) so the Kotlin port behaves identically;
# extract_urls() maps every whitespace character to ' ' first.
URL_RE = re.compile(
    r"((?:https?://|hxxps?://|www\.)[^ <>\"']+|(?<![A-Za-z0-9_])(?:[a-z0-9-]+\.)+[a-z]{2,24}(?:/[^ <>\"']*)?)",
    re.IGNORECASE,
)
MULTI_SUFFIXES = {
    "co.in", "org.in", "net.in", "gov.in", "nic.in", "ac.in", "edu.in", "res.in", "firm.in", "gen.in", "ind.in",
    "bank.in", "fin.in", "co.uk", "org.uk", "com.au", "co.jp", "com.br", "com.cn", "com.sg", "co.za",
}
# visually confusable ASCII sequences (after lower-casing)
CONFUSABLE = [("rn", "m"), ("vv", "w"), ("0", "o"), ("1", "l"), ("l", "i"), ("3", "e"), ("5", "s"), ("@", "a")]
RISK_WORDS = ["kyc", "update", "verify", "verification", "login", "secure", "account", "block", "suspend", "reward", "refund",
              "cashback", "challan", "lottery", "prize", "bonus", "kbc", "aadhar", "aadhaar", "pan", "otp", "upi", "gift", "claim", "apk"]

REASONS = {
    "genuine": ("Matches an official domain", "आधिकारिक डोमेन से मेल खाता है"),
    "brand_in_unofficial_domain": ("Uses a bank/government name but is not its official website", "बैंक/सरकारी नाम का इस्तेमाल, पर आधिकारिक वेबसाइट नहीं"),
    "typosquat": ("Spelled almost like an official website", "आधिकारिक वेबसाइट जैसी मिलती-जुलती स्पेलिंग"),
    "homoglyph": ("Uses look-alike letters to imitate a real website", "असली वेबसाइट की नकल के लिए मिलते-जुलते अक्षर"),
    "ip_host": ("Uses a raw IP address instead of a name", "नाम की जगह सीधा आईपी पता"),
    "at_sign": ("Hides the real destination with '@'", "'@' से असली पता छुपाया गया"),
    "shortener": ("Shortened link hides the real website", "छोटा लिंक असली वेबसाइट छुपाता है"),
    "apk_download": ("Downloads an Android app (APK) outside the Play Store", "प्ले स्टोर के बाहर से ऐप (एपीके) डाउनलोड"),
    "suspicious_tld": ("Uses a domain ending often used in scams", "स्कैम में अक्सर इस्तेमाल होने वाला डोमेन"),
    "user_content_brand": ("Free website builder page pretending to be an official site", "मुफ्त वेबसाइट पेज जो आधिकारिक साइट होने का दिखावा करता है"),
    "risk_words": ("Contains words like KYC, verify, refund, reward", "केवाईसी, वेरिफाई, रिफंड, इनाम जैसे शब्द"),
    "model": ("Pattern matches known phishing links", "जाने-माने फिशिंग लिंक जैसा पैटर्न"),
}
GLUE_WORDS = set(RISK_WORDS) | {"online", "bank", "netbanking", "net", "care", "help", "helpline", "support", "service", "services", "india", "in",
                                  "app", "pay", "rewards", "card", "cards", "official", "customer", "portal", "gov", "my", "e", "web", "points", "alert"}
RULE_RISK = {
    "brand_in_unofficial_domain": 0.9, "typosquat": 0.9, "homoglyph": 0.95, "ip_host": 0.8, "at_sign": 0.8,
    "apk_download": 0.85, "user_content_brand": 0.85, "shortener": 0.5, "suspicious_tld": 0.45, "risk_words": 0.4,
}


@dataclass
class UrlVerdict:
    url: str
    host: str
    registrable: str
    risk: float
    level: str  # safe | unknown | suspicious | dangerous
    reasons: list[str] = field(default_factory=list)
    nearest_genuine: str | None = None

    def reason_text(self, lang: str = "en") -> list[str]:
        k = 0 if lang == "en" else 1
        return [REASONS[r][k] for r in self.reasons]


def extract_urls(text: str) -> list[str]:
    text = "".join(" " if c.isspace() else c for c in text)
    out = []
    for m in URL_RE.finditer(text):
        u = m.group(1).rstrip(".,;:!?)]}")
        if "." in u and not re.fullmatch(r"[\d.]+", u.split("/")[0]) or u.lower().startswith(("http", "hxxp")):
            out.append(u)
    return out


def split_url(url: str) -> tuple[str, str]:
    u = url.strip()
    u = re.sub(r"^hxxp", "http", u, flags=re.IGNORECASE)
    u = re.sub(r"^[a-zA-Z][a-zA-Z0-9+.-]*://", "", u)
    hostport, _, path = u.partition("/")
    path = "/" + path if path else ""
    if "@" in hostport:
        hostport = hostport.rsplit("@", 1)[1]
    host = hostport.split(":")[0].strip(".").lower()
    if host.startswith("www."):
        host = host[4:]
    return host, path


def _authority(url: str) -> str:
    u = re.sub(r"^[a-zA-Z][a-zA-Z0-9+.-]*://", "", url.strip())
    return u.split("/", 1)[0]


def to_unicode_host(host: str) -> str:
    try:
        return host.encode("ascii").decode("idna") if "xn--" in host else host
    except Exception:
        return host


def registrable_domain(host: str) -> str:
    parts = host.split(".")
    if len(parts) >= 3 and ".".join(parts[-2:]) in MULTI_SUFFIXES:
        return ".".join(parts[-3:])
    return ".".join(parts[-2:]) if len(parts) >= 2 else host


def label_of(registrable: str) -> str:
    parts = registrable.split(".")
    if len(parts) >= 3 and ".".join(parts[-2:]) in MULTI_SUFFIXES:
        return parts[-3]
    return parts[0]


def osa_distance(a: str, b: str, cap: int = 3) -> int:
    """Optimal-string-alignment edit distance (adjacent transposition costs 1)."""
    if abs(len(a) - len(b)) > cap:
        return cap + 1
    d = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i in range(len(a) + 1):
        d[i][0] = i
    for j in range(len(b) + 1):
        d[0][j] = j
    for i in range(1, len(a) + 1):
        for j in range(1, len(b) + 1):
            cost = 0 if a[i - 1] == b[j - 1] else 1
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if i > 1 and j > 1 and a[i - 1] == b[j - 2] and a[i - 2] == b[j - 1]:
                d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
    return d[len(a)][len(b)]


def is_typosquat(label: str, genuine: str) -> bool:
    """Distance 1 (substitution, deletion or swap, not a pure extra letter) for names of
    5+ letters; distance 2 only for names of 8+ letters. Tuned to keep real words such
    as 'kotaku' (kotak + u) or 'usbank' out."""
    if len(genuine) < 5 or label == genuine:
        return False
    dist = osa_distance(label, genuine, 2)
    if dist == 1:
        return len(label) <= len(genuine)
    return dist == 2 and len(genuine) >= 8 and len(label) == len(genuine)


def deconfuse(s: str) -> str:
    for a, b in CONFUSABLE:
        s = s.replace(a, b)
    return s


class UrlChecker:
    def __init__(self, model: LinearModel | None = None, path: Path | str = ASSETS / "genuine_domains.json",
                 model_threshold: float | None = None):
        d = json.loads(Path(path).read_text(encoding="utf-8"))
        self.domains = set(d["domains"])
        self.suffixes = d["trusted_suffixes"]
        self.brand_set = set(d["brands"])
        # deterministic order (longest first, then alphabetical) - identical in Kotlin
        self.brands = sorted(self.brand_set, key=lambda b: (-len(b), b))
        self.bad_tlds = set(d["suspicious_tlds"])
        self.shorteners = set(d["shorteners"])
        self.user_content = d["user_content_hosts"]
        self.genuine_labels = sorted({label_of(x) for x in self.domains if "." in x} | {b for b in self.brands if len(b) >= 5})
        self.model = model
        # model-only flags need a higher bar than rule flags; the value is chosen on the dev
        # split for <= 3% false alarms on popular benign domains (models/classifiers/train_url.py)
        self.model_threshold = model_threshold if model_threshold is not None else (model.threshold if model else 0.5)

    # --- allow-list
    def is_genuine(self, host: str) -> bool:
        if any(host == u or host.endswith("." + u) for u in self.user_content):
            return False
        if any(host == s or host.endswith("." + s) for s in self.suffixes):
            return True
        return any(host == g or host.endswith("." + g) for g in self.domains)

    def find_brand(self, host: str) -> str | None:
        """Brand name used as a whole label piece, or glued to a scam-typical word ("sbikyc", "hdfc-netbanking")."""
        pieces = [p for p in re.split(r"[.\-_0-9]+", host) if p]
        for p in pieces:
            for b in self.brands:
                if len(b) < 3:
                    continue
                if p == b:
                    return b
                if p.startswith(b) and (p[len(b):] in GLUE_WORDS or p[len(b):] in self.brand_set):
                    return b
                if p.endswith(b) and (p[: -len(b)] in GLUE_WORDS or p[: -len(b)] in self.brand_set):
                    return b
        return None

    def _user_content(self, host: str) -> bool:
        return any(host == u or host.endswith("." + u) for u in self.user_content)

    def model_text(self, host: str, path: str) -> str:
        """Input string for the URL model: host labels and path pieces as tokens."""
        return " ".join(re.split(r"[./\-_?=&]+", host + path)).strip()

    def check(self, url: str) -> UrlVerdict:
        host, path = split_url(url)
        uhost = to_unicode_host(host)
        reg = registrable_domain(host)
        if self.is_genuine(host):
            return UrlVerdict(url, host, reg, 0.0, "safe", ["genuine"])
        reasons: list[str] = []
        nearest = None
        label = label_of(reg)

        if re.fullmatch(r"\d{1,3}(\.\d{1,3}){3}", host):
            reasons.append("ip_host")
        if "@" in _authority(url):
            reasons.append("at_sign")
        if host in self.shorteners or reg in self.shorteners:
            reasons.append("shortener")
        if path.lower().split("?")[0].endswith(".apk"):
            reasons.append("apk_download")
        tld = host.rsplit(".", 1)[-1] if "." in host else ""
        if tld in self.bad_tlds:
            reasons.append("suspicious_tld")
        # any internationalised label is treated as a look-alike signal: decoders differ
        # between platforms and genuine Indian bank / payment sites are all ASCII
        if "xn--" in host or uhost != host or any(ord(c) > 127 for c in uhost):
            reasons.append("homoglyph")

        brand = self.find_brand(host)
        if self._user_content(host):
            if brand or any(b in path.lower() for b in self.brands if len(b) >= 4):
                reasons.append("user_content_brand")
        elif brand and brand not in ("kyc", "upi", "gst", "lic", "bob", "pan"):
            reasons.append("brand_in_unofficial_domain")
            nearest = brand
        elif brand in ("kyc", "upi"):
            reasons.append("risk_words")

        if "homoglyph" not in reasons and "brand_in_unofficial_domain" not in reasons and len(label) >= 4:
            dl = deconfuse(label)
            for g in self.genuine_labels:
                if len(g) < 4:
                    continue
                if dl == deconfuse(g) and label != g:
                    reasons.append("homoglyph")
                    nearest = g
                    break
                if is_typosquat(label, g):
                    reasons.append("typosquat")
                    nearest = g
                    break

        low = (host + path).lower()
        if "risk_words" not in reasons and sum(w in low for w in RISK_WORDS) >= 2:
            reasons.append("risk_words")

        rule_risk = max((RULE_RISK[r] for r in reasons), default=0.0)
        # combinations are stronger than single weak signals
        weak = sum(r in ("shortener", "suspicious_tld", "risk_words") for r in reasons)
        if weak >= 2:
            rule_risk = max(rule_risk, 0.75)
        model_p = self.model.predict_proba(self.model_text(host, path)) if self.model else 0.0
        # rescale so that the dev-chosen model threshold maps to 0.5 (suspicious)
        t = self.model_threshold
        model_risk = 0.5 * model_p / t if model_p < t else 0.5 + 0.5 * (model_p - t) / max(1e-6, 1 - t)
        if model_risk >= 0.5 and not reasons:
            reasons.append("model")
        risk = max(rule_risk, model_risk)
        level = "dangerous" if risk >= 0.8 else "suspicious" if risk >= 0.5 else "unknown"
        return UrlVerdict(url, host, reg, round(risk, 4), level, reasons, nearest)

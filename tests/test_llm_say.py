from kavach_core.llm import whole_sentences


def test_unfinished_sentence_dropped():
    s = "यह डिजिटल अरेस्ट स्कैम है। पैसे मत भेजिए। 1930 पर"
    assert whole_sentences(s) == "यह डिजिटल अरेस्ट स्कैम है। पैसे मत भेजिए।"
    assert whole_sentences("This is a scam. Hang up and call") == "This is a scam."
    assert whole_sentences("Hang up now") == "Hang up now"
    assert whole_sentences("Hang up now.") == "Hang up now."

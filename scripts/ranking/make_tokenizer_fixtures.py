"""Reference fixtures for the Java BertTokenizer (src/test/resources/ce/tokenizer-fixtures.jsonl).

Encodes realistic (query, document) pairs with the Hugging Face tokenizer of cross-encoder/ms-marco-MiniLM-L6-v2
(the fast BertTokenizer that sentence-transformers' CrossEncoder used in the ranking study), exactly as the study did:
tok(query, document, truncation=True, max_length=N). Documents are rendered with scripts/research/common.py
candidate_text from docs/research/data/ranking-eval.jsonl plus hand-written edge cases.

    docker run --rm -v "$PWD":/repo:ro -v <dir with vocab.txt/tokenizer.json>:/model:ro -w /repo \
      <python image with transformers, e.g. the research image of scripts/research/docker> \
      python scripts/ranking/make_tokenizer_fixtures.py /model > src/test/resources/ce/tokenizer-fixtures.jsonl
Prints the token-length distribution of the evaluation set to stderr.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "research"))

from common import candidate_text, load_dataset, load_det_features  # noqa: E402
from transformers import AutoTokenizer  # noqa: E402

PATTERNS = ["µ", "Ω", "℃", r"[一-鿿]", "±", "1x6P", r"2\.54mm", "°", "~", "/"]

EDGE = [
    ("10µF 25V X7R 0805", "Murata | GRM21BR71E106KA73L | 10μF ±10% 25V X7R 0805 MLCC | package 0805"),
    ("4.7kΩ 1% 0603", "UNI-ROYAL | 0603WAF4701T5E | 4.7kΩ ±1% 100mW ±100ppm/℃ 0603 Chip Resistor"),
    ("crystal 16MHz 20pF", "Würth Elektronik | 830108237309 | Quarz 16 MHz ±20 ppm, Lastkapazität 20 pF, Gehäuse 3225 – Résumé naïve café"),
    ("ＵＳＢ－Ｃ connector", "ＵＳＢ　Ｔｙｐｅ－Ｃ 母座 16P 贴片 SMD | 连接器 / USB连接器"),
    ("tab\tseparated\nquery", "control\u0000chars​ and nbsp﻿ here\r\nnew line"),
    ("emoji 🔌 test", "plug 🔌 socket ⚡ 3.3V → 5V ≥ 2A ≤ 3A × 2 ÷ 4 ∞"),
    ("Ł Ø ß Æ İ", "ŁÓDŹ ØRSTED STRAßE ÆØÅ İSTANBUL Σίσυφος ΑΒΓ"),
    ("1x6P 2.54mm female header right angle", "Shenzhen Kinghelm Elec | KH-2.54FH-1X6P-H8.5 | 1x6P 2.54mm Female Header 弯插 P=2.54mm"),
    ("supercalifragilisticexpialidociousantidisestablishmentarianismpneumonoultramicroscopicsilicovolcanoconiosis1234567890abcdefghij",
     "averylongtokenwithoutanyspacesthatexceedsonehundredcharacters" * 3),
    ("", "empty query"),
    ("[CLS] [SEP] literal", "[UNK] [PAD] [MASK] tokens in text"),
]


def main():
    tok = AutoTokenizer.from_pretrained(sys.argv[1] if len(sys.argv) > 1 else "cross-encoder/ms-marco-MiniLM-L6-v2")
    data = load_dataset()
    det = load_det_features()
    pairs, lengths = [], []
    seen_pat = {p: 0 for p in PATTERNS}
    for rec in data:
        rows = {r["key"]: r for r in det[rec["id"]]["candidates"]}
        texts = [candidate_text(c["part"], rows[c["key"]]["comparable"]) for c in rec["candidates"]]
        for t in texts:
            lengths.append(len(tok(rec["query"], t)["input_ids"]))
        pairs.append((rec["query"], texts[0]))  # one plain pair per query
        for t in texts[1:]:
            for p in PATTERNS:
                if seen_pat[p] < 2 and re.search(p, t):
                    seen_pat[p] += 1
                    pairs.append((rec["query"], t))
                    break
    longest = max(((rec["query"], candidate_text(c["part"], {r["key"]: r for r in det[rec["id"]]["candidates"]}[c["key"]]["comparable"]))
                   for rec in data for c in rec["candidates"]), key=lambda qt: len(qt[1]))
    pairs.append(longest)
    pairs.extend(EDGE)
    out = []
    for q, d in pairs:
        for max_len in (256, 512):
            out.append((q, d, max_len))
    # both sides truncated (longest_first behaviour) and odd budgets
    long_q = " ".join(["capacitor 10uF X7R"] * 30)
    long_d = data[0]["candidates"][0]["part"]["description"] * 20
    for max_len in (16, 17, 32, 33, 64):
        out.append((long_q, long_d, max_len))
        out.append(("short query", long_d, max_len))
        out.append((long_q, "short doc", max_len))
    for q, d, max_len in out:
        enc = tok(q, d, truncation=True, max_length=max_len)
        print(json.dumps({"query": q, "document": d, "max_length": max_len, "input_ids": enc["input_ids"],
                          "token_type_ids": enc["token_type_ids"]}, ensure_ascii=False))
    lengths.sort()
    n = len(lengths)
    print("pairs=%d fixtures=%d eval token lengths: median %d p95 %d p99 %d max %d, >256: %d"
          % (len(pairs), len(out), lengths[n // 2], lengths[int(n * 0.95)], lengths[int(n * 0.99)], lengths[-1],
             sum(1 for x in lengths if x > 256)), file=sys.stderr)


if __name__ == "__main__":
    main()

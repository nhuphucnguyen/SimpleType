#!/usr/bin/env python3
"""Builds the next-word prediction models in app/src/main/assets/predict/.

The model is a pruned word trigram model (stupid-backoff style) counted over a plain-text
corpus, one sentence or subtitle line per input line. See docs/next-word-prediction.md.

Output format (UTF-8, tab-separated, one record per line):
  #simpletype-ngram 1 <lang>
  u <display> <cost>               unigram; ids are assigned in file order, id 0 is <s>
  b <ctx> <id>:<cost> ...          bigram successors of word <ctx>, best first
  t <ctx2> <ctx1> <id>:<cost> ...  trigram successors of the pair, best first

<cost> is round(-100 * log10(P)): P(w) for unigrams, P(w | ctx) for n-grams. <s> marks a
sentence start, so "b 0 ..." lists the words sentences usually begin with.

Corpora used for the bundled models:
  en  OpenSubtitles v2018 (OPUS), English side of the en-vi pair
      https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/moses/en-vi.txt.zip
  vi  OpenSubtitles Vietnamese lines plus news/Wikipedia text from the
      vietnamese-punctuation project's corpus

Usage:
  python3 tools/generate_prediction_model.py en OpenSubtitles.en-vi.en
  python3 tools/generate_prediction_model.py vi subs.txt.gz news_wiki.txt --max-lines 0,1500000 \
      --max-trigram-contexts 60000
"""

import argparse
import gzip
import math
import re
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets/predict"
DICTIONARIES = ROOT / "app/src/main/assets/dictionaries"

EN_VOCAB = 40_000
BIGRAM_TOP = 10
TRIGRAM_TOP = 6
MIN_NGRAM_COUNT = 3
MIN_BIGRAM_CONTEXT = 15
MIN_TRIGRAM_CONTEXT = 40
MAX_TRIGRAM_CONTEXTS = 150_000

# Never predicted, even though subtitles are full of them (Gboard does the same).
BLOCKLIST = {
    "en": {
        "fuck", "shit", "cunt", "nigger", "nigga", "faggot", "bitch", "asshole", "dick",
        "pussy", "cock", "whore", "slut", "bastard", "wank", "twat", "fucking", "fucked",
        "fucker", "motherfucker", "bullshit", "dickhead", "cocksucker", "fuckin", "shitty",
        "bitches", "fag", "retard", "goddamn", "damn", "dammit", "goddamnit", "crap",
        "piss", "pissed", "tits", "boobs", "ass", "asses",
    },
    "vi": {"địt", "đụ", "lồn", "cặc", "buồi", "đéo", "đĩ", "đếch"},
}

SENTENCE_END = re.compile(r"[.?!…]+")
APOSTROPHES = str.maketrans({"’": "'", "‘": "'", "`": "'"})
TOKEN = {
    # A word, or anything else (digits, symbols) which breaks the n-gram chain.
    "en": re.compile(r"([A-Za-z]+(?:'[A-Za-z]+)?)|(\S)"),
    "vi": re.compile(r"([^\W\d_]+)|(\S)"),
}
BREAK = None
TAGS = re.compile(r"<[^>]*>|\{[^}]*\}|\[[^\]]*\]")


def open_text(path):
    return gzip.open(path, "rt", encoding="utf-8", errors="replace") if path.endswith(".gz") \
        else open(path, encoding="utf-8", errors="replace")


def sentences(paths, max_lines):
    for path, limit in zip(paths, max_lines):
        with open_text(path) as f:
            for n, line in enumerate(f):
                if limit and n >= limit:
                    break
                line = TAGS.sub(" ", unicodedata.normalize("NFC", line.translate(APOSTROPHES)))
                line = line.strip().lstrip("-– ")
                for part in SENTENCE_END.split(line):
                    if part.strip():
                        yield part


def tokens(lang, sentence):
    """Words of [sentence], with BREAK wherever something that is not a word interrupts."""
    out = []
    for m in TOKEN[lang].finditer(sentence):
        if m.group(1):
            out.append(m.group(1))
        elif m.group(2) not in ",;:\"'()":
            out.append(BREAK)
    return out


def allowed_words(lang):
    """The glide dictionary is already curated (no foreign tokens, no profanity)."""
    words = set()
    with open(DICTIONARIES / f"{lang}.txt", encoding="utf-8") as f:
        for line in f:
            words.add(line.split("\t", 1)[0])
    return words


def in_vocab(lang, key, allowed):
    if key in BLOCKLIST[lang]:
        return False
    return key.replace("'", "") in allowed if lang == "en" else key in allowed


def cost(p):
    return max(0, round(-100 * math.log10(p)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("lang", choices=["en", "vi"])
    ap.add_argument("inputs", nargs="+")
    ap.add_argument("--max-lines", default="", help="comma-separated per-input line limits (0 = all)")
    ap.add_argument("--max-trigram-contexts", type=int, default=MAX_TRIGRAM_CONTEXTS,
                    help="keep the trigram lists of only this many most frequent word pairs")
    args = ap.parse_args()
    lang = args.lang
    limits = [int(x) for x in args.max_lines.split(",")] if args.max_lines else [0] * len(args.inputs)
    allowed = allowed_words(lang)

    # Pass 1: vocabulary and display casing.
    counts = Counter()
    casing = defaultdict(Counter)
    for s in sentences(args.inputs, limits):
        for i, tok in enumerate(tokens(lang, s)):
            if tok is BREAK:
                continue
            key = tok.lower()
            counts[key] += 1
            # Casing is judged mid-sentence only, where capitals mean a proper noun (or "I").
            if i > 0 and (len(tok) == 1 or not tok.isupper()):
                casing[key][tok] += 1
    vocab = [w for w, _ in counts.most_common() if in_vocab(lang, w, allowed)]
    if lang == "en":
        vocab = vocab[:EN_VOCAB]
    display = {}
    for w in vocab:
        forms = casing.get(w)
        best, n = forms.most_common(1)[0] if forms else (w, 0)
        display[w] = best if best != w and n >= 0.8 * sum(forms.values()) else w
    ids = {w: i + 1 for i, w in enumerate(vocab)}  # 0 is <s>
    print(f"{lang}: {sum(counts.values()):,} tokens, vocab {len(vocab):,}", file=sys.stderr)

    # Pass 2: n-gram counts over word ids. Out-of-vocabulary words break the chain.
    V = len(vocab) + 1
    uni = Counter()
    bi = Counter()
    tri = Counter()
    for s in sentences(args.inputs, limits):
        p2, p1 = None, 0
        for tok in tokens(lang, s):
            w = ids.get(tok.lower()) if tok is not BREAK else None
            if w is None:
                p2, p1 = None, None
                continue
            uni[w] += 1
            if p1 is not None:
                bi[p1 * V + w] += 1
                if p2 is not None:
                    tri[(p2 * V + p1) * V + w] += 1
            p2, p1 = p1, w
    starts = sum(c for k, c in bi.items() if k // V == 0)
    print(f"{lang}: {len(bi):,} bigrams, {len(tri):,} trigrams", file=sys.stderr)

    ctx_bi = Counter()
    for k, c in bi.items():
        ctx_bi[k // V] += c
    by_ctx = defaultdict(list)
    for k, c in bi.items():
        if c >= MIN_NGRAM_COUNT:
            by_ctx[k // V].append((c, k % V))
    ctx_tri = Counter()
    for k, c in tri.items():
        ctx_tri[k // V] += c
    tri_ctx = {k for k, c in ctx_tri.most_common(args.max_trigram_contexts) if c >= MIN_TRIGRAM_CONTEXT}
    by_pair = defaultdict(list)
    for k, c in tri.items():
        if c >= MIN_NGRAM_COUNT and k // V in tri_ctx:
            by_pair[k // V].append((c, k % V))

    total = sum(uni.values())
    ASSETS.mkdir(parents=True, exist_ok=True)
    out_path = ASSETS / f"{lang}.txt"
    n_bi = n_tri = 0
    with open(out_path, "w", encoding="utf-8") as out:
        out.write(f"#simpletype-ngram 1 {lang}\n")
        out.write("u\t<s>\t0\n")
        for w in vocab:
            out.write(f"u\t{display[w]}\t{cost(max(uni[ids[w]], 1) / total)}\n")
        for ctx in sorted(by_ctx):
            if ctx_bi[ctx] < MIN_BIGRAM_CONTEXT:
                continue
            top = sorted(by_ctx[ctx], reverse=True)[:BIGRAM_TOP]
            denom = starts if ctx == 0 else ctx_bi[ctx]
            out.write(f"b\t{ctx}\t" + " ".join(f"{w}:{cost(c / denom)}" for c, w in top) + "\n")
            n_bi += 1
        for pair in sorted(by_pair):
            # Only worth storing when it says something the bigram list doesn't.
            top = sorted(by_pair[pair], reverse=True)[:TRIGRAM_TOP]
            bigram_top = [w for _, w in sorted(by_ctx.get(pair % V, []), reverse=True)[:len(top)]]
            if [w for _, w in top] == bigram_top:
                continue
            out.write(f"t\t{pair // V}\t{pair % V}\t" +
                      " ".join(f"{w}:{cost(c / ctx_tri[pair])}" for c, w in top) + "\n")
            n_tri += 1
    size = out_path.stat().st_size
    print(f"{lang}: wrote {out_path} ({size / 1e6:.1f} MB): {len(vocab):,} words, "
          f"{n_bi:,} bigram contexts, {n_tri:,} trigram contexts", file=sys.stderr)


if __name__ == "__main__":
    main()

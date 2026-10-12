#!/usr/bin/env python3
"""Regenerates the glide-typing dictionaries in app/src/main/assets/dictionaries/.

Formats (see docs/glide-typing.md):
  en.txt  "word<TAB>zipf100"           — keys default to the word itself
  vi.txt  "word<TAB>zipf100<TAB>keys"  — keys are the diacritic-folded qwerty letters
                                          (người -> nguoi), so users swipe base letters

Requires: pip install wordfreq
Usage:    python3 tools/generate_gesture_dictionary.py [en|vi]
"""

import re
import sys
import unicodedata
from pathlib import Path

from wordfreq import top_n_list, zipf_frequency

ASSETS = Path(__file__).resolve().parent.parent / "app/src/main/assets/dictionaries"

# ---- English configuration ---------------------------------------------------

EN_WORD_COUNT = 30_000       # entries kept in the final dictionary
EN_CANDIDATE_POOL = 60_000   # frequency-ranked pool to filter from
MAX_WORD_LENGTH = 20

# Words to always include even if outside the frequency pool: (word, zipf100).
EN_EXTRA_WORDS: list[tuple[str, int]] = [
    # ("simpletype", 200),
]

# Offensive words excluded so a sloppy swipe never produces them (Gboard does
# the same). Users can still tap-type anything.
EN_BLOCKLIST = {
    "fuck", "shit", "cunt", "nigger", "nigga", "faggot", "bitch", "asshole",
    "dick", "pussy", "cock", "whore", "slut", "bastard", "wank", "twat",
    "fucking", "fucked", "fucker", "motherfucker", "bullshit", "dickhead",
    "cocksucker",
}

# ---- Vietnamese configuration -------------------------------------------------

VI_CANDIDATE_POOL = 100_000  # wordfreq 'vi' has ~10k usable syllables; take them all
VI_MAX_KEYS_LENGTH = 12
# Local/dialect words: maintained in tools/vi-local-words.txt (one word per line,
# optional zipf100 second column — see that file's header). Loaded automatically and
# merged as overrides. VI_EXTRA_WORDS below is a programmatic escape hatch for the
# same thing; the txt file is the recommended place.
#
# NOTE on short/crowded key paths: words sharing one path (me -> mẹ/mê/mè/mệ...) rank
# purely by zipf100, and only the top 3 fit the suggestion strip. Check where your word
# lands with the sim (or on-device) and raise its value just enough — every notch also
# pushes a standard word down for that same swipe. Some paths aren't worth winning:
# "do" has 7 entries above 600 (đó/độ/do/đồ/đô...), so "dồ" stays tap-typed unless you
# deliberately give it ~630+.
VI_EXTRA_WORDS: list[tuple[str, int]] = []
VI_LOCAL_WORDS_FILE = Path(__file__).resolve().parent / "vi-local-words.txt"
# Words in the local file without an explicit zipf100 get at least this weight
# ("common in speech"), or their measured wordfreq value if that is higher.
DEFAULT_LOCAL_ZIPF100 = 450
# Letters absent from the Vietnamese alphabet; folded keys containing them are
# foreign loanwords/noise from the corpus.
VI_FOREIGN_LETTERS = set("fjwz")

# -------------------------------------------------------------------------------


def fold_diacritics(word: str) -> str:
    """Maps a Vietnamese word to the qwerty keys a user swipes: NFD-decompose,
    drop combining marks (tones, breve, circumflex, horn), then đ -> d."""
    decomposed = unicodedata.normalize("NFD", word)
    stripped = "".join(c for c in decomposed if unicodedata.category(c) != "Mn")
    return stripped.replace("đ", "d").replace("Đ", "D")


def generate_english() -> None:
    entries: list[tuple[str, int]] = []
    seen: set[str] = set()

    for word in top_n_list("en", EN_CANDIDATE_POOL):
        if not re.fullmatch(r"[a-z]+", word):
            continue
        if len(word) > MAX_WORD_LENGTH or word in EN_BLOCKLIST or word in seen:
            continue
        zipf = zipf_frequency(word, "en")
        if zipf <= 0:
            continue
        seen.add(word)
        entries.append((word, round(zipf * 100)))

    for word, zipf100 in EN_EXTRA_WORDS:
        if word not in seen and re.fullmatch(r"[a-z]+", word):
            seen.add(word)
            entries.append((word, zipf100))

    entries.sort(key=lambda e: -e[1])
    entries = entries[:EN_WORD_COUNT]

    out = ASSETS / "en.txt"
    with out.open("w", encoding="utf-8") as f:
        for word, zipf100 in entries:
            f.write(f"{word}\t{zipf100}\n")
    print(f"Wrote {len(entries)} entries to {out}")


def load_local_words() -> list[tuple[str, int]]:
    """Parses tools/vi-local-words.txt. Each non-comment line is one of:

    word                -> weight auto-derived: max(wordfreq(word), default)
    word zipf100        -> explicit weight
    word = equivalent   -> weight copied from the standard word it corresponds to
                           (e.g. "mệ = mẹ" ranks mệ like mẹ)
    """
    words: list[tuple[str, int]] = []
    if not VI_LOCAL_WORDS_FILE.exists():
        return words
    for raw in VI_LOCAL_WORDS_FILE.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue

        equivalent = None
        if "=" in line:
            head, tail = line.split("=", 1)
            line = head.strip()
            equivalent = unicodedata.normalize("NFC", tail.strip().lower())

        parts = line.split()
        if not parts:
            continue
        word = unicodedata.normalize("NFC", parts[0].lower())
        if not re.fullmatch(r"[a-z]+", fold_diacritics(word)):
            print(f"  skipping invalid local word: {parts[0]!r}")
            continue

        if equivalent is not None:
            measured = round(zipf_frequency(equivalent, "vi") * 100)
            if measured <= 0:
                print(f"  {word!r}: equivalent {equivalent!r} unknown to wordfreq, "
                      f"using default {DEFAULT_LOCAL_ZIPF100}")
                zipf100 = DEFAULT_LOCAL_ZIPF100
            else:
                zipf100 = measured
        elif len(parts) > 1:
            zipf100 = int(parts[1])
        else:
            measured = round(zipf_frequency(word, "vi") * 100)
            zipf100 = max(measured, DEFAULT_LOCAL_ZIPF100)
        words.append((word, zipf100))
    return words


def generate_vietnamese() -> None:
    entries: list[tuple[str, int, str]] = []
    seen: set[str] = set()

    for word in top_n_list("vi", VI_CANDIDATE_POOL):
        word = unicodedata.normalize("NFC", word)
        if word in seen:
            continue
        keys = fold_diacritics(word)
        if not re.fullmatch(r"[a-z]+", keys):
            continue
        if set(keys) & VI_FOREIGN_LETTERS:
            continue
        if not (2 <= len(keys) <= VI_MAX_KEYS_LENGTH):
            continue
        zipf = zipf_frequency(word, "vi")
        if zipf <= 0:
            continue
        seen.add(word)
        entries.append((word, round(zipf * 100), keys))

    for word, zipf100 in VI_EXTRA_WORDS + load_local_words():
        word = unicodedata.normalize("NFC", word)
        entries = [e for e in entries if e[0] != word]  # extras override wordfreq
        seen.add(word)
        entries.append((word, zipf100, fold_diacritics(word)))

    entries.sort(key=lambda e: -e[1])

    out = ASSETS / "vi.txt"
    with out.open("w", encoding="utf-8") as f:
        for word, zipf100, keys in entries:
            f.write(f"{word}\t{zipf100}\t{keys}\n")
    print(f"Wrote {len(entries)} entries to {out}")


def main() -> None:
    targets = sys.argv[1:] or ["en", "vi"]
    ASSETS.mkdir(parents=True, exist_ok=True)
    if "en" in targets:
        generate_english()
    if "vi" in targets:
        generate_vietnamese()


if __name__ == "__main__":
    main()

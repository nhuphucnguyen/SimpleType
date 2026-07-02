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
VI_EXTRA_WORDS: list[tuple[str, int]] = []
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

    for word, zipf100 in VI_EXTRA_WORDS:
        word = unicodedata.normalize("NFC", word)
        if word not in seen:
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

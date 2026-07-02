#!/usr/bin/env python3
"""Regenerates app/src/main/assets/dictionaries/en.txt for glide typing.

Format: one entry per line, "word<TAB>zipf100" where zipf100 is the Zipf
frequency x 100 as an integer. See docs/glide-typing.md.

Requires: pip install wordfreq
Usage:    python3 tools/generate_gesture_dictionary.py
"""

import re
from pathlib import Path

from wordfreq import top_n_list, zipf_frequency

# ---- Configuration ----------------------------------------------------------

WORD_COUNT = 30_000          # entries kept in the final dictionary
CANDIDATE_POOL = 60_000      # frequency-ranked pool to filter from
MAX_WORD_LENGTH = 20

# Words to always include even if outside the frequency pool: (word, zipf100).
EXTRA_WORDS: list[tuple[str, int]] = [
    # ("simpletype", 200),
]

# Offensive words excluded so a sloppy swipe never produces them (Gboard does
# the same). Users can still tap-type anything.
BLOCKLIST = {
    "fuck", "shit", "cunt", "nigger", "nigga", "faggot", "bitch", "asshole",
    "dick", "pussy", "cock", "whore", "slut", "bastard", "wank", "twat",
    "fucking", "fucked", "fucker", "motherfucker", "bullshit", "dickhead",
    "cocksucker",
}

# ------------------------------------------------------------------------------

ASSET = Path(__file__).resolve().parent.parent / (
    "app/src/main/assets/dictionaries/en.txt"
)


def main() -> None:
    entries: list[tuple[str, int]] = []
    seen: set[str] = set()

    for word in top_n_list("en", CANDIDATE_POOL):
        if not re.fullmatch(r"[a-z]+", word):
            continue
        if len(word) > MAX_WORD_LENGTH or word in BLOCKLIST or word in seen:
            continue
        zipf = zipf_frequency(word, "en")
        if zipf <= 0:
            continue
        seen.add(word)
        entries.append((word, round(zipf * 100)))

    for word, zipf100 in EXTRA_WORDS:
        if word not in seen and re.fullmatch(r"[a-z]+", word):
            seen.add(word)
            entries.append((word, zipf100))

    entries.sort(key=lambda e: -e[1])
    entries = entries[:WORD_COUNT]

    ASSET.parent.mkdir(parents=True, exist_ok=True)
    with ASSET.open("w", encoding="utf-8") as f:
        for word, zipf100 in entries:
            f.write(f"{word}\t{zipf100}\n")

    print(f"Wrote {len(entries)} entries to {ASSET}")


if __name__ == "__main__":
    main()

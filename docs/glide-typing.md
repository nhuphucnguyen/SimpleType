# Glide Typing (Swipe-to-Type)

How SimpleType's gesture typing works, how the dictionary is stored and loaded, and how to
extend it.

## Architecture

```
LatinKeyboard.kt (Compose UI)
  └─ captures the touch path, draws the trail, distinguishes tap / hint-flick / glide
       └─ LatinKeyboardListener.onGlideTyped(path, keyGeometry)
            └─ SimpleTypeIME
                 ├─ GestureDecoder.decode(path, geometry)  → ranked candidates
                 ├─ GestureCommit.textToCommit(...)        → auto-space + capitalization
                 └─ suggestion strip (KeyboardScreen)      → tap to replace last word
```

All decoding logic lives in `dev.phucngu.simpletype.gesture` and is **pure Kotlin with no
Android dependencies**, so it is fully covered by plain JVM unit tests
(`GestureDecoderTest`, `GestureCommitTest`).

### Decoder (SHARK²-style)

The swiped path is resampled to 32 equidistant points and compared against each candidate
word's *ideal path* (the polyline through its letters' key centers, consecutive duplicate
letters collapsed):

- **Location channel** — mean point-to-point distance in key-width units, with 3× weight
  on the first/last points (users are most precise at the endpoints).
- **Shape channel** — same comparison after both paths are translated to their centroid
  and scaled to a unit box; tolerant of sloppy-but-well-shaped gestures.
- **Frequency prior** — a Zipf-based bonus so common words win ties (e.g. the identical
  paths of "to" and "too").

Candidates are pruned before scoring: the first letter must be near the gesture start, the
last letter near the gesture end, and the ideal-path length within a ratio band of the
gesture length. Tuning constants are in `GestureDecoder`'s companion object.

Measured on simulated traces over the bundled dictionary: ~97% top-1 on clean traces,
~90% top-1 / ~99% top-3 with heavy jitter.

## Dictionary

### On-disk format (the assets)

`app/src/main/assets/dictionaries/{en,vi}.txt` — plain UTF-8 text, one entry per line,
2 or 3 tab-separated columns:

```
word<TAB>zipf100[<TAB>keys]
```

- `word`: the text committed to the editor. English: lowercase `a–z`. Vietnamese: the
  full diacritic form, NFC-normalized (e.g. `người`).
- `zipf100`: the word's [Zipf frequency](https://en.wikipedia.org/wiki/Zipf%27s_law)
  multiplied by 100, as an integer. Range in practice: ~100 (very rare) to ~770 ("the").
- `keys` (optional): the lowercase `a–z` qwerty keys the user actually swipes. Defaults
  to `word` when omitted (English). Vietnamese entries carry the diacritic-folded form
  (`người` → `nguoi`): users swipe base letters, the decoder returns the accented word.
- Line order does **not** matter; entries are bucketed at load time by `keys[0]`.
- Malformed lines are skipped silently by the parser.

Bundled files (both generated from the
[`wordfreq`](https://pypi.org/project/wordfreq/) dataset):

- `en.txt` — ~30,000 most frequent English words, profanity filtered, ~360 KB raw.
- `vi.txt` — ~9,300 Vietnamese syllables (all usable `wordfreq` entries after filtering
  out foreign tokens containing f/j/w/z), ~145 KB raw. Diacritic folding is
  NFD-decompose → strip combining marks → `đ`→`d`.

Because many Vietnamese syllables share one key path (`viet` → việt/viết), Vietnamese
top-1 accuracy is inherently lower (~87% on simulated traces vs ~97% for English), but
top-3 is ~99.5% — the intended variant is almost always in the suggestion strip. If this
matters more later, the fix is a previous-word bigram context model, not a bigger
dictionary.

### In-memory representation

`GestureDictionary` is intentionally simple: a 26-slot array indexed by first letter,
where each slot is a list of `Entry(word, zipf100)`.

There is deliberately **no trie/DAWG**. The decoder never does prefix traversal — it
prunes by first letter (one bucket lookup), then filters by last letter and length, then
scores linearly. At 30k words a decode touches only ~1–2k entries and completes in a few
milliseconds, so a smarter structure would add build complexity for no user-visible gain.
Revisit only if the dictionary grows past ~100k entries or profiling says otherwise.

### Text asset vs. prebuilt binary

The internal representation is built **at runtime**, not shipped as a binary:

- Parsing happens once per IME process, in `SimpleTypeIME.onCreate()` on
  `Dispatchers.IO` — roughly 50–150 ms on a mid-range device, never blocking the UI.
- **Known trade-off:** `gestureDecoder` is null until the load finishes, so a swipe made
  in the first instant after the keyboard process starts is silently ignored (it falls
  back to doing nothing; taps are unaffected). IME processes are long-lived, so in
  practice this window is hit rarely. If it ever becomes noticeable, the fixes are, in
  order of effort: show a brief "loading" state, block on the load with a short timeout,
  or move to a memory-mapped binary format.
- A prebuilt binary (serialized buckets or an mmap-able flat file) would cut load time
  and String-allocation churn, but adds a Gradle build step and a format-versioning
  concern. Not worth it at 30k words.

## Adding or changing words

**Quick manual edit** — append lines to the asset in any order:

```
myword	350
tuỳ	420	tuy
```

(English can omit the third column; Vietnamese must include the folded keys.) Pick
`zipf100` relative to neighbors: ~500+ for everyday words, ~300 for ordinary vocabulary,
~150 for niche terms. Overshooting inflates the word's ability to beat similar-path
competitors.

**Regenerate from scratch** — run the generator (requires `pip install wordfreq`):

```
python3 tools/generate_gesture_dictionary.py        # both languages
python3 tools/generate_gesture_dictionary.py vi     # one language
```

Word counts, extra words (`EN_EXTRA_WORDS` / `VI_EXTRA_WORDS`), and the profanity
blocklist are configured at the top of that script. After regenerating, run
`./gradlew test` — `GestureDecoderTest` and `VietnameseGlideTest` include integration
tests that decode common words against the real assets.

**Future: user dictionary.** The clean seam is `GestureDictionary.parse(InputStream)`:
load a second, user-writable file (e.g. from internal storage) and merge its buckets, or
give `GestureDictionary` an `add(word, zipf)` method and call it for words the user types
or corrects. Nothing in the decoder assumes a single source.

## Behavior notes

- Glide is active for **English and Vietnamese on the QWERTY layout**, and disabled for
  password and direct-commit (`TYPE_NULL`) fields.
- **Vietnamese**: swipe the base letters (Gboard-style) — `n-g-u-o-i` commits `người`.
  Telex modifier keys are *not* part of the swipe. Tap-typing with Telex is unchanged;
  a glide first finishes any in-progress Telex composition, and tapping an alternate
  suggestion releases the Telex context pickup before replacing the word.
- The swipe-down-for-number/symbol hint still works with glide enabled, but is resolved
  on finger-up: a fast (<250 ms), short, mostly-vertical flick fires the hint; anything
  longer or wider is decoded as a word.
- Settings → Typing options → **Glide typing** toggles the feature
  (`kb_glide` in `simpletype_prefs`, default on).

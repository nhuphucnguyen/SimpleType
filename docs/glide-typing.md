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

### On-disk format (the asset)

`app/src/main/assets/dictionaries/en.txt` — plain UTF-8 text, one entry per line:

```
word<TAB>zipf100
```

- `word`: lowercase `a–z` only (no diacritics, apostrophes, or digits).
- `zipf100`: the word's [Zipf frequency](https://en.wikipedia.org/wiki/Zipf%27s_law)
  multiplied by 100, as an integer. Range in practice: ~100 (very rare) to ~770 ("the").
- Line order does **not** matter; entries are bucketed at load time.
- Malformed lines are skipped silently by the parser.

The bundled file holds the ~30,000 most frequent English words (generated from the
[`wordfreq`](https://pypi.org/project/wordfreq/) dataset, profanity filtered), ~360 KB raw
and considerably smaller once compressed inside the APK.

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

**Quick manual edit** — append lines to `en.txt` in any order:

```
myword	350
```

Pick `zipf100` relative to neighbors: ~500+ for everyday words, ~300 for ordinary
vocabulary, ~150 for niche terms. Overshooting inflates the word's ability to beat
similar-path competitors.

**Regenerate from scratch** — run the generator (requires `pip install wordfreq`):

```
python3 tools/generate_gesture_dictionary.py
```

Word count, extra words, and the profanity blocklist are configured at the top of that
script. After regenerating, run `./gradlew test` — `GestureDecoderTest` includes an
integration test that decodes common words against the real asset.

**Future: user dictionary.** The clean seam is `GestureDictionary.parse(InputStream)`:
load a second, user-writable file (e.g. from internal storage) and merge its buckets, or
give `GestureDictionary` an `add(word, zipf)` method and call it for words the user types
or corrects. Nothing in the decoder assumes a single source.

## Behavior notes

- Glide is active only for **English + QWERTY layout**, and disabled for password and
  direct-commit (`TYPE_NULL`) fields. Vietnamese/Telex input is untouched (Vietnamese
  glide would need diacritic-aware decoding — see product backlog).
- The swipe-down-for-number/symbol hint still works with glide enabled, but is resolved
  on finger-up: a fast (<250 ms), short, mostly-vertical flick fires the hint; anything
  longer or wider is decoded as a word.
- Settings → Typing options → **Glide typing** toggles the feature
  (`kb_glide` in `simpletype_prefs`, default on).

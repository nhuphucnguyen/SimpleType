# Word Suggestions (Next-Word Prediction)

Gboard-style suggestions in the toolbar strip: the likely next word after a space, and
completions of the word being typed.

## Behaviour

- **After a space** (or at the start of a field or sentence) the strip shows three
  predictions, the best in the middle. They are capitalised when a sentence starts.
- **While typing a word** it shows completions of it. If the exact typed text isn't one of
  them, it takes the left slot so it can be kept as-is.
- **Tapping a suggestion** replaces the word in progress with it, adds a space and shows the
  next predictions, so whole phrases can be tapped out. Punctuation typed right after takes
  the automatic space back (`word .` becomes `word.`).
- **Glide typing**: after a swipe the strip shows the decoder's candidates as before. Tapping
  one (including the one already applied) settles the word, adds a space and switches to
  next-word predictions.
- **Vietnamese**: completions match without accents, so `viet` offers `việt` and `viết`;
  accents already typed with Telex rank matching words first.
- Off in password, email, URL, `NO_SUGGESTIONS` and raw (`TYPE_NULL`) fields. Settings →
  Typing has a toggle and **Forget learned words**.

## Architecture

```
SimpleTypeIME ── refreshPredictions() after keys, cursor moves, picks
  └─ WordPredictions (ime)         models + histories per language, load/save
       └─ NextWordPredictor (predict)  scoring, casing
            ├─ NgramModel             bundled trigram model (assets/predict/{en,vi}.txt)
            ├─ UserHistory            words/pairs the user typed (filesDir)
            └─ TypingContext          parses the text before the cursor
```

`dev.phucngu.simpletype.predict` is pure Kotlin and covered by `NextWordPredictorTest`,
which also checks a few phrases against the bundled models.

### Scoring

Stupid backoff in log10: a word's score is its trigram probability given the two previous
words, else 0.4 × its bigram probability given the previous word, else 0.16 × its unigram
probability. `<s>` (start of sentence) is a context word, so the first word of a sentence
is predicted from what sentences usually start with. Completions also scan every
vocabulary word with the typed prefix (binary search over the accent-folded, sorted
vocabulary).

User history adds `P(word | previous word)` as observed in the user's own typing, and
lets words the user types (names, slang) appear as completions. A word or pair needs two
uses before it is suggested, so a one-off typo isn't.

### Learning

A word is learned when it is finished: by space, enter or punctuation, by tapping a
suggestion, or when a glided word is followed by another glide. Nothing is learned where
the app sets `IME_FLAG_NO_PERSONALIZED_LEARNING` (incognito) or where suggestions are off.
A capital that only comes from starting a sentence isn't learned as the word's casing.
Histories are capped (4,000 words and 12,000 pairs, rarest dropped first) and saved to
`files/predict_history_{english,vietnamese}.txt` when a field closes.

## Models

`tools/generate_prediction_model.py` counts n-grams over a plain-text corpus and writes the
pruned model; the file format is documented at the top of the script. Vocabulary is
limited to words in the glide dictionaries (`assets/dictionaries/`), which are already
curated and profanity-filtered, plus an extra blocklist for predictions.

| Model | Corpus | Words | Size |
|-------|--------|-------|------|
| `en.txt` | OpenSubtitles v2018 (OPUS), English side of en-vi, 21M tokens | 31.5k | 2.5 MB |
| `vi.txt` | OpenSubtitles vi + news/Wikipedia from the vietnamese-punctuation corpus, 124M tokens | 9.4k syllables | see script output |

Subtitles are the closest freely available text to chat, but they skew towards film
dialogue ("Hello" → "Mr"); learned history corrects that for each user. Models parse in a
background coroutine at keyboard start (until then the strip simply has no predictions).

To regenerate (see the script's docstring for the corpus URLs):

```
python3 tools/generate_prediction_model.py en OpenSubtitles.en-vi.en
python3 tools/generate_prediction_model.py vi subs.txt.gz news_wiki.txt \
    --max-lines 0,1500000 --max-trigram-contexts 60000
```

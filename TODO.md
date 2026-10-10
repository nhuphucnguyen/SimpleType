# TODO

## Vietnamese punctuation model

The on-device model (`voice/punct/`, trained in the `vietnamese-punctuation` repo) restores
`.` and `,` in Vietnamese dictation and judges pauses between VAD segments. Next steps, in
order of payoff:

### 1. Question marks — small, very visible
Questions currently end with `.`. Vietnamese questions are strongly marked by particles
(*không, chưa, à, hả, nhỉ, sao, gì, đâu, mấy, bao giờ*), and the training data already has
`?` (it is folded into PERIOD today).
- [ ] Add a QUESTION label to the training data pipeline
- [ ] Fine-tune (~1 h on the Mac) and recalibrate the decision rule
- [ ] Re-export `punct_vi.bin` and update the Kotlin decision rule + tests

### 2. Proper-noun capitalisation — medium, very visible
The vi Zipformer outputs all caps and everything is lowercased: "hà nội", "anh tuấn",
"việt nam".
- [ ] Add a second (casing) head to the same model; labels come from the original corpus casing
- [ ] Retrain from scratch (hours on the Mac, much faster on the RTX 4060)
- [ ] Apply the casing in `Punctuator` / `SherpaText`; near-zero extra cost on the phone

Do 1 and 2 together in one retrain: they share the data pipeline.

### 3. Real-dictation log — the most reliable improvement over time
All current test sets were written by Claude models, so the scores may be optimistic.
- [ ] Opt-in, local-only "punctuation log" toggle in Settings (strings in `strings.xml`)
- [ ] Record the raw ASR segment and the text the user finally kept after edits
- [ ] Export the log; use it as an honest test set and as fine-tuning corrections

Start this early so real data accumulates while 1 and 2 are in progress.

### 4. Bigger / distilled model on the RTX 4060 — later
- [ ] Train a 20–30M parameter model on more data, or distil from PhoBERT
- [ ] Expect a few points of comma F1; check the per-segment latency (logcat
      `SherpaAsrEngine: punctuated in … ms`) stays acceptable (~50–100 ms)

### Not now
- Inverse text normalisation (spoken numbers → digits, "hai mươi lăm nghìn" → "25.000"):
  a separate, fiddly problem with a smaller payoff than the items above.

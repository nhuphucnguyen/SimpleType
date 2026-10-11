# TODO

## Vietnamese punctuation model

The on-device model (`voice/punct/`, trained in the `vietnamese-punctuation` repo) restores
`.` `,` `?` and proper-noun casing in Vietnamese dictation and judges pauses between VAD segments. Next steps, in
order of payoff:

Status (2026-10-11): model **m09** ships (`assets/punct/punct_vi.version`): `?` and
proper-noun casing, decision rule read from `punct_vi.bin` (VPN2). Spoken test set, m07 → m09:
comma 0.73 → 0.76, question 0 → 0.89, casing 0 → 0.97, pause decisions between segments
0.80 → 0.84 accuracy.

### 1. Question marks — done (m08/m09)
- [x] QUESTION label in the training data, fine-tuned, decision rule recalibrated
- [x] `punct_vi.bin` v2 + `Punctuator` / `SherpaText` / `VoiceCommandHandler` handle `?`
      (a pause can also turn the previous `.` into `?` and back)

### 2. Proper-noun capitalisation — done (m08/m09)
- [x] Casing outputs on the same model (lower / Cap / UPPER); applied in `Punctuator`
- [ ] Acronyms are weak ("fpt" stays lowercase): UPPER is rare in the data
- [ ] Not retrained from scratch: the head was grown onto m07. A from-scratch run on
      dataset_v4 (`run_all.sh`) is the cleaner baseline, best on the RTX 4060

### 3. Real-dictation log — app side done, waiting for data
- [x] Opt-in, local-only toggle in Settings › Voice ("Help improve Vietnamese punctuation"):
      segments as committed + the text kept when the field is left; export / clear
- [ ] Use it for a few weeks, export, then in vietnamese-punctuation:
      `python src/import_dictation_log.py <exported.jsonl>` → real test set + fine-tune split

### 4. Bigger / distilled model on the RTX 4060 — later
- [ ] Train a 20–30M parameter model on more data, or distil from PhoBERT
- [ ] Expect a few points of comma F1; check the per-segment latency (logcat
      `SherpaAsrEngine: punctuated in … ms`) stays acceptable (~50–100 ms)

### Not now
- Inverse text normalisation (spoken numbers → digits, "hai mươi lăm nghìn" → "25.000"):
  a separate, fiddly problem with a smaller payoff than the items above.

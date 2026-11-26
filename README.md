# Rafiki wa Biashara — Android app

The offline Android client of **Rafiki wa Biashara**, a bilingual (English/Kiswahili) advisory assistant for Kenyan MSME operators. This is the deployed part of my MSIT Master's project at Strathmore University. Operators use it in the pilot and usability study.

> **This repository is one of two.** The research and evaluation side of the project (knowledge base, verified answers, router source, fine-tuned model, test set and evaluation scripts) lives in
> **[kathembo-tsongo/kenya-msme-adtc](https://github.com/kathembo-tsongo/kenya-msme-adtc)**.
> This repository holds only the phone app.

## How the two repositories fit together

```
kenya-msme-adtc (research + evaluation)          rafiki-wa-biashara-mobile (this repo)
─────────────────────────────────────────        ─────────────────────────────────────
query_pack.py   router (source of truth)  ──port──▶  rafiki/Router.kt
build_pack.py   builds rafiki_pack.db     ──pack──▶  app/src/main/assets/rafiki_pack.db
download_model.sh  fine-tuned GGUF model  ──file──▶  chosen on the phone at first run
pull_phone_log.py  analyses the pilot log ◀──log───  rafiki/RafikiLog.kt (rafiki_log.jsonl)
eval/              laptop + phone evaluation ◀─────  same routes, same test set
```

- **Router.** `Router.kt` is a line-for-line port of `route()` in `query_pack.py`. All word lists and thresholds come from the knowledge pack's config tables, so the Python and phone routers share one source. A parity self-test (`assets/parity.txt`, same format as `query_pack.py --parity`) checks that the phone and the laptop route every test question identically.
- **Knowledge pack.** `rafiki_pack.db` is a single SQLite file (verified answers in English and Kiswahili, topic keywords, bilingual glossary, corpus chunks with an FTS4 index, and the pack version and "accurate as of" date). It is built by `build_pack.py` in `kenya-msme-adtc` and is not committed here.
- **Model.** The fine-tuned `msme-qwen2.5-1.5b-Q4_K_M.gguf` (about 940 MiB) runs on the phone through llama.cpp. It is distributed separately (Hugging Face `kathembo-tsongo/qwen-msme-gguf`), not in git.
- **Versions.** Every log line records the pack version (with its source commit in `kenya-msme-adtc`) and the app version, so each result can be traced to the exact code that produced it.

## What the app does

Each question is routed before any text is generated. Only three routes reach the model:

| Route | What happens |
|---|---|
| FACTUAL (strong match) | The model answers **only** from the retrieved passages |
| DIGEST | The model answers from verified facts in the system prompt |
| ADVISORY | General advice, labelled as such |

Everything else is answered instantly from the pack, with no model and nothing that can be invented: verified answers, topic suggestions, out-of-scope replies, and app information such as the date. `FigureCheck.kt` also removes any money amount or percentage in a model answer that does not appear in the passages it was given.

## Code layout

The app lives in llama.cpp's Android example. Everything else in `llama.cpp/` is upstream llama.cpp.

```
llama.cpp/examples/llama.android/
├── lib/                      llama.cpp JNI bindings (upstream)
└── app/src/main/
    ├── assets/               parity.txt (+ rafiki_pack.db, copied in before building)
    └── java/com/example/llama/
        ├── MainActivity.kt         chat screen, model selection
        ├── HistoryActivity.kt      past conversations
        ├── ConversationStore.kt    conversation storage
        ├── MessageAdapter.kt       chat bubbles
        └── rafiki/
            ├── Rafiki.kt           app-wide router + conversation state, system prompts
            ├── Router.kt           question router (port of query_pack.route())
            ├── Responder.kt        turns a route into the answer the operator sees
            ├── AndroidPack.kt      opens the pack on Android SQLite; PackInstaller
            ├── FigureCheck.kt      removes invented amounts and percentages
            ├── PilotConsent.kt     consent screen (Kiswahili/English) + participant code
            ├── PilotTools.kt       researcher menu: export log and report, withdraw
            └── RafikiLog.kt        study log, one JSON line per question
```

`Router.kt` and `Responder.kt` are plain Kotlin with no Android imports, so they can be tested off the phone.

## Building

Requirements: Android Studio (or the command-line SDK), Android NDK 29, a phone with Android 13 or later (minSdk 33), arm64.

1. **Build the knowledge pack** in `kenya-msme-adtc`:
   ```bash
   cd ~/"Programming Projects"/kenya-msme-adtc
   python3 build_pack.py --as-of YYYY-MM-DD
   ```
2. **Copy it into the app's assets:**
   ```bash
   cp rafiki_pack.db ~/"Programming Projects"/rafiki-wa-biashara-mobile/llama.cpp/examples/llama.android/app/src/main/assets/
   ```
3. **Build and install the APK:**
   ```bash
   cd ~/"Programming Projects"/rafiki-wa-biashara-mobile/llama.cpp/examples/llama.android
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. **Put the model on the phone** (from `kenya-msme-adtc`, after `bash download_model.sh`):
   ```bash
   adb push model/msme-qwen2.5-1.5b-Q4_K_M.gguf /sdcard/Download/
   ```
   On first launch the app shows the consent screen. Then choose the `.gguf` file from Downloads.

## Pilot study and logging

- On first launch the app asks for consent in Kiswahili and English (`PilotConsent.kt`). Logging starts only after "Nakubali / I agree".
- Log lines are linked to a **participant code**, never a name, phone number or location.
- The researcher menu (long-press the history button) exports the log and a readable report, or withdraws a participant.
- Pull the log to the laptop with `python3 pull_phone_log.py` in `kenya-msme-adtc`.
- The consent text in `PilotConsent.kt` is a draft. Replace it with the wording approved by the ethics committee before the study.

## Current limitations

These are being worked on:

- Model answers are written in English. Kiswahili users get verified answers in Kiswahili, but generated answers are in English, with a footer explaining this.
- A new knowledge pack is bundled in the APK, so updating it means installing a new APK.
- Operators choose the model file by hand at first run.
- The app's buttons and messages are in English only.

## Licence

The app code is built on the llama.cpp Android example (MIT licence, see `llama.cpp/LICENSE`).

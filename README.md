<p align="center"><img src="art/libreon-store-icon-512.png" width="140" alt="Libreon: a fox curled around three coloured tiles"></p>

<h1 align="center">Libreon</h1>
<p align="center"><b>Work freely.</b><br>A private, fully offline office suite for Android. No ads, no subscriptions, no trackers, no AI.</p>

<p align="center"><i>Beta: expect rough edges, and please report them.</i></p>

---

## Why this exists

You know the drill. You just want to open a Word file on your phone. Instead you get a full-screen ad, a "Go Premium!" pop-up, a nudge to sign in, another ad, a request to "let our AI summarise this for you", and then, finally, the document, with a banner ad stuck over the bottom third of it.

I got tired of that. Every popular Android office app seemed to want my money every two seconds, my data all the time, or my patience for AI features nobody asked for. So I did something about it: I *reverse vibe coded* a clean-room office app that does what those apps do, minus the junk.

**No ads. No payments. No trackers. No AI. No account. It doesn't even need the internet.** It opens your files, lets you edit them, and stays out of your way. You can work freely.

The whole thing was built with two AI coding assistants doing the heavy lifting: **Claude Code (Opus 5.5)** and **Google Antigravity (Gemini 3.8 Flash)**. Yes, it's a little ironic that an app with no AI in it was built with AI. The AI wrote code; it didn't move into the app.

"Clean room" means no code, resources or assets were taken from any existing app. Existing apps were only used to understand what features people expect. Everything here is written from scratch or comes from properly licensed open-source projects (listed below).

## What it does

**Office documents**
- Open, edit and save Word, Excel and PowerPoint files: `.docx` `.xlsx` `.pptx`, and the older `.doc` `.xls` `.ppt`.
- OpenDocument (`.odt` `.ods` `.odp`, plus flat `.fodt` `.fods` `.fodp`) and `.rtf`.
- Apple **Pages, Numbers and Keynote** files open read-only.
- Find in document (with match counts), and a live word count for Word documents.

**PDF**
- Smooth viewing with pinch zoom, thumbnails and search.
- Fill PDF forms, add text, check marks and crosses to flat forms, and **sign** with saved signatures.
- **Highlight text** by long-pressing and dragging. Highlights are standard PDF annotations, so other PDF apps see them too.
- Copy selected text and count its words.

**Text**
- Plain text, Markdown (with live preview), CSV (with a table view) and JSON (with formatting), plus many code and config file types.
- Handles huge files (hundreds of MB) without choking.
- Find and replace, go to line, and a word count in the status line.

**Everywhere**
- **Tabs:** keep lots of documents open at once. If Android closes the app in the background, your unsaved changes are still there when you come back.
- **Search** your open, recent and recently closed files by name.
- **Word count** for any document and for your selection.
- Light, dark or follow-the-system themes (set in Settings), "light pages" for a single document while the app is dark, and a themed icon that matches your wallpaper.
- Screen-reader friendly controls.

## Privacy

Libreon has **no internet permission at all**. Android itself stops it from going online. There are no accounts, no analytics, no crash reporters and no "cloud" anything. Your documents never leave your device.

## Install

1. Download the APK from [Releases](../../releases):
   - `arm64-v8a` for almost every phone and tablet;
   - `x86_64` for emulators and some Chromebooks.
2. Open it and allow installing from your browser or file manager when Android asks.

Requires **Android 12 or newer**. The download is about 115 MB, because a whole office engine lives inside.

## How it works

Libreon is a native Android app (Kotlin and Jetpack Compose) with three engines:

| Part | What it uses |
|---|---|
| **Office documents** | [LibreOffice](https://www.libreoffice.org/) core through LibreOfficeKit, with the [Collabora Online](https://www.collaboraonline.com/) web front-end running locally in a WebView. Each Office document runs in its own process, shown in the app through a shared surface, so a heavy document can't take the app down with it. |
| **PDF** | [PDFium](https://pdfium.googlesource.com/pdfium/) (the PDF engine in Chrome) through a small JNI bridge, for rendering, forms, annotations and saving. |
| **Text** | A custom editor built on Compose text fields, with streaming and line indexing for very large files, [commonmark-java](https://github.com/commonmark/commonmark-java) for Markdown, and its own CSV and JSON handling. |

Other details:
- **Tabs and memory:** documents are "hot" (engine loaded) or "parked" (saved to a recovery copy and unloaded) depending on how much memory the phone has, so you can keep many tabs without running out.
- **Saving:** files are written back to where they came from through Android's storage framework. If another app changed the file meanwhile, Libreon notices and asks before overwriting.
- **Signing:** releases are signed with a private key that is not in this repository.

## Building from source

You need Windows with WSL 2 (or Linux), the Android SDK and NDK, and a lot of disk space and patience. The Office engine takes a few hours to build.

1. **Office engine:** in a WSL distro, run `native/lok/provision.sh` once, then `native/lok/build-all.sh arm64-v8a` (and/or `x86_64`). This builds LibreOffice core and the Collabora front-end for Android and copies the results into `third_party/lok/`.
2. **PDFium:** run `scripts/fetch-pdfium.sh` to fetch the prebuilt library into `third_party/pdfium/`.
3. **App:** `./gradlew assembleRelease`. Without a signing key in `signing/`, release builds are signed with the debug key.

The icon layers are generated from the artwork in `art/` by `art/make_icons.py`.

More detail: [architecture](docs/architecture.md) and [milestones and test results](docs/milestones.md).

## Status

This is a **beta**. It works well on the devices it was tested on (Android emulator and BlueStacks); real-phone testing is ongoing. Known gaps:
- Printing and exporting Office documents to other formats aren't available yet.
- Copy and paste inside Office documents stays within that document.
- Apple iWork files are view-only.

## License

Libreon is free software, licensed under the [GNU General Public License v3.0 or later](LICENSE).

Its Office engine is based on LibreOffice, from The Document Foundation, and the Collabora Online front-end (both MPL-2.0). Libreon is not affiliated with The Document Foundation or Collabora. Every third-party component and its license is listed in [docs/licenses.md](docs/licenses.md) and on the app's "Open-source licenses" screen.

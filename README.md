<p align="center"><img src="art/libreon-store-icon-512.png" width="140" alt="Libreon: a fox curled around three coloured tiles"></p>

<h1 align="center">Libreon</h1>
<p align="center"><b>Work freely.</b><br>A private, fully offline office suite for Android. No ads, no subscriptions, no trackers, no AI.</p>

<p align="center">
  <a href="https://github.com/jainiakhil/Libreon/releases/latest"><img src="https://img.shields.io/badge/Download-Latest%20APK-2ea44f?style=for-the-badge&logo=android&logoColor=white" alt="Download the latest APK"></a>
  <br><sub>Under <b>Assets</b>, pick the <code>arm64-v8a</code> APK: it's the one for almost every phone. <a href="#install">Install help</a></sub>
</p>

<p align="center"><i>Beta: expect rough edges, and please report them.</i></p>

---

## Why this exists

You know the drill. You just want to open a Word file on your phone. Instead you get a full-screen ad, a "Go Premium!" pop-up, a nudge to sign in, another ad, a request to "let our AI summarise this for you", and then, finally, the document, with a banner ad stuck over the bottom third of it.

I got tired of that. Every popular Android office app seemed to want my money every two seconds, my data all the time, or my patience for AI features nobody asked for. So I did something about it: I built a clean-room office app that does what those apps do, minus the junk.

**No ads. No payments. No trackers. No AI. No account. It doesn't even need the internet.** It opens your files, lets you edit them, and stays out of your way. You can work freely.

**About the AI:** I built the Android app with two AI coding assistants, **Claude Code (Opus 5.5)** and **Google Antigravity (Gemini 3.8 Flash)**, while I designed it, reviewed the work and tested it on real phones. The parts that open, edit and save your documents are [LibreOffice](https://www.libreoffice.org/) and [PDFium](https://pdfium.googlesource.com/pdfium/), mature open-source engines written by people over decades. Yes, it's a little ironic that an app with no AI in it was built with AI: the AI wrote code; it didn't move into the app. And with no internet permission and every line of code public, you don't have to take my word for any of it.

"Clean room" means no code, resources or assets were taken from any existing app. Existing apps were only used to understand what features people expect. Everything here is written from scratch or comes from properly licensed open-source projects (listed below).

## What it does

**Office documents**
- Open, edit and save Word, Excel and PowerPoint files: `.docx` `.xlsx` `.pptx`, and the older `.doc` `.xls` `.ppt`.
- OpenDocument (`.odt` `.ods` `.odp`, plus flat `.fodt` `.fods` `.fodp`) and `.rtf`.
- Apple **Pages, Numbers and Keynote** files open read-only.
- Find in document (with match counts), zoom buttons and zoom levels (fit width, 50%–400%), and a live word count for Word documents.
- **Export or share as PDF**, ready for printing (Android's share sheet includes Print).
- Copy and paste with other apps.

**PDF**
- Smooth viewing with pinch zoom (up to 1600%, or zoomed out to see several pages), preset zoom levels, thumbnails and search.
- Fill PDF forms, add text, check marks and crosses to flat forms, and **sign** with saved signatures.
- **Highlight, underline or strike out text** by long-pressing and dragging, and **annotate** with freehand drawing, rectangles, ellipses and sticky notes. These are standard PDF annotations, so other PDF apps see them too.
- **Page tools:** rotate, move, delete and extract pages, or insert the pages of another PDF.
- Copy selected text and count its words.

**Text**
- Plain text, Markdown (with live preview), CSV (with a table view) and JSON (with formatting), plus many code and config file types.
- Handles huge files (hundreds of MB) without choking.
- Find and replace, go to line, and a word count in the status line.

**Everywhere**
- **Tabs:** keep lots of documents open at once. If Android closes the app in the background, your unsaved changes are still there when you come back.
- **Search** your open, recent and recently closed files by name.
- **Share** any open document, unsaved edits included, and read it **full screen**.
- **Word count** for any document and for your selection.
- Light, dark or follow-the-system themes (set in Settings), "light pages" for a single document while the app is dark, and a themed icon that matches your wallpaper.
- Screen-reader friendly controls.

## Privacy

Libreon has **no internet permission at all**. Android itself stops it from going online. There are no accounts, no analytics, no crash reporters and no "cloud" anything. Your documents never leave your device.

The only web links in the app are at the bottom of Settings (the GitHub page and the coffee banner below). They open in your browser, and only if you tap them; Libreon itself still never goes online.

## Install

1. Download the APK from the [latest release](https://github.com/jainiakhil/Libreon/releases/latest) (under **Assets**):
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

This is a **beta**. It has been tested on a **Samsung Galaxy S24 Ultra** (Android 17, One UI 9), the Android emulator and BlueStacks. More real-phone testing is ongoing, and [bug reports](../../issues/new?template=bug_report.yml) are very welcome. Known gaps:
- Office documents export to PDF only (other formats aren't offered yet). Printing goes through Share as PDF.
- Scanned PDFs have no text recognition (OCR) yet, and the existing text of a PDF can't be edited.
- Apple iWork files are view-only.

## Support Libreon

Libreon is free and always will be: no ads, no subscriptions, no "premium" version, no nagging. If it saves you some money or frustration and you'd like to say thanks, you can [buy me a coffee](https://buymeacoffee.com/jainiakhil).

That's the **only** place it will ever come up: a small banner at the very bottom of Settings (⋮ → Settings). No pop-ups, no reminders, no features held back. Ignoring it is completely fine.

## Contributing

Bug reports, ideas and fixes are all welcome, and you don't need to be a developer to help.

- **Found a bug?** [Open a bug report](../../issues/new?template=bug_report.yml). You can also get there from the app: ⋮ → Settings → *Report a problem or suggest a feature*.
- **Have an idea?** [Suggest a feature](../../issues/new?template=feature_request.yml), or give a 👍 to an existing one.
- **Want to fix something yourself?** Fork the repository, make your change on a branch, and open a pull request. [CONTRIBUTING.md](CONTRIBUTING.md) walks through it step by step, along with the few rules every change keeps (offline, no ads or trackers, clean room).

## License

Libreon is free software, licensed under the [GNU General Public License v3.0 or later](LICENSE).

Its Office engine is based on LibreOffice, from The Document Foundation, and the Collabora Online front-end (both MPL-2.0). Libreon is not affiliated with The Document Foundation or Collabora. Every third-party component and its license is listed in [docs/licenses.md](docs/licenses.md) and on the app's "Open-source licenses" screen.

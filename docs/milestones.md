# Milestone log

Results of each milestone as built and tested. Devices: Android 16 emulator (x86_64 phone, 1080×2400) and BlueStacks (Android 16, x86_64, 1920×1080 windowed).

## M0: engine spikes (2026-10-31)

**PDFium**
- Prebuilt `chromium/8100` plus our JNI bridge.
- All test PDFs open in 150–410 ms and render correctly.

**LibreOfficeKit (x86_64)**
- Built from Collabora's monorepo in WSL2 (`native/lok/`). The engine takes about 3 h on 12 cores; the front-end/Android layer about 11 min.
- **Opening** a docx works with the device in airplane mode: about 1.9 s, about 290 MB PSS.
- **Edit and save:** typing in edit mode followed by `.uno:Save` (22 ms) wrote back to the original MediaStore file. The marker was present, the paragraph count unchanged, and the non-space characters differed by exactly the typed text.
- **No PDFium clash:** the engine was built with `--disable-pdfimport --disable-pdfium` and exports no `FPDF*` symbols.

**Problems found**
- **Size.** `liblo-native-code.so` was 204 MB stripped (76 MB gzip), and the upstream test APK 262 MB. Slimming results (x86_64, all stripped):

  | Step | Library | gzip |
  |---|---|---|
  | M0 baseline | 204 MB | 76 MB |
  | Feature disables (DB, scripting, Java, avmedia, CoinMP, ZXing, galleries, templates, dictionaries) | 192 MB | — |
  | `-Os` passed to make (`.text` 86 → 74 MB) | 180 MB | 67 MB |
  | RELR relocations (`.rela.dyn` 21 → 0.4 MB) | **158 MB** | **65 MB** |

  LTO is not usable (it adds a GCC-only linker flag that NDK clang rejects). RELR needed a second change: the final library link in `android/Bootstrap/Makefile.shared` ignores `LDFLAGS`, so `build-engine.sh` adds the flag to that line in the build tree. The 33 MB ICU data file is the next candidate (see architecture §10).
- **Process kill on close.** Confirmed in M4: on Android, closing a document ends LibreOffice's main loop and the process exits (status 0), whether the close comes from `BYE` or from loading the next document. See architecture §10.1.
- **Offline message.** The front-end briefly showed "The server has been disconnected" while offline, then recovered. To be suppressed in M4.
- **Test input.** `adb input text` does not reach the WebView's text input; key events do. This affects automated tests only.

## M1: app shell
Built, then device-tested on the emulator:
- Tabs, switcher and home screen.
- SAF open and safe save, including the external-change conflict prompt.
- Recovery copies and restore after the process is killed.
- No permissions (enforced at build time).

Known limitation: the same file opened through two different providers gives two tabs.

## M2: text and Markdown (merged a9a4c51)
- Encoding, BOM and line endings preserved byte-exactly: UTF-16LE, Latin-1, CR, CRLF.
- Large-file read-only mode (5 MB tested) and first/last 4 MB views for files over 50 MB.
- Find and replace, a Markdown preview locked down with no JS and no network, CSV table view, JSON format.
- 25 JVM tests.

## M3: PDF (merged a67f59e)
- Zoom with sharp re-render, thumbnails, search, and a password prompt.
- AcroForm text, checkbox and choice fields.
- Flat-form text and marks.
- Signatures: drawn or imported, stored on device, flattened by default or kept as a /Stamp annotation.
- Save as new file by default for signed documents; incremental save only after a classic xref table.
- Independently re-verified with pypdf: field values present; a flattened signature lives in page content; annotation-only adds exactly one /Stamp; the original is untouched.
- 21 JVM tests.
- Gaps:
  - no undo for form or mark edits
  - flattened text is Latin-only (Helvetica)
  - photo import untested on a device
- Cross-model review (Gemini 3.8 Flash) of `pdf_jni.cpp`, findings checked against the code:
  - **Fixed:** a page-cache teardown that could hand a freed page to form callbacks; an unchecked bitmap wrap in `renderPage`; unchecked JNI allocations (`NewFloatArray`, `GetStringChars`); an ignored second-read result in `ReadWide`.
  - **Rejected:** the `pageSize` array-length "issue" (the internal caller always passes a size-2 array).
  - **Known risk:** form callbacks that load more than 4 other pages could evict the caller's cached page. Unlikely; revisit if form-heavy PDFs misbehave.

## M4: Word, Excel and PowerPoint (branch m4-office)
The engine runs in its own `:office` process and draws into the app's tab (architecture §10.1). Tested on BlueStacks and the phone emulator:
- **Opens and renders:** docx, a docx template with 52 content controls, xlsx (both sheets, formulas), a 16 MB pptx with sections, legacy .doc and .ppt. 10 tabs across all engines at once.
- **Editing:** touch, the hardware keyboard and the on-screen keyboard reach the page; edits mark the tab unsaved.
- **Save round trips:**
  - docx: paragraph count unchanged (37) and the edit present; the only other difference is apostrophes written as `&apos;`, which is the same text.
  - xlsx: the edited cell present, both sheets and all three formulas kept (the engine adds `aca="false"` to formulas).
- **Tab switching:**
  - Office to PDF and back keeps the same engine process and page state.
  - Office to Office starts a new engine process in about 2 s.
  - An unsaved spreadsheet edit survived an Office-to-Office switch (recovery copy) and a full app restart (restored banner).
- **Bugs found and fixed during testing:**
  - The surface sat below our window, so input never reached the page.
  - The native `SAVE` notice was mistaken for a save request, which looped.
  - Saving an unmodified document failed in the engine.
- **Known gaps:**
  - The CV template refuses edits ("write-protected content"); its form-filling restrictions still need checking.
  - Print, export (PDF and others) and links are ignored.
  - Clipboard is internal to the document only.
  - On the phone the page zooms in after entering edit mode.
  - The first open after install unpacks engine files (about 2.7 s on the emulator).
  - arm64 has no engine build yet: arm64 APKs contain the assets but no engine library.
- 18 new JVM tests (socket framing, engine messages), written by Gemini 3.8 Flash and reviewed; 64 in total.

## M5: polish (in progress)
Tested on the phone emulator (Android 16, 1080×2400) unless noted.
- **Memory pressure and recreation:**
  - Activity destroyed and recreated ("Don't keep activities"): the Office tab re-attaches with its edit state.
  - Rotation: re-laid out, edit state kept.
  - Background memory trim with a hidden dirty Office tab: the tab is checkpointed and its engine process ended; the edit is back when the tab is reopened.
  - Engine process killed while visible: the app keeps running and shows "The Office engine stopped unexpectedly" with **Reload**, which reopens from the recovery copy.
- **Fixed:** the engine surface sits above our window, so progress and error messages were hidden under it. The surface now stays transparent while loading and is removed on failure.
- **Dark mode:** the app and Markdown preview already followed the system theme. Office pages now also switch live (front-end toolbar and document) when the theme changes.
- **Large files:** 16 MB pptx, 15 MB PDF, 11 MB docx with images (engine process about 334 MB PSS, app about 70 MB), text files over 5 MB (M2). All open and scroll.
- **CV template:** its "filling in forms only" protection works as in Word (only the fields are editable), and filled fields save with protection intact (52 fields, 74 paragraphs, 3 tables). **Engine gap:** a filled field stays marked "showing placeholder" with the grey placeholder style, so Word may show the typed text greyed out.

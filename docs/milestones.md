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

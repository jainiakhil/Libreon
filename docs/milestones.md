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
- **Size.** `liblo-native-code.so` is 204 MB stripped (76 MB gzip), and the upstream test APK is 262 MB. A slimming option set is now in `build-engine.sh` (no DB, scripting, Java, avmedia, CoinMP, ZXing, galleries, templates or bundled dictionaries, plus LTO and `-Os`), cross-checked against `configure.ac`. It takes effect on the next engine rebuild.
- **Process kill on close.** Upstream `LOActivity` kills the app process when a document closes. M4 must host the engine without that, since tabs share our process.
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

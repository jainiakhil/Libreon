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
  - ~~arm64 has no engine build yet~~ (built in M5).
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
- **Accessibility pass:**
  - Done by Gemini 3.8 Flash in its own worktree (UI files only), reviewed by Claude.
  - Buttons, tabs, list rows and toggles now announce their role and state.
  - The page, mark and signature-pad drawing surfaces have descriptions, and the ink colour dots meet the 48 dp touch target.
  - Rejected: a label on the Office surface, since it could hide the embedded page's own content from screen readers.
  - Kept small on purpose: the 22 dp mark resize handles.
- **arm64 engine:**
  - Built with the same slim configuration (`native/lok/build-all.sh arm64-v8a`, about 3 hours in WSL).
  - `liblo-native-code.so` is 160 MB (x86_64: 158 MB). The arm64 release APK is 228 MB uncompressed on disk, the x86_64 one 227 MB.
  - Installed with `--abi arm64-v8a` on the phone emulator (arm64 code runs there through translation): a restored docx tab opened and rendered in about 40 s. A real arm64 phone has not been tried yet.

## M6: everyday features and a public build (in progress)
Requested after M5: file search, find in documents, word counts, PDF highlights, more formats, an installable release.
- **File search:** the home screen filters open, recent and closed files by name (every word of the query must match); with no match it offers the system picker's own search. The tab switcher gets a filter from six tabs up. (First draft by Gemini 3.8 Flash, reviewed.)
- **Find in document**, from a Find button in the app bar for every kind of document:
  - Text and PDF: the existing find bars.
  - Office: a new find bar driving the front-end's own search service. Find-all highlights and counts matches; next/previous move the selection. Tested: "1 match" for a word in a docx, scrolled to and selected.
- **Word count**, from the app bar menu, with document and selection figures:
  - Text: live in the status line ("Ln, Col · N words · M chars", or "Selected: a of N words").
  - Word documents: a live line under the page from Writer's own count. With a selection, this engine reports only the selection, so the last document total is kept for the dialog. Selection counts in any Office document come from the engine's plain-text selection.
  - PDF: the whole document's text layer, and the selected text.
  - One shared word-counting rule (letters/digits joined by inner apostrophes, hyphens and digit separators; Chinese/Japanese characters count one each), with JVM tests.
- **PDF text selection and highlights:**
  - Long-press selects a word, dragging extends it; a bar shows the word count, Copy and four highlight colours.
  - Highlights are standard /Highlight annotations (quad points, colour, the text as /Contents), so other PDF viewers show them and they survive saving (checked in a saved file) and tab recovery. Tapping one offers Remove.
  - New PDFium bridge calls: page text, character at a point, text rectangles, add/list/remove highlight annotations.
- **More formats:**
  - Apple Pages, Numbers and Keynote open read-only through libetonyek, which the engine already built but did not link. `native/lok/enable-import-filters.py` registers the three filters and the link line gains libetonyek. Tested with the engine's own sample files: Pages and Numbers open with their content (BlueStacks); Keynote opens in the release build on the phone emulator (its one-slide sample renders blank, so fidelity is unchecked). The system file picker labels .key files as PGP keys, so the picker also offers that type. The engine grows by about 2 MB (x86_64 158.5 → 160.6 MB, arm64 160 → 162 MB).
  - Tried and dropped: WordPerfect, Works/Lotus, AbiWord, eBook (FB2 and others) and StarOffice import. They link and register the same way, and Visio (already in Collabora's build) works through the same framework, but these fail to load any document ("loadComponentFromURL returned an empty reference"); finding out why needs an engine build with logging.
  - Flat ODF (.fodt/.fods/.fodp) opens; more text extensions (code, subtitles, config files) open in the text editor.
- **Release build:** signed with a private release key kept outside the repository (`signing/`, git-ignored; release builds fall back to the debug key without it). Native libraries are now compressed in the APK. Release APKs: arm64 115 MB, x86_64 118 MB (were 228 MB). Checked on the release build (minified): launch, Keynote import, PDF text selection with word count.
- **Fixed:** opening a file from Home now brings its tab to the front (it used to stay on Home).
- **Name and icon:** the app is now **Libreon** ("Work freely"), app ID `dev.libreon.app`. The adaptive launcher icon (full colour, plus a monochrome layer for themed icons) is cut from the supplied artwork (`art/libreon-icons-sheet.png`) by `art/make_icons.py`, which only separates the art from its tile and centres it in the safe zone; checked under circle, squircle and square masks. First beta: `0.9.0-beta.1`.

## Beta 2: fixes from the first phone test (Galaxy S24 Ultra, Android 17)
- **PDFs would not open** ("PDFium error 2132311536"): the phone tags heap pointers in their top byte, so the document handle looked negative and was taken for an error code. Only PDFium's small error codes are errors now. The emulator does not tag pointers, so this needs checking on the phone.
- **Selecting text in Office documents:** the front-end selects on a double-tap, not a long-press. A long-press in view mode is now turned into a double-tap (tested: selects the word with handles), and a one-time tip explains it. In edit mode a long-press still opens the front-end's menu.
- **Blank Office page after editing and coming back:** not reproduced on the emulator. The engine process now reuses its display host when the same app window comes back, instead of moving the page into a new one each time, and reports a visible error with Reload if showing the page fails.
- **Back** from a document now goes to Home instead of leaving the app.
- **Close all tabs** and **Clear Home** (tabs, recent and recently closed), each confirmed first; **Discard changes** for a document with unsaved edits (reopens it as saved, in view mode).
- **Settings:** theme (follow system, light, dark), the tab bar on wide screens, version and licenses. Office pages follow the app theme; **Light pages** in a document's menu keeps that document light while the app is dark.
- **Home:** a header with the logo, name and caption, and the big logo at the end of the page.
- **Landscape:** a button in the top bar hides or shows the tab bar.
- **Slideshow button looked disabled:** an upstream bug: 16 dark-theme icons kept the light theme's dark grey. `package-lok.sh` now recolours them.
- Not yet fixed (need the files): "Invalid value" when filling cells in Excel forms, and the badly formatted "Font Disallows Editing" message in a PowerPoint file.

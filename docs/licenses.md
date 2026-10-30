# Third-party libraries and licenses

**Offline Office itself is licensed GPL-3.0-or-later** (see [LICENSE](../LICENSE)). The tables below list every third-party component and are kept in sync with the actual Gradle and native dependencies. Every entry here must also appear on the in-app "Open-source licenses" screen.

## Shipped in the app

In the build today: PDFium, commonmark-java, Kotlin/kotlinx.coroutines, AndroidX. The LibreOfficeKit rows arrive with milestone M4.

| Library | Use | License | Notes |
|---|---|---|---|
| LibreOffice core (via LibreOfficeKit) | docx/xlsx/pptx engine | MPL-2.0 (some files dual MPL-2.0 / LGPL-3.0+) | Bundled externals each carry their own license (e.g. ICU: Unicode, Boost: BSL-1.0, libxml2: MIT, HarfBuzz: MIT, cairo: MPL-1.1/LGPL-2.1, NSS: MPL-2.0); the full list is generated from the LOK build in M0 |
| Collabora Online browser front-end | Office editing UI in a local WebView | MPL-2.0 | Only local assets; online-only features stripped |
| PDFium (via bblanchon/pdfium-binaries) | PDF render, forms, save | BSD-3-Clause / Apache-2.0 | Includes FreeType (FTL), libjpeg-turbo (IJG/BSD), OpenJPEG (BSD-2), lcms2 (MIT), zlib (zlib); binaries packaging is MIT |
| commonmark-java 0.24.0 (`commonmark`, `commonmark-ext-gfm-tables`, `commonmark-ext-gfm-strikethrough`, `commonmark-ext-task-list-items`) | Markdown → HTML for the preview | BSD-2-Clause | Pure Java, no transitive dependencies |
| Kotlin stdlib, kotlinx.coroutines | Language/runtime | Apache-2.0 | |
| AndroidX (Compose, Material 3, Activity, Lifecycle, Room, DataStore, WebKit) | UI and persistence | Apache-2.0 | |

## Contingency only (not in v1 unless needed)

| Library | Use | License |
|---|---|---|
| Apache POI (ooxml-lite) + Apache XMLBeans | "Safe edit" Office fallback | Apache-2.0 |
| PdfBox-Android (`com.tom-roush:pdfbox-android`) | Extra PDF writer for appearance streams | Apache-2.0 |
| androidx.pdf | Possible future renderer | Apache-2.0 |

## Rejected (license or scope)

| Library | Reason |
|---|---|
| MuPDF | AGPL-3.0 (or commercial) |
| ONLYOFFICE editors | AGPL-3.0, pending your answer to open question 3 |
| sora-editor | LGPL-2.1; not needed for our text scope |

## Test-only (not shipped)

| Library | Use | License |
|---|---|---|
| JUnit 4.13.2 (+ Hamcrest 1.3) | JVM unit tests | EPL-1.0 (Hamcrest: BSD-3-Clause) |

## Analysis tooling (not shipped)

JADX 1.5.6 (Apache-2.0), Apktool 3.0.3 (Apache-2.0), REA 6.8.0 (`rea-agents`), jadx-headless-mcp 0.7.1. Used only for the clean-room study in [findings.md](findings.md); none of their output is in this repository.

## Compatibility

MPL-2.0 is file-level copyleft and is compatible with combining Apache-2.0, BSD, MIT and similar code in one app ("Larger Work", MPL §3.3). Obligations:

1. Ship the MPL license text.
2. Publish the source of any MPL-covered file we distribute, including our modifications to those files.
3. Keep upstream notices.

Our own files are GPL-3.0-or-later. MPL-2.0 code may be combined into a GPL-3.0 work as a "Secondary License" (MPL §3.3) as long as the MPL files themselves stay available under MPL; Apache-2.0, BSD and MIT are GPL-3.0-compatible.

// SPDX-License-Identifier: GPL-3.0-or-later
// JNI bridge between dev.offlineoffice.pdf and PDFium's public C API.
//
// PDFium keeps global state and is not thread-safe, so every entry point takes
// the same process-wide lock. Callers should still confine a document to one
// worker thread to avoid contention.
//
// Coordinates crossing this bridge are "display points": PDF points measured from
// the top-left corner of the page as it is shown (after /Rotate and the crop box).
// Each page's display -> user-space mapping is derived from FPDF_DeviceToPage.

#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstring>
#include <list>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "fpdf_annot.h"
#include "fpdf_edit.h"
#include "fpdf_formfill.h"
#include "fpdf_ppo.h"
#include "fpdf_save.h"
#include "fpdf_text.h"
#include "fpdfview.h"

#define LOG_TAG "oopdf"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_lock;
bool g_initialized = false;

// Pages kept loaded per document. The form-fill environment wants a page to stay
// loaded while the user interacts with it, and reloading big pages is slow.
constexpr size_t kPageCacheSize = 4;

struct Document;

// FPDF_FORMFILLINFO must be the first member so callbacks can recover the Document.
struct FormHost {
    FPDF_FORMFILLINFO info{};
    Document* doc = nullptr;
};

struct Document {
    int fd = -1;
    FPDF_FILEACCESS access{};
    FPDF_DOCUMENT doc = nullptr;
    FormHost host{};
    FPDF_FORMHANDLE form = nullptr;
    std::list<std::pair<int, FPDF_PAGE>> pages;  // most recently used first
    // Text layer of one loaded page, for selection; closed with that page.
    FPDF_PAGE textOwner = nullptr;
    FPDF_TEXTPAGE textPage = nullptr;
    int nextTimer = 1;
};

// Reads a block of the file for PDFium. The fd is owned by Document.
int ReadBlock(void* param, unsigned long position, unsigned char* buf, unsigned long size) {
    const int fd = static_cast<Document*>(param)->fd;
    unsigned long done = 0;
    while (done < size) {
        const ssize_t n = pread(fd, buf + done, size - done, static_cast<off_t>(position + done));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return 0;
        done += static_cast<unsigned long>(n);
    }
    return 1;
}

Document* FromHandle(jlong handle) { return reinterpret_cast<Document*>(handle); }

void ThrowIllegalState(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, msg);
}

void ClosePage(Document* d, FPDF_PAGE page) {
    if (d->textOwner == page) {
        FPDFText_ClosePage(d->textPage);
        d->textOwner = nullptr;
        d->textPage = nullptr;
    }
    if (d->form != nullptr) FORM_OnBeforeClosePage(page, d->form);
    FPDF_ClosePage(page);
}

// Returns a cached page (loading it if needed); the cache owns it.
FPDF_PAGE GetPage(Document* d, int index) {
    for (auto it = d->pages.begin(); it != d->pages.end(); ++it) {
        if (it->first == index) {
            d->pages.splice(d->pages.begin(), d->pages, it);
            return it->second;
        }
    }
    FPDF_PAGE page = FPDF_LoadPage(d->doc, index);
    if (page == nullptr) return nullptr;
    if (d->form != nullptr) FORM_OnAfterLoadPage(page, d->form);
    d->pages.emplace_front(index, page);
    while (d->pages.size() > kPageCacheSize) {
        ClosePage(d, d->pages.back().second);
        d->pages.pop_back();
    }
    return page;
}

// Drops a page from the cache so the next access reloads it from the document.
void EvictPage(Document* d, int index) {
    for (auto it = d->pages.begin(); it != d->pages.end(); ++it) {
        if (it->first == index) {
            ClosePage(d, it->second);
            d->pages.erase(it);
            return;
        }
    }
}

void CloseAllPages(Document* d) {
    // Unlink each page before closing it: FORM_OnBeforeClosePage may call back into
    // OnGetCurrentPage/OnGetPage, which must never see (or iterate over) a closed page.
    while (!d->pages.empty()) {
        FPDF_PAGE page = d->pages.front().second;
        d->pages.pop_front();
        ClosePage(d, page);
    }
}

// --- form-fill callbacks: we re-render from Kotlin after each edit, so most are no-ops ---

Document* HostDoc(FPDF_FORMFILLINFO* info) { return reinterpret_cast<FormHost*>(info)->doc; }

void OnInvalidate(FPDF_FORMFILLINFO*, FPDF_PAGE, double, double, double, double) {}
void OnOutputSelectedRect(FPDF_FORMFILLINFO*, FPDF_PAGE, double, double, double, double) {}
void OnSetCursor(FPDF_FORMFILLINFO*, int) {}
int OnSetTimer(FPDF_FORMFILLINFO* info, int, TimerCallback) { return HostDoc(info)->nextTimer++; }
void OnKillTimer(FPDF_FORMFILLINFO*, int) {}
FPDF_SYSTEMTIME OnGetLocalTime(FPDF_FORMFILLINFO*) { return FPDF_SYSTEMTIME{}; }
void OnChange(FPDF_FORMFILLINFO*) {}
FPDF_PAGE OnGetPage(FPDF_FORMFILLINFO* info, FPDF_DOCUMENT, int index) {
    return GetPage(HostDoc(info), index);
}
FPDF_PAGE OnGetCurrentPage(FPDF_FORMFILLINFO* info, FPDF_DOCUMENT) {
    Document* d = HostDoc(info);
    return d->pages.empty() ? nullptr : d->pages.front().second;
}
int OnGetRotation(FPDF_FORMFILLINFO*, FPDF_PAGE) { return 0; }
void OnExecuteNamedAction(FPDF_FORMFILLINFO*, FPDF_BYTESTRING) {}
void OnSetTextFieldFocus(FPDF_FORMFILLINFO*, FPDF_WIDESTRING, FPDF_DWORD, FPDF_BOOL) {}
void OnDoURIAction(FPDF_FORMFILLINFO*, FPDF_BYTESTRING) {}  // never follow links: offline app
void OnDoGoToAction(FPDF_FORMFILLINFO*, int, int, float*, int) {}

void InitForm(Document* d) {
    FPDF_FORMFILLINFO& info = d->host.info;
    info.version = 1;
    info.FFI_Invalidate = OnInvalidate;
    info.FFI_OutputSelectedRect = OnOutputSelectedRect;
    info.FFI_SetCursor = OnSetCursor;
    info.FFI_SetTimer = OnSetTimer;
    info.FFI_KillTimer = OnKillTimer;
    info.FFI_GetLocalTime = OnGetLocalTime;
    info.FFI_OnChange = OnChange;
    info.FFI_GetPage = OnGetPage;
    info.FFI_GetCurrentPage = OnGetCurrentPage;
    info.FFI_GetRotation = OnGetRotation;
    info.FFI_ExecuteNamedAction = OnExecuteNamedAction;
    info.FFI_SetTextFieldFocus = OnSetTextFieldFocus;
    info.FFI_DoURIAction = OnDoURIAction;
    info.FFI_DoGoToAction = OnDoGoToAction;
    info.m_pJsPlatform = nullptr;  // no JavaScript
    d->host.doc = d;
    d->form = FPDFDOC_InitFormFillEnvironment(d->doc, &info);
    if (d->form != nullptr) {
        // Light blue tint on fillable fields; affects rendering only, never the saved file.
        FPDF_SetFormFieldHighlightColor(d->form, FPDF_FORMFIELD_UNKNOWN, 0xFFE4DD);
        FPDF_SetFormFieldHighlightAlpha(d->form, 80);
    }
}

// --- display points <-> PDF user space ---

// user = (a*x + c*y + e, b*x + d*y + f) for display point (x, y).
struct Affine {
    double a = 1, b = 0, c = 0, d = 1, e = 0, f = 0;
    void Map(double x, double y, double* ux, double* uy) const {
        *ux = a * x + c * y + e;
        *uy = b * x + d * y + f;
    }
    void Unmap(double ux, double uy, double* x, double* y) const {
        const double det = a * d - b * c;
        const double px = ux - e, py = uy - f;
        *x = (d * px - c * py) / det;
        *y = (-b * px + a * py) / det;
    }
};

Affine DisplayToUser(FPDF_PAGE page) {
    // A large virtual device keeps the integer device coordinates exact enough.
    constexpr int kScale = 64;
    const double w = FPDF_GetPageWidthF(page), h = FPDF_GetPageHeightF(page);
    const int dw = static_cast<int>(w * kScale), dh = static_cast<int>(h * kScale);
    double x0, y0, x1, y1, x2, y2;
    FPDF_DeviceToPage(page, 0, 0, dw, dh, 0, 0, 0, &x0, &y0);
    FPDF_DeviceToPage(page, 0, 0, dw, dh, 0, dw, 0, &x1, &y1);
    FPDF_DeviceToPage(page, 0, 0, dw, dh, 0, 0, dh, &x2, &y2);
    const double sx = static_cast<double>(dw) / kScale, sy = static_cast<double>(dh) / kScale;
    Affine m;
    m.a = (x1 - x0) / sx;
    m.b = (y1 - y0) / sx;
    m.c = (x2 - x0) / sy;
    m.d = (y2 - y0) / sy;
    m.e = x0;
    m.f = y0;
    return m;
}

// Converts a user-space rectangle to a display rectangle {left, top, right, bottom}.
void RectToDisplay(const Affine& m, double l, double b, double r, double t, float out[4]) {
    double xs[4], ys[4];
    m.Unmap(l, b, &xs[0], &ys[0]);
    m.Unmap(r, b, &xs[1], &ys[1]);
    m.Unmap(l, t, &xs[2], &ys[2]);
    m.Unmap(r, t, &xs[3], &ys[3]);
    out[0] = static_cast<float>(*std::min_element(xs, xs + 4));
    out[1] = static_cast<float>(*std::min_element(ys, ys + 4));
    out[2] = static_cast<float>(*std::max_element(xs, xs + 4));
    out[3] = static_cast<float>(*std::max_element(ys, ys + 4));
}

// --- small UTF-16 JSON writer (PDFium speaks UTF-16LE, Java strings are UTF-16) ---

class Json {
public:
    void Raw(const char* s) { while (*s) out_.push_back(static_cast<char16_t>(*s++)); }
    void Str(const std::u16string& s) {
        out_.push_back(u'"');
        for (char16_t ch : s) {
            if (ch == u'"' || ch == u'\\') {
                out_.push_back(u'\\');
                out_.push_back(ch);
            } else if (ch < 0x20) {
                char buf[8];
                snprintf(buf, sizeof(buf), "\\u%04x", ch);
                Raw(buf);
            } else {
                out_.push_back(ch);
            }
        }
        out_.push_back(u'"');
    }
    void Num(double v) {
        char buf[32];
        snprintf(buf, sizeof(buf), "%.3f", std::isfinite(v) ? v : 0.0);
        Raw(buf);
    }
    void Int(long v) {
        char buf[24];
        snprintf(buf, sizeof(buf), "%ld", v);
        Raw(buf);
    }
    void Key(const char* k) {
        Raw("\"");
        Raw(k);
        Raw("\":");
    }
    jstring ToJava(JNIEnv* env) const {
        return env->NewString(reinterpret_cast<const jchar*>(out_.data()), static_cast<jsize>(out_.size()));
    }

private:
    std::u16string out_;
};

// Calls a PDFium "fill a UTF-16 buffer, return byte length" getter.
template <typename Getter>
std::u16string ReadWide(Getter getter) {
    const unsigned long bytes = getter(nullptr, 0);
    if (bytes <= 2) return {};
    std::vector<FPDF_WCHAR> buf(bytes / 2);
    if (getter(buf.data(), bytes) == 0) return {};
    return std::u16string(reinterpret_cast<const char16_t*>(buf.data()), buf.size() - 1);
}

std::u16string JavaToWide(JNIEnv* env, jstring s) {
    const jchar* chars = env->GetStringChars(s, nullptr);
    if (chars == nullptr) return {};  // OutOfMemoryError is pending
    std::u16string out(reinterpret_cast<const char16_t*>(chars), env->GetStringLength(s));
    env->ReleaseStringChars(s, chars);
    return out;  // FPDF_WIDESTRING wants a NUL-terminated buffer: use .c_str()
}

FPDF_WIDESTRING Wide(const std::u16string& s) { return reinterpret_cast<FPDF_WIDESTRING>(s.c_str()); }

// Copies an Android ARGB_8888 (premultiplied RGBA bytes) bitmap into a new straight-alpha BGRA
// FPDF_BITMAP. Returns null on failure.
FPDF_BITMAP CopyBitmap(JNIEnv* env, jobject bitmap) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return nullptr;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    const int w = static_cast<int>(info.width), h = static_cast<int>(info.height);
    FPDF_BITMAP out = FPDFBitmap_Create(w, h, 1);
    if (out != nullptr) {
        auto* dst = static_cast<uint8_t*>(FPDFBitmap_GetBuffer(out));
        const int dstStride = FPDFBitmap_GetStride(out);
        for (int y = 0; y < h; ++y) {
            const auto* s = static_cast<const uint8_t*>(pixels) + static_cast<size_t>(y) * info.stride;
            uint8_t* o = dst + static_cast<size_t>(y) * dstStride;
            for (int x = 0; x < w; ++x, s += 4, o += 4) {
                const uint8_t a = s[3];
                auto unpremul = [a](uint8_t c) -> uint8_t {
                    return a == 0 ? 0 : static_cast<uint8_t>(std::min(255, (c * 255 + a / 2) / a));
                };
                o[0] = unpremul(s[2]);
                o[1] = unpremul(s[1]);
                o[2] = unpremul(s[0]);
                o[3] = a;
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return out;
}

struct FileWriter {
    FPDF_FILEWRITE base{};
    int fd = -1;
    bool failed = false;
};

int WriteBlock(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
    auto* w = reinterpret_cast<FileWriter*>(self);
    const auto* p = static_cast<const uint8_t*>(data);
    unsigned long done = 0;
    while (done < size) {
        const ssize_t n = write(w->fd, p + done, size - done);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) {
            w->failed = true;
            return 0;
        }
        done += static_cast<unsigned long>(n);
    }
    return 1;
}

// Loads the widget annotation at annotIndex on page, focusing nothing.
FPDF_ANNOTATION WidgetAt(FPDF_PAGE page, int annotIndex) {
    FPDF_ANNOTATION annot = FPDFPage_GetAnnot(page, annotIndex);
    if (annot != nullptr && FPDFAnnot_GetSubtype(annot) != FPDF_ANNOT_WIDGET) {
        FPDFPage_CloseAnnot(annot);
        return nullptr;
    }
    return annot;
}

// Returns the text layer of a page (loading it if needed). Owned by the Document; valid
// until that page leaves the page cache. Sets *pageOut to the page.
FPDF_TEXTPAGE GetTextPage(Document* d, int index, FPDF_PAGE* pageOut) {
    FPDF_PAGE page = GetPage(d, index);
    *pageOut = page;
    if (page == nullptr) return nullptr;
    if (d->textOwner == page) return d->textPage;
    if (d->textPage != nullptr) FPDFText_ClosePage(d->textPage);
    d->textPage = FPDFText_LoadPage(page);
    d->textOwner = d->textPage != nullptr ? page : nullptr;
    return d->textPage;
}

jfloatArray ToJava(JNIEnv* env, const std::vector<float>& v) {
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(v.size()));
    if (result == nullptr) return nullptr;  // OutOfMemoryError is pending
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(v.size()), v.data());
    return result;
}

// Appends a highlight annotation's quadrilaterals as display rectangles.
void QuadsToDisplay(FPDF_ANNOTATION annot, const Affine& m, std::vector<float>* out) {
    const size_t quads = FPDFAnnot_CountAttachmentPoints(annot);
    out->push_back(static_cast<float>(quads));
    for (size_t q = 0; q < quads; ++q) {
        FS_QUADPOINTSF p{};
        FPDFAnnot_GetAttachmentPoints(annot, q, &p);
        const double l = std::min({p.x1, p.x2, p.x3, p.x4}), r = std::max({p.x1, p.x2, p.x3, p.x4});
        const double b = std::min({p.y1, p.y2, p.y3, p.y4}), t = std::max({p.y1, p.y2, p.y3, p.y4});
        float rect[4];
        RectToDisplay(m, l, b, r, t, rect);
        out->insert(out->end(), rect, rect + 4);
    }
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_init(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_initialized) return;
    FPDF_LIBRARY_CONFIG config{};
    config.version = 2;
    FPDF_InitLibraryWithConfig(&config);
    g_initialized = true;
}

// Takes ownership of fd (closed in close(), or here on failure).
// Returns a document handle, or a negative PDFium error code (FPDF_ERR_*).
JNIEXPORT jlong JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_open(JNIEnv* env, jclass, jint fd, jlong length,
                                             jstring password) {
    std::lock_guard<std::mutex> guard(g_lock);
    auto* d = new Document();
    d->fd = fd;
    d->access.m_FileLen = static_cast<unsigned long>(length);
    d->access.m_GetBlock = ReadBlock;
    d->access.m_Param = d;

    const char* pw = password != nullptr ? env->GetStringUTFChars(password, nullptr) : nullptr;
    d->doc = FPDF_LoadCustomDocument(&d->access, pw);
    if (pw != nullptr) env->ReleaseStringUTFChars(password, pw);

    if (d->doc == nullptr) {
        const auto err = static_cast<jlong>(FPDF_GetLastError());
        close(d->fd);
        delete d;
        return -(err == 0 ? FPDF_ERR_UNKNOWN : err);
    }
    InitForm(d);
    return reinterpret_cast<jlong>(d);
}

JNIEXPORT void JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_close(JNIEnv*, jclass, jlong handle) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    if (d == nullptr) return;
    CloseAllPages(d);
    if (d->form != nullptr) FPDFDOC_ExitFormFillEnvironment(d->form);
    FPDF_CloseDocument(d->doc);
    close(d->fd);
    delete d;
}

JNIEXPORT jint JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_pageCount(JNIEnv*, jclass, jlong handle) {
    std::lock_guard<std::mutex> guard(g_lock);
    return FPDF_GetPageCount(FromHandle(handle)->doc);
}

/** One of FORMTYPE_*. */
JNIEXPORT jint JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_formType(JNIEnv*, jclass, jlong handle) {
    std::lock_guard<std::mutex> guard(g_lock);
    return FPDF_GetFormType(FromHandle(handle)->doc);
}

// Writes {width, height} in PDF points into out.
JNIEXPORT jboolean JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_pageSize(JNIEnv* env, jclass, jlong handle, jint index,
                                                 jfloatArray out) {
    std::lock_guard<std::mutex> guard(g_lock);
    FS_SIZEF size{};
    if (!FPDF_GetPageSizeByIndexF(FromHandle(handle)->doc, index, &size)) return JNI_FALSE;
    const jfloat values[2] = {size.width, size.height};
    env->SetFloatArrayRegion(out, 0, 2, values);
    return JNI_TRUE;
}

// Renders page `index` into an ARGB_8888 bitmap. (startX, startY, sizeX, sizeY) is the
// page's placement in bitmap pixels, so a sub-rectangle of a zoomed page can be drawn
// as a tile by passing negative offsets. Form fields are drawn with their current values.
JNIEXPORT void JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_renderPage(JNIEnv* env, jclass, jlong handle, jint index,
                                                   jobject bitmap, jint startX, jint startY,
                                                   jint sizeX, jint sizeY, jboolean drawAnnots) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        ThrowIllegalState(env, "bitmap must be ARGB_8888");
        return;
    }
    FPDF_PAGE page = GetPage(d, index);
    if (page == nullptr) {
        ThrowIllegalState(env, "cannot load page");
        return;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        ThrowIllegalState(env, "cannot lock bitmap");
        return;
    }
    FPDF_BITMAP target = FPDFBitmap_CreateEx(static_cast<int>(info.width),
                                             static_cast<int>(info.height), FPDFBitmap_BGRA,
                                             pixels, static_cast<int>(info.stride));
    if (target == nullptr) {
        AndroidBitmap_unlockPixels(env, bitmap);
        ThrowIllegalState(env, "cannot wrap bitmap for rendering");
        return;
    }
    FPDFBitmap_FillRect(target, 0, 0, static_cast<int>(info.width),
                        static_cast<int>(info.height), 0xFFFFFFFF);
    // Android bitmaps are RGBA in memory; ask PDFium to emit that order directly.
    int flags = FPDF_REVERSE_BYTE_ORDER | FPDF_LCD_TEXT;
    if (drawAnnots) flags |= FPDF_ANNOT;
    FPDF_RenderPageBitmap(target, page, startX, startY, sizeX, sizeY, 0, flags);
    if (drawAnnots && d->form != nullptr) {
        FPDF_FFLDraw(d->form, target, page, startX, startY, sizeX, sizeY, 0, flags);
    }
    FPDFBitmap_Destroy(target);
    AndroidBitmap_unlockPixels(env, bitmap);
}

// Describes the page's form widgets as a JSON array of objects:
// {i: annot index, type, flags, name, value, checked, rect: [l,t,r,b] display points,
//  options: [{label, selected}]}.
JNIEXPORT jstring JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_pageFields(JNIEnv* env, jclass, jlong handle, jint index) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    Json json;
    json.Raw("[");
    FPDF_PAGE page = d->form != nullptr ? GetPage(d, index) : nullptr;
    if (page != nullptr) {
        const Affine m = DisplayToUser(page);
        const int count = FPDFPage_GetAnnotCount(page);
        bool first = true;
        for (int i = 0; i < count; ++i) {
            FPDF_ANNOTATION annot = WidgetAt(page, i);
            if (annot == nullptr) continue;
            const int type = FPDFAnnot_GetFormFieldType(d->form, annot);
            FS_RECTF r{};
            if (type <= FPDF_FORMFIELD_UNKNOWN || !FPDFAnnot_GetRect(annot, &r)) {
                FPDFPage_CloseAnnot(annot);
                continue;
            }
            float rect[4];
            RectToDisplay(m, r.left, r.bottom, r.right, r.top, rect);
            if (!first) json.Raw(",");
            first = false;
            json.Raw("{");
            json.Key("i"); json.Int(i);
            json.Raw(","); json.Key("type"); json.Int(type);
            json.Raw(","); json.Key("flags"); json.Int(FPDFAnnot_GetFormFieldFlags(d->form, annot));
            json.Raw(","); json.Key("name");
            json.Str(ReadWide([&](FPDF_WCHAR* b, unsigned long n) { return FPDFAnnot_GetFormFieldName(d->form, annot, b, n); }));
            json.Raw(","); json.Key("value");
            json.Str(ReadWide([&](FPDF_WCHAR* b, unsigned long n) { return FPDFAnnot_GetFormFieldValue(d->form, annot, b, n); }));
            json.Raw(","); json.Key("checked"); json.Raw(FPDFAnnot_IsChecked(d->form, annot) ? "true" : "false");
            json.Raw(","); json.Key("rect"); json.Raw("[");
            for (int k = 0; k < 4; ++k) {
                if (k) json.Raw(",");
                json.Num(rect[k]);
            }
            json.Raw("]");
            const int options = FPDFAnnot_GetOptionCount(d->form, annot);
            if (options > 0) {
                json.Raw(","); json.Key("options"); json.Raw("[");
                for (int k = 0; k < options; ++k) {
                    if (k) json.Raw(",");
                    json.Raw("{"); json.Key("label");
                    json.Str(ReadWide([&](FPDF_WCHAR* b, unsigned long n) { return FPDFAnnot_GetOptionLabel(d->form, annot, k, b, n); }));
                    json.Raw(","); json.Key("selected");
                    json.Raw(FPDFAnnot_IsOptionSelected(d->form, annot, k) ? "true" : "false");
                    json.Raw("}");
                }
                json.Raw("]");
            }
            json.Raw("}");
            FPDFPage_CloseAnnot(annot);
        }
    }
    json.Raw("]");
    return json.ToJava(env);
}

// Replaces a text field's (or editable combo box's) value through the form-fill environment,
// so PDFium validates it and regenerates the appearance stream.
JNIEXPORT jboolean JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_setFieldText(JNIEnv* env, jclass, jlong handle, jint index,
                                                     jint annotIndex, jstring text) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    FPDF_PAGE page = d->form != nullptr ? GetPage(d, index) : nullptr;
    if (page == nullptr) return JNI_FALSE;
    FPDF_ANNOTATION annot = WidgetAt(page, annotIndex);
    if (annot == nullptr) return JNI_FALSE;
    const std::u16string value = JavaToWide(env, text);
    bool ok = FORM_SetFocusedAnnot(d->form, annot);
    if (ok) {
        FORM_SelectAllText(d->form, page);
        FORM_ReplaceSelection(d->form, page, Wide(value));
        FORM_ForceToKillFocus(d->form);
    }
    FPDFPage_CloseAnnot(annot);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Selects (or, for multi-select list boxes, toggles) option `option` of a choice field.
JNIEXPORT jboolean JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_setFieldChoice(JNIEnv*, jclass, jlong handle, jint index,
                                                       jint annotIndex, jint option, jboolean selected) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    FPDF_PAGE page = d->form != nullptr ? GetPage(d, index) : nullptr;
    if (page == nullptr) return JNI_FALSE;
    FPDF_ANNOTATION annot = WidgetAt(page, annotIndex);
    if (annot == nullptr) return JNI_FALSE;
    bool ok = FORM_SetFocusedAnnot(d->form, annot) &&
              FORM_SetIndexSelected(d->form, page, option, selected);
    FORM_ForceToKillFocus(d->form);
    FPDFPage_CloseAnnot(annot);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Clicks the centre of a widget: toggles check boxes and selects radio buttons.
JNIEXPORT jboolean JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_clickField(JNIEnv*, jclass, jlong handle, jint index,
                                                   jint annotIndex) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    FPDF_PAGE page = d->form != nullptr ? GetPage(d, index) : nullptr;
    if (page == nullptr) return JNI_FALSE;
    FPDF_ANNOTATION annot = WidgetAt(page, annotIndex);
    if (annot == nullptr) return JNI_FALSE;
    FS_RECTF r{};
    const bool ok = FPDFAnnot_GetRect(annot, &r);
    FPDFPage_CloseAnnot(annot);
    if (!ok) return JNI_FALSE;
    const double x = (r.left + r.right) / 2, y = (r.top + r.bottom) / 2;
    FORM_OnMouseMove(d->form, page, 0, x, y);
    FORM_OnLButtonDown(d->form, page, 0, x, y);
    FORM_OnLButtonUp(d->form, page, 0, x, y);
    FORM_ForceToKillFocus(d->form);
    return JNI_TRUE;
}

// Finds `query` on a page. Returns [matchCount, then per match: rectCount, l, t, r, b, ...]
// in display points.
JNIEXPORT jfloatArray JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_search(JNIEnv* env, jclass, jlong handle, jint index,
                                               jstring query, jboolean matchCase, jboolean wholeWord) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    std::vector<float> out{0};
    FPDF_PAGE page = GetPage(d, index);
    FPDF_TEXTPAGE text = page != nullptr ? FPDFText_LoadPage(page) : nullptr;
    if (text != nullptr) {
        const Affine m = DisplayToUser(page);
        const std::u16string q = JavaToWide(env, query);
        unsigned long flags = 0;
        if (matchCase) flags |= FPDF_MATCHCASE;
        if (wholeWord) flags |= FPDF_MATCHWHOLEWORD;
        FPDF_SCHHANDLE search = FPDFText_FindStart(text, Wide(q), flags, 0);
        while (search != nullptr && FPDFText_FindNext(search)) {
            const int start = FPDFText_GetSchResultIndex(search);
            const int count = FPDFText_GetSchCount(search);
            const int rects = FPDFText_CountRects(text, start, count);
            out[0] += 1;
            out.push_back(static_cast<float>(std::max(rects, 0)));
            for (int k = 0; k < rects; ++k) {
                double l, t, r, b;
                FPDFText_GetRect(text, k, &l, &t, &r, &b);
                float rect[4];
                RectToDisplay(m, l, b, r, t, rect);
                out.insert(out.end(), rect, rect + 4);
            }
        }
        if (search != nullptr) FPDFText_FindClose(search);
        FPDFText_ClosePage(text);
    }
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(out.size()));
    if (result == nullptr) return nullptr;  // OutOfMemoryError is pending
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

// Places a bitmap into the display rectangle (l, t, r, b). With asAnnotation the image becomes
// the appearance of a new /Stamp annotation; otherwise it is written into the page content.
JNIEXPORT jboolean JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_insertImage(JNIEnv* env, jclass, jlong handle, jint index,
                                                    jobject bitmap, jfloat l, jfloat t, jfloat r,
                                                    jfloat b, jboolean asAnnotation) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    FPDF_PAGE page = GetPage(d, index);
    if (page == nullptr) return JNI_FALSE;
    FPDF_BITMAP pixels = CopyBitmap(env, bitmap);
    if (pixels == nullptr) return JNI_FALSE;
    FPDF_PAGEOBJECT image = FPDFPageObj_NewImageObj(d->doc);
    bool ok = image != nullptr && FPDFImageObj_SetBitmap(&page, 1, image, pixels);
    FPDFBitmap_Destroy(pixels);
    if (!ok) {
        if (image != nullptr) FPDFPageObj_Destroy(image);
        return JNI_FALSE;
    }
    // The image's unit square maps to the rectangle: (0,0) is its bottom-left corner.
    const Affine m = DisplayToUser(page);
    double ox, oy;
    m.Map(l, b, &ox, &oy);
    FS_MATRIX matrix{};
    matrix.a = static_cast<float>((r - l) * m.a);
    matrix.b = static_cast<float>((r - l) * m.b);
    matrix.c = static_cast<float>(-(b - t) * m.c);
    matrix.d = static_cast<float>(-(b - t) * m.d);
    matrix.e = static_cast<float>(ox);
    matrix.f = static_cast<float>(oy);
    FPDFPageObj_SetMatrix(image, &matrix);

    if (asAnnotation) {
        FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(page, FPDF_ANNOT_STAMP);
        ok = annot != nullptr;
        if (ok) {
            double xs[4], ys[4];
            m.Map(l, t, &xs[0], &ys[0]);
            m.Map(r, t, &xs[1], &ys[1]);
            m.Map(l, b, &xs[2], &ys[2]);
            m.Map(r, b, &xs[3], &ys[3]);
            FS_RECTF rect{};
            rect.left = static_cast<float>(*std::min_element(xs, xs + 4));
            rect.right = static_cast<float>(*std::max_element(xs, xs + 4));
            rect.bottom = static_cast<float>(*std::min_element(ys, ys + 4));
            rect.top = static_cast<float>(*std::max_element(ys, ys + 4));
            FPDFAnnot_SetRect(annot, &rect);
            FPDFAnnot_SetFlags(annot, FPDF_ANNOT_FLAG_PRINT);
            ok = FPDFAnnot_AppendObject(annot, image);  // takes ownership on success
            if (!ok) FPDFPageObj_Destroy(image);
            FPDFPage_CloseAnnot(annot);
        } else {
            FPDFPageObj_Destroy(image);
        }
    } else {
        FPDFPage_InsertObject(page, image);
        ok = FPDFPage_GenerateContent(page);
    }
    EvictPage(d, index);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Writes text in standard Helvetica into the page content. (x, baseline) is the start of the
// baseline in display points; color is 0xAARRGGBB.
JNIEXPORT jboolean JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_insertText(JNIEnv* env, jclass, jlong handle, jint index,
                                                   jstring text, jfloat x, jfloat baseline,
                                                   jfloat fontSize, jint color) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    FPDF_PAGE page = GetPage(d, index);
    if (page == nullptr) return JNI_FALSE;
    FPDF_PAGEOBJECT obj = FPDFPageObj_NewTextObj(d->doc, "Helvetica", fontSize);
    if (obj == nullptr) return JNI_FALSE;
    const std::u16string value = JavaToWide(env, text);
    FPDFText_SetText(obj, Wide(value));
    const auto c = static_cast<uint32_t>(color);
    FPDFPageObj_SetFillColor(obj, (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, (c >> 24) & 0xFF);
    const Affine m = DisplayToUser(page);
    double ox, oy;
    m.Map(x, baseline, &ox, &oy);
    // Text space x runs along display x; text space y runs up, i.e. along -display y.
    FPDFPageObj_Transform(obj, m.a, m.b, -m.c, -m.d, ox, oy);
    FPDFPage_InsertObject(page, obj);
    const bool ok = FPDFPage_GenerateContent(page);
    EvictPage(d, index);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Strokes polylines into the page content. points holds display-point pairs; a NaN pair
// starts a new polyline. color is 0xAARRGGBB, width in points.
JNIEXPORT jboolean JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_insertPath(JNIEnv* env, jclass, jlong handle, jint index,
                                                   jfloatArray points, jfloat width, jint color) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    FPDF_PAGE page = GetPage(d, index);
    if (page == nullptr) return JNI_FALSE;
    const jsize n = env->GetArrayLength(points);
    std::vector<float> p(static_cast<size_t>(n));
    env->GetFloatArrayRegion(points, 0, n, p.data());
    const Affine m = DisplayToUser(page);
    FPDF_PAGEOBJECT path = nullptr;
    bool penDown = false;
    for (jsize i = 0; i + 1 < n; i += 2) {
        if (std::isnan(p[i])) {
            penDown = false;
            continue;
        }
        double ux, uy;
        m.Map(p[i], p[i + 1], &ux, &uy);
        if (path == nullptr) {
            path = FPDFPageObj_CreateNewPath(static_cast<float>(ux), static_cast<float>(uy));
        } else if (!penDown) {
            FPDFPath_MoveTo(path, static_cast<float>(ux), static_cast<float>(uy));
        } else {
            FPDFPath_LineTo(path, static_cast<float>(ux), static_cast<float>(uy));
        }
        penDown = true;
    }
    if (path == nullptr) return JNI_FALSE;
    const auto c = static_cast<uint32_t>(color);
    FPDFPageObj_SetStrokeColor(path, (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, (c >> 24) & 0xFF);
    FPDFPageObj_SetStrokeWidth(path, width);
    FPDFPageObj_SetLineCap(path, FPDF_LINECAP_ROUND);
    FPDFPageObj_SetLineJoin(path, FPDF_LINEJOIN_ROUND);
    FPDFPath_SetDrawMode(path, FPDF_FILLMODE_NONE, true);
    FPDFPage_InsertObject(page, path);
    const bool ok = FPDFPage_GenerateContent(page);
    EvictPage(d, index);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// --- text selection and highlights ---

// The page's text, one UTF-16 unit per character index (PDFium adds generated spaces and
// line breaks between text runs, which also count as characters).
JNIEXPORT jstring JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_pageText(JNIEnv* env, jclass, jlong handle, jint index) {
    std::lock_guard<std::mutex> guard(g_lock);
    FPDF_PAGE page;
    FPDF_TEXTPAGE text = GetTextPage(FromHandle(handle), index, &page);
    const int count = text != nullptr ? FPDFText_CountChars(text) : 0;
    std::vector<unsigned short> buf(static_cast<size_t>(std::max(count, 0)) + 1, 0);
    const int written = count > 0 ? FPDFText_GetText(text, 0, count, buf.data()) : 0;
    // GetText writes a terminating NUL; characters it cannot map come back as 0 too.
    const int length = std::max(written - 1, 0);
    for (int i = 0; i < length; ++i) {
        if (buf[i] == 0) buf[i] = u' ';
    }
    return env->NewString(reinterpret_cast<const jchar*>(buf.data()), length);
}

// The character index nearest the display point (x, y) within `tolerance` points, or -1.
JNIEXPORT jint JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_charIndexAt(JNIEnv*, jclass, jlong handle, jint index,
                                                    jfloat x, jfloat y, jfloat tolerance) {
    std::lock_guard<std::mutex> guard(g_lock);
    FPDF_PAGE page;
    FPDF_TEXTPAGE text = GetTextPage(FromHandle(handle), index, &page);
    if (text == nullptr) return -1;
    double ux, uy;
    DisplayToUser(page).Map(x, y, &ux, &uy);
    const int at = FPDFText_GetCharIndexAtPos(text, ux, uy, tolerance, tolerance);
    return at >= 0 ? at : -1;
}

// Display rectangles [l, t, r, b, ...] covering `count` characters from `start`, one per run.
JNIEXPORT jfloatArray JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_textRects(JNIEnv* env, jclass, jlong handle, jint index,
                                                  jint start, jint count) {
    std::lock_guard<std::mutex> guard(g_lock);
    FPDF_PAGE page;
    FPDF_TEXTPAGE text = GetTextPage(FromHandle(handle), index, &page);
    std::vector<float> out;
    if (text != nullptr && count > 0) {
        const Affine m = DisplayToUser(page);
        const int rects = FPDFText_CountRects(text, start, count);
        for (int k = 0; k < rects; ++k) {
            double l, t, r, b;
            FPDFText_GetRect(text, k, &l, &t, &r, &b);
            float rect[4];
            RectToDisplay(m, l, b, r, t, rect);
            out.insert(out.end(), rect, rect + 4);
        }
    }
    return ToJava(env, out);
}

// Adds a text markup annotation (subtype FPDF_ANNOT_HIGHLIGHT, _UNDERLINE, _STRIKEOUT or
// _SQUIGGLY) covering the display rectangles [l, t, r, b, ...] in color 0xAARRGGBB, with
// `contents` (the marked text) as its /Contents.
JNIEXPORT jboolean JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_addHighlight(JNIEnv* env, jclass, jlong handle, jint index,
                                                     jint subtype, jfloatArray rects, jint color, jstring contents) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    FPDF_PAGE page = GetPage(d, index);
    const jsize n = env->GetArrayLength(rects);
    if (page == nullptr || n < 4) return JNI_FALSE;
    if (subtype != FPDF_ANNOT_HIGHLIGHT && subtype != FPDF_ANNOT_UNDERLINE &&
        subtype != FPDF_ANNOT_STRIKEOUT && subtype != FPDF_ANNOT_SQUIGGLY) return JNI_FALSE;
    std::vector<float> r(static_cast<size_t>(n));
    env->GetFloatArrayRegion(rects, 0, n, r.data());
    FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(page, subtype);
    if (annot == nullptr) return JNI_FALSE;
    const Affine m = DisplayToUser(page);
    FS_RECTF bounds{};
    bool first = true, ok = true;
    for (jsize i = 0; i + 3 < n && ok; i += 4) {
        // Quad points go top-left, top-right, bottom-left, bottom-right.
        double xs[4], ys[4];
        m.Map(r[i], r[i + 1], &xs[0], &ys[0]);
        m.Map(r[i + 2], r[i + 1], &xs[1], &ys[1]);
        m.Map(r[i], r[i + 3], &xs[2], &ys[2]);
        m.Map(r[i + 2], r[i + 3], &xs[3], &ys[3]);
        FS_QUADPOINTSF q{};
        q.x1 = static_cast<float>(xs[0]); q.y1 = static_cast<float>(ys[0]);
        q.x2 = static_cast<float>(xs[1]); q.y2 = static_cast<float>(ys[1]);
        q.x3 = static_cast<float>(xs[2]); q.y3 = static_cast<float>(ys[2]);
        q.x4 = static_cast<float>(xs[3]); q.y4 = static_cast<float>(ys[3]);
        ok = FPDFAnnot_AppendAttachmentPoints(annot, &q);
        const float l = static_cast<float>(*std::min_element(xs, xs + 4));
        const float rr = static_cast<float>(*std::max_element(xs, xs + 4));
        const float b = static_cast<float>(*std::min_element(ys, ys + 4));
        const float t = static_cast<float>(*std::max_element(ys, ys + 4));
        if (first) {
            bounds = {l, t, rr, b};
            first = false;
        } else {
            bounds.left = std::min(bounds.left, l);
            bounds.right = std::max(bounds.right, rr);
            bounds.bottom = std::min(bounds.bottom, b);
            bounds.top = std::max(bounds.top, t);
        }
    }
    if (ok) {
        const auto c = static_cast<uint32_t>(color);
        ok = FPDFAnnot_SetRect(annot, &bounds) &&
             FPDFAnnot_SetColor(annot, FPDFANNOT_COLORTYPE_Color, (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, (c >> 24) & 0xFF) &&
             FPDFAnnot_SetFlags(annot, FPDF_ANNOT_FLAG_PRINT);
        const std::u16string text = JavaToWide(env, contents);
        if (ok && !text.empty()) FPDFAnnot_SetStringValue(annot, "Contents", Wide(text));
    }
    FPDFPage_CloseAnnot(annot);
    if (!ok) {
        // Drop the half-made annotation (it is the last one on the page).
        FPDFPage_RemoveAnnot(page, FPDFPage_GetAnnotCount(page) - 1);
    }
    EvictPage(d, index);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// The page's markup annotations that the app can select and remove (text markup, ink,
// rectangles, ellipses and notes): [count, then per annotation: annotIndex, subtype, red,
// green, blue, rectCount, l, t, r, b, ...]. Text markup gives one rectangle per quad; the
// others give their bounding rectangle.
JNIEXPORT jfloatArray JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_highlights(JNIEnv* env, jclass, jlong handle, jint index) {
    std::lock_guard<std::mutex> guard(g_lock);
    FPDF_PAGE page = GetPage(FromHandle(handle), index);
    std::vector<float> out{0};
    if (page != nullptr) {
        const Affine m = DisplayToUser(page);
        const int annots = FPDFPage_GetAnnotCount(page);
        for (int i = 0; i < annots; ++i) {
            FPDF_ANNOTATION annot = FPDFPage_GetAnnot(page, i);
            if (annot == nullptr) continue;
            const int subtype = FPDFAnnot_GetSubtype(annot);
            const bool markup = subtype == FPDF_ANNOT_HIGHLIGHT || subtype == FPDF_ANNOT_UNDERLINE ||
                                subtype == FPDF_ANNOT_STRIKEOUT || subtype == FPDF_ANNOT_SQUIGGLY;
            const bool other = subtype == FPDF_ANNOT_INK || subtype == FPDF_ANNOT_SQUARE ||
                               subtype == FPDF_ANNOT_CIRCLE || subtype == FPDF_ANNOT_TEXT;
            if (markup || other) {
                unsigned int r = 255, g = 235, b = 59, a = 255;
                FPDFAnnot_GetColor(annot, FPDFANNOT_COLORTYPE_Color, &r, &g, &b, &a);
                out[0] += 1;
                out.insert(out.end(), {static_cast<float>(i), static_cast<float>(subtype),
                                       static_cast<float>(r), static_cast<float>(g), static_cast<float>(b)});
                FS_RECTF rect{};
                if (markup) {
                    QuadsToDisplay(annot, m, &out);
                } else if (FPDFAnnot_GetRect(annot, &rect)) {
                    float d[4];
                    RectToDisplay(m, rect.left, rect.bottom, rect.right, rect.top, d);
                    out.push_back(1);
                    out.insert(out.end(), d, d + 4);
                } else {
                    out.push_back(0);
                }
            }
            FPDFPage_CloseAnnot(annot);
        }
    }
    return ToJava(env, out);
}

// The /Contents text of the annotation at annotIndex (a note's text), or null.
JNIEXPORT jstring JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_annotContents(JNIEnv* env, jclass, jlong handle, jint index, jint annotIndex) {
    std::lock_guard<std::mutex> guard(g_lock);
    FPDF_PAGE page = GetPage(FromHandle(handle), index);
    if (page == nullptr) return nullptr;
    FPDF_ANNOTATION annot = FPDFPage_GetAnnot(page, annotIndex);
    if (annot == nullptr) return nullptr;
    const std::u16string text = ReadWide([&](FPDF_WCHAR* buf, unsigned long len) {
        return FPDFAnnot_GetStringValue(annot, "Contents", buf, len);
    });
    FPDFPage_CloseAnnot(annot);
    return env->NewString(reinterpret_cast<const jchar*>(text.data()), static_cast<jsize>(text.size()));
}

// Removes the annotation at annotIndex.
JNIEXPORT jboolean JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_removeAnnot(JNIEnv*, jclass, jlong handle, jint index, jint annotIndex) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    FPDF_PAGE page = GetPage(d, index);
    const bool ok = page != nullptr && FPDFPage_RemoveAnnot(page, annotIndex);
    EvictPage(d, index);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Saves a copy of the document into fd (not closed here). Incremental saves append the
// changes to the original bytes; full saves rewrite the file.
JNIEXPORT jboolean JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_save(JNIEnv*, jclass, jlong handle, jint fd,
                                             jboolean incremental) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    if (d->form != nullptr) FORM_ForceToKillFocus(d->form);
    FileWriter writer;
    writer.base.version = 1;
    writer.base.WriteBlock = WriteBlock;
    writer.fd = fd;
    const bool ok = FPDF_SaveAsCopy(d->doc, &writer.base, incremental ? FPDF_INCREMENTAL : FPDF_NO_INCREMENTAL);
    return ok && !writer.failed ? JNI_TRUE : JNI_FALSE;
}

// --- drawn annotations (PDFium generates their appearance) ---

namespace {

// Sets an annotation's colour (0xAARRGGBB), line width and print flag.
bool StyleAnnot(FPDF_ANNOTATION annot, jint color, float width) {
    const auto c = static_cast<uint32_t>(color);
    return FPDFAnnot_SetColor(annot, FPDFANNOT_COLORTYPE_Color, (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, (c >> 24) & 0xFF) &&
           (width <= 0 || FPDFAnnot_SetBorder(annot, 0, 0, width)) &&
           FPDFAnnot_SetFlags(annot, FPDF_ANNOT_FLAG_PRINT);
}

// Maps display rectangle {l, t, r, b} to a user-space FS_RECTF, grown by `pad` points.
FS_RECTF UserRect(const Affine& m, float l, float t, float r, float b, float pad) {
    double xs[4], ys[4];
    m.Map(l, t, &xs[0], &ys[0]);
    m.Map(r, t, &xs[1], &ys[1]);
    m.Map(l, b, &xs[2], &ys[2]);
    m.Map(r, b, &xs[3], &ys[3]);
    FS_RECTF rect{};
    rect.left = static_cast<float>(*std::min_element(xs, xs + 4)) - pad;
    rect.right = static_cast<float>(*std::max_element(xs, xs + 4)) + pad;
    rect.bottom = static_cast<float>(*std::min_element(ys, ys + 4)) - pad;
    rect.top = static_cast<float>(*std::max_element(ys, ys + 4)) + pad;
    return rect;
}

// Finishes a newly created annotation: on failure it is removed again (it is the page's last).
jboolean FinishAnnot(Document* d, int index, FPDF_PAGE page, FPDF_ANNOTATION annot, bool ok) {
    FPDFPage_CloseAnnot(annot);
    if (!ok) FPDFPage_RemoveAnnot(page, FPDFPage_GetAnnotCount(page) - 1);
    EvictPage(d, index);
    return ok ? JNI_TRUE : JNI_FALSE;
}

}  // namespace

// Adds an /Ink annotation. strokes = [pointCount, x, y, x, y, ..., pointCount, ...] in display points.
JNIEXPORT jboolean JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_addInk(JNIEnv* env, jclass, jlong handle, jint index,
                                               jfloatArray strokes, jint color, jfloat width) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    FPDF_PAGE page = GetPage(d, index);
    const jsize n = env->GetArrayLength(strokes);
    if (page == nullptr || n < 3) return JNI_FALSE;
    std::vector<float> s(static_cast<size_t>(n));
    env->GetFloatArrayRegion(strokes, 0, n, s.data());
    FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(page, FPDF_ANNOT_INK);
    if (annot == nullptr) return JNI_FALSE;
    const Affine m = DisplayToUser(page);
    float l = 1e9f, t = 1e9f, r = -1e9f, b = -1e9f;
    bool ok = true;
    for (size_t i = 0; i < s.size() && ok;) {
        const auto count = static_cast<size_t>(s[i++]);
        if (count == 0 || i + 2 * count > s.size()) break;
        std::vector<FS_POINTF> points(count);
        for (size_t p = 0; p < count; ++p, i += 2) {
            double ux, uy;
            m.Map(s[i], s[i + 1], &ux, &uy);
            points[p] = {static_cast<float>(ux), static_cast<float>(uy)};
            l = std::min(l, s[i]); r = std::max(r, s[i]);
            t = std::min(t, s[i + 1]); b = std::max(b, s[i + 1]);
        }
        ok = FPDFAnnot_AddInkStroke(annot, points.data(), count) >= 0;
    }
    if (ok) {
        const FS_RECTF rect = UserRect(m, l, t, r, b, width);
        ok = FPDFAnnot_SetRect(annot, &rect) && StyleAnnot(annot, color, width);
    }
    return FinishAnnot(d, index, page, annot, ok);
}

// Adds a /Square (subtype FPDF_ANNOT_SQUARE) or /Circle annotation outlining the display
// rectangle {l, t, r, b}.
JNIEXPORT jboolean JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_addShape(JNIEnv*, jclass, jlong handle, jint index, jint subtype,
                                                 jfloat l, jfloat t, jfloat r, jfloat b, jint color, jfloat width) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    FPDF_PAGE page = GetPage(d, index);
    if (page == nullptr || (subtype != FPDF_ANNOT_SQUARE && subtype != FPDF_ANNOT_CIRCLE)) return JNI_FALSE;
    FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(page, subtype);
    if (annot == nullptr) return JNI_FALSE;
    const FS_RECTF rect = UserRect(DisplayToUser(page), l, t, r, b, 0);
    const bool ok = FPDFAnnot_SetRect(annot, &rect) && StyleAnnot(annot, color, width);
    return FinishAnnot(d, index, page, annot, ok);
}

// Adds a sticky note (/Text annotation) with `contents`, its icon's top-left at display point (x, y).
JNIEXPORT jboolean JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_addNote(JNIEnv* env, jclass, jlong handle, jint index,
                                                jfloat x, jfloat y, jstring contents, jint color) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    FPDF_PAGE page = GetPage(d, index);
    if (page == nullptr) return JNI_FALSE;
    FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(page, FPDF_ANNOT_TEXT);
    if (annot == nullptr) return JNI_FALSE;
    constexpr float kIcon = 20;
    const FS_RECTF rect = UserRect(DisplayToUser(page), x, y, x + kIcon, y + kIcon, 0);
    const std::u16string text = JavaToWide(env, contents);
    const bool ok = FPDFAnnot_SetRect(annot, &rect) && StyleAnnot(annot, color, 0) &&
                    FPDFAnnot_SetStringValue(annot, "Contents", Wide(text));
    return FinishAnnot(d, index, page, annot, ok);
}

// --- pages ---

// Deletes the pages at `indices` (any order).
JNIEXPORT jboolean JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_deletePages(JNIEnv* env, jclass, jlong handle, jintArray indices) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    std::vector<jint> pages(static_cast<size_t>(env->GetArrayLength(indices)));
    env->GetIntArrayRegion(indices, 0, static_cast<jsize>(pages.size()), pages.data());
    CloseAllPages(d);
    std::sort(pages.rbegin(), pages.rend());  // last first, so earlier indices stay valid
    for (jint p : pages) FPDFPage_Delete(d->doc, p);
    return JNI_TRUE;
}

// Turns the pages at `indices` by `quarterTurns` x 90 degrees clockwise.
JNIEXPORT jboolean JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_rotatePages(JNIEnv* env, jclass, jlong handle, jintArray indices, jint quarterTurns) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    std::vector<jint> pages(static_cast<size_t>(env->GetArrayLength(indices)));
    env->GetIntArrayRegion(indices, 0, static_cast<jsize>(pages.size()), pages.data());
    CloseAllPages(d);
    bool ok = true;
    for (jint p : pages) {
        FPDF_PAGE page = FPDF_LoadPage(d->doc, p);
        if (page == nullptr) {
            ok = false;
            continue;
        }
        FPDFPage_SetRotation(page, ((FPDFPage_GetRotation(page) + quarterTurns) % 4 + 4) % 4);
        FPDF_ClosePage(page);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Moves the pages at `indices` (kept in that order) so the first lands at `destIndex` of the result.
JNIEXPORT jboolean JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_movePages(JNIEnv* env, jclass, jlong handle, jintArray indices, jint destIndex) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    std::vector<jint> pages(static_cast<size_t>(env->GetArrayLength(indices)));
    env->GetIntArrayRegion(indices, 0, static_cast<jsize>(pages.size()), pages.data());
    CloseAllPages(d);
    const bool ok = FPDF_MovePages(d->doc, reinterpret_cast<const int*>(pages.data()),
                                   static_cast<unsigned long>(pages.size()), destIndex);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Inserts every page of the PDF in srcFd (not closed here) before page `insertAt`.
// Returns the number of pages inserted, or a negative PDFium error (e.g. -FPDF_ERR_PASSWORD).
JNIEXPORT jint JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_importPages(JNIEnv*, jclass, jlong handle, jint srcFd, jlong length, jint insertAt) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    Document src;
    src.fd = srcFd;
    src.access.m_FileLen = static_cast<unsigned long>(length);
    src.access.m_GetBlock = ReadBlock;
    src.access.m_Param = &src;
    src.doc = FPDF_LoadCustomDocument(&src.access, nullptr);
    if (src.doc == nullptr) {
        const auto err = static_cast<jint>(FPDF_GetLastError());
        return -(err == 0 ? FPDF_ERR_UNKNOWN : err);
    }
    const int count = FPDF_GetPageCount(src.doc);
    CloseAllPages(d);
    const bool ok = FPDF_ImportPages(d->doc, src.doc, nullptr, insertAt);
    FPDF_CloseDocument(src.doc);
    return ok ? count : -FPDF_ERR_UNKNOWN;
}

// Writes a new PDF holding copies of the pages at `indices` (in that order) to fd (not closed here).
JNIEXPORT jboolean JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_extractPages(JNIEnv* env, jclass, jlong handle, jintArray indices, jint fd) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    std::vector<jint> pages(static_cast<size_t>(env->GetArrayLength(indices)));
    env->GetIntArrayRegion(indices, 0, static_cast<jsize>(pages.size()), pages.data());
    if (d->form != nullptr) FORM_ForceToKillFocus(d->form);
    FPDF_DOCUMENT out = FPDF_CreateNewDocument();
    if (out == nullptr) return JNI_FALSE;
    bool ok = FPDF_ImportPagesByIndex(out, d->doc, reinterpret_cast<const int*>(pages.data()),
                                      static_cast<unsigned long>(pages.size()), 0);
    if (ok) {
        FileWriter writer;
        writer.base.version = 1;
        writer.base.WriteBlock = WriteBlock;
        writer.fd = fd;
        ok = FPDF_SaveAsCopy(out, &writer.base, FPDF_NO_INCREMENTAL) && !writer.failed;
    }
    FPDF_CloseDocument(out);
    return ok ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"

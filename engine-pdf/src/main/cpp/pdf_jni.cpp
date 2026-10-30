// SPDX-License-Identifier: GPL-3.0-or-later
// JNI bridge between dev.offlineoffice.pdf and PDFium's public C API.
//
// PDFium keeps global state and is not thread-safe, so every entry point takes
// the same process-wide lock. Callers should still confine a document to one
// worker thread to avoid contention.

#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <mutex>

#include "fpdfview.h"

#define LOG_TAG "oopdf"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_lock;
bool g_initialized = false;

struct Document {
    int fd = -1;
    FPDF_FILEACCESS access{};
    FPDF_DOCUMENT doc = nullptr;
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
    return reinterpret_cast<jlong>(d);
}

JNIEXPORT void JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_close(JNIEnv*, jclass, jlong handle) {
    std::lock_guard<std::mutex> guard(g_lock);
    Document* d = FromHandle(handle);
    if (d == nullptr) return;
    FPDF_CloseDocument(d->doc);
    close(d->fd);
    delete d;
}

JNIEXPORT jint JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_pageCount(JNIEnv*, jclass, jlong handle) {
    std::lock_guard<std::mutex> guard(g_lock);
    return FPDF_GetPageCount(FromHandle(handle)->doc);
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
// as a tile by passing negative offsets.
JNIEXPORT void JNICALL
Java_dev_offlineoffice_pdf_PdfiumNative_renderPage(JNIEnv* env, jclass, jlong handle, jint index,
                                                   jobject bitmap, jint startX, jint startY,
                                                   jint sizeX, jint sizeY, jboolean drawAnnots) {
    std::lock_guard<std::mutex> guard(g_lock);
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        ThrowIllegalState(env, "bitmap must be ARGB_8888");
        return;
    }
    FPDF_PAGE page = FPDF_LoadPage(FromHandle(handle)->doc, index);
    if (page == nullptr) {
        ThrowIllegalState(env, "cannot load page");
        return;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        FPDF_ClosePage(page);
        ThrowIllegalState(env, "cannot lock bitmap");
        return;
    }
    FPDF_BITMAP target = FPDFBitmap_CreateEx(static_cast<int>(info.width),
                                             static_cast<int>(info.height), FPDFBitmap_BGRA,
                                             pixels, static_cast<int>(info.stride));
    FPDFBitmap_FillRect(target, 0, 0, static_cast<int>(info.width),
                        static_cast<int>(info.height), 0xFFFFFFFF);
    // Android bitmaps are RGBA in memory; ask PDFium to emit that order directly.
    int flags = FPDF_REVERSE_BYTE_ORDER | FPDF_LCD_TEXT;
    if (drawAnnots) flags |= FPDF_ANNOT;
    FPDF_RenderPageBitmap(target, page, startX, startY, sizeX, sizeY, 0, flags);
    FPDFBitmap_Destroy(target);
    AndroidBitmap_unlockPixels(env, bitmap);
    FPDF_ClosePage(page);
}

}  // extern "C"

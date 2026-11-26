/*
 * Thin annotation bindings for the PDFium already packaged by pdfiumandroid-core:2.0.3.
 * This library never opens/parses a document and contains no PDF implementation.
 * It accepts page handles owned by the existing isolated PDFium worker, under its lock.
 * ABI declarations match https://pdfium.googlesource.com/pdfium/+/main/public/fpdf_annot.h
 */
#include <jni.h>
#include <dlfcn.h>
#include <locale.h>
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef void *PdfPage;
typedef void *PdfAnnot;
typedef struct { float left, top, right, bottom; } PdfRect;
typedef struct { float x1, y1, x2, y2, x3, y3, x4, y4; } PdfQuad;

static struct {
    PdfAnnot (*create)(PdfPage, int);
    int (*count)(PdfPage);
    PdfAnnot (*get)(PdfPage, int);
    int (*remove)(PdfPage, int);
    void (*close)(PdfAnnot);
    int (*rect)(PdfAnnot, const PdfRect *);
    int (*quad)(PdfAnnot, const PdfQuad *);
    int (*color)(PdfAnnot, int, unsigned int, unsigned int, unsigned int, unsigned int);
    int (*setString)(PdfAnnot, const char *, const unsigned short *);
    unsigned long (*getString)(PdfAnnot, const char *, unsigned short *, unsigned long);
    int (*flags)(PdfAnnot, int);
    int (*appearance)(PdfAnnot, int, const unsigned short *);
} api;
static pthread_once_t once = PTHREAD_ONCE_INIT;
static const char *load_error;

static void load_api(void) {
    void *library = dlopen("libpdfium.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) { load_error = "Existing PDFium library is unavailable"; return; }
#define LOAD(field, symbol) do { \
    *(void **)(&api.field) = dlsym(library, symbol); \
    if (!api.field) { load_error = "Existing PDFium is missing " symbol; return; } \
} while (0)
    LOAD(create, "FPDFPage_CreateAnnot");
    LOAD(count, "FPDFPage_GetAnnotCount");
    LOAD(get, "FPDFPage_GetAnnot");
    LOAD(remove, "FPDFPage_RemoveAnnot");
    LOAD(close, "FPDFPage_CloseAnnot");
    LOAD(rect, "FPDFAnnot_SetRect");
    LOAD(quad, "FPDFAnnot_AppendAttachmentPoints");
    LOAD(color, "FPDFAnnot_SetColor");
    LOAD(setString, "FPDFAnnot_SetStringValue");
    LOAD(getString, "FPDFAnnot_GetStringValue");
    LOAD(flags, "FPDFAnnot_SetFlags");
    LOAD(appearance, "FPDFAnnot_SetAP");
#undef LOAD
}

static void fail(JNIEnv *env, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type) (*env)->ThrowNew(env, type, message);
}

static int ready(JNIEnv *env, jlong page) {
    pthread_once(&once, load_api);
    if (load_error) { fail(env, load_error); return 0; }
    if (!page) { fail(env, "PDF annotation page is closed"); return 0; }
    return 1;
}

/* GetStringChars has no terminating NUL; PDFium requires an explicitly terminated UTF-16LE. */
static unsigned short *utf16(JNIEnv *env, jstring value, jsize max_length) {
    if (!value) { fail(env, "Missing PDF annotation text"); return NULL; }
    jsize length = (*env)->GetStringLength(env, value);
    if (length > max_length) { fail(env, "PDF annotation text is too long"); return NULL; }
    unsigned short *copy = calloc((size_t)length + 1, sizeof(unsigned short));
    if (!copy) { fail(env, "Could not allocate PDF annotation text"); return NULL; }
    (*env)->GetStringRegion(env, value, 0, length, (jchar *)copy);
    if ((*env)->ExceptionCheck(env)) { free(copy); return NULL; }
    return copy;
}

JNIEXPORT void JNICALL
Java_com_example_frogreader_data_pdf_compat_PdfiumAnnotationNative_removeOwned(
        JNIEnv *env, jclass type, jlong page_pointer, jstring prefix) {
    (void)type;
    if (!ready(env, page_pointer)) return;
    unsigned short *owned = utf16(env, prefix, 512);
    if (!owned) return;
    const size_t prefix_length = (size_t)(*env)->GetStringLength(env, prefix);
    if (!prefix_length) { free(owned); fail(env, "Missing annotation ownership prefix"); return; }
    PdfPage page = (PdfPage)(uintptr_t)page_pointer;
    int count = api.count(page);
    if (count < 0) { free(owned); fail(env, "Could not read PDF annotations"); return; }
    for (int index = count - 1; index >= 0; --index) {
        PdfAnnot annotation = api.get(page, index);
        if (!annotation) continue;
        unsigned long bytes = api.getString(annotation, "NM", NULL, 0);
        int matches = 0;
        /* Foreign, exceptionally long names are preserved without allocating their contents. */
        if (bytes >= (prefix_length + 1) * 2 && bytes <= 2048 && bytes % 2 == 0) {
            unsigned short name[1024];
            unsigned long written = api.getString(annotation, "NM", name, sizeof(name));
            matches = written == bytes && memcmp(name, owned, prefix_length * 2) == 0;
        }
        api.close(annotation);
        if (matches && !api.remove(page, index)) {
            free(owned); fail(env, "Could not remove PDF highlight"); return;
        }
    }
    free(owned);
}

JNIEXPORT void JNICALL
Java_com_example_frogreader_data_pdf_compat_PdfiumAnnotationNative_addHighlight(
        JNIEnv *env, jclass type, jlong page_pointer, jstring name, jstring contents,
        jint color, jfloatArray native_quads) {
    (void)type;
    if (!ready(env, page_pointer)) return;
    if (!native_quads) { fail(env, "Missing PDF highlight bounds"); return; }
    jsize length = (*env)->GetArrayLength(env, native_quads);
    if (length <= 0 || length % 8 != 0 || length > 8192 * 8) {
        fail(env, "Invalid PDF highlight bounds"); return;
    }
    unsigned short *annotation_name = utf16(env, name, 512);
    if (!annotation_name) return;
    unsigned short *annotation_contents = utf16(env, contents, 1000000);
    if (!annotation_contents) { free(annotation_name); return; }
    jfloat *points = (*env)->GetFloatArrayElements(env, native_quads, NULL);
    if (!points) { free(annotation_name); free(annotation_contents); return; }
    PdfRect rect = { points[0], points[1], points[0], points[1] };
    for (jsize index = 0; index < length; index += 2) {
        if (!isfinite(points[index]) || !isfinite(points[index + 1]) ||
                fabsf(points[index]) > 10000000 || fabsf(points[index + 1]) > 10000000) {
            fail(env, "Invalid PDF highlight coordinates"); goto cleanup_points;
        }
        rect.left = fminf(rect.left, points[index]);
        rect.right = fmaxf(rect.right, points[index]);
        rect.bottom = fminf(rect.bottom, points[index + 1]);
        rect.top = fmaxf(rect.top, points[index + 1]);
    }
    if (rect.left >= rect.right || rect.bottom >= rect.top) {
        fail(env, "Empty PDF highlight bounds"); goto cleanup_points;
    }
    PdfAnnot annotation = api.create((PdfPage)(uintptr_t)page_pointer, 9 /* Highlight */);
    if (!annotation) { fail(env, "Could not create PDF highlight"); goto cleanup_points; }
    unsigned int red = ((unsigned int)color >> 16) & 255;
    unsigned int green = ((unsigned int)color >> 8) & 255;
    unsigned int blue = (unsigned int)color & 255;
    const unsigned short title[] = { 'F','r','o','g','R','e','a','d','e','r',0 };
    int okay = api.rect(annotation, &rect) && api.color(annotation, 0, red, green, blue, 90) &&
        api.setString(annotation, "NM", annotation_name) &&
        api.setString(annotation, "Contents", annotation_contents) &&
        api.setString(annotation, "T", title) && api.flags(annotation, 4 /* Print */);
    for (jsize index = 0; okay && index < length; index += 8) {
        PdfQuad quad;
        memcpy(&quad, points + index, sizeof(quad));
        okay = api.quad(annotation, &quad);
    }
    /* A standard appearance makes the highlight visible in readers that don't generate one. */
    size_t capacity = (size_t)(length / 8) * 320 + 128;
    char *appearance = okay ? malloc(capacity) : NULL;
    if (okay && !appearance) okay = 0;
    if (appearance) {
        locale_t c_locale = newlocale(LC_NUMERIC_MASK, "C", (locale_t)0);
        if (!c_locale) {
            free(appearance);
            api.close(annotation);
            fail(env, "Could not prepare PDF highlight appearance");
            goto cleanup_points;
        }
        locale_t previous = uselocale(c_locale);
        size_t used = (size_t)snprintf(appearance, capacity, "q /GS gs %.6f %.6f %.6f rg\n",
            red / 255.0, green / 255.0, blue / 255.0);
        for (jsize index = 0; okay && index < length; index += 8) {
            int written = snprintf(appearance + used, capacity - used,
                "%.6f %.6f m %.6f %.6f l %.6f %.6f l %.6f %.6f l h f\n",
                points[index], points[index+1], points[index+2], points[index+3],
                points[index+6], points[index+7], points[index+4], points[index+5]);
            if (written < 0 || (size_t)written >= capacity - used) okay = 0;
            else used += (size_t)written;
        }
        if (c_locale) { uselocale(previous); freelocale(c_locale); }
        if (okay && used + 3 < capacity) { appearance[used++] = 'Q'; appearance[used] = 0; }
        else okay = 0;
        unsigned short *wide = okay ? calloc(used + 1, 2) : NULL;
        if (okay && !wide) okay = 0;
        if (wide) {
            for (size_t index = 0; index < used; ++index) wide[index] = (unsigned char)appearance[index];
            okay = api.appearance(annotation, 0 /* Normal */, wide);
            free(wide);
        }
        free(appearance);
    }
    api.close(annotation);
    if (!okay) fail(env, "Could not write PDF highlight annotation");
cleanup_points:
    (*env)->ReleaseFloatArrayElements(env, native_quads, points, JNI_ABORT);
    free(annotation_name);
    free(annotation_contents);
}

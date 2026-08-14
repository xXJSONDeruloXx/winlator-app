/*
 * Minimal GLX ABI compatibility for the embedded Gladio client.
 *
 * Gladio implements the GLX rendering entry points used by its client
 * protocol, but its glXQueryExtension export is currently a stub.  Steam's
 * loader calls this probe before issuing the GLX requests that XServerCore
 * already handles.  Keep this interposer deliberately narrow: it reports
 * the GLX extension and the error base owned by GLXExtension without
 * pretending to implement any additional X11 protocol.
 */

typedef struct _XDisplay Display;
typedef struct {
    void *visual;
    void *visualid;
    int screen;
    int depth;
    int class;
    unsigned long red_mask;
    unsigned long green_mask;
    unsigned long blue_mask;
    int colormap_size;
    int bits_per_rgb;
} XVisualInfo;

static const unsigned char GLADIO_VENDOR[] = "Winlator";
static const unsigned char GLADIO_RENDERER[] = "Gladio";
static const unsigned char GLADIO_VERSION[] = "3.3";
static const unsigned char GLADIO_GLSL_VERSION[] = "3.30";
static const unsigned char GLADIO_EXTENSIONS[] = "";

int glXQueryExtension(Display *display, int *errorBase, int *eventBase) {
    (void) display;
    if (errorBase != 0) *errorBase = 128;
    if (eventBase != 0) *eventBase = 0;
    return 1;
}

/*
 * The pinned Gladio client leaves glXGetConfig as a diagnostic stub.  Steam
 * calls it while validating the XVisualInfo returned by glXChooseVisual;
 * report the same single 32-bpp TrueColor configuration that GLXExtension
 * advertises to the X server.  Keep this ABI-only shim free of libc so it can
 * be loaded by the glibc Holo guest without pulling Android linker-script
 * dependencies into the guest.
 */
int glXGetConfig(Display *display, XVisualInfo *visual, int attribute, int *value) {
    (void) display;
    (void) visual;
    if (value == 0) return 2; /* GLX_BAD_ATTRIBUTE */

    switch (attribute) {
        case 1:      /* GLX_USE_GL */
        case 4:      /* GLX_RGBA */
        case 5:      /* GLX_DOUBLEBUFFER */
        case 0x8012: /* GLX_X_RENDERABLE */
            *value = 1;
            return 0;
        case 2:      /* GLX_BUFFER_SIZE */
            *value = 32;
            return 0;
        case 3:      /* GLX_LEVEL */
        case 6:      /* GLX_STEREO */
        case 7:      /* GLX_AUX_BUFFERS */
        case 14:     /* GLX_ACCUM_RED_SIZE */
        case 15:     /* GLX_ACCUM_GREEN_SIZE */
        case 16:     /* GLX_ACCUM_BLUE_SIZE */
        case 17:     /* GLX_ACCUM_ALPHA_SIZE */
            *value = 0;
            return 0;
        case 8:      /* GLX_RED_SIZE */
        case 9:      /* GLX_GREEN_SIZE */
        case 10:     /* GLX_BLUE_SIZE */
        case 11:     /* GLX_ALPHA_SIZE */
            *value = 8;
            return 0;
        case 12:     /* GLX_DEPTH_SIZE */
            *value = 24;
            return 0;
        case 13:     /* GLX_STENCIL_SIZE */
            *value = 8;
            return 0;
        case 0x22:   /* GLX_X_VISUAL_TYPE */
            *value = 0x8002; /* GLX_TRUE_COLOR */
            return 0;
        case 0x23:   /* GLX_TRANSPARENT_TYPE */
            *value = 0x8000; /* GLX_NONE */
            return 0;
        case 0x800b: /* GLX_VISUAL_ID */
        case 0x800c: /* GLX_SCREEN */
            *value = attribute == 0x800b ? 1 : 0;
            return 0;
        case 0x8010: /* GLX_DRAWABLE_TYPE */
            *value = 1; /* GLX_WINDOW_BIT */
            return 0;
        case 0x8011: /* GLX_RENDER_TYPE */
        case 0x8013: /* GLX_FBCONFIG_ID */
            *value = 1; /* GLX_RGBA_BIT/FBCONFIG 1 */
            return 0;
        default:
            return 2; /* GLX_BAD_ATTRIBUTE */
    }
}

void glXDestroyContext(Display *display, void *context) {
    (void) display;
    /*
     * Gladio's client-side probe can tear down a context that its own
     * glXCreateContext returned as NULL after the server-side probe already
     * succeeded.  Its native implementation dereferences that NULL handle
     * while cleaning up.  Keep the probe cleanup harmless; real rendering
     * context destruction remains a follow-up once client handle creation is
     * validated.
     */
    if (context == 0) return;
}

/*
 * Mesa's GLVND libGLX resolves this symbol while it initializes its vendor
 * dispatch table. Gladio drives swap/present through its own ring protocol,
 * so there is no separate X11 swap-interval request to issue here. Returning
 * successfully keeps GLVND's optional dispatch lookup from aborting the
 * process; Gladio's glXSwapBuffers remains the presentation boundary.
 */
void glXSwapIntervalEXT(Display *display, void *drawable, int interval) {
    (void) display;
    (void) drawable;
    (void) interval;
}

int glXSwapIntervalMESA(unsigned int interval) {
    (void) interval;
    return 0;
}

const unsigned char *glGetString(unsigned int name) {
    switch (name) {
        case 0x1f00: /* GL_VENDOR */
            return GLADIO_VENDOR;
        case 0x1f01: /* GL_RENDERER */
            return GLADIO_RENDERER;
        case 0x1f02: /* GL_VERSION */
            return GLADIO_VERSION;
        case 0x1f03: /* GL_EXTENSIONS */
            return GLADIO_EXTENSIONS;
        case 0x8b8c: /* GL_SHADING_LANGUAGE_VERSION */
            return GLADIO_GLSL_VERSION;
        default:
            return GLADIO_EXTENSIONS;
    }
}

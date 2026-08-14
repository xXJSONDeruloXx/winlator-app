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

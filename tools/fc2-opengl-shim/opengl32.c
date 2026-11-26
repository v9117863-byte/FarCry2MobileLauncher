#define WINAPI __attribute__((stdcall))
typedef void *HDC;
typedef void *HGLRC;
typedef unsigned int GLenum;
typedef unsigned char GLubyte;
typedef int BOOL;
#define TRUE 1

/*
 * Far Cry 2's Ubisoft systemdetection.dll queries OpenGL only to identify
 * the adapter. Winlator's Wine OpenGL backend has no current GL context at
 * this point and its null_glGetString entry aborts the process. DX9 rendering
 * itself is handled separately by DXVK/Vulkan.
 *
 * This deliberately tiny, game-local OpenGL probe shim provides stable
 * adapter strings and a harmless dummy WGL context for the detector.
 * It is not a renderer and must not be used by games that render with OpenGL.
 */
typedef unsigned int GLenum;
typedef unsigned char GLubyte;

static const GLubyte vendor[] = "Qualcomm";
static const GLubyte renderer[] = "Adreno (TM) 610";
static const GLubyte version[] = "1.1 Far Cry 2 compatibility probe";
static const GLubyte extensions[] = "";

__declspec(dllexport) const GLubyte * WINAPI glGetString(GLenum name) {
    switch (name) {
        case 0x1F00: return vendor;      /* GL_VENDOR */
        case 0x1F01: return renderer;    /* GL_RENDERER */
        case 0x1F02: return version;     /* GL_VERSION */
        case 0x1F03: return extensions;  /* GL_EXTENSIONS */
        default: return (const GLubyte *)"";
    }
}

__declspec(dllexport) HGLRC WINAPI wglCreateContext(HDC deviceContext) {
    (void)deviceContext;
    return (HGLRC)(unsigned long)0x1234;
}

__declspec(dllexport) BOOL WINAPI wglDeleteContext(HGLRC context) {
    (void)context;
    return TRUE;
}

__declspec(dllexport) BOOL WINAPI wglMakeCurrent(HDC deviceContext, HGLRC context) {
    (void)deviceContext;
    (void)context;
    return TRUE;
}

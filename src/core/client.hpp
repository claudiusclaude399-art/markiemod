#pragma once
#include <windows.h>

// Frame orchestration. Everything here runs on the render thread, called from
// the wglSwapBuffers hook, once per frame.
namespace client {

void onFrame(HDC hdc);

// Signalled once the render thread has fully torn the client down and the
// hooks can be removed.
HANDLE teardownDoneEvent();

}  // namespace client

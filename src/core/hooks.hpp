#pragma once

// MinHook on opengl32!wglSwapBuffers. GLFW's glfwSwapBuffers ends up there once
// per frame, on Minecraft's render thread.
namespace hooks {

bool install();
// Disable the hook and wait until no thread is inside the detour.
void uninstall();

}  // namespace hooks

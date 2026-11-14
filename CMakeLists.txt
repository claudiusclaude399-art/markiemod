cmake_minimum_required(VERSION 3.24)
project(markiemod LANGUAGES C CXX)

# Single target configuration: Windows x64, Minecraft 1.21.11 (Fabric).
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(MSVC)
  set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")  # static CRT: no redist needed
endif()

include(FetchContent)
FetchContent_Declare(imgui
  GIT_REPOSITORY https://github.com/ocornut/imgui.git
  GIT_TAG        v1.91.9b
  GIT_SHALLOW    TRUE)
FetchContent_Declare(minhook
  GIT_REPOSITORY https://github.com/TsudaKageyu/minhook.git
  GIT_TAG        v1.3.4
  GIT_SHALLOW    TRUE
  SOURCE_SUBDIR  do-not-add)   # sources only; we build MinHook ourselves below
FetchContent_MakeAvailable(imgui minhook)

# --- JNI headers (only headers: jvm.dll is found at runtime) -------------------
if(NOT JNI_INCLUDE_DIR)
  if(DEFINED ENV{JAVA_HOME})
    set(JNI_INCLUDE_DIR "$ENV{JAVA_HOME}/include")
  else()
    message(FATAL_ERROR "Set JAVA_HOME (any JDK 21) or pass -DJNI_INCLUDE_DIR=<jdk>/include")
  endif()
endif()
if(NOT EXISTS "${JNI_INCLUDE_DIR}/jni.h")
  message(FATAL_ERROR "jni.h not found in ${JNI_INCLUDE_DIR}")
endif()

# --- third party -----------------------------------------------------------------
add_library(imgui STATIC
  ${imgui_SOURCE_DIR}/imgui.cpp
  ${imgui_SOURCE_DIR}/imgui_draw.cpp
  ${imgui_SOURCE_DIR}/imgui_tables.cpp
  ${imgui_SOURCE_DIR}/imgui_widgets.cpp
  ${imgui_SOURCE_DIR}/backends/imgui_impl_win32.cpp
  ${imgui_SOURCE_DIR}/backends/imgui_impl_opengl3.cpp)
target_include_directories(imgui PUBLIC ${imgui_SOURCE_DIR} ${imgui_SOURCE_DIR}/backends)
target_compile_definitions(imgui PUBLIC IMGUI_DISABLE_OBSOLETE_FUNCTIONS)

add_library(minhook STATIC
  ${minhook_SOURCE_DIR}/src/buffer.c
  ${minhook_SOURCE_DIR}/src/hook.c
  ${minhook_SOURCE_DIR}/src/trampoline.c
  ${minhook_SOURCE_DIR}/src/hde/hde64.c)
target_include_directories(minhook PUBLIC ${minhook_SOURCE_DIR}/include)

# --- the client DLL ----------------------------------------------------------------
add_library(markiemod SHARED
  src/dllmain.cpp
  src/core/client.cpp
  src/core/config.cpp
  src/core/gui.cpp
  src/core/hooks.cpp
  src/core/input.cpp
  src/core/log.cpp
  src/jni/jvm.cpp
  src/sdk/sdk.cpp
  src/modules/module.cpp
  src/modules/manager.cpp
  src/modules/visual/fullbright.cpp
  src/modules/visual/zoom.cpp
  src/modules/visual/timechanger.cpp
  src/modules/visual/clearweather.cpp
  src/modules/movement/autosprint.cpp
  src/modules/movement/flight.cpp
  src/modules/movement/speed.cpp
  src/modules/movement/step.cpp
  src/modules/movement/highjump.cpp
  src/modules/world/nofall.cpp
  src/modules/world/fastplace.cpp
  src/modules/world/fastbreak.cpp)
target_include_directories(markiemod PRIVATE src ${JNI_INCLUDE_DIR} ${JNI_INCLUDE_DIR}/win32)
target_compile_definitions(markiemod PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX UNICODE _UNICODE _CRT_SECURE_NO_WARNINGS)
target_link_libraries(markiemod PRIVATE imgui minhook opengl32 gdi32 user32 dwmapi)
if(MSVC)
  target_compile_options(markiemod PRIVATE /W4 /permissive- /utf-8)
else()
  target_compile_options(markiemod PRIVATE -Wall -Wextra -Wno-cast-function-type)
  target_link_options(markiemod PRIVATE -static -static-libgcc -static-libstdc++)
endif()

# --- injector ----------------------------------------------------------------------
add_executable(injector injector/injector.cpp)
target_compile_definitions(injector PRIVATE WIN32_LEAN_AND_MEAN UNICODE _UNICODE _CRT_SECURE_NO_WARNINGS)
if(MSVC)
  target_compile_options(injector PRIVATE /W4)
else()
  target_link_options(injector PRIVATE -municode -static)
endif()

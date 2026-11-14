#include "log.hpp"

#include <windows.h>

#include <cstdarg>
#include <cstdio>
#include <deque>
#include <mutex>

namespace {
std::mutex g_mutex;
std::deque<std::string> g_lines;
FILE* g_file = nullptr;
constexpr size_t kMaxLines = 200;
}

void logf(const char* fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);

    SYSTEMTIME t;
    GetLocalTime(&t);
    char line[1100];
    snprintf(line, sizeof(line), "[%02d:%02d:%02d] %s", t.wHour, t.wMinute, t.wSecond, buf);

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_file) g_file = fopen("markiemod.log", "a");
    if (g_file) { fprintf(g_file, "%s\n", line); fflush(g_file); }
    OutputDebugStringA(line);
    OutputDebugStringA("\n");
    g_lines.emplace_back(line);
    while (g_lines.size() > kMaxLines) g_lines.pop_front();
}

std::vector<std::string> logLines() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return {g_lines.begin(), g_lines.end()};
}

void logClose() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_file) { fclose(g_file); g_file = nullptr; }
}

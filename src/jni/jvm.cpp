#include "jvm.hpp"

#include <windows.h>

#include <string>
#include <unordered_map>

#include "../core/log.hpp"

namespace jni {

namespace {

JavaVM* g_vm = nullptr;
JNIEnv* g_env = nullptr;
jobject g_loader = nullptr;          // global ref: game (Knot) class loader
jmethodID g_loadClass = nullptr;     // ClassLoader.loadClass(String)
jclass g_integer = nullptr;          // global refs for boxing
jclass g_double = nullptr;
jmethodID g_intValueOf = nullptr, g_intValue = nullptr;
jmethodID g_dblValueOf = nullptr, g_dblValue = nullptr;
jmethodID g_throwableToString = nullptr;
bool g_resolved = false;

std::vector<ClassRef*>& classRegistry() { static std::vector<ClassRef*> v; return v; }
std::vector<FieldRef*>& fieldRegistry() { static std::vector<FieldRef*> v; return v; }
std::vector<MethodRef*>& methodRegistry() { static std::vector<MethodRef*> v; return v; }

std::string toDots(const char* s) {
    std::string r(s);
    for (char& c : r) if (c == '/') c = '.';
    return r;
}

jclass globalClass(const char* name) {
    jclass local = g_env->FindClass(name);
    if (check(name) || !local) return nullptr;
    auto g = static_cast<jclass>(g_env->NewGlobalRef(local));
    g_env->DeleteLocalRef(local);
    return g;
}

// Try to use `loader` (local ref) as the game loader: it must see MinecraftClient.
bool adoptLoader(jobject loader, const char* how) {
    if (!loader) return false;
    jstring n = g_env->NewStringUTF("net.minecraft.class_310");
    jobject c = g_env->CallObjectMethod(loader, g_loadClass, n);
    g_env->DeleteLocalRef(n);
    bool ok = !check("probe loader") && c != nullptr;
    if (c) g_env->DeleteLocalRef(c);
    if (ok) {
        g_loader = g_env->NewGlobalRef(loader);
        logf("[jni] game class loader found via %s", how);
    }
    return ok;
}

bool findGameLoader() {
    jclass clClass = g_env->FindClass("java/lang/ClassLoader");
    if (check("FindClass ClassLoader") || !clClass) return false;
    g_loadClass = g_env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    g_env->DeleteLocalRef(clClass);
    if (check("loadClass id") || !g_loadClass) return false;

    // 1) Context class loader of the render thread. Fabric's Knot sets it to itself.
    jclass threadClass = g_env->FindClass("java/lang/Thread");
    if (!check("FindClass Thread") && threadClass) {
        jmethodID cur = g_env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
        jmethodID getCl = g_env->GetMethodID(threadClass, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
        if (!check("Thread ids") && cur && getCl) {
            jobject t = g_env->CallStaticObjectMethod(threadClass, cur);
            jobject cl = t ? g_env->CallObjectMethod(t, getCl) : nullptr;
            check("context loader");
            bool ok = adoptLoader(cl, "render thread context loader");
            if (cl) g_env->DeleteLocalRef(cl);
            if (t) g_env->DeleteLocalRef(t);
            if (ok) { g_env->DeleteLocalRef(threadClass); return true; }
        }
        g_env->DeleteLocalRef(threadClass);
    }

    // 2) FabricLauncherBase.getLauncher().getTargetClassLoader()
    jclass flb = g_env->FindClass("net/fabricmc/loader/impl/launch/FabricLauncherBase");
    if (!check("FindClass FabricLauncherBase") && flb) {
        jmethodID getLauncher = g_env->GetStaticMethodID(flb, "getLauncher", "()Lnet/fabricmc/loader/impl/launch/FabricLauncher;");
        if (!check("getLauncher id") && getLauncher) {
            jobject launcher = g_env->CallStaticObjectMethod(flb, getLauncher);
            if (!check("getLauncher") && launcher) {
                jclass lc = g_env->GetObjectClass(launcher);
                jmethodID getTcl = g_env->GetMethodID(lc, "getTargetClassLoader", "()Ljava/lang/ClassLoader;");
                if (!check("getTargetClassLoader id") && getTcl) {
                    jobject cl = g_env->CallObjectMethod(launcher, getTcl);
                    check("getTargetClassLoader");
                    bool ok = adoptLoader(cl, "FabricLauncherBase");
                    if (cl) g_env->DeleteLocalRef(cl);
                    g_env->DeleteLocalRef(lc);
                    g_env->DeleteLocalRef(launcher);
                    g_env->DeleteLocalRef(flb);
                    if (ok) return true;
                } else {
                    g_env->DeleteLocalRef(lc);
                    g_env->DeleteLocalRef(launcher);
                }
            }
        }
        g_env->DeleteLocalRef(flb);
    }
    logf("[jni] ERROR: could not find the Fabric game class loader (is this Fabric 1.21.11?)");
    return false;
}

std::unordered_map<std::string, int>& errorCounts() { static std::unordered_map<std::string, int> m; return m; }

}  // namespace

// ----- VM / env ----------------------------------------------------------------

bool init() {
    HMODULE jvm = GetModuleHandleW(L"jvm.dll");
    if (!jvm) { logf("[jni] ERROR: jvm.dll not loaded in this process"); return false; }
    using GetVMs = jint(JNICALL*)(JavaVM**, jsize, jsize*);
    auto getVMs = reinterpret_cast<GetVMs>(GetProcAddress(jvm, "JNI_GetCreatedJavaVMs"));
    if (!getVMs) { logf("[jni] ERROR: JNI_GetCreatedJavaVMs not exported"); return false; }

    jsize count = 0;
    if (getVMs(&g_vm, 1, &count) != JNI_OK || count == 0 || !g_vm) {
        logf("[jni] ERROR: no Java VM found");
        return false;
    }
    jint r = g_vm->GetEnv(reinterpret_cast<void**>(&g_env), JNI_VERSION_1_8);
    if (r == JNI_EDETACHED) {
        // The render thread is always a Java thread, so this should never happen.
        r = g_vm->AttachCurrentThread(reinterpret_cast<void**>(&g_env), nullptr);
    }
    if (r != JNI_OK || !g_env) { logf("[jni] ERROR: GetEnv failed (%d)", static_cast<int>(r)); return false; }

    jclass thr = g_env->FindClass("java/lang/Throwable");
    if (thr) {
        g_throwableToString = g_env->GetMethodID(thr, "toString", "()Ljava/lang/String;");
        g_env->DeleteLocalRef(thr);
    }
    g_env->ExceptionClear();

    if (!findGameLoader()) return false;

    g_integer = globalClass("java/lang/Integer");
    g_double = globalClass("java/lang/Double");
    if (!g_integer || !g_double) return false;
    g_intValueOf = g_env->GetStaticMethodID(g_integer, "valueOf", "(I)Ljava/lang/Integer;");
    g_intValue = g_env->GetMethodID(g_integer, "intValue", "()I");
    g_dblValueOf = g_env->GetStaticMethodID(g_double, "valueOf", "(D)Ljava/lang/Double;");
    g_dblValue = g_env->GetMethodID(g_double, "doubleValue", "()D");
    if (check("boxing ids")) return false;

    logf("[jni] attached to Java VM");
    return true;
}

void shutdown() {
    if (!g_env) return;
    for (ClassRef* c : classRegistry()) {
        if (c->cls) g_env->DeleteGlobalRef(c->cls);
        c->cls = nullptr;
    }
    for (FieldRef* f : fieldRegistry()) f->id = nullptr;
    for (MethodRef* m : methodRegistry()) m->id = nullptr;
    if (g_loader) g_env->DeleteGlobalRef(g_loader);
    if (g_integer) g_env->DeleteGlobalRef(g_integer);
    if (g_double) g_env->DeleteGlobalRef(g_double);
    g_loader = nullptr;
    g_integer = g_double = nullptr;
    g_resolved = false;
    g_env = nullptr;
    g_vm = nullptr;
}

JNIEnv* env() { return g_env; }

jclass loadClass(const char* name) {
    if (!g_env || !g_loader) return nullptr;
    std::string dotted = toDots(name);
    jstring s = g_env->NewStringUTF(dotted.c_str());
    auto c = static_cast<jclass>(g_env->CallObjectMethod(g_loader, g_loadClass, s));
    g_env->DeleteLocalRef(s);
    if (check(name)) return nullptr;
    return c;
}

bool check(const char* where) {
    if (!g_env || !g_env->ExceptionCheck()) return false;
    jthrowable ex = g_env->ExceptionOccurred();
    g_env->ExceptionClear();

    int& n = errorCounts()[where ? where : "?"];
    if (++n <= 3) {  // log each failing call site a few times, then stay quiet
        std::string text = "<unknown>";
        if (ex && g_throwableToString) {
            auto js = static_cast<jstring>(g_env->CallObjectMethod(ex, g_throwableToString));
            if (g_env->ExceptionCheck()) g_env->ExceptionClear();
            else if (js) {
                const char* c = g_env->GetStringUTFChars(js, nullptr);
                if (c) { text = c; g_env->ReleaseStringUTFChars(js, c); }
                g_env->DeleteLocalRef(js);
            }
        }
        logf("[jni] exception at %s: %s", where ? where : "?", text.c_str());
    }
    if (ex) g_env->DeleteLocalRef(ex);
    return true;
}

LocalFrame::LocalFrame(jint capacity) : ok_(g_env && g_env->PushLocalFrame(capacity) == 0) {
    if (!ok_ && g_env) check("PushLocalFrame");
}
LocalFrame::~LocalFrame() {
    if (ok_) g_env->PopLocalFrame(nullptr);
}

// ----- registry --------------------------------------------------------------

ClassRef::ClassRef(const char* n) : name(n) { classRegistry().push_back(this); }
FieldRef::FieldRef(ClassRef& o, const char* n, const char* s, bool st) : owner(o), name(n), sig(s), isStatic(st) {
    fieldRegistry().push_back(this);
}
MethodRef::MethodRef(ClassRef& o, const char* n, const char* s, bool st) : owner(o), name(n), sig(s), isStatic(st) {
    methodRegistry().push_back(this);
}

bool resolveAll(std::vector<std::string>& missing) {
    missing.clear();
    if (!g_env) { missing.emplace_back("JNI not initialised"); return false; }

    for (ClassRef* c : classRegistry()) {
        if (c->cls) continue;
        jclass local = loadClass(c->name);
        if (!local) { missing.emplace_back(std::string("class ") + c->name); continue; }
        c->cls = static_cast<jclass>(g_env->NewGlobalRef(local));
        g_env->DeleteLocalRef(local);
    }
    for (FieldRef* f : fieldRegistry()) {
        if (!f->owner.cls) continue;  // already reported with its class
        f->id = f->isStatic ? g_env->GetStaticFieldID(f->owner.cls, f->name, f->sig)
                            : g_env->GetFieldID(f->owner.cls, f->name, f->sig);
        if (g_env->ExceptionCheck()) g_env->ExceptionClear();
        if (!f->id) missing.push_back(std::string("field ") + f->owner.name + "." + f->name + " " + f->sig);
    }
    for (MethodRef* m : methodRegistry()) {
        if (!m->owner.cls) continue;
        m->id = m->isStatic ? g_env->GetStaticMethodID(m->owner.cls, m->name, m->sig)
                            : g_env->GetMethodID(m->owner.cls, m->name, m->sig);
        if (g_env->ExceptionCheck()) g_env->ExceptionClear();
        if (!m->id) missing.push_back(std::string("method ") + m->owner.name + "." + m->name + m->sig);
    }
    for (const auto& s : missing) logf("[jni] missing %s", s.c_str());
    logf("[jni] resolved %zu classes, %zu fields, %zu methods (%zu missing)",
         classRegistry().size(), fieldRegistry().size(), methodRegistry().size(), missing.size());
    g_resolved = missing.empty();
    return g_resolved;
}

bool resolved() { return g_resolved; }

// ----- typed helpers ---------------------------------------------------------

#define JNI_GUARD_FIELD(def) if (!g_env || !o || !f.id) return def
#define JNI_GUARD_METHOD(def) if (!g_env || !o || !m.id) return def

jobject getObject(jobject o, const FieldRef& f) {
    JNI_GUARD_FIELD(nullptr);
    jobject r = g_env->GetObjectField(o, f.id);
    return check(f.name) ? nullptr : r;
}
jint getInt(jobject o, const FieldRef& f) {
    JNI_GUARD_FIELD(0);
    jint r = g_env->GetIntField(o, f.id);
    return check(f.name) ? 0 : r;
}
jboolean getBool(jobject o, const FieldRef& f) {
    JNI_GUARD_FIELD(JNI_FALSE);
    jboolean r = g_env->GetBooleanField(o, f.id);
    return check(f.name) ? JNI_FALSE : r;
}
jfloat getFloat(jobject o, const FieldRef& f) {
    JNI_GUARD_FIELD(0.f);
    jfloat r = g_env->GetFloatField(o, f.id);
    return check(f.name) ? 0.f : r;
}
jdouble getDouble(jobject o, const FieldRef& f) {
    JNI_GUARD_FIELD(0.0);
    jdouble r = g_env->GetDoubleField(o, f.id);
    return check(f.name) ? 0.0 : r;
}
void setObject(jobject o, const FieldRef& f, jobject v) { JNI_GUARD_FIELD(); g_env->SetObjectField(o, f.id, v); check(f.name); }
void setInt(jobject o, const FieldRef& f, jint v)       { JNI_GUARD_FIELD(); g_env->SetIntField(o, f.id, v); check(f.name); }
void setBool(jobject o, const FieldRef& f, bool v)      { JNI_GUARD_FIELD(); g_env->SetBooleanField(o, f.id, v ? JNI_TRUE : JNI_FALSE); check(f.name); }
void setFloat(jobject o, const FieldRef& f, jfloat v)   { JNI_GUARD_FIELD(); g_env->SetFloatField(o, f.id, v); check(f.name); }
void setDouble(jobject o, const FieldRef& f, jdouble v) { JNI_GUARD_FIELD(); g_env->SetDoubleField(o, f.id, v); check(f.name); }

jobject getStaticObject(const FieldRef& f) {
    if (!g_env || !f.id || !f.owner.cls) return nullptr;
    jobject r = g_env->GetStaticObjectField(f.owner.cls, f.id);
    return check(f.name) ? nullptr : r;
}

jobject callObject(jobject o, const MethodRef& m, const jvalue* a) {
    JNI_GUARD_METHOD(nullptr);
    jobject r = g_env->CallObjectMethodA(o, m.id, a);
    return check(m.name) ? nullptr : r;
}
void callVoid(jobject o, const MethodRef& m, const jvalue* a) {
    JNI_GUARD_METHOD();
    g_env->CallVoidMethodA(o, m.id, a);
    check(m.name);
}
jboolean callBool(jobject o, const MethodRef& m, const jvalue* a) {
    JNI_GUARD_METHOD(JNI_FALSE);
    jboolean r = g_env->CallBooleanMethodA(o, m.id, a);
    return check(m.name) ? JNI_FALSE : r;
}
jdouble callDouble(jobject o, const MethodRef& m, const jvalue* a) {
    JNI_GUARD_METHOD(0.0);
    jdouble r = g_env->CallDoubleMethodA(o, m.id, a);
    return check(m.name) ? 0.0 : r;
}
jobject callStaticObject(const MethodRef& m, const jvalue* a) {
    if (!g_env || !m.id || !m.owner.cls) return nullptr;
    jobject r = g_env->CallStaticObjectMethodA(m.owner.cls, m.id, a);
    return check(m.name) ? nullptr : r;
}
void callStaticVoid(const MethodRef& m, const jvalue* a) {
    if (!g_env || !m.id || !m.owner.cls) return;
    g_env->CallStaticVoidMethodA(m.owner.cls, m.id, a);
    check(m.name);
}
jobject newObject(const MethodRef& ctor, const jvalue* a) {
    if (!g_env || !ctor.id || !ctor.owner.cls) return nullptr;
    jobject r = g_env->NewObjectA(ctor.owner.cls, ctor.id, a);
    return check(ctor.owner.name) ? nullptr : r;
}

jobject boxInt(jint v) {
    if (!g_env || !g_intValueOf) return nullptr;
    jvalue a[1] = { jv(v) };
    jobject r = g_env->CallStaticObjectMethodA(g_integer, g_intValueOf, a);
    return check("Integer.valueOf") ? nullptr : r;
}
jobject boxDouble(jdouble v) {
    if (!g_env || !g_dblValueOf) return nullptr;
    jvalue a[1] = { jv(v) };
    jobject r = g_env->CallStaticObjectMethodA(g_double, g_dblValueOf, a);
    return check("Double.valueOf") ? nullptr : r;
}
jint unboxInt(jobject o, jint fallback) {
    if (!g_env || !o || !g_env->IsInstanceOf(o, g_integer)) return fallback;
    jint r = g_env->CallIntMethod(o, g_intValue);
    return check("Integer.intValue") ? fallback : r;
}
jdouble unboxDouble(jobject o, jdouble fallback) {
    if (!g_env || !o || !g_env->IsInstanceOf(o, g_double)) return fallback;
    jdouble r = g_env->CallDoubleMethod(o, g_dblValue);
    return check("Double.doubleValue") ? fallback : r;
}

}  // namespace jni

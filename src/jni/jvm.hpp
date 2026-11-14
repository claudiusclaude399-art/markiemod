#pragma once
// JNI core: VM access, class loading through Fabric's Knot loader, a registry of
// cached class/field/method references, and exception-safe call helpers.
//
// Threading rule (the one rule everything else relies on):
//   ALL JNI work happens on Minecraft's render thread, inside the
//   wglSwapBuffers hook. That thread is also the client thread, so touching
//   game state there is exactly as safe as vanilla code doing it.

#include <jni.h>
#include <string>
#include <vector>

namespace jni {

// ----- VM / env ----------------------------------------------------------------

// Call once from the render thread. Finds the JavaVM, caches the env and the
// game class loader. Returns false (with a log message) on failure.
bool init();

// Release the global refs taken by init() and the registry. Render thread only.
void shutdown();

JNIEnv* env();  // env of the render thread (valid after init())

// Loads a class by JNI name ("a/b/C$D") through the game class loader.
// Returns a LOCAL ref, or nullptr (exception cleared, logged).
jclass loadClass(const char* name);

// If a Java exception is pending: log it (with `where`), clear it, return true.
bool check(const char* where);

// Every frame runs inside one of these so no local reference survives it.
class LocalFrame {
public:
    explicit LocalFrame(jint capacity = 256);
    ~LocalFrame();
    LocalFrame(const LocalFrame&) = delete;
    LocalFrame& operator=(const LocalFrame&) = delete;
private:
    bool ok_;
};

// ----- reference registry --------------------------------------------------
// Declare refs as globals; they register themselves and are all resolved by
// resolveAll() in one pass, so a wrong name is reported at startup instead of
// failing later in the middle of a module.

struct ClassRef {
    const char* name;
    jclass cls = nullptr;  // global ref
    explicit ClassRef(const char* n);
};

struct FieldRef {
    ClassRef& owner;
    const char* name;
    const char* sig;
    bool isStatic;
    jfieldID id = nullptr;
    FieldRef(ClassRef& o, const char* n, const char* s, bool st = false);
};

struct MethodRef {
    ClassRef& owner;
    const char* name;
    const char* sig;
    bool isStatic;
    jmethodID id = nullptr;
    MethodRef(ClassRef& o, const char* n, const char* s, bool st = false);
};

// Resolves every registered ref. Fills `missing` with what could not be found.
bool resolveAll(std::vector<std::string>& missing);
bool resolved();

// ----- typed helpers ---------------------------------------------------------
// All helpers: null object or unresolved id -> return default, never crash.
// Any Java exception is cleared and logged.

jobject getObject(jobject o, const FieldRef& f);
jint    getInt(jobject o, const FieldRef& f);
jboolean getBool(jobject o, const FieldRef& f);
jfloat  getFloat(jobject o, const FieldRef& f);
jdouble getDouble(jobject o, const FieldRef& f);
void setObject(jobject o, const FieldRef& f, jobject v);
void setInt(jobject o, const FieldRef& f, jint v);
void setBool(jobject o, const FieldRef& f, bool v);
void setFloat(jobject o, const FieldRef& f, jfloat v);
void setDouble(jobject o, const FieldRef& f, jdouble v);
jobject getStaticObject(const FieldRef& f);

jobject  callObject(jobject o, const MethodRef& m, const jvalue* args = nullptr);
void     callVoid(jobject o, const MethodRef& m, const jvalue* args = nullptr);
jboolean callBool(jobject o, const MethodRef& m, const jvalue* args = nullptr);
jdouble  callDouble(jobject o, const MethodRef& m, const jvalue* args = nullptr);
jobject  callStaticObject(const MethodRef& m, const jvalue* args = nullptr);
void     callStaticVoid(const MethodRef& m, const jvalue* args = nullptr);
jobject  newObject(const MethodRef& ctor, const jvalue* args = nullptr);

// Boxing (java.lang.Integer / Double), used for SimpleOption values.
jobject boxInt(jint v);
jobject boxDouble(jdouble v);
jint    unboxInt(jobject o, jint fallback);
jdouble unboxDouble(jobject o, jdouble fallback);

inline jvalue jv(jboolean v) { jvalue x; x.z = v; return x; }
inline jvalue jv(bool v)     { jvalue x; x.z = v ? JNI_TRUE : JNI_FALSE; return x; }
inline jvalue jv(jint v)     { jvalue x; x.i = v; return x; }
inline jvalue jv(jlong v)    { jvalue x; x.j = v; return x; }
inline jvalue jv(jfloat v)   { jvalue x; x.f = v; return x; }
inline jvalue jv(jdouble v)  { jvalue x; x.d = v; return x; }
inline jvalue jv(jobject v)  { jvalue x; x.l = v; return x; }

}  // namespace jni

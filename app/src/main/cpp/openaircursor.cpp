#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_system32crowdstrikewin11_openaircursor_NativeBridge_version(
    JNIEnv* env, jobject /* this */) {
    std::string info = "NDK r29 (";
#if defined(__aarch64__)
    info += "arm64-v8a";
#elif defined(__arm__)
    info += "armeabi-v7a";
#elif defined(__x86_64__)
    info += "x86_64";
#else
    info += "unknown-abi";
#endif
    info += ", API ";
    info += std::to_string(__ANDROID_API__);
    info += ")";
    return env->NewStringUTF(info.c_str());
}

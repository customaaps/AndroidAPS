// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include "vendor_library.hpp"
#include <stdexcept>
#include <limits>

namespace {
constexpr jint parser = 1095774808;
constexpr int64_t baseSeconds = 1262304000;
void fail(JNIEnv* env, const std::exception& error) {
    if (!env->ExceptionCheck()) {
        auto cls = env->FindClass("java/lang/IllegalStateException");
        if (cls) env->ThrowNew(cls, error.what());
    }
}
libre2::VendorLibrary& ready() {
    auto& lib = libre2::vendorLibrary();
    if (!lib.loaded()) throw std::runtime_error("Libre2 vendor is not initialized");
    return lib;
}
jbyteArray token(JNIEnv* env, libre2::VendorLibrary& lib) {
    static jbyteArray global = nullptr;
    if (!global) {
        auto* data = reinterpret_cast<data_t*>(lib.token());
        auto local = env->NewByteArray(data->length());
        if (!local) throw std::runtime_error("Unable to allocate Libre2 token");
        env->SetByteArrayRegion(local, 0, data->length(), data->data());
        global = static_cast<jbyteArray>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        if (!global) throw std::runtime_error("Unable to retain Libre2 token");
    }
    return global;
}
void checkArray(JNIEnv* env, jbyteArray value, int maxLength) {
    if (value && env->GetArrayLength(value) > maxLength) throw std::invalid_argument("Oversized Libre2 auth argument");
}
void identity(JNIEnv* env, jbyteArray uid, jbyteArray info) {
    if (!uid || !info || env->GetArrayLength(uid) != 8 || env->GetArrayLength(info) != 6)
        throw std::invalid_argument("Invalid Libre2 sensor identity");
}
jint secondsSinceBase(jlong seconds) {
    if (seconds < baseSeconds || seconds - baseSeconds > std::numeric_limits<jint>::max())
        throw std::invalid_argument("Invalid Libre2 authentication time");
    return static_cast<jint>(seconds - baseSeconds);
}
jbyteArray payload(JNIEnv* env, jbyteArray value) {
    if (!value || env->GetArrayLength(value) == 0 || env->GetArrayLength(value) > 4096)
        throw std::runtime_error("Libre2 vendor returned no usable authentication payload");
    return value;
}
}
extern "C" JNIEXPORT jint JNICALL
Java_app_aaps_plugins_source_libre2_Libre2NativeDecoder_v1(JNIEnv* env, jclass, jint code, jint handle, jbyteArray first, jbyteArray second) {
    try {
        std::lock_guard lock(libre2::vendorMutex);
        auto& lib = ready();
        checkArray(env, first, 4096); checkArray(env, second, 4096);
        if (!lib.p1) throw std::runtime_error("Libre2 Gen2 authentication is unavailable");
        return lib.p1(env, nullptr, code, handle, first, second, token(env, lib));
    } catch (const std::exception& error) { fail(env, error); return -1; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_aaps_plugins_source_libre2_Libre2NativeDecoder_v2(JNIEnv* env, jclass, jint code, jint handle, jbyteArray first, jbyteArray second) {
    try {
        std::lock_guard lock(libre2::vendorMutex);
        auto& lib = ready();
        checkArray(env, first, 4096); checkArray(env, second, 4096);
        if (!lib.p2) throw std::runtime_error("Libre2 Gen2 authentication is unavailable");
        auto result = lib.p2(env, nullptr, code, handle, first, second, token(env, lib));
        checkArray(env, result, 4096);
        return result;
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_aaps_plugins_source_libre2_Libre2NativeDecoder_activationPayload(JNIEnv* env, jclass, jbyteArray uid, jbyteArray info) {
    try {
        std::lock_guard lock(libre2::vendorMutex);
        auto& lib = ready(); identity(env, uid, info);
        auto authToken = token(env, lib);
        const auto command = lib.activationCommand(env, nullptr, parser, info, authToken);
        auto data = payload(env, lib.activationPayload(env, nullptr, parser, uid, info, 0, authToken));
        const auto length = env->GetArrayLength(data);
        auto output = env->NewByteArray(length + 3);
        if (!output) return nullptr;
        const jbyte header[]{2, command, 7};
        env->SetByteArrayRegion(output, 0, 3, header);
        std::vector<jbyte> bytes(length);
        env->GetByteArrayRegion(data, 0, length, bytes.data());
        env->SetByteArrayRegion(output, 3, length, bytes.data());
        return output;
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_aaps_plugins_source_libre2_Libre2NativeDecoder_streamingPayload(JNIEnv* env, jclass, jbyteArray uid, jbyteArray info, jlong seconds) {
    try {
        std::lock_guard lock(libre2::vendorMutex);
        auto& lib = ready(); identity(env, uid, info);
        return payload(env, lib.streamingPayload(env, nullptr, parser, uid, info, 0, secondsSinceBase(seconds), token(env, lib)));
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_aaps_plugins_source_libre2_Libre2NativeDecoder_unlockPayload(JNIEnv* env, jclass, jbyteArray uid, jbyteArray info, jbyte person, jlong enabledAt, jint counter) {
    try {
        std::lock_guard lock(libre2::vendorMutex);
        auto& lib = ready(); identity(env, uid, info);
        if (counter < 0) throw std::invalid_argument("Invalid Libre2 unlock counter");
        return payload(env, lib.unlockPayload(env, nullptr, parser, uid, info, person,
            secondsSinceBase(enabledAt), counter, token(env, lib)));
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}

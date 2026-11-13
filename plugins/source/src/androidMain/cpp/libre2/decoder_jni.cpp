// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include "decoder.hpp"
#include "vendor_library.hpp"
#include <limits>
#include <stdexcept>

namespace {
void fail(JNIEnv* env, const std::exception& error) {
    if (!env->ExceptionCheck()) {
        auto cls = env->FindClass("java/lang/IllegalStateException");
        if (cls) env->ThrowNew(cls, error.what());
    }
}
libre2::Bytes bytes(JNIEnv* env, jbyteArray array, size_t maxLength) {
    if (!array) throw std::invalid_argument("Missing decoder array");
    auto size = env->GetArrayLength(array);
    if (size < 0 || static_cast<size_t>(size) > maxLength) throw std::invalid_argument("Decoder array exceeds its limit");
    libre2::Bytes output(size);
    env->GetByteArrayRegion(array, 0, size, output.data());
    return output;
}
libre2::DecoderState readState(JNIEnv* env, jlongArray metadata, jobjectArray arrays) {
    if (!metadata || !arrays || env->GetArrayLength(metadata) != 5 || env->GetArrayLength(arrays) != 3)
        throw std::invalid_argument("Invalid decoder state shape");
    jlong values[5]{};
    env->GetLongArrayRegion(metadata, 0, 5, values);
    if (values[0] < 0 || values[1] < 0 || values[2] < 0 || values[2] > 31*24*60 ||
        values[3] < 0 || values[3] > 31*24*60 || values[4] < -128 || values[4] > 127)
        throw std::invalid_argument("Invalid decoder state metadata");
    libre2::DecoderState result;
    result.sensorStartSeconds = values[0];
    result.algorithmEndSeconds = values[1];
    result.warmupMinutes = static_cast<int>(values[2]);
    result.wearMinutes = static_cast<int>(values[3]);
    result.person = static_cast<jbyte>(values[4]);
    for (int i = 0; i < 3; ++i) {
        auto array = static_cast<jbyteArray>(env->GetObjectArrayElement(arrays, i));
        result.arrays[i] = bytes(env, array, 1024*1024);
        env->DeleteLocalRef(array);
    }
    return result;
}
jobjectArray reply(JNIEnv* env, const libre2::Decoded& decoded) {
    jlong values[]{decoded.status, decoded.state.sensorStartSeconds, decoded.state.algorithmEndSeconds,
        decoded.state.warmupMinutes, decoded.state.wearMinutes, decoded.state.person,
        decoded.glucoseMgdl, decoded.quality, decoded.sampleId, decoded.trend, decoded.removed ? 1 : 0};
    auto meta = env->NewLongArray(11);
    if (!meta) return nullptr;
    env->SetLongArrayRegion(meta, 0, 11, values);
    auto rate = env->NewFloatArray(1);
    if (!rate) return nullptr;
    env->SetFloatArrayRegion(rate, 0, 1, &decoded.rateMgdlPerMinute);
    auto byteArrayClass = env->FindClass("[B");
    if (!byteArrayClass) return nullptr;
    auto arrays = env->NewObjectArray(3, byteArrayClass, nullptr);
    if (!arrays) return nullptr;
    for (int i = 0; i < 3; ++i) {
        const auto& data = decoded.state.arrays[i];
        auto array = env->NewByteArray(static_cast<jsize>(data.size()));
        if (!array) return nullptr;
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(data.size()), data.data());
        env->SetObjectArrayElement(arrays, i, array);
        env->DeleteLocalRef(array);
    }
    auto objectClass = env->FindClass("java/lang/Object");
    if (!objectClass) return nullptr;
    auto output = env->NewObjectArray(3, objectClass, nullptr);
    if (!output) return nullptr;
    env->SetObjectArrayElement(output, 0, meta);
    env->SetObjectArrayElement(output, 1, rate);
    env->SetObjectArrayElement(output, 2, arrays);
    return output;
}
}

extern "C" JNIEXPORT void JNICALL
Java_app_aaps_plugins_source_libre2_Libre2NativeDecoder_load(JNIEnv* env, jclass, jstring path) {
    try {
        if (!path) throw std::invalid_argument("Missing vendor library path");
        const char* value = env->GetStringUTFChars(path, nullptr);
        if (!value) return;
        std::string owned(value);
        env->ReleaseStringUTFChars(path, value);
        libre2::vendorLibrary().load(owned);
    } catch (const std::exception& error) { fail(env, error); }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_aaps_plugins_source_libre2_Libre2NativeDecoder_decodeNfc(
    JNIEnv* env, jclass, jbyteArray uid, jbyteArray info, jbyteArray fram,
    jlong seconds, jlongArray metadata, jobjectArray arrays) {
    try {
        return reply(env, libre2::decodeNfc(bytes(env, uid, 8), bytes(env, info, 6),
            bytes(env, fram, 344), seconds, readState(env, metadata, arrays)));
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_aaps_plugins_source_libre2_Libre2NativeDecoder_decodeBluetooth(
    JNIEnv* env, jclass, jbyteArray uid, jbyteArray info, jbyteArray packet,
    jlong seconds, jlongArray metadata, jobjectArray arrays) {
    try {
        return reply(env, libre2::decodeBluetooth(bytes(env, uid, 8), bytes(env, info, 6),
            bytes(env, packet, 512), seconds, readState(env, metadata, arrays)));
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}

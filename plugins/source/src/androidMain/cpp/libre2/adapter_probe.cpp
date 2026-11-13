// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <algorithm>
#include <string>
#include "inout.hpp"
#include "jnisub.hpp"
#include "jnisubin.hpp"

// Vendor RegisterNatives is collected in the isolated class registry.
// No dependency on Juggluco Application, activities or sensor database.
extern "C" jint subRegisterNatives(JNIEnv*, jclass cls, const JNINativeMethod* methods, jint count) {
    if (!cls || !methods || count < 0) return JNI_ERR;
    auto* target = reinterpret_cast<jclasser*>(cls);
    for (int i = 0; i < count; ++i) {
        if (!methods[i].name || !methods[i].signature || !methods[i].fnPtr) return JNI_ERR;
    }
    for (int i = 0; i < count; ++i) {
        const auto& method = methods[i];
        auto found = std::find_if(target->funcs.begin(), target->funcs.end(), [&](const Method& m) {
            return m.name == method.name && m.pat == method.signature;
        });
        if (found == target->funcs.end())
            target->funcs.push_back({method.name, method.signature, method.fnPtr});
        else found->func = method.fnPtr;
    }
    return JNI_OK;
}

// Probe tests JNI argument/array mapping only. It does not assert glucose decoding works.
extern "C" JNIEXPORT jboolean JNICALL
Java_app_aaps_plugins_source_libre2_Libre2NativeDecoder_probe(JNIEnv*, jclass) {
    try {
        scanstate memory(4);
        jnidata_t context{ subenv->functions, &memory };
        auto* env = reinterpret_cast<JNIEnv*>(&context);
        auto bytes = env->NewByteArray(6);
        const jbyte input[]{1, 2, 3, 4, 5, 6};
        env->SetByteArrayRegion(bytes, 0, 6, input);
        jbyte output[6]{};
        env->GetByteArrayRegion(bytes, 0, 6, output);
        if (env->GetArrayLength(bytes) != 6 || !std::equal(input, input + 6, output)) return JNI_FALSE;
        auto cls = env->FindClass("com/abbottdiabetescare/flashglucose/sensorabstractionservice/dataprocessing/GlucoseValue");
        auto ctor = env->GetMethodID(cls, "<init>", "(III)V");
        auto* glucose = reinterpret_cast<GlucoseValue*>(env->NewObject(cls, ctor, 15, 0, 110));
        const bool valid = glucose && glucose->getId() == 15 && glucose->getQuality() == 0 && glucose->getValue() == 110;
        delete glucose;
        return valid ? JNI_TRUE : JNI_FALSE;
    } catch (...) { return JNI_FALSE; }
}

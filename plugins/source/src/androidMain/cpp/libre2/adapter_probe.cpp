// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <algorithm>
#include <string>
#include "inout.hpp"
#include "jnisub.hpp"
#include "jnisubin.hpp"
#include "decoder.hpp"

namespace {
// Compile-time regressions run for both supported ABIs, without a sensor or vendor library.
constexpr bool decoderStateRules() {
    libre2::Decoded decoded;
    decoded.status = 0;
    decoded.glucoseMgdl = 110;
    decoded.state.arrays = {libre2::Bytes{1}, libre2::Bytes{2}, libre2::Bytes{3}};
    libre2::finishSensorInitialization(decoded, {});
    if (!decoded.state.arrays[0].empty() || !decoded.state.arrays[1].empty() ||
        decoded.state.arrays[2] != libre2::Bytes{3} || decoded.glucoseMgdl != 0) return false;
    libre2::finishSensorInitialization(decoded, libre2::Bytes{4, 5});
    if (decoded.state.arrays[2] != libre2::Bytes{4, 5}) return false;

    libre2::DecoderState previous;
    previous.sensorStartSeconds = 1'700'000'000;
    decoded.state.sensorStartSeconds = 1'900'000'000; // Inconsistent vendor stream output.
    decoded.state.algorithmEndSeconds = 0;
    decoded.glucoseMgdl = 35;
    libre2::finishBluetoothDecode(decoded, previous, 1'700'000'060);
    if (decoded.state.sensorStartSeconds != previous.sensorStartSeconds ||
        decoded.state.algorithmEndSeconds != 1'700'000'060 || decoded.glucoseMgdl != 35) return false;
    decoded.status = 1;
    libre2::finishBluetoothDecode(decoded, previous, 1'800'000'000);
    return decoded.state.algorithmEndSeconds == 1'700'000'060;
}
static_assert(decoderStateRules());

constexpr bool nfcTimeRecoveryRules() {
    // Exact future start seen in AndroidAPS_LOG_1794934323318.
    constexpr int64_t now = 1794934249;
    constexpr int age = 3357;
    constexpr int64_t corrupt = 2224976429;
    constexpr int64_t recovered = (now / 60 - age) * 60;
    if (libre2::sensorStartMatchesNfcAge(corrupt, now, age)) return false;
    if (libre2::nfcSensorStartSeconds(now, age, corrupt, corrupt) != recovered) return false;
    if (!libre2::sensorStartMatchesNfcAge(recovered, now, age)) return false;
    // Preserve a valid existing start across subsequent scans, rather than rounding it again.
    if (libre2::nfcSensorStartSeconds(now + 61, age + 1, recovered + 17, corrupt) != recovered + 17) return false;
    if (libre2::nfcSensorStartSeconds(now, age, 0, recovered + 21) != recovered + 21) return false;
    if (libre2::nfcSensorStartSeconds(now, -1, corrupt, corrupt) != 0) return false;
    if (libre2::nfcSensorStartSeconds(now, 65535, corrupt, corrupt) != 0) return false;
    if (libre2::nfcSensorStartSeconds(now, 0, corrupt, corrupt) != now / 60 * 60) return false;
    libre2::Bytes fram(344);
    fram[316] = static_cast<jbyte>(age & 255);
    fram[317] = static_cast<jbyte>(age >> 8);
    return libre2::nfcSensorAgeMinutes(fram) == age && libre2::nfcSensorAgeMinutes({}) == -1;
}
static_assert(nfcTimeRecoveryRules());
}

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

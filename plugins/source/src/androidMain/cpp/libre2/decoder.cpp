/*
 * Libre 2 algorithm calls adapted from JugglucoNG bb5757761a, libre2.cpp.
 * Original copyright (C) 2021 Jaap Korthals Altes.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <jni.h>
#include "decoder.hpp"
#include "vendor_library.hpp"
#include "jnisub.hpp"
#include <memory>
#include <limits>
#include <ctime>
#include <cmath>

extern jclasser AlarmC, NonActiona, GluRange, attconf;
namespace libre2 {
namespace {
constexpr int parser = 1095774808;
constexpr int64_t baseSeconds = 1262304000;
struct OwnedArray {
    data_t* value;
    explicit OwnedArray(const Bytes& bytes) : value(data_t::newex(bytes)) {}
    ~OwnedArray() { data_t::deleteex(value); }
    operator jbyteArray() const { return value->length() ? reinterpret_cast<jbyteArray>(value) : nullptr; }
};
struct Call {
    scanstate memory{256};
    jnidata_t context{subenv->functions, &memory};
    multimmap* previous = reinterpret_cast<jnidata_t*>(subenv)->map;
    Call() { reinterpret_cast<jnidata_t*>(subenv)->map = &memory; }
    ~Call() { reinterpret_cast<jnidata_t*>(subenv)->map = previous; }
    JNIEnv* env() { return reinterpret_cast<JNIEnv*>(&context); }
    Bytes bytes(void* ptr) {
        if (!ptr) return {};
        const auto begin = reinterpret_cast<uintptr_t>(memory.map.data());
        const auto end = begin + memory.map.size();
        const auto address = reinterpret_cast<uintptr_t>(ptr);
        if (address < begin || address > end - sizeof(data_t)) throw std::runtime_error("Vendor returned an invalid state address");
        auto* value = static_cast<data_t*>(ptr);
        if (value->length() < 0 || static_cast<size_t>(value->length()) > end - address - sizeof(data_t))
            throw std::runtime_error("Vendor returned an invalid state length");
        return {value->data(), value->data() + value->length()};
    }
};
jint timeSinceBase(int64_t seconds, bool allowZero = false) {
    if (allowZero && seconds == 0) return 0;
    if (seconds < baseSeconds || seconds - baseSeconds > std::numeric_limits<jint>::max())
        throw std::invalid_argument("Unsupported sensor timestamp");
    return static_cast<jint>(seconds - baseSeconds);
}
void validate(const Bytes& uid, const Bytes& info, int64_t now) {
    if (uid.size() != 8 || info.size() != 6) throw std::invalid_argument("Invalid Libre2 identity");
    timeSinceBase(now);
    if (!vendorLibrary().loaded()) throw std::runtime_error("Libre2 decoder is not initialized");
}
Decoded collect(Call& call, intptr_t status, const DecoderState& previous,
                outobj& start, outobj& end, outobj& removed,
                outobj& comp, outobj& atten, outobj& measurement, outobj& result) {
    std::unique_ptr<AlgorithmResults> algorithm(static_cast<AlgorithmResults*>(result.ptr));
    Decoded decoded;
    decoded.status = static_cast<int>(status);
    decoded.state = previous;
    if (status != 0) return decoded;
    decoded.state.arrays = {call.bytes(comp.ptr), call.bytes(atten.ptr), call.bytes(measurement.ptr)};
    if (decoded.state.arrays[2].empty()) throw std::runtime_error("Vendor returned no measurement state");
    const auto newStart = start.toint(), newEnd = end.toint();
    if (newStart > 0) decoded.state.sensorStartSeconds = baseSeconds + newStart;
    if (newEnd > 0) decoded.state.algorithmEndSeconds = baseSeconds + newEnd;
    decoded.removed = removed.toint() != 0;
    if (algorithm && algorithm->glu) {
        const auto& glucose = algorithm->currentglucose();
        decoded.quality = glucose.getQuality();
        decoded.sampleId = glucose.getId();
        decoded.trend = glucose.trend();
        decoded.rateMgdlPerMinute = glucose.rate();
        // Keep genuine low values. Invalid quality and removed sensors never publish glucose.
        if (!decoded.removed && decoded.quality == 0 && glucose.getValue() > 0 && glucose.getValue() <= 1000)
            decoded.glucoseMgdl = glucose.getValue();
    }
    return decoded;
}
}
Decoded decodeNfc(const Bytes& uid, const Bytes& info, const Bytes& fram,
                  int64_t now, const DecoderState& previous) {
    std::lock_guard lock(vendorMutex);
    validate(uid, info, now);
    if (fram.size() != 344) throw std::invalid_argument("Libre2 FRAM must contain 344 bytes");
    auto& lib = vendorLibrary();
    Call call;
    OwnedArray id(uid), patch(info), input(fram);
    OwnedArray oldComp(previous.arrays[0]), oldAtten(previous.arrays[1]), oldMess(previous.arrays[2]);
    algobj alarm(&AlarmC), nonAction(&NonActiona), range(&GluRange), attenuation(&attconf);
    outobj start, end, inserted, removed, comp, atten, measurement, result, events, person;
    DecoderState metadata = previous;
    if (metadata.wearMinutes <= 0) {
        outobj warmup, wear;
        if (!lib.patchTimeValues(call.env(), nullptr, parser, patch, warmup, wear, lib.token()))
            throw std::runtime_error("Unable to read sensor lifetime");
        metadata.warmupMinutes = static_cast<int>(warmup.toint());
        metadata.wearMinutes = static_cast<int>(wear.toint());
        if (metadata.warmupMinutes < 0 || metadata.wearMinutes <= metadata.warmupMinutes || metadata.wearMinutes > 31 * 24 * 60)
            throw std::runtime_error("Invalid sensor lifetime");
    }
    time_t unixTime = now;
    tm local{};
    localtime_r(&unixTime, &local);
    const int offsetMillis = static_cast<int>((timegm(&local) - unixTime) * 1000);
    jobject response = nullptr;
    if (lib.scan210)
        response = lib.scan210(call.env(), nullptr, parser, alarm, nonAction, range, attenuation, JNI_FALSE,
            id, patch, input, timeSinceBase(previous.sensorStartSeconds, true), timeSinceBase(now),
            metadata.warmupMinutes, metadata.wearMinutes, JNI_TRUE, offsetMillis,
            oldComp, oldAtten, oldMess, start, end, inserted, removed, comp, atten, measurement, result, events, person, lib.token());
    else if (lib.scan27)
        response = lib.scan27(call.env(), nullptr, parser, alarm, nonAction, range, attenuation,
            id, patch, input, timeSinceBase(previous.sensorStartSeconds, true), timeSinceBase(now),
            metadata.warmupMinutes, metadata.wearMinutes, offsetMillis,
            oldComp, oldAtten, oldMess, start, end, inserted, removed, comp, atten, measurement, result, events, person, lib.token());
    else throw std::runtime_error("NFC decoder ABI is unavailable");
    // PatchEvent lists use the same vendor list representation as history.
    auto* patchEvents = static_cast<std::vector<PatchEvent*>*>(events.ptr);
    if (patchEvents) { for (auto* event : *patchEvents) delete event; delete patchEvents; }
    auto decoded = collect(call, reinterpret_cast<intptr_t>(response), metadata,
                           start, end, removed, comp, atten, measurement, result);
    if (person.ptr) decoded.state.person = static_cast<jbyte>(person.toint());
    if (decoded.status == 0 && previous.sensorStartSeconds == 0) {
        const int sensorStart = timeSinceBase(decoded.state.sensorStartSeconds);
        OwnedArray mess(decoded.state.arrays[2]);
        auto* activated = lib.finishActivation(call.env(), nullptr, id,
            sensorStart > 94694400 ? (sensorStart - 94694400) / 86400 : 0,
            sensorStart, mess, lib.token());
        auto activatedBytes = call.bytes(activated);
        if (!activatedBytes.empty()) {
            decoded.state.arrays[2] = std::move(activatedBytes);
            decoded.state.arrays[0].clear();
            decoded.state.arrays[1].clear();
        }
        // Initializing algorithm state does not constitute a fresh glucose sample.
        decoded.glucoseMgdl = 0;
    }
    return decoded;
}
Decoded decodeBluetooth(const Bytes& uid, const Bytes& info, const Bytes& packet,
                        int64_t now, const DecoderState& previous) {
    std::lock_guard lock(vendorMutex);
    validate(uid, info, now);
    if (packet.empty() || packet.size() > 512 || previous.sensorStartSeconds == 0 ||
        previous.arrays[2].empty() || previous.wearMinutes <= 0)
        throw std::invalid_argument("Bluetooth decoding requires initialized sensor state");
    auto& lib = vendorLibrary();
    Call call;
    OwnedArray id(uid), patch(info), input(packet);
    OwnedArray oldComp(previous.arrays[0]), oldAtten(previous.arrays[1]), oldMess(previous.arrays[2]);
    algobj alarm(&AlarmC), nonAction(&NonActiona), range(&GluRange), attenuation(&attconf);
    outobj start, end, inserted, removed, comp, atten, measurement, result;
    jobject response = nullptr;
    if (lib.stream210)
        response = lib.stream210(call.env(), nullptr, parser, alarm, nonAction, range, attenuation,
            id, patch, previous.person, input, timeSinceBase(previous.sensorStartSeconds), timeSinceBase(now),
            previous.warmupMinutes, previous.wearMinutes, JNI_TRUE, oldComp, oldAtten, oldMess,
            start, end, inserted, removed, comp, atten, measurement, result, lib.token());
    else if (lib.stream27)
        response = lib.stream27(call.env(), nullptr, parser, alarm, nonAction, range, attenuation,
            id, patch, previous.person, input, timeSinceBase(previous.sensorStartSeconds), timeSinceBase(now),
            previous.warmupMinutes, previous.wearMinutes, oldComp, oldAtten, oldMess,
            start, end, inserted, removed, comp, atten, measurement, result, lib.token());
    else throw std::runtime_error("Bluetooth decoder ABI is unavailable");
    auto decoded = collect(call, reinterpret_cast<intptr_t>(response), previous,
                           start, end, removed, comp, atten, measurement, result);
    // As in Juggluco, live BLE readings use packet reception time. Sample IDs are deduplicated
    // separately; the vendor end-time output is not a live glucose timestamp.
    if (decoded.status == 0) decoded.state.algorithmEndSeconds = now;
    return decoded;
}
}

// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <jni.h>
#include <array>
#include <cstdint>
#include <vector>

namespace libre2 {
using Bytes = std::vector<jbyte>;
struct DecoderState {
    // Opaque vendor arrays: composite, attenuation, measurement.
    std::array<Bytes, 3> arrays;
    int64_t sensorStartSeconds = 0;
    int64_t algorithmEndSeconds = 0;
    int warmupMinutes = 0;
    int wearMinutes = 0;
    jbyte person = 0;
};
struct Decoded {
    DecoderState state;
    int status = -1;
    int glucoseMgdl = 0;
    int quality = -1;
    int sampleId = 0;
    int trend = 0;
    float rateMgdlPerMinute = 0;
    bool removed = false;
};
// Stream outputs must not replace the NFC-established start of an existing sensor.
constexpr void finishBluetoothDecode(Decoded& decoded, const DecoderState& previous, int64_t now) {
    if (decoded.status == 0) {
        decoded.state.sensorStartSeconds = previous.sensorStartSeconds;
        decoded.state.algorithmEndSeconds = now;
    }
}

// A newly initialized sensor retains MESS only, even without an activation replacement.
constexpr void finishSensorInitialization(Decoded& decoded, const Bytes& activated) {
    if (!activated.empty()) decoded.state.arrays[2] = activated;
    decoded.state.arrays[0].clear();
    decoded.state.arrays[1].clear();
    decoded.glucoseMgdl = 0; // Initialization is not a fresh reading.
}

// Input must already have passed the generation-specific NFC authentication/decryption.
Decoded decodeNfc(const Bytes& uid, const Bytes& patchInfo, const Bytes& fram,
                  int64_t unixSeconds, const DecoderState& previous);
Decoded decodeBluetooth(const Bytes& uid, const Bytes& patchInfo, const Bytes& decryptedPacket,
                        int64_t unixSeconds, const DecoderState& previous);
}

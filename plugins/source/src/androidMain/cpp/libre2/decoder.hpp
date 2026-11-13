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
// Input must already have passed the generation-specific NFC authentication/decryption.
Decoded decodeNfc(const Bytes& uid, const Bytes& patchInfo, const Bytes& fram,
                  int64_t unixSeconds, const DecoderState& previous);
Decoded decodeBluetooth(const Bytes& uid, const Bytes& patchInfo, const Bytes& decryptedPacket,
                        int64_t unixSeconds, const DecoderState& previous);
}

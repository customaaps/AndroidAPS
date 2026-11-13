// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <mutex>
#include <string>
#include "inout.hpp"
#include "jnisubin.hpp"
#include "vendor_abi.hpp"

namespace libre2 {
/** All calls into the vendor and its JNI registry must hold this lock. */
extern std::recursive_mutex vendorMutex;

/** One vendor image per process. Vendor background threads prevent safe dlclose/reload. */
class VendorLibrary {
    void* handle = nullptr;
    std::string loadedPath;
    scanstate initMemory{64};
    void* resolve(const char* name);
    void* require(const char* name);
public:
    void load(const std::string& absolutePath);
    bool loaded() const { return !loadedPath.empty(); }
    std::string scanSignature, streamSignature;
    initialize_fn initialize = nullptr;
    initializeNative_fn initializeNative = nullptr;
    dpSetMaxAlgorithmVersion_fn setMaxVersion = nullptr;
    getPatchTimeValues_fn patchTimeValues = nullptr;
    getProductFamily_fn productFamily = nullptr;
    getActivationCommand_fn activationCommand = nullptr;
    getActivationPayload_fn activationPayload = nullptr;
    getEnableStreamingPayload_fn streamingPayload = nullptr;
    getStreamingUnlockPayload_fn unlockPayload = nullptr;
    activationComplete_fn finishActivation = nullptr;
    getTotalMemorySize_fn totalMemory = nullptr;
    P1_fn p1 = nullptr;
    P2_fn p2 = nullptr;
    processScan2_7_fn scan27 = nullptr;
    processScan2_10_fn scan210 = nullptr;
    processStream2_7_fn stream27 = nullptr;
    processStream2_10_fn stream210 = nullptr;
    jbyteArray token() const;
};
VendorLibrary& vendorLibrary();
}

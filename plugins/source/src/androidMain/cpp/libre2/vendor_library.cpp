/*
 * Vendor initialization adapted from JugglucoNG bb5757761a, libre2.cpp.
 * Original copyright (C) 2021 Jaap Korthals Altes.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "vendor_library.hpp"
#include <dlfcn.h>
#include <stdexcept>
#include <algorithm>

extern JavaVM* getnewvm();
extern "C" void libre2_set_initializing(bool);
namespace libre2 {
std::recursive_mutex vendorMutex;
static constexpr const unsigned char vendorToken[]={0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x27,0x2B,0xCD,0x3B,0xF3,0xCC,0xA0,0xB8,0x56,0xCE,0x96,0x1F,0xDB,0xA5,0x6B,0xB6,0x17,0xB2,0x1B,0xD6,0x57,0x0D,0xF3,0x5F,0x00,0x6D,0x4C,0xD4,0x62,0x66,0x26,0x54,0x1F,0xF9,0x5C,0x87,0xAE,0x63,0xD9,0x7F,0x53,0x6E,0xB6,0x80,0xE7,0x71,0x2E,0x59,0xCF,0xE4,0x50,0xC0,0xC8,0x53,0x1A,0x33,0x61,0xE2,0xDB,0x32,0xF2,0x2A,0xC1,0xB6};

jbyteArray VendorLibrary::token() const {
    static auto* value = data_t::newex(vendorToken);
    return reinterpret_cast<jbyteArray>(value);
}

VendorLibrary& vendorLibrary() {
    // Intentionally process-lifetime: the vendor may retain native callbacks and start threads.
    static auto* library = new VendorLibrary();
    return *library;
}

void* VendorLibrary::resolve(const char* name) {
    auto found = jclasser::classid.find("DataProcessingNative");
    if (found != jclasser::classid.end() && found->second) {
        for (const auto& method : found->second->funcs)
            if (method.name == name) return method.func;
    }
    return dlsym(handle, (std::string("Java_com_abbottdiabetescare_flashglucose_sensorabstractionservice_dataprocessing_DataProcessingNative_") + name).c_str());
}

void* VendorLibrary::require(const char* name) {
    void* result = resolve(name);
    if (!result) throw std::runtime_error(std::string("Missing Libre2 vendor method: ") + name);
    return result;
}

void VendorLibrary::load(const std::string& path) {
    std::lock_guard lock(vendorMutex);
    if (loaded()) {
        if (path != loadedPath) throw std::runtime_error("Libre2 vendor image cannot be replaced in a running process");
        return;
    }
    if (handle) throw std::runtime_error("Libre2 vendor initialization previously failed; restart the sensor process");
    if (path.empty() || path.front() != '/') throw std::invalid_argument("Vendor library requires an absolute path");
    handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!handle) throw std::runtime_error("Unable to load Libre2 vendor library");
    auto* context = reinterpret_cast<jnidata_t*>(subenv);
    auto* previousMemory = context->map;
    context->map = &initMemory;
    try {
        if (!resolve("processScan")) {
            auto onLoad = reinterpret_cast<jint (*)(JavaVM*, void*)>(dlsym(handle, "JNI_OnLoad"));
            if (!onLoad || onLoad(getnewvm(), nullptr) != JNI_VERSION_1_6)
                throw std::runtime_error("Libre2 vendor JNI registration failed");
        }
        initializeNative = reinterpret_cast<initializeNative_fn>(resolve("initializeNative"));
        initialize = reinterpret_cast<initialize_fn>(resolve("initialize"));
        setMaxVersion = reinterpret_cast<dpSetMaxAlgorithmVersion_fn>(require("dpSetMaxAlgorithmVersion"));
        patchTimeValues = reinterpret_cast<getPatchTimeValues_fn>(require("getPatchTimeValues"));
        productFamily = reinterpret_cast<getProductFamily_fn>(require("getProductFamily"));
        activationCommand = reinterpret_cast<getActivationCommand_fn>(require("getActivationCommand"));
        activationPayload = reinterpret_cast<getActivationPayload_fn>(require("getActivationPayload"));
        streamingPayload = reinterpret_cast<getEnableStreamingPayload_fn>(require("getEnableStreamingPayload"));
        unlockPayload = reinterpret_cast<getStreamingUnlockPayload_fn>(require("getStreamingUnlockPayload"));
        finishActivation = reinterpret_cast<activationComplete_fn>(require("activationComplete"));
        totalMemory = reinterpret_cast<getTotalMemorySize_fn>(require("getTotalMemorySize"));
        p1 = reinterpret_cast<P1_fn>(resolve("P1"));
        p2 = reinterpret_cast<P2_fn>(resolve("P2"));

        auto cls = jclasser::classid.find("DataProcessingNative");
        if (cls != jclasser::classid.end() && cls->second) {
            for (const auto& method : cls->second->funcs) {
                if (method.name == "processScan") scanSignature = method.pat;
                if (method.name == "processStream") streamSignature = method.pat;
            }
        }
        // Same ABI discriminators as JugglucoNG. Validate lengths before indexing.
        if (scanSignature.size() > 339 && streamSignature.size() > 350) {
            if (scanSignature[339] == 'Z') scan210 = reinterpret_cast<processScan2_10_fn>(require("processScan"));
            else scan27 = reinterpret_cast<processScan2_7_fn>(require("processScan"));
            if (streamSignature[350] == 'Z') stream210 = reinterpret_cast<processStream2_10_fn>(require("processStream"));
            else stream27 = reinterpret_cast<processStream2_7_fn>(require("processStream"));
        } else {
            // An exported function alone does not establish which parameter ABI it expects.
            throw std::runtime_error("Unrecognized Libre2 decoder ABI");
        }
        libre2_set_initializing(true);
        if (initializeNative) initializeNative(subenv, nullptr, nullptr, nullptr);
        else if (initialize) initialize(subenv, nullptr, nullptr);
        else throw std::runtime_error("Libre2 vendor has no initializer");
        libre2_set_initializing(false);
        setMaxVersion(subenv, nullptr, 3, token());
        loadedPath = path;
    } catch (...) {
        libre2_set_initializing(false);
        context->map = previousMemory;
        throw;
    }
    context->map = previousMemory;
}
}

cmake_minimum_required(VERSION 3.22)
project(aaps_libre2_adapter LANGUAGES CXX)
add_library(aaps_libre2_compat SHARED vendor_compat.cpp)
# The pinned vendor ELF has DT_NEEDED=libg.so. This is a small compatibility shim.
set_target_properties(aaps_libre2_compat PROPERTIES OUTPUT_NAME g)
target_compile_features(aaps_libre2_compat PRIVATE cxx_std_20)
target_link_options(aaps_libre2_compat PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/vendor_compat.map" "-Wl,-z,max-page-size=16384")
target_link_libraries(aaps_libre2_compat PRIVATE "-Wl,--no-as-needed" log "-Wl,--as-needed")
add_library(aaps_libre2_adapter SHARED jnisub.cpp adapter_probe.cpp vendor_library.cpp decoder.cpp decoder_jni.cpp auth_jni.cpp)
target_compile_features(aaps_libre2_adapter PRIVATE cxx_std_20)
target_link_libraries(aaps_libre2_adapter PRIVATE dl aaps_libre2_compat)
target_compile_options(aaps_libre2_adapter PRIVATE -fvisibility=hidden -fexceptions)
target_include_directories(aaps_libre2_adapter PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_options(aaps_libre2_adapter PRIVATE "-Wl,-z,max-page-size=16384")

# Standalone Android test executable; no APK or physical sensor required.
# Execution still requires a device/emulator of the matching ABI.
add_executable(libre2_adapter_test adapter_test.cpp)
target_link_libraries(libre2_adapter_test PRIVATE aaps_libre2_adapter)

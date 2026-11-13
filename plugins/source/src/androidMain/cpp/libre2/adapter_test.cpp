// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
extern "C" jboolean Java_app_aaps_plugins_source_libre2_Libre2NativeDecoder_probe(JNIEnv*, jclass);
int main() {
    return Java_app_aaps_plugins_source_libre2_Libre2NativeDecoder_probe(nullptr, nullptr) == JNI_TRUE ? 0 : 1;
}

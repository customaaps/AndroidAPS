/*
 * Libre 2 ABI declarations from JugglucoNG bb5757761a, libre2.cpp.
 * Copyright (C) 2021 Jaap Korthals Altes.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#pragma once
#include <jni.h>
namespace libre2 {
using getTotalMemorySize_fn = jint (JNICALL*) (JNIEnv *env, jobject thiz, jint get,  jbyteArray);

using initialize_fn = void (JNICALL*) (JNIEnv *, jobject, jobject);

using dpSetMaxAlgorithmVersion_fn = void (JNICALL*) (JNIEnv *, jobject, jint, jbyteArray);

using dpSetMaxAlgorithmVersiongen2_fn = void (JNICALL*) (JNIEnv *, jobject, jint, jbyteArray);

using P1_fn = jint (JNICALL*) (JNIEnv *envin, jobject obj, jint i, jint i2,jbyteArray  bArr,jbyteArray  bArr2,jbyteArray token72);

using P2_fn = jbyteArray (JNICALL*) (JNIEnv *, jobject, jint, jint, jbyteArray, jbyteArray,jbyteArray token72);

using initializeNative_fn = void (JNICALL*) (JNIEnv *, jobject, jobject, jbyteArray);

using processScan2_7_fn = jobject (JNICALL*) (JNIEnv *, jobject, jint, jobject, jobject, jobject, jobject, jbyteArray, jbyteArray, jbyteArray, jint, jint, jint, jint, jint, jbyteArray, jbyteArray, jbyteArray, jobject, jobject, jobject, jobject, jobject, jobject, jobject, jobject, jobject, jobject,jbyteArray token72);

using processScan2_10_fn = jobject (JNICALL*) (JNIEnv *, jobject, jint, jobject, jobject, jobject, jobject, jboolean vitC,jbyteArray, jbyteArray, jbyteArray, jint, jint, jint, jint,jboolean streaming, jint, jbyteArray, jbyteArray, jbyteArray, jobject, jobject, jobject, jobject, jobject, jobject, jobject, jobject, jobject, jobject,jbyteArray token72);

using getEnableStreamingPayload_fn = jbyteArray (JNICALL*) (JNIEnv *, jobject, jint, jbyteArray, jbyteArray, jbyte, jint,jbyteArray token72);

using activationComplete_fn = jbyteArray (JNICALL*) (JNIEnv *, jobject, jbyteArray, jint, jint, jbyteArray,jbyteArray token72);

using getProductFamily_fn = jint (JNICALL*) (JNIEnv *, jobject, jint, jbyteArray,jbyteArray token72);

using getPatchTimeValues_fn = jboolean (JNICALL*) (JNIEnv *, jobject, jint, jbyteArray, jobject, jobject,jbyteArray token72);

using getActivationCommand_fn = jbyte (JNICALL*) (JNIEnv *env, jobject obj, jint parsertype, jbyteArray info,jbyteArray token72);

using getActivationPayload_fn = jbyteArray (JNICALL*) (JNIEnv *env, jobject obj, jint parsertype, jbyteArray patchid, jbyteArray info, jbyte person,jbyteArray token72);

using getStreamingUnlockPayload_fn = jbyteArray (JNICALL*) (JNIEnv *envin, jobject obj, jint parsertype, jbyteArray sensorident, jbyteArray patchinfo, jbyte person, jint timestamp, jint unlockcount,jbyteArray token72);

using getNextRegionToRead_fn = jobject (JNICALL*) (JNIEnv *, jobject, jint, jbyteArray, jbyteArray, jbyteArray, jint,jbyteArray token72);

using processStream2_7_fn = jobject (JNICALL*) (JNIEnv *envin, jobject obj,
jint parsertype, jobject alarmconf, jobject nonaction, jobject glrange, jobject attenuatinconfig, jbyteArray sensorident,

jbyteArray info,

jbyte person, jbyteArray bluetoothdata,
jint startsincebase,jint nusincebase,jint warmup,jint wear,
jbyteArray compostate, jbyteArray attenustate, jbyteArray measurestate,
jobject outstarttime, jobject endtime, jobject confinsert, jobject removed,
jobject compo, jobject attenu, jobject messstate,
 jobject algorithresults,jbyteArray token72);

using processStream2_10_fn = jobject (JNICALL*) (JNIEnv *envin, jobject obj,
jint parsertype, jobject alarmconf, jobject nonaction, jobject glrange, jobject attenuatinconfig, jbyteArray sensorident,

jbyteArray info,

jbyte person, jbyteArray bluetoothdata,
jint startsincebase,jint nusincebase,jint warmup,jint wear,jboolean isstreaming,
jbyteArray compostate, jbyteArray attenustate, jbyteArray measurestate,
jobject outstarttime, jobject endtime, jobject confinsert, jobject removed,
jobject compo, jobject attenu, jobject messstate,
 jobject algorithresults,jbyteArray token72);
}

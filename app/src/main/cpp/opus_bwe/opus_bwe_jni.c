/*
 * JNI wrapper around Opus 1.6's neural bandwidth extension (OSCE BWE, BSD licence): 16 kHz voice in,
 * 48 kHz voice out with the 8-20 kHz band recreated. Built against an Opus 1.6 tree configured with
 * -DOPUS_OSCE=ON -DCMAKE_C_FLAGS=-DENABLE_OSCE_BWE (see build.sh); the result is the prebuilt
 * jniLibs/arm64-v8a/libopusbwe.so.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "arch.h"
#include "cpu_support.h"
#include "osce.h"
#include "osce_structs.h"
#include "silk/structs.h"

#define FRAME 320

typedef struct {
    OSCEModel model;
    silk_OSCE_BWE_struct state;
    int arch;
} Bwe;

JNIEXPORT jlong JNICALL
Java_com_andrerinas_openheadunit_decoder_OpusBwe_nativeCreate(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    Bwe *b = calloc(1, sizeof(Bwe));
    if (!b) return 0;
    b->arch = opus_select_arch();
    if (osce_load_models(&b->model, NULL, 0) != 0) {
        free(b);
        return 0;
    }
    osce_bwe_reset(&b->state);
    return (jlong)(intptr_t)b;
}

/* One 320-sample frame at 16 kHz into 960 samples at 48 kHz. */
JNIEXPORT void JNICALL
Java_com_andrerinas_openheadunit_decoder_OpusBwe_nativeProcess(JNIEnv *env, jclass cls, jlong handle,
                                                               jshortArray in, jshortArray out) {
    (void)cls;
    Bwe *b = (Bwe *)(intptr_t)handle;
    opus_int16 x_in[FRAME];
    opus_int16 x_out[3 * FRAME];
    (*env)->GetShortArrayRegion(env, in, 0, FRAME, x_in);
    osce_bwe(&b->model, &b->state, x_out, x_in, FRAME, b->arch);
    (*env)->SetShortArrayRegion(env, out, 0, 3 * FRAME, x_out);
}

JNIEXPORT void JNICALL
Java_com_andrerinas_openheadunit_decoder_OpusBwe_nativeReset(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    osce_bwe_reset(&((Bwe *)(intptr_t)handle)->state);
}

JNIEXPORT void JNICALL
Java_com_andrerinas_openheadunit_decoder_OpusBwe_nativeDestroy(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    free((Bwe *)(intptr_t)handle);
}

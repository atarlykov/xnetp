
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif


#include <cstdint>
#include <jni.h>
#include <sched.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>
#include <string.h>


#include "scheda.h"


/*
 * Class:     xnetp_poc_affinity_Affinity
 * Method:    __getAffinity
 * Signature: ()[B
 */
JNIEXPORT jbyteArray JNICALL Java_xnetp_poc_affinity_Affinity__1_1getAffinity
  (JNIEnv *env, jclass _class)
{
    cpu_set_t mask;
    const size_t mask_size = sizeof(mask);

    int res = sched_getaffinity(0, mask_size, &mask);
    if (res < 0) {
        return NULL;
    }

    jbyteArray obj = env->NewByteArray(mask_size);
    jbyte* elements = env->GetByteArrayElements(obj, 0);
    memcpy(elements, &mask, mask_size);
    //env->SetByteArrayRegion(obj, 0, mask_size, bytes);
    env->ReleaseByteArrayElements(obj, elements, 0);

    return obj;
}

/*
 * Class:     xnetp_poc_affinity_Affinity
 * Method:    __setAffinity
 * Signature: ([B)V
 */
JNIEXPORT void JNICALL Java_xnetp_poc_affinity_Affinity__1_1setAffinity
  (JNIEnv *env, jclass _class, jbyteArray affinity)
{
    cpu_set_t mask;
    const size_t mask_size = sizeof(mask);
    CPU_ZERO(&mask);

    jbyte* elements = env->GetByteArrayElements(affinity, 0);
    uint64_t length = (uint64_t)env->GetArrayLength(affinity);
    if (mask_size < length) {
        // prevent copy of possible trash
        length = mask_size;
    }
    memcpy(&mask, elements, length);
    sched_setaffinity(0, mask_size, &mask);
    env->ReleaseByteArrayElements(affinity, elements, 0);

}

/*
 * Class:     xnetp_poc_affinity_Affinity
 * Method:    __getThreadId
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_affinity_Affinity__1_1getThreadId
  (JNIEnv *env, jclass _class)
{
    return (jint) /*(pid_t)*/ syscall(SYS_gettid);
}

  
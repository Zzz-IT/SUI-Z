/*
 * This file is part of Sui.
 *
 * Sui is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Sui is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Sui.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (c) 2021-2026 Sui Contributors
 */

#include <jni.h>
#include "logging.h"
#include "bridge_service.h"

namespace BridgeService {

// sync with SuiBridgeService.java
#define BRIDGE_SERVICE_DESCRIPTOR "android.app.IActivityManager"
#define BRIDGE_SERVICE_NAME "activity"
#define BRIDGE_ACTION_GET_BINDER 2

// sync with IShizukuService.aidl
#define DESCRIPTOR "moe.shizuku.server.IShizukuService"

static jclass serviceManagerClass;
static jmethodID getServiceMethod;
static jmethodID transactMethod;
static jclass parcelClass;
static jmethodID obtainMethod;
static jmethodID recycleMethod;
static jmethodID writeInterfaceTokenMethod;
static jmethodID writeIntMethod;
static jmethodID writeStringMethod;
static jmethodID readExceptionMethod;
static jmethodID readStrongBinderMethod;
static jmethodID createStringArray;
static jclass deadObjectExceptionClass;

static std::mutex initMutex;
static bool initDone = false;

static bool ensureInit(JNIEnv* env) {
    std::lock_guard<std::mutex> lock(initMutex);
    if (initDone) return true;

    auto serviceManagerClassLocal = env->FindClass("android/os/ServiceManager");
    if (!serviceManagerClassLocal) {
        env->ExceptionClear();
        LOGE("FindClass ServiceManager failed");
        return false;
    }
    serviceManagerClass = (jclass) env->NewGlobalRef(serviceManagerClassLocal);
    env->DeleteLocalRef(serviceManagerClassLocal);
    if (!serviceManagerClass) return false;

    getServiceMethod = env->GetStaticMethodID(
            serviceManagerClass,
            "getService",
            "(Ljava/lang/String;)Landroid/os/IBinder;");
    if (!getServiceMethod) {
        env->ExceptionClear();
        LOGE("getService method not found");
        return false;
    }

    auto iBinderClass = env->FindClass("android/os/IBinder");
    if (!iBinderClass) {
        env->ExceptionClear();
        LOGE("FindClass IBinder failed");
        return false;
    }
    transactMethod = env->GetMethodID(
            iBinderClass,
            "transact",
            "(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z");
    env->DeleteLocalRef(iBinderClass);
    if (!transactMethod) {
        env->ExceptionClear();
        LOGE("IBinder.transact method not found");
        return false;
    }

    auto parcelClassLocal = env->FindClass("android/os/Parcel");
    if (!parcelClassLocal) {
        env->ExceptionClear();
        LOGE("FindClass Parcel failed");
        return false;
    }
    parcelClass = (jclass) env->NewGlobalRef(parcelClassLocal);
    env->DeleteLocalRef(parcelClassLocal);
    if (!parcelClass) return false;

    obtainMethod = env->GetStaticMethodID(parcelClass, "obtain", "()Landroid/os/Parcel;");
    recycleMethod = env->GetMethodID(parcelClass, "recycle", "()V");
    writeInterfaceTokenMethod = env->GetMethodID(parcelClass, "writeInterfaceToken", "(Ljava/lang/String;)V");
    writeIntMethod = env->GetMethodID(parcelClass, "writeInt", "(I)V");
    writeStringMethod = env->GetMethodID(parcelClass, "writeString", "(Ljava/lang/String;)V");
    readExceptionMethod = env->GetMethodID(parcelClass, "readException", "()V");
    readStrongBinderMethod = env->GetMethodID(parcelClass, "readStrongBinder", "()Landroid/os/IBinder;");
    createStringArray = env->GetMethodID(parcelClass, "createStringArray", "()[Ljava/lang/String;");

    if (!obtainMethod || !recycleMethod || !writeInterfaceTokenMethod ||
        !writeIntMethod || !readExceptionMethod || !readStrongBinderMethod ||
        !writeStringMethod || !createStringArray) {
        env->ExceptionClear();
        LOGE("Parcel method lookup failed");
        return false;
    }

    auto deadObjectExceptionClassLocal = env->FindClass("android/os/DeadObjectException");
    if (deadObjectExceptionClassLocal) {
        deadObjectExceptionClass = (jclass) env->NewGlobalRef(deadObjectExceptionClassLocal);
        env->DeleteLocalRef(deadObjectExceptionClassLocal);
    } else {
        env->ExceptionClear();
        LOGW("DeadObjectException class not found");
    }

    initDone = true;
    return true;
}

void init(JNIEnv* env) {
    ensureInit(env);
}

static jobject serviceBinder = nullptr;
static std::mutex binderMutex;

static jobject requireBinder(JNIEnv* env, bool force = false) {
    if (!ensureInit(env)) {
        return nullptr;
    }

    {
        std::lock_guard<std::mutex> lock(binderMutex);
        if (serviceBinder && !force) {
            return env->NewLocalRef(serviceBinder);
        }
    }

    jstring bridgeServiceName = nullptr;
    jstring descriptor = nullptr;
    jobject bridgeService = nullptr;
    jobject data = nullptr;
    jobject reply = nullptr;
    jobject service = nullptr;
    jboolean res = JNI_FALSE;

    bridgeServiceName = env->NewStringUTF(BRIDGE_SERVICE_NAME);
    if (!bridgeServiceName) goto cleanup;

    bridgeService = env->CallStaticObjectMethod(serviceManagerClass, getServiceMethod, bridgeServiceName);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        goto cleanup;
    }
    if (!bridgeService) {
        LOGD("can't get %s", BRIDGE_SERVICE_NAME);
        goto cleanup;
    }

    data = env->CallStaticObjectMethod(parcelClass, obtainMethod);
    reply = env->CallStaticObjectMethod(parcelClass, obtainMethod);
    if (env->ExceptionCheck() || !data || !reply) {
        env->ExceptionClear();
        goto cleanup;
    }

    descriptor = env->NewStringUTF(BRIDGE_SERVICE_DESCRIPTOR);
    if (!descriptor) goto cleanup;

    env->CallVoidMethod(data, writeInterfaceTokenMethod, descriptor);
    env->CallVoidMethod(data, writeIntMethod, BRIDGE_ACTION_GET_BINDER);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        goto cleanup;
    }

    res = env->CallBooleanMethod(
            bridgeService,
            transactMethod,
            BRIDGE_TRANSACTION_CODE,
            data,
            reply,
            0);
    if (env->ExceptionCheck() || !res) {
        env->ExceptionClear();
        goto cleanup;
    }

    env->CallVoidMethod(reply, readExceptionMethod);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        goto cleanup;
    }

    service = env->CallObjectMethod(reply, readStrongBinderMethod);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        service = nullptr;
        goto cleanup;
    }

    if (service) {
        std::lock_guard<std::mutex> lock(binderMutex);

        if (serviceBinder) {
            env->DeleteGlobalRef(serviceBinder);
            serviceBinder = nullptr;
        }

        serviceBinder = env->NewGlobalRef(service);
    }

cleanup:
    if (data) env->CallVoidMethod(data, recycleMethod);
    if (reply) env->CallVoidMethod(reply, recycleMethod);

    if (descriptor) env->DeleteLocalRef(descriptor);
    if (reply) env->DeleteLocalRef(reply);
    if (data) env->DeleteLocalRef(data);
    if (bridgeService) env->DeleteLocalRef(bridgeService);
    if (bridgeServiceName) env->DeleteLocalRef(bridgeServiceName);

    return service;
}

static void clearCachedBinder(JNIEnv* env) {
    std::lock_guard<std::mutex> lock(binderMutex);
    if (serviceBinder) {
        env->DeleteGlobalRef(serviceBinder);
        serviceBinder = nullptr;
    }
}

static bool isDeadObject(JNIEnv* env, jthrowable exception) {
    if (!exception || !deadObjectExceptionClass) return false;
    return env->IsInstanceOf(exception, deadObjectExceptionClass);
}

static jboolean tryTransact(JNIEnv* env, jint code, jobject data, jobject reply, jint flags) {
    for (int attempt = 0; attempt < 2; ++attempt) {
        jobject binder = requireBinder(env, attempt > 0);
        if (!binder) {
            LOGE("binder is null");
            return JNI_FALSE;
        }

        jboolean res = env->CallBooleanMethod(binder, transactMethod, code, data, reply, flags);

        jthrowable exception = env->ExceptionOccurred();
        if (!exception) {
            env->DeleteLocalRef(binder);
            return res;
        }

        env->ExceptionClear();
        bool dead = isDeadObject(env, exception);
        env->DeleteLocalRef(exception);
        env->DeleteLocalRef(binder);

        if (!dead) {
            return JNI_FALSE;
        }

        clearCachedBinder(env);
    }

    return JNI_FALSE;
}
}  // namespace BridgeService
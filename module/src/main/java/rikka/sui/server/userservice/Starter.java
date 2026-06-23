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

package rikka.sui.server.userservice;

import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ServiceManager;
import android.util.Log;
import android.util.Pair;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.server.UserService;
import rikka.sui.util.BridgeConstants;

public class Starter {

    private static final String TAG = "SuiUserServiceStarter";

    public static void main(String[] args) {
        if (Looper.myLooper() == null) {
            if (Looper.getMainLooper() == null) {
                prepareMainLooper();
            } else {
                Looper.prepare();
            }
        }

        IBinder service;
        String token;

        UserService.setTag(TAG);
        Pair<IBinder, String> result = UserService.create(args);

        if (result == null) {
            System.exit(1);
            return;
        }

        service = result.first;
        token = result.second;

        if (!sendBinder(service, token)) {
            System.exit(1);
        }

        Looper.loop();
        System.exit(0);

        Log.i(TAG, "service exited");
    }

    @SuppressWarnings("deprecation")
    private static void prepareMainLooper() {
        Looper.prepareMainLooper();
    }

    private static final int BRIDGE_BINDER_RETRY_MAX = 20;
    private static final long BRIDGE_BINDER_RETRY_DELAY_MS = 100L;

    private static IBinder requestBinderFromBridgeOnce() {
        IBinder binder = ServiceManager.getService(BridgeConstants.SERVICE_NAME);
        if (binder == null) return null;

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(BridgeConstants.SERVICE_DESCRIPTOR);
            data.writeInt(BridgeConstants.ACTION_GET_BINDER);
            binder.transact(BridgeConstants.TRANSACTION_CODE, data, reply, 0);
            reply.readException();
            return reply.readStrongBinder();
        } catch (Throwable e) {
            Log.w(TAG, "request binder from bridge failed", e);
        } finally {
            data.recycle();
            reply.recycle();
        }
        return null;
    }

    private static IBinder requestBinderFromBridgeWithRetry() {
        for (int i = 0; i < BRIDGE_BINDER_RETRY_MAX; i++) {
            IBinder binder = requestBinderFromBridgeOnce();
            if (binder != null) {
                return binder;
            }

            try {
                Thread.sleep(BRIDGE_BINDER_RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static boolean sendBinder(IBinder binder, String token) {
        IShizukuService shizukuService = IShizukuService.Stub.asInterface(requestBinderFromBridgeWithRetry());
        if (shizukuService == null) {
            return false;
        }

        Bundle data = new Bundle();
        data.putString(USER_SERVICE_ARG_TOKEN, token);
        try {
            shizukuService.attachUserService(binder, data);
        } catch (Throwable e) {
            Log.w(TAG, Log.getStackTraceString(e));
            return false;
        }
        return true;
    }
}

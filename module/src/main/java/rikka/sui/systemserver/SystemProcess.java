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

package rikka.sui.systemserver;

import static rikka.sui.systemserver.SystemServerConstants.LOGGER;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import java.util.Arrays;
import rikka.sui.server.SuiConfig;
import rikka.sui.util.ParcelUtils;

public final class SystemProcess {

    private static final BridgeService SERVICE = new BridgeService();

    private static final class PermissionSnapshot {
        final int[] hiddenUids;
        final int[] rootUids;
        final int[] deniedUids;
        final int[] shellUids;
        final int defaultPermissionFlags;

        PermissionSnapshot(
                int[] hiddenUids,
                int[] rootUids,
                int[] deniedUids,
                int[] shellUids,
                int defaultPermissionFlags) {
            this.hiddenUids = hiddenUids;
            this.rootUids = rootUids;
            this.deniedUids = deniedUids;
            this.shellUids = shellUids;
            this.defaultPermissionFlags = defaultPermissionFlags;
        }

        static PermissionSnapshot empty() {
            return new PermissionSnapshot(new int[0], new int[0], new int[0], new int[0], 0);
        }
    }

    private static final java.util.concurrent.atomic.AtomicReference<PermissionSnapshot> snapshot =
            new java.util.concurrent.atomic.AtomicReference<>(PermissionSnapshot.empty());

    private static boolean execActivityTransaction(
            @NonNull Binder binder, int code, Parcel data, Parcel reply, int flags) {
        return SERVICE.onTransact(code, data, reply, flags);
    }

    public static boolean execTransact(@NonNull Binder binder, int code, long dataObj, long replyObj, int flags) {
        if (!SERVICE.isServiceTransaction(code)) {
            return false;
        }

        Parcel data = ParcelUtils.fromNativePointer(dataObj);
        Parcel reply = ParcelUtils.fromNativePointer(replyObj);

        if (data == null) {
            return false;
        }

        boolean res;
        try {
            res = execActivityTransaction(binder, code, data, reply, flags);
        } catch (Exception e) {
            if ((flags & IBinder.FLAG_ONEWAY) != 0) {
                LOGGER.w(e, "Caught a Exception from the binder stub implementation.");
            } else {
                if (reply != null) {
                    reply.setDataPosition(0);
                    reply.writeException(e);
                }
            }
            res = false;
        } finally {
            data.setDataPosition(0);
            if (reply != null) reply.setDataPosition(0);
        }

        if (res) {
            data.recycle();
            if (reply != null) reply.recycle();
        }

        return res;
    }

    public static void main(String[] args) {
        LOGGER.d("main: %s", Arrays.toString(args));

        // Note: IShizukuService only provides getHiddenUids().
        // Root and shell UIDs will be pushed shortly by SuiService via ACTION_SYNC_UIDS.
        try {
            moe.shizuku.server.IShizukuService service = BridgeService.get();
            if (service != null) {
                int[] uids = service.getHiddenUids();
                LOGGER.d("syncing %d hidden uids to native and Java cache", uids.length);
                updateUids(uids, new int[0], new int[0], new int[0], 0);
            } else {
                LOGGER.w("IShizukuService is null in SystemProcess.main");
            }
        } catch (Throwable e) {
            LOGGER.w(e, "failed to sync hidden uids");
        }
    }

    private static int[] normalize(int[] input) {
        if (input == null || input.length == 0) {
            return new int[0];
        }
        int[] copy = input.clone();
        Arrays.sort(copy);
        return copy;
    }

    public static void updateUids(int[] hidden, int[] root, int[] denied, int[] shell, int defaultFlags) {
        int[] hiddenCopy = normalize(hidden);
        int[] rootCopy = normalize(root);
        int[] deniedCopy = normalize(denied);
        int[] shellCopy = normalize(shell);

        PermissionSnapshot next = new PermissionSnapshot(
                hiddenCopy,
                rootCopy,
                deniedCopy,
                shellCopy,
                defaultFlags & SuiConfig.MASK_PERMISSION);

        snapshot.set(next);

        LOGGER.d(
                "syncing %d hidden, %d root, %d denied, %d shell uids to native, defaultFlags=%d",
                hiddenCopy.length,
                rootCopy.length,
                deniedCopy.length,
                shellCopy.length,
                next.defaultPermissionFlags);

        setHiddenUids(hiddenCopy);
    }

    public static boolean isHidden(int uid) {
        PermissionSnapshot s = snapshot.get();
        return Arrays.binarySearch(s.hiddenUids, uid) >= 0;
    }

    public static int getEffectivePermissionFlags(int uid) {
        PermissionSnapshot s = snapshot.get();

        int flags = s.defaultPermissionFlags;

        if (Arrays.binarySearch(s.hiddenUids, uid) >= 0) {
            flags = SuiConfig.FLAG_HIDDEN;
        } else if (Arrays.binarySearch(s.deniedUids, uid) >= 0) {
            flags = SuiConfig.FLAG_DENIED;
        } else if (Arrays.binarySearch(s.rootUids, uid) >= 0) {
            flags = SuiConfig.FLAG_ALLOWED;
        } else if (Arrays.binarySearch(s.shellUids, uid) >= 0) {
            flags = SuiConfig.FLAG_ALLOWED_SHELL;
        }

        return flags & SuiConfig.MASK_PERMISSION;
    }

    public static boolean isUidHiddenEffective(int uid) {
        return (getEffectivePermissionFlags(uid) & SuiConfig.FLAG_HIDDEN) != 0;
    }

    public static boolean isRootAllowed(int uid) {
        PermissionSnapshot s = snapshot.get();
        return Arrays.binarySearch(s.rootUids, uid) >= 0;
    }

    public static boolean isShellAllowed(int uid) {
        PermissionSnapshot s = snapshot.get();
        return Arrays.binarySearch(s.shellUids, uid) >= 0;
    }

    public static boolean isDenied(int uid) {
        PermissionSnapshot s = snapshot.get();
        return Arrays.binarySearch(s.deniedUids, uid) >= 0;
    }

    public static int getDefaultPermissionFlags() {
        return snapshot.get().defaultPermissionFlags;
    }

    @Keep
    @SuppressWarnings("JavaJniMissingFunction")
    private static native void setHiddenUids(int[] uids);
}

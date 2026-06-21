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
import android.os.RemoteException;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import moe.shizuku.server.IShizukuService;
import rikka.sui.server.SuiConfig;
import rikka.sui.util.BridgeConstants;

public class BridgeService {

    private static final IBinder.DeathRecipient DEATH_RECIPIENT_ROOT = () -> {
        rootServiceBinder = null;
        rootService = null;
        rootServerPid = -1;
        serviceStarted = false;
        LOGGER.i("root service is dead");
    };
    private static final IBinder.DeathRecipient DEATH_RECIPIENT_SHELL = () -> {
        shellServiceBinder = null;
        shellService = null;
        shellServerPid = -1;
        LOGGER.i("shell service is dead");
    };

    private static volatile IBinder rootServiceBinder;
    private static IShizukuService rootService;
    private static volatile IBinder shellServiceBinder;
    private static IShizukuService shellService;
    private static volatile boolean serviceStarted;
    private static volatile int rootServerPid = -1;
    private static volatile int shellServerPid = -1;

    public static IShizukuService get() {
        return rootService;
    }

    public static IShizukuService getShell() {
        return shellService;
    }

    public static boolean isServiceStarted() {
        return serviceStarted;
    }

    private void sendBinder(IBinder binder, boolean isRoot) {
        if (binder == null) {
            LOGGER.w("received empty binder");
            return;
        }

        IBinder.DeathRecipient recipient = isRoot ? DEATH_RECIPIENT_ROOT : DEATH_RECIPIENT_SHELL;

        try {
            binder.linkToDeath(recipient, 0);
        } catch (RemoteException e) {
            LOGGER.w(e, "received dead binder");
            return;
        }

        try {
            if (isRoot) {
                IBinder old = rootServiceBinder;
                if (old == null) {
                    PackageReceiver.register();
                } else {
                    try {
                        old.unlinkToDeath(DEATH_RECIPIENT_ROOT, 0);
                    } catch (Throwable e) {
                        LOGGER.w(e, "unlink old root binder");
                    }
                }

                rootServiceBinder = binder;
                rootService = IShizukuService.Stub.asInterface(binder);
                LOGGER.i("root binder received");
            } else {
                IBinder old = shellServiceBinder;
                if (old != null) {
                    try {
                        old.unlinkToDeath(DEATH_RECIPIENT_SHELL, 0);
                    } catch (Throwable e) {
                        LOGGER.w(e, "unlink old shell binder");
                    }
                }

                shellServiceBinder = binder;
                shellService = IShizukuService.Stub.asInterface(binder);
                LOGGER.i("shell binder received");
            }
        } catch (Throwable e) {
            try {
                binder.unlinkToDeath(recipient, 0);
            } catch (Throwable ignored) {
            }
            LOGGER.w(e, "sendBinder failed");
        }
    }

    public boolean isServiceTransaction(int code) {
        return code == BridgeConstants.TRANSACTION_CODE;
    }

    public boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags) {
        data.enforceInterface(BridgeConstants.SERVICE_DESCRIPTOR);

        int action = data.readInt();
        LOGGER.d(
                "onTransact: action=%d, callingUid=%d, callingPid=%d",
                action, Binder.getCallingUid(), Binder.getCallingPid());

        switch (action) {
            case BridgeConstants.ACTION_SEND_BINDER: {
                int callingUid = Binder.getCallingUid();
                int callingPid = Binder.getCallingPid();

                if (callingUid == 0 || callingUid == 2000) {
                    IBinder binder = data.readStrongBinder();

                    long identity = Binder.clearCallingIdentity();
                    try {
                        sendBinder(binder, callingUid == 0);

                        if (callingUid == 0) {
                            rootServerPid = callingPid;
                        } else {
                            shellServerPid = callingPid;
                        }
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }

                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                break;
            }
            case BridgeConstants.ACTION_GET_BINDER: {
                int callingUid = Binder.getCallingUid();
                int callingPid = Binder.getCallingPid();

                Integer requestedServerUid = null;

                if ((callingUid == 0 || callingUid == 2000) && data.dataAvail() >= Integer.BYTES) {
                    int value = data.readInt();
                    if (value == BridgeConstants.SERVER_UID_ROOT || value == BridgeConstants.SERVER_UID_SHELL) {
                        requestedServerUid = value;
                    }
                }

                IBinder requestedBinder = null;

                if (requestedServerUid != null) {
                    if (!isTrustedServerDelegate(callingUid, callingPid, requestedServerUid)) {
                        LOGGER.w(
                                "reject server binder request: uid=%d pid=%d target=%d",
                                callingUid,
                                callingPid,
                                requestedServerUid);

                        if (reply != null) {
                            reply.writeNoException();
                            reply.writeStrongBinder(null);
                        }
                        return true;
                    }

                    requestedBinder = requestedServerUid == BridgeConstants.SERVER_UID_ROOT
                            ? rootServiceBinder
                            : shellServiceBinder;

                } else {
                    int permissionFlags = Bridge.getPermissionFlags(callingUid);

                    if ((permissionFlags & SuiConfig.FLAG_HIDDEN) != 0) {
                        return false;
                    }

                    if ((permissionFlags & SuiConfig.FLAG_DENIED) != 0) {
                        requestedBinder = null;

                    } else if ((permissionFlags & SuiConfig.FLAG_ALLOWED_SHELL) != 0) {
                        requestedBinder = shellServiceBinder;

                    } else {
                        /*
                         * ASK 或 ROOT allowed/default:
                         * 返回 root binder，保持原始 app -> root service 的 Binder 调用身份。
                         * root service 内部继续限制未授权 app 只能走 attach/requestPermission。
                         */
                        requestedBinder = rootServiceBinder;
                    }
                }

                if (reply != null) {
                    reply.writeNoException();
                    reply.writeStrongBinder(requestedBinder);
                }
                return true;
            }
            case BridgeConstants.ACTION_NOTIFY_FINISHED: {
                if (Binder.getCallingUid() == 0) {
                    serviceStarted = true;

                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                break;
            }
            case BridgeConstants.ACTION_SYNC_UIDS: {
                if (Binder.getCallingUid() == 0) {
                    int[] hiddenUids = data.createIntArray();
                    int[] rootUids = data.createIntArray();
                    int[] shellUids = data.createIntArray();
                    int defaultFlags = 0;
                    int[] deniedUids = new int[0];

                    if (data.dataAvail() >= Integer.BYTES) {
                        defaultFlags = data.readInt();
                    }

                    if (data.dataAvail() >= Integer.BYTES) {
                        deniedUids = data.createIntArray();
                    }
                    SystemProcess.updateUids(hiddenUids, rootUids, deniedUids, shellUids, defaultFlags);
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                break;
            }
        }
        return false;
    }

    private boolean isTrustedServerDelegate(int uid, int pid, int requestedServerUid) {
        if (uid == 0) {
            return pid > 0 && pid == rootServerPid;
        }

        if (uid == 2000) {
            return pid > 0
                    && pid == shellServerPid
                    && (requestedServerUid == BridgeConstants.SERVER_UID_ROOT
                            || requestedServerUid == BridgeConstants.SERVER_UID_SHELL);
        }

        return false;
    }
}

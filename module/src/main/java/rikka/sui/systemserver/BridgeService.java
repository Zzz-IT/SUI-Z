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
        serviceStarted = false;
        LOGGER.i("root service is dead");
    };
    private static final IBinder.DeathRecipient DEATH_RECIPIENT_SHELL = () -> {
        shellServiceBinder = null;
        shellService = null;
        LOGGER.i("shell service is dead");
    };

    private static volatile IBinder rootServiceBinder;
    private static IShizukuService rootService;
    private static volatile IBinder shellServiceBinder;
    private static IShizukuService shellService;
    private static volatile boolean serviceStarted;

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
                if (callingUid == 0 || callingUid == 2000) {
                    IBinder binder = data.readStrongBinder();
                    long identity = Binder.clearCallingIdentity();
                    try {
                        sendBinder(binder, callingUid == 0);
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
                int targetUid = callingUid;
                Integer requestedServerUid = null;
                String token = null;

                if ((callingUid == 0 || callingUid == 2000) && data.dataAvail() >= Integer.BYTES) {
                    int value = data.readInt();
                    if (value == BridgeConstants.SERVER_UID_ROOT || value == BridgeConstants.SERVER_UID_SHELL) {
                        requestedServerUid = value;
                    }
                    if (data.dataAvail() > 0) {
                        token = data.readString();
                    }
                }

                int permissionFlags = Bridge.getPermissionFlags(targetUid);
                IBinder requestedBinder = null;

                if (requestedServerUid != null) {
                    if (!isTrustedServerDelegate(callingUid, Binder.getCallingPid(), requestedServerUid, token)) {
                        LOGGER.w("reject server binder request: uid=%d pid=%d target=%d",
                                callingUid, Binder.getCallingPid(), requestedServerUid);
                        if (reply != null) {
                            reply.writeNoException();
                            reply.writeStrongBinder(null);
                        }
                        return true;
                    }

                    requestedBinder = requestedServerUid == BridgeConstants.SERVER_UID_ROOT
                            ? rootServiceBinder
                            : shellServiceBinder;
                } else if ((permissionFlags & SuiConfig.FLAG_HIDDEN) != 0) {
                    return false;
                } else if ((permissionFlags & SuiConfig.FLAG_DENIED) != 0) {
                    requestedBinder = new RestrictedBridgeBinder(RestrictedBridgeBinder.MODE_DENY);
                } else if ((permissionFlags & SuiConfig.FLAG_ALLOWED) != 0) {
                    requestedBinder = rootServiceBinder;
                } else if ((permissionFlags & SuiConfig.FLAG_ALLOWED_SHELL) != 0) {
                    requestedBinder = shellServiceBinder;
                } else {
                    requestedBinder = new RestrictedBridgeBinder(RestrictedBridgeBinder.MODE_ASK);
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

    private static volatile String shellDelegateToken;

    private boolean isTrustedServerDelegate(int uid, int pid, int requestedServerUid, @Nullable String token) {
        if (uid == 0) return true;

        if (uid == 2000 && requestedServerUid == BridgeConstants.SERVER_UID_ROOT) {
            return token != null && token.equals(shellDelegateToken) && isKnownShellServerPid(pid);
        }

        if (uid == 2000 && requestedServerUid == BridgeConstants.SERVER_UID_SHELL) {
            return isKnownShellServerPid(pid);
        }

        return false;
    }

    private boolean isKnownShellServerPid(int pid) {
        return true;
    }

    private static final class RestrictedBridgeBinder extends Binder {
        static final int MODE_ASK = 1;
        static final int MODE_DENY = 2;

        private final int mode;

        RestrictedBridgeBinder(int mode) {
            this.mode = mode;
        }

        @Override
        protected boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags) throws RemoteException {
            if (mode == MODE_DENY) {
                if (reply != null) {
                    reply.writeException(new SecurityException("Sui permission denied"));
                }
                return true;
            }

            if (mode == MODE_ASK) {
                if (code == moe.shizuku.server.IShizukuService.Stub.TRANSACTION_attachApplication
                        || code == moe.shizuku.server.IShizukuService.Stub.TRANSACTION_requestPermission) {
                    IBinder root = BridgeService.get().asBinder();
                    if (root != null) {
                        return root.transact(code, data, reply, flags);
                    }
                }

                if (reply != null) {
                    reply.writeException(new SecurityException("Permission not granted yet"));
                }
                return true;
            }

            return false;
        }
    }
}

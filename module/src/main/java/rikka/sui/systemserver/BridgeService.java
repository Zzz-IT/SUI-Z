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
        rootServerPid = -1;
        rootRegisterToken = null;
        serviceStarted = false;
        LOGGER.i("root service is dead");
    };
    private static final IBinder.DeathRecipient DEATH_RECIPIENT_SHELL = () -> {
        shellServiceBinder = null;
        shellServerPid = -1;
        shellRegisterToken = null;
        LOGGER.i("shell service is dead");
    };

    private static volatile IBinder rootServiceBinder;
    private static volatile IBinder shellServiceBinder;
    private static volatile boolean serviceStarted;
    private static volatile int rootServerPid = -1;
    private static volatile int shellServerPid = -1;
    private static volatile String rootRegisterToken;
    private static volatile String shellRegisterToken;

    public static IShizukuService get() {
        IBinder binder = rootServiceBinder;
        return binder == null ? null : IShizukuService.Stub.asInterface(binder);
    }

    public static IShizukuService getShell() {
        IBinder binder = shellServiceBinder;
        return binder == null ? null : IShizukuService.Stub.asInterface(binder);
    }

    public static boolean isServiceStarted() {
        return serviceStarted;
    }

    private boolean sendBinder(IBinder binder, boolean isRoot) {
        if (binder == null) {
            LOGGER.w("received empty binder");
            return false;
        }

        IBinder.DeathRecipient recipient = isRoot ? DEATH_RECIPIENT_ROOT : DEATH_RECIPIENT_SHELL;

        try {
            binder.linkToDeath(recipient, 0);
        } catch (RemoteException e) {
            LOGGER.w(e, "received dead binder");
            return false;
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
                LOGGER.i("shell binder received");
            }
            return true;
        } catch (Throwable e) {
            try {
                binder.unlinkToDeath(recipient, 0);
            } catch (Throwable ignored) {
            }
            LOGGER.w(e, "sendBinder failed");
            return false;
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

                    String token = null;
                    if (data.dataAvail() > 0) {
                        token = data.readString();
                    }

                    if (!isRegisterTokenAllowed(callingUid, token)) {
                        LOGGER.w("reject binder registration: invalid token uid=%d pid=%d", callingUid, callingPid);
                        if (reply != null) {
                            reply.writeNoException();
                            reply.writeInt(0);
                        }
                        return true;
                    }

                    boolean ok;
                    long identity = Binder.clearCallingIdentity();
                    try {
                        ok = sendBinder(binder, callingUid == 0);
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }

                    if (ok) {
                        if (callingUid == 0) {
                            rootServerPid = callingPid;
                        } else {
                            shellServerPid = callingPid;
                        }
                    } else {
                        LOGGER.w(
                                "reject binder registration: uid=%d pid=%d isRoot=%s",
                                callingUid,
                                callingPid,
                                Boolean.toString(callingUid == 0));
                    }

                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(ok ? 1 : 0);
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

                String delegateToken = null;
                if (requestedServerUid != null && data.dataAvail() > 0) {
                    delegateToken = data.readString();
                }

                IBinder requestedBinder = null;

                if (requestedServerUid != null) {
                    if (!isTrustedServerDelegate(callingUid, callingPid, requestedServerUid, delegateToken)) {
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
            case BridgeConstants.ACTION_REGISTER_TOKEN: {
                int callingUid = Binder.getCallingUid();
                int callingPid = Binder.getCallingPid();

                if (callingUid != 0) {
                    LOGGER.w("reject token registration from uid=%d pid=%d", callingUid, callingPid);
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }

                String rToken = data.readString();
                String sToken = data.readString();

                if (!isValidToken(rToken) || !isValidToken(sToken)) {
                    LOGGER.w("reject invalid bridge tokens");
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }

                rootRegisterToken = rToken;
                shellRegisterToken = sToken;

                LOGGER.i("bridge tokens registered by root pid=%d", callingPid);

                if (reply != null) {
                    reply.writeNoException();
                }
                return true;
            }
        }
        return false;
    }

    private boolean isTrustedServerDelegate(int uid, int pid, int requestedServerUid, @Nullable String token) {
        if (uid == 0) {
            return pid > 0
                    && pid == rootServerPid
                    && rootRegisterToken != null
                    && rootRegisterToken.equals(token);
        }

        if (uid == 2000) {
            return pid > 0
                    && pid == shellServerPid
                    && shellRegisterToken != null
                    && shellRegisterToken.equals(token)
                    && (requestedServerUid == BridgeConstants.SERVER_UID_ROOT
                            || requestedServerUid == BridgeConstants.SERVER_UID_SHELL);
        }

        return false;
    }

    private static boolean isRegisterTokenAllowed(int uid, @Nullable String token) {
        if (uid == 0) {
            return rootRegisterToken != null && rootRegisterToken.equals(token);
        }

        if (uid == 2000) {
            return shellRegisterToken != null && shellRegisterToken.equals(token);
        }

        return false;
    }

    private static boolean isValidToken(@Nullable String token) {
        return token != null && token.length() >= 32 && token.length() <= 128;
    }
}

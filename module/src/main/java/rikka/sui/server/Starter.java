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

package rikka.sui.server;

import static rikka.sui.server.ServerConstants.LOGGER;

import android.content.Context;
import android.ddm.DdmHandleAppName;
import android.os.ServiceManager;
import android.os.SystemClock;
import java.util.Objects;

public class Starter {

    private static boolean waitSystemService(String name, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        boolean logged = false;

        while (ServiceManager.getService(name) == null) {
            long now = SystemClock.uptimeMillis();
            if (now >= deadline) {
                LOGGER.e("service %s is not started after %d ms", name, timeoutMs);
                return false;
            }

            if (!logged) {
                LOGGER.i("service %s is not started, waiting...", name);
                logged = true;
            }

            try {
                Thread.sleep(Math.min(1000L, deadline - now));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.w(e, "wait service interrupted");
                return false;
            }
        }

        return true;
    }

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            LOGGER.e(e, "Uncaught exception on thread %s", t.getName());
            System.exit(1);
        });

        String filesPath = null;
        boolean isShell = false;

        for (String arg : args) {
            if (arg.equals("--debug")) {
                DdmHandleAppName.setAppName("sui", 0);
            } else if (arg.startsWith("--files-path=")) {
                filesPath = arg.substring("--files-path=".length());
                SuiUserServiceManager.setStartDex(filesPath + "/sui.dex");
            } else if (arg.equals("--shell")) {
                isShell = true;
            }
        }

        Objects.requireNonNull(filesPath, "--files-path not set");

        if (!waitSystemService("package", 60_000)
                || !waitSystemService("activity", 60_000)
                || !waitSystemService(Context.USER_SERVICE, 60_000)
                || !waitSystemService(Context.APP_OPS_SERVICE, 60_000)) {
            System.exit(1);
            return;
        }

        SuiService.main(filesPath, isShell);
    }
}

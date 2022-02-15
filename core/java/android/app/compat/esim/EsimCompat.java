/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.compat.esim;

import android.annotation.SystemApi;
import android.app.ActivityThread;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.Disabled;
import android.content.pm.Signature;
import android.os.Process;
import android.util.Log;

import com.android.internal.esimcompat.EsimInfo;

/**
 * This class provides helpers for Google Play Services compatibility. It allows the following apps
 * to work as regular, unprivileged user apps:
 *     - Google Play Services (Google Mobile Services, aka "GMS")
 *     - Google Services Framework
 *     - Google Play Store
 *     - All apps depending on Google Play Services
 *
 * All GMS compatibility hooks should call methods on GmsCompat. Hooks that are more complicated
 * than returning a simple constant value should also be implemented in GmsHooks to reduce
 * maintenance overhead.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class EsimCompat {
    private static final String TAG = "EsimCompat/Core";

    /**
     * Whether to enable Google Play Services compatibility for this app.
     *
     * This compatibility change is special because the system enables it automatically for certain
     * apps, but it still needs to be declared with a change ID.
     *
     * We don't have a bug for this in Google's issue tracker, so the change ID is a
     * randomly-generated long.
     */
    @ChangeId
    @Disabled
    private static final long ESIM_COMPAT = 1385729219845645771L;

    private static final boolean DEBUG_VERBOSE = true;

    // Static only
    private EsimCompat() { }

    private static void logEnabled(boolean enabled) {
        if (!DEBUG_VERBOSE) {
            return;
        }

        String pkg = ActivityThread.currentPackageName();
        if (pkg == null) {
            pkg = (Process.myUid() == Process.SYSTEM_UID) ? "system_server" : "[unknown]";
        }

        Log.d(TAG, "Enabled for " + pkg + " (" + Process.myPid() + "): " + enabled);
    }

    public static boolean isEnabled() {
        boolean enabled = Compatibility.isChangeEnabled(ESIM_COMPAT);

        // Compatibility changes aren't available in the system process, but this should never be
        // enabled for it.
        if (Process.myUid() == Process.SYSTEM_UID) {
            enabled = false;
        }

        logEnabled(enabled);
        return enabled;
    }

    /**
     * Check whether the given app is the Google eSIM app
     *
     * @hide
     */
    public static boolean isEsimApp(String packageName, Signature[] signatures) {
        if (!EsimInfo.PACKAGE_EUICC.equals(packageName)) {
            return false;
        }

        // Validate signature to avoid affecting apps like microG and Gcam Services Provider.
        // This isn't actually necessary from a security perspective because GMS doesn't get any
        // special privileges.
        boolean validCert = false;
        for (Signature signature : signatures) {
            if (signature.toCharsString().equals(EsimInfo.EUICC_SIGNING_CERT)) {
                validCert = true;
            }
        }

        return validCert;
    }
}

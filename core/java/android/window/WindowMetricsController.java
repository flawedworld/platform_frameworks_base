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

package android.window;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_VISIBLE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;

import android.annotation.NonNull;
import android.app.ResourcesManager;
import android.app.WindowConfiguration;
import android.content.Context;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.InsetsState;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.WindowMetrics;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A controller to handle {@link android.view.WindowMetrics} related APIs, which are
 * <ol>
 *     <li>{@link WindowManager#getCurrentWindowMetrics()}</li>
 *     <li>{@link WindowManager#getMaximumWindowMetrics()}</li>
 *     <li>{@link WindowManager#getPossibleMaximumWindowMetrics(int)}</li>
 * </ol>
 *
 * @hide
 */
public final class WindowMetricsController {
    // TODO(b/151908239): Remove and always enable this if it is stable.
    private static final boolean LAZY_WINDOW_INSETS = android.os.SystemProperties.getBoolean(
            "persist.wm.debug.win_metrics_lazy_insets", true);
    private final Context mContext;

    public WindowMetricsController(@NonNull Context context) {
        mContext = context;
    }

    /** @see WindowManager#getCurrentWindowMetrics() */
    public WindowMetrics getCurrentWindowMetrics() {
        return getWindowMetricsInternal(false /* isMaximum */);
    }

    /** @see WindowManager#getMaximumWindowMetrics() */
    public WindowMetrics getMaximumWindowMetrics() {
        return getWindowMetricsInternal(true /* isMaximum */);
    }

    /**
     * The core implementation to obtain {@link WindowMetrics}
     *
     * @param isMaximum {@code true} to obtain {@link WindowManager#getCurrentWindowMetrics()}.
     *                  {@code false} to obtain {@link WindowManager#getMaximumWindowMetrics()}.
     */
    private WindowMetrics getWindowMetricsInternal(boolean isMaximum) {
        final Rect bounds;
        final float density;
        final boolean isScreenRound;
        final int windowingMode;
        synchronized (ResourcesManager.getInstance()) {
            final Configuration config = mContext.getResources().getConfiguration();
            final WindowConfiguration winConfig = config.windowConfiguration;
            bounds = (isMaximum) ? winConfig.getMaxBounds() : winConfig.getBounds();
            // Multiply default density scale because WindowMetrics provide the density value with
            // the scaling factor for the Density Independent Pixel unit, which is the same unit
            // as DisplayMetrics#density
            density = config.densityDpi * DisplayMetrics.DENSITY_DEFAULT_SCALE;
            isScreenRound = config.isScreenRound();
            windowingMode = winConfig.getWindowingMode();
        }
        final IBinder token = Context.getToken(mContext);
        final Supplier<WindowInsets> insetsSupplier = () -> getWindowInsetsFromServerForDisplay(
                mContext.getDisplayId(), token, bounds, isScreenRound, windowingMode);
        return LAZY_WINDOW_INSETS
                ? new WindowMetrics(new Rect(bounds), insetsSupplier, density)
                : new WindowMetrics(new Rect(bounds), insetsSupplier.get(), density);
    }

    /**
     * Retrieves WindowInsets for the given context and display, given the window bounds.
     *
     * @param displayId the ID of the logical display to calculate insets for
     * @param token the token of Activity or WindowContext
     * @param bounds the window bounds to calculate insets for
     * @param isScreenRound if the display identified by displayId is round
     * @param windowingMode the windowing mode of the window to calculate insets for
     * @return WindowInsets calculated for the given window bounds, on the given display
     */
    private static WindowInsets getWindowInsetsFromServerForDisplay(int displayId, IBinder token,
            Rect bounds, boolean isScreenRound, int windowingMode) {
        try {
            final InsetsState insetsState = new InsetsState();
            final boolean alwaysConsumeSystemBars = WindowManagerGlobal.getWindowManagerService()
                    .getWindowInsets(displayId, token, insetsState);
            final float overrideInvScale = CompatibilityInfo.getOverrideInvertedScale();
            if (overrideInvScale != 1f) {
                insetsState.scale(overrideInvScale);
            }
            return insetsState.calculateInsets(bounds, null /* ignoringVisibilityState */,
                    isScreenRound, alwaysConsumeSystemBars, SOFT_INPUT_ADJUST_NOTHING,
                    0 /* flags */, SYSTEM_UI_FLAG_VISIBLE,
                    WindowManager.LayoutParams.INVALID_WINDOW_TYPE, windowingMode,
                    null /* typeSideMap */);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @see WindowManager#getPossibleMaximumWindowMetrics(int) */
    @NonNull
    public Set<WindowMetrics> getPossibleMaximumWindowMetrics(int displayId) {
        List<DisplayInfo> possibleDisplayInfos;
        try {
            possibleDisplayInfos = WindowManagerGlobal.getWindowManagerService()
                    .getPossibleDisplayInfo(displayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        Set<WindowMetrics> maxMetrics = new HashSet<>();
        WindowInsets windowInsets;
        DisplayInfo currentDisplayInfo;
        for (int i = 0; i < possibleDisplayInfos.size(); i++) {
            currentDisplayInfo = possibleDisplayInfos.get(i);

            // Calculate max bounds for this rotation and state.
            Rect maxBounds = new Rect(0, 0, currentDisplayInfo.logicalWidth,
                    currentDisplayInfo.logicalHeight);

            // Calculate insets for the rotated max bounds.
            final boolean isScreenRound = (currentDisplayInfo.flags & Display.FLAG_ROUND) != 0;
            // Initialize insets based upon display rotation. Note any window-provided insets
            // will not be set.
            windowInsets = getWindowInsetsFromServerForDisplay(
                    currentDisplayInfo.displayId, null /* token */,
                    new Rect(0, 0, currentDisplayInfo.getNaturalWidth(),
                            currentDisplayInfo.getNaturalHeight()), isScreenRound,
                    WINDOWING_MODE_FULLSCREEN);
            // Set the hardware-provided insets.
            windowInsets = new WindowInsets.Builder(windowInsets).setRoundedCorners(
                            currentDisplayInfo.roundedCorners)
                    .setDisplayCutout(currentDisplayInfo.displayCutout).build();

            // Multiply default density scale because WindowMetrics provide the density value with
            // the scaling factor for the Density Independent Pixel unit, which is the same unit
            // as DisplayMetrics#density
            final float density = currentDisplayInfo.logicalDensityDpi
                    * DisplayMetrics.DENSITY_DEFAULT_SCALE;
            maxMetrics.add(new WindowMetrics(maxBounds, windowInsets, density));
        }
        return maxMetrics;
    }
}

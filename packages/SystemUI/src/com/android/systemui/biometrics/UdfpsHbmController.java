package com.android.systemui.biometrics;

import android.annotation.Nullable;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.Display;

import com.android.systemui.biometrics.dagger.BiometricsBackground;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.concurrency.Execution;

import com.google.hardware.pixel.display.IDisplay;

import java.util.concurrent.Executor;

import javax.inject.Inject;

@SysUISingleton
public class UdfpsHbmController implements UdfpsDisplayModeProvider, DisplayManager.DisplayListener {
    private static final String TAG = "UdfpsHbmController";

    final Context context;
    final Execution execution;
    final Handler mainHandler;
    final Executor biometricsExecutor;
    final AuthController authController;
    final DisplayManager displayManager;

    final int displayId;
    final float peakRefreshRate;

    @Inject
    UdfpsHbmController(Context context, Execution execution,
                       @Main Handler mainHandler,
                       @BiometricsBackground Executor biometricsExecutor,
                       AuthController authController, DisplayManager displayManager) {
        this.context = context;
        this.execution = execution;
        this.mainHandler = mainHandler;
        this.biometricsExecutor = biometricsExecutor;
        this.authController = authController;
        this.displayManager = displayManager;

        displayId = context.getDisplayId();
        peakRefreshRate = getPeakRefreshRate(displayId);
    }

    static class Request {
        @Nullable Runnable onHbmEnabled;

        boolean executed;
    }

    @Nullable private Request currentRequest;

    @Override
    public void enable(@Nullable Runnable onHbmEnabled) {
        execution.assertIsMainThread();

        var hbmListener = authController.getUdfpsHbmListener();
        if (hbmListener == null) {
            Log.e(TAG, "enable: UdfpsHbmListener is null");
            return;
        }

        if (currentRequest != null) {
            Log.e(TAG, "enable: HBM is already requested");
            return;
        }

        var request = new Request();
        request.onHbmEnabled = onHbmEnabled;

        currentRequest = request;
        displayManager.registerDisplayListener(this, mainHandler);

        try {
            // requests refresh rate freeze
            hbmListener.onHbmEnabled(displayId);
            Log.i(TAG, "hbmListener.onHbmEnabled");
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }

        var display = displayManager.getDisplay(displayId);

        if (display.getState() == Display.STATE_ON && display.getRefreshRate() == peakRefreshRate) {
            // HBM can be enabled right now, don't wait for onDisplayChanged()
            executeRequest(request);
        }
    }

    private void executeRequest(Request req) {
        execution.assertIsMainThread();

        if (req.executed) {
            return;
        }
        req.executed = true;

        var callback = req.onHbmEnabled;
        if (callback != null) {
            callback.run();
        }
    }

    @Override
    public void onDisplayChanged(int displayId) {
        execution.assertIsMainThread();

        var req = currentRequest;
        if (req == null) {
            Log.w(TAG, "onDisplayChanged: currentRequest is null");
            return;
        }

        if (displayId != this.displayId) {
            Log.w(TAG, "onDisplayChanged: unknown displayId " + displayId);
            return;
        }

        var display = displayManager.getDisplay(displayId);
        int state = display.getState();
        if (state != Display.STATE_ON) {
            Log.w(TAG, "onDisplayChanged: state is not ON: " + state);
            if (req.executed) {
                Log.e(TAG, "onDisplayChanged: state changed while HBM is enabled");
            }
            return;
        }

        float refreshRate = display.getRefreshRate();
        if (refreshRate != peakRefreshRate) {
            Log.w(TAG, "onDisplayChanged: refreshRate " + refreshRate + " is not peak " + peakRefreshRate);
            if (req.executed) {
                Log.e(TAG, "onDisplayChanged: refreshRate changed while HBM is enabled");
            }
            return;
        }

        executeRequest(req);
    }

    @Override
    public void disable(@Nullable Runnable onHbmDisabled) {
        execution.assertIsMainThread();

        var req = currentRequest;
        if (req == null) {
            Log.w(TAG, "HBM is already disabled");
            return;
        }

        currentRequest = null;
        displayManager.unregisterDisplayListener(this);

        if (!req.executed) {
            Log.d(TAG, "disable: request wasn't executed yet");
            return;
        }

        Runnable onFinished = () -> {
            var hbmListener = authController.getUdfpsHbmListener();
            try {
                hbmListener.onHbmDisabled(displayId);
                Log.i(TAG, "hbmListener.onHbmDisabled");
            } catch (RemoteException|NullPointerException e) {
                Log.e(TAG, "unable to unfreeze refresh rate", e);
            }

            if (onHbmDisabled != null) {
                onHbmDisabled.run();
            }
        };

        onFinished.run();
    }

    float getPeakRefreshRate(int displayId) {
        float r = 0f;
        for (Display.Mode mode : displayManager.getDisplay(displayId).getSupportedModes()) {
            r = Math.max(r, mode.getRefreshRate());
        }
        return r;
    }

    @Override
    public void onDisplayAdded(int displayId) {
        execution.assertIsMainThread();
        Log.d(TAG, "display added, id " + displayId);
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        execution.assertIsMainThread();
        Log.d(TAG, "display removed, id " + displayId);
    }
}

package dev.brave.extension.perf;

import android.os.Build;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import java.util.List;

public class DisplayHelper {

    public static void forceMaxRefreshRateForWindow(Window window, List<?> supportedModes) {
        if (window == null || supportedModes == null || supportedModes.isEmpty()) {
            return;
        }
        try {
            forceMaxRefreshRate(window, window.getAttributes(), supportedModes);
        } catch (Throwable ignored) {}
    }

    /**
     * Resolves the active display and applies the maximum available refresh rate mode
     * matching the device's currently active screen resolution.
     */
    public static void forceMaxRefreshRate(Window window, WindowManager.LayoutParams params, List<?> supportedModes) {
        if (window == null || params == null || supportedModes == null || supportedModes.isEmpty()) {
            return;
        }

        try {
            Display display = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    display = window.getContext().getDisplay();
                } catch (Throwable ignored) {}
            }
            if (display == null) {
                display = window.getWindowManager().getDefaultDisplay();
            }

            int currentWidth = 0;
            int currentHeight = 0;
            if (display != null) {
                Display.Mode activeMode = display.getMode();
                if (activeMode != null) {
                    currentWidth = activeMode.getPhysicalWidth();
                    currentHeight = activeMode.getPhysicalHeight();
                }
            }

            int bestModeId = 0;
            float maxRefreshRate = 0.0f;

            for (Object item : supportedModes) {
                if (!(item instanceof Display.Mode)) continue;
                Display.Mode mode = (Display.Mode) item;

                int modeW = mode.getPhysicalWidth();
                int modeH = mode.getPhysicalHeight();

                // If active resolution is known, preserve it strictly (handling portrait and landscape)
                if (currentWidth > 0 && currentHeight > 0) {
                    boolean matchesResolution = (modeW == currentWidth && modeH == currentHeight)
                            || (modeW == currentHeight && modeH == currentWidth);
                    if (!matchesResolution) {
                        continue;
                    }
                }

                if (mode.getRefreshRate() > maxRefreshRate) {
                    maxRefreshRate = mode.getRefreshRate();
                    bestModeId = mode.getModeId();
                }
            }

            if (bestModeId != 0 && params.preferredDisplayModeId != bestModeId) {
                params.preferredDisplayModeId = bestModeId;
                window.setAttributes(params);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Helper to select max refresh rate mode ID for a given physical resolution,
     * using 64-bit area calculations to prevent integer overflow.
     */
    public static int getMaxRefreshRateModeId(List<Display.Mode> modes, int currentWidth, int currentHeight) {
        if (modes == null || modes.isEmpty()) {
            return 0;
        }

        int bestModeId = 0;
        float maxRefreshRate = 0.0f;
        long maxArea = 0L;

        for (Display.Mode mode : modes) {
            if (mode == null) continue;

            int w = mode.getPhysicalWidth();
            int h = mode.getPhysicalHeight();
            long area = (long) w * (long) h;

            if (currentWidth > 0 && currentHeight > 0) {
                boolean matchesCurrent = (w == currentWidth && h == currentHeight)
                        || (w == currentHeight && h == currentWidth);
                if (!matchesCurrent) continue;
            }

            float rate = mode.getRefreshRate();
            if (rate > maxRefreshRate || (rate == maxRefreshRate && area > maxArea)) {
                maxRefreshRate = rate;
                maxArea = area;
                bestModeId = mode.getModeId();
            }
        }

        return bestModeId;
    }
}

package dev.brave.extension.gestures;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.view.Window;
import org.chromium.chrome.browser.tab.Tab;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class VideoGestureOverlay {
    private static final String TAG = "BraveVideoGestures";
    private static final String OVERLAY_TAG = "brave_fullscreen_gesture_overlay";

    // Value uses WeakReference to avoid retaining Activity in View.mContext
    private static final Map<Activity, WeakReference<FullscreenGestureOverlayView>> sOverlays =
            Collections.synchronizedMap(new WeakHashMap<Activity, WeakReference<FullscreenGestureOverlayView>>());
    private static final Map<Activity, FullscreenGestureTouchHandler> sTouchHandlers =
            Collections.synchronizedMap(new WeakHashMap<Activity, FullscreenGestureTouchHandler>());
    private static final Map<Class<?>, Field> sActivityFieldCache = new ConcurrentHashMap<>();
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static volatile boolean sLifecycleRegistered = false;

    private VideoGestureOverlay() {}

    /**
     * Entry point called from Smali bytecode at Lv28;->g(Tab, FullscreenOptions)V success path.
     * Takes Object manager (p0) and Tab (p1) via invoke-static/range to avoid register overflow.
     */
    public static void attach(final Object manager, final Tab tab) {
        sMainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Activity activity = resolveActivity(manager, tab);
                    if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    ensureLifecycleTracking(activity);

                    Window window = activity.getWindow();
                    if (window == null) return;
                    ViewGroup decorView = (ViewGroup) window.getDecorView();
                    if (decorView == null) return;

                    WeakReference<FullscreenGestureOverlayView> overlayRef = sOverlays.get(activity);
                    FullscreenGestureOverlayView overlay = overlayRef != null ? overlayRef.get() : null;
                    if (overlay == null) {
                        overlay = decorView.findViewWithTag(OVERLAY_TAG);
                        if (overlay != null) {
                            sOverlays.put(activity, new WeakReference<>(overlay));
                        }
                    }

                    if (overlay == null) {
                        overlay = new FullscreenGestureOverlayView(activity);
                        overlay.setTag(OVERLAY_TAG);
                        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT);
                        decorView.addView(overlay, lp);
                        sOverlays.put(activity, new WeakReference<>(overlay));
                    }

                    // Install or update Window.Callback touch interceptor
                    Window.Callback currentCallback = window.getCallback();
                    if (currentCallback instanceof FullscreenGestureTouchHandler) {
                        FullscreenGestureTouchHandler existing = (FullscreenGestureTouchHandler) currentCallback;
                        existing.updateTab(tab);
                        existing.setDetached(false);
                        sTouchHandlers.put(activity, existing); // Always ensure registration in sTouchHandlers
                    } else {
                        FullscreenGestureTouchHandler handler = new FullscreenGestureTouchHandler(
                                currentCallback, activity, overlay, tab);
                        window.setCallback(handler);
                        sTouchHandlers.put(activity, handler);
                    }

                    Log.d(TAG, "Attached FullscreenGestureOverlay to " + activity.getClass().getSimpleName());
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to attach FullscreenGestureOverlay", t);
                }
            }
        });
    }

    /**
     * Entry point called from Smali bytecode at Lv28;->c()V method entry.
     * Takes Object manager (p0) via invoke-static/range to avoid register overflow.
     */
    public static void detach(final Object manager) {
        sMainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Activity activity = resolveActivity(manager, null);
                    if (activity != null) {
                        detachActivity(activity);
                    } else {
                        // Copy keys into a separate list to prevent ConcurrentModificationException
                        List<Activity> activities;
                        synchronized (sOverlays) {
                            activities = new ArrayList<>(sOverlays.keySet());
                        }
                        for (Activity act : activities) {
                            if (act != null) detachActivity(act);
                        }
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to detach FullscreenGestureOverlay", t);
                }
            }
        });
    }

    public static void detachActivity(final Activity activity) {
        if (activity == null) return;
        try {
            // Restore window callback
            Window window = activity.getWindow();
            if (window != null) {
                Window.Callback currentCallback = window.getCallback();
                if (currentCallback instanceof FullscreenGestureTouchHandler) {
                    window.setCallback(((FullscreenGestureTouchHandler) currentCallback).getWrapped());
                }
            }

            FullscreenGestureTouchHandler handler = sTouchHandlers.remove(activity);
            if (handler != null) {
                handler.cleanup();
            }

            WeakReference<FullscreenGestureOverlayView> overlayRef = sOverlays.remove(activity);
            FullscreenGestureOverlayView overlay = overlayRef != null ? overlayRef.get() : null;
            if (overlay == null && window != null) {
                try {
                    ViewGroup decorView = (ViewGroup) window.getDecorView();
                    if (decorView != null) {
                        overlay = decorView.findViewWithTag(OVERLAY_TAG);
                    }
                } catch (Throwable ignored) {}
            }

            if (overlay != null) {
                overlay.cleanup();
                if (overlay.getParent() instanceof ViewGroup) {
                    ((ViewGroup) overlay.getParent()).removeView(overlay);
                }
            }
            Log.d(TAG, "Detached FullscreenGestureOverlay from " + activity.getClass().getSimpleName());
        } catch (Throwable t) {
            Log.e(TAG, "Error in detachActivity", t);
        }
    }

    private static Activity resolveActivity(Object manager, Tab tab) {
        if (tab != null) {
            try {
                Context ctx = tab.getContext();
                while (ctx instanceof ContextWrapper && !(ctx instanceof Activity)) {
                    ctx = ((ContextWrapper) ctx).getBaseContext();
                }
                if (ctx instanceof Activity) {
                    return (Activity) ctx;
                }
            } catch (Throwable ignored) {}
        }

        if (manager != null) {
            Class<?> clazz = manager.getClass();
            Field cachedField = sActivityFieldCache.get(clazz);
            if (cachedField != null) {
                try {
                    Object val = cachedField.get(manager);
                    if (val instanceof Activity) return (Activity) val;
                } catch (Throwable ignored) {}
            }

            Class<?> curr = clazz;
            while (curr != null && curr != Object.class) {
                for (Field field : curr.getDeclaredFields()) {
                    if (Activity.class.isAssignableFrom(field.getType())) {
                        try {
                            field.setAccessible(true);
                            Object val = field.get(manager);
                            if (val instanceof Activity) {
                                sActivityFieldCache.put(clazz, field);
                                return (Activity) val;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                curr = curr.getSuperclass();
            }
        }
        return null;
    }

    private static void ensureLifecycleTracking(Activity activity) {
        if (sLifecycleRegistered || activity == null) return;
        try {
            Application app = activity.getApplication();
            if (app != null) {
                app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityCreated(Activity a, Bundle b) {}
                    @Override public void onActivityStarted(Activity a) {}
                    @Override public void onActivityResumed(Activity a) {}
                    @Override public void onActivityPaused(Activity a) {}
                    @Override public void onActivityStopped(Activity a) {}
                    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                    @Override
                    public void onActivityDestroyed(Activity a) {
                        detachActivity(a);
                    }
                });
                sLifecycleRegistered = true;
            }
        } catch (Throwable ignored) {}
    }
}

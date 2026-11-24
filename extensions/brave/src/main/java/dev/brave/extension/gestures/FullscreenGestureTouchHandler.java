package dev.brave.extension.gestures;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.ActionMode;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import org.chromium.chrome.browser.tab.Tab;

import java.lang.ref.WeakReference;
import java.util.List;

public class FullscreenGestureTouchHandler implements Window.Callback {
    private static final String TAG = "FullscreenTouchHandler";

    private final Window.Callback mWrapped;
    private final WeakReference<Activity> mActivityRef;
    private final WeakReference<FullscreenGestureOverlayView> mOverlayViewRef;
    private volatile WeakReference<Tab> mTabRef;
    private final int mTouchSlop;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mDetached = false;

    private static final int MODE_NONE = 0;
    private static final int MODE_BRIGHTNESS = 1;
    private static final int MODE_VOLUME = 2;
    private static final int MODE_SEEK = 3;
    private static final int MODE_LONG_PRESS_2X = 4;

    private static final long LONG_PRESS_TIMEOUT_MS = 450L;
    private static final long HUD_HIDE_DELAY_MS = 600L;

    private float mDownX;
    private float mDownY;
    private int mActivePointerId = -1;
    private boolean mIsDragging;
    private int mGestureMode = MODE_NONE;
    private float mInitialBrightness = -1f;
    private boolean mBrightnessAdjusted = false; // Session-level flag: only reset in cleanup()
    private int mInitialVolume = 0;
    private int mBaseSeekSeconds = 0; // Tracks accumulated seek across pointer handoffs
    private int mAccumulatedSeekSeconds = 0;
    private boolean mHasSentCancelToChildren = false;

    private final Runnable mLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mIsDragging && mGestureMode == MODE_NONE && !mDetached) {
                mGestureMode = MODE_LONG_PRESS_2X;
                Activity activity = mActivityRef.get();
                FullscreenGestureOverlayView overlay = mOverlayViewRef.get();

                // Send synthetic cancel to underlying controls so they don't stay in a pressed state
                if (!mHasSentCancelToChildren && mWrapped != null) {
                    long now = SystemClock.uptimeMillis();
                    MotionEvent cancelEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, mDownX, mDownY, 0);
                    mWrapped.dispatchTouchEvent(cancelEvent);
                    cancelEvent.recycle();
                    mHasSentCancelToChildren = true;
                }

                if (activity != null) {
                    try {
                        activity.getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    } catch (Throwable ignored) {}
                }
                if (overlay != null) {
                    overlay.showTopHud("2x \u00BB");
                }
                executeVideoScript(
                        "(() => {"
                                + "  function getTarget() {"
                                + "    if (document.fullscreenElement) {"
                                + "      if (document.fullscreenElement.tagName === 'VIDEO') return document.fullscreenElement;"
                                + "      const v = document.fullscreenElement.querySelector('video');"
                                + "      if (v) return v;"
                                + "      if (document.fullscreenElement.shadowRoot) {"
                                + "        const sv = document.fullscreenElement.shadowRoot.querySelector('video');"
                                + "        if (sv) return sv;"
                                + "      }"
                                + "    }"
                                + "    return document.querySelector('video');"
                                + "  }"
                                + "  const v = getTarget();"
                                + "  if (v) {"
                                + "    if (window._brave_prev_rate === undefined) { window._brave_prev_rate = v.playbackRate; }"
                                + "    v.playbackRate = 2.0;"
                                + "  }"
                                + "})();"
                );
            }
        }
    };

    public FullscreenGestureTouchHandler(
            Window.Callback wrapped,
            Activity activity,
            FullscreenGestureOverlayView overlayView,
            Tab tab) {
        this.mWrapped = wrapped;
        this.mActivityRef = new WeakReference<>(activity);
        this.mOverlayViewRef = new WeakReference<>(overlayView);
        this.mTabRef = new WeakReference<>(tab);
        this.mTouchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
    }

    public Window.Callback getWrapped() {
        return mWrapped;
    }

    public void updateTab(Tab tab) {
        this.mTabRef = new WeakReference<>(tab);
    }

    public void setDetached(boolean detached) {
        this.mDetached = detached;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (mDetached) {
            return mWrapped != null ? mWrapped.dispatchTouchEvent(event) : false;
        }

        Activity activity = mActivityRef.get();
        FullscreenGestureOverlayView overlay = mOverlayViewRef.get();
        if (activity == null || overlay == null) {
            return mWrapped != null ? mWrapped.dispatchTouchEvent(event) : false;
        }

        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                mActivePointerId = event.getPointerId(0);
                mDownX = event.getX(0);
                mDownY = event.getY(0);
                mIsDragging = false;
                mGestureMode = MODE_NONE;
                mHasSentCancelToChildren = false;
                mBaseSeekSeconds = 0;
                mAccumulatedSeekSeconds = 0;

                // Read current brightness and clamp safely to [0.01, 1.0]
                float b = activity.getWindow().getAttributes().screenBrightness;
                if (b >= 0.0f) {
                    mInitialBrightness = Math.max(0.01f, Math.min(1.0f, b));
                } else {
                    try {
                        int sys = Settings.System.getInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
                        mInitialBrightness = Math.max(0.01f, Math.min(1.0f, sys / 255.0f));
                    } catch (Throwable t) {
                        mInitialBrightness = 0.5f;
                    }
                }

                // Read current volume
                AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    mInitialVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                }

                mHandler.removeCallbacks(mLongPressRunnable);
                mHandler.postDelayed(mLongPressRunnable, LONG_PRESS_TIMEOUT_MS);

                // Normal dispatch: pass ACTION_DOWN to underlying views so native buttons get ready
                return mWrapped != null ? mWrapped.dispatchTouchEvent(event) : false;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                // Multi-finger touch (e.g. pinch-to-zoom) must cancel long-press 2x timer
                mHandler.removeCallbacks(mLongPressRunnable);
                if (mIsDragging || mGestureMode == MODE_LONG_PRESS_2X) {
                    return true;
                }
                return mWrapped != null ? mWrapped.dispatchTouchEvent(event) : false;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                int pointerIndex = event.getActionIndex();
                int pointerId = event.getPointerId(pointerIndex);
                if (pointerId == mActivePointerId) {
                    int newIndex = (pointerIndex == 0) ? 1 : 0;
                    mActivePointerId = event.getPointerId(newIndex);
                    mDownX = event.getX(newIndex);
                    mDownY = event.getY(newIndex);

                    // Re-base values to avoid sudden jumps on finger handoff
                    if (mGestureMode == MODE_BRIGHTNESS) {
                        float b = activity.getWindow().getAttributes().screenBrightness;
                        if (b >= 0.0f) mInitialBrightness = Math.max(0.01f, Math.min(1.0f, b));
                    } else if (mGestureMode == MODE_VOLUME) {
                        AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
                        if (am != null) mInitialVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                    } else if (mGestureMode == MODE_SEEK) {
                        mBaseSeekSeconds = mAccumulatedSeekSeconds;
                    }
                }
                if (mIsDragging || mGestureMode == MODE_LONG_PRESS_2X) {
                    return true;
                }
                return mWrapped != null ? mWrapped.dispatchTouchEvent(event) : false;
            }

            case MotionEvent.ACTION_MOVE: {
                int pointerIndex = event.findPointerIndex(mActivePointerId);
                if (pointerIndex < 0) {
                    pointerIndex = 0;
                    mActivePointerId = event.getPointerId(0);
                }
                float curX = event.getX(pointerIndex);
                float curY = event.getY(pointerIndex);
                float dx = curX - mDownX;
                float dy = curY - mDownY;
                float dist = (float) Math.hypot(dx, dy);

                if (!mIsDragging && dist > mTouchSlop) {
                    mHandler.removeCallbacks(mLongPressRunnable);
                    if (mGestureMode == MODE_NONE) {
                        mIsDragging = true;
                        int width = activity.getWindow().getDecorView().getWidth();
                        if (Math.abs(dx) > Math.abs(dy)) {
                            mGestureMode = MODE_SEEK;
                        } else {
                            if (mDownX < width / 2.0f) {
                                mGestureMode = MODE_BRIGHTNESS;
                            } else {
                                mGestureMode = MODE_VOLUME;
                            }
                        }

                        // Cancel touch in underlying video controls so they don't trigger accidental clicks
                        if (!mHasSentCancelToChildren && mWrapped != null) {
                            long now = SystemClock.uptimeMillis();
                            MotionEvent cancelEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, curX, curY, 0);
                            mWrapped.dispatchTouchEvent(cancelEvent);
                            cancelEvent.recycle();
                            mHasSentCancelToChildren = true;
                        }
                    }
                }

                if (mIsDragging) {
                    int viewWidth = Math.max(1, activity.getWindow().getDecorView().getWidth());
                    int viewHeight = Math.max(1, activity.getWindow().getDecorView().getHeight());

                    if (mGestureMode == MODE_BRIGHTNESS) {
                        float delta = -dy / (float) viewHeight;
                        float newBrightness = Math.max(0.01f, Math.min(1.0f, mInitialBrightness + delta));
                        WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
                        lp.screenBrightness = newBrightness;
                        activity.getWindow().setAttributes(lp);
                        mBrightnessAdjusted = true;
                        overlay.showCenterHud("Brightness: " + Math.round(newBrightness * 100f) + "%");
                        return true;
                    } else if (mGestureMode == MODE_VOLUME) {
                        AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
                        if (am != null) {
                            int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                            float delta = (-dy / (float) viewHeight) * maxVol * 1.2f;
                            int newVol = Math.max(0, Math.min(maxVol, Math.round(mInitialVolume + delta)));
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0);
                            int pct = Math.round(((float) newVol / (float) Math.max(1, maxVol)) * 100f);
                            overlay.showCenterHud("Volume: " + pct + "%");
                        }
                        return true;
                    } else if (mGestureMode == MODE_SEEK) {
                        float seekFactor = 90.0f;
                        int currentDelta = Math.round((dx / (float) viewWidth) * seekFactor);
                        mAccumulatedSeekSeconds = mBaseSeekSeconds + currentDelta;
                        String prefix = (mAccumulatedSeekSeconds >= 0) ? "+" : "";
                        overlay.showCenterHud(prefix + mAccumulatedSeekSeconds + "s");
                        return true;
                    }
                }

                if (mGestureMode == MODE_LONG_PRESS_2X) {
                    return true;
                }

                return mWrapped != null ? mWrapped.dispatchTouchEvent(event) : false;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                mHandler.removeCallbacks(mLongPressRunnable);

                if (mGestureMode == MODE_LONG_PRESS_2X) {
                    restorePlaybackSpeed();
                    overlay.hideTopHud();
                    mGestureMode = MODE_NONE;
                    mIsDragging = false;
                    return true;
                }

                if (mGestureMode == MODE_SEEK) {
                    if (mAccumulatedSeekSeconds != 0) {
                        applySeek(mAccumulatedSeekSeconds);
                    }
                    overlay.hideCenterHud(HUD_HIDE_DELAY_MS);
                    mGestureMode = MODE_NONE;
                    mIsDragging = false;
                    return true;
                }

                if (mGestureMode == MODE_BRIGHTNESS || mGestureMode == MODE_VOLUME) {
                    overlay.hideCenterHud(HUD_HIDE_DELAY_MS);
                    mGestureMode = MODE_NONE;
                    mIsDragging = false;
                    return true;
                }

                mGestureMode = MODE_NONE;
                mIsDragging = false;
                // Pure single tap: pass through to underlying view hierarchy
                return mWrapped != null ? mWrapped.dispatchTouchEvent(event) : false;
            }
        }

        return mWrapped != null ? mWrapped.dispatchTouchEvent(event) : false;
    }

    private void applySeek(int seconds) {
        String script = "(() => {"
                + "  function getTarget() {"
                + "    if (document.fullscreenElement) {"
                + "      if (document.fullscreenElement.tagName === 'VIDEO') return document.fullscreenElement;"
                + "      const v = document.fullscreenElement.querySelector('video');"
                + "      if (v) return v;"
                + "      if (document.fullscreenElement.shadowRoot) {"
                + "        const sv = document.fullscreenElement.shadowRoot.querySelector('video');"
                + "        if (sv) return sv;"
                + "      }"
                + "    }"
                + "    return document.querySelector('video');"
                + "  }"
                + "  const v = getTarget();"
                + "  if (v) {"
                + "    v.currentTime = Math.max(0, Math.min(v.duration || Infinity, v.currentTime + (" + seconds + ")));"
                + "  }"
                + "})();";
        executeVideoScript(script);
    }

    private void restorePlaybackSpeed() {
        String script = "(() => {"
                + "  function getTarget() {"
                + "    if (document.fullscreenElement) {"
                + "      if (document.fullscreenElement.tagName === 'VIDEO') return document.fullscreenElement;"
                + "      const v = document.fullscreenElement.querySelector('video');"
                + "      if (v) return v;"
                + "      if (document.fullscreenElement.shadowRoot) {"
                + "        const sv = document.fullscreenElement.shadowRoot.querySelector('video');"
                + "        if (sv) return sv;"
                + "      }"
                + "    }"
                + "    return document.querySelector('video');"
                + "  }"
                + "  const v = getTarget();"
                + "  if (v && window._brave_prev_rate !== undefined) {"
                + "    v.playbackRate = window._brave_prev_rate;"
                + "    delete window._brave_prev_rate;"
                + "  }"
                + "})();";
        executeVideoScript(script);
    }

    private void executeVideoScript(String script) {
        Tab tab = mTabRef != null ? mTabRef.get() : null;
        if (tab != null) {
            ChromiumJsBridge.executeScript(tab, script);
        }
    }

    public void cleanup() {
        int prevMode = mGestureMode;
        mDetached = true;
        mGestureMode = MODE_NONE;
        mIsDragging = false;
        mHandler.removeCallbacks(mLongPressRunnable);
        if (prevMode == MODE_LONG_PRESS_2X) {
            restorePlaybackSpeed();
        }
        Activity activity = mActivityRef.get();
        if (activity != null && mBrightnessAdjusted) {
            try {
                WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                activity.getWindow().setAttributes(lp);
            } catch (Throwable ignored) {}
            mBrightnessAdjusted = false;
        }
    }

    // Delegated Window.Callback implementations
    @Override public boolean dispatchKeyEvent(KeyEvent event) { return mWrapped != null ? mWrapped.dispatchKeyEvent(event) : false; }
    @Override public boolean dispatchKeyShortcutEvent(KeyEvent event) { return mWrapped != null ? mWrapped.dispatchKeyShortcutEvent(event) : false; }
    @Override public boolean dispatchTrackballEvent(MotionEvent event) { return mWrapped != null ? mWrapped.dispatchTrackballEvent(event) : false; }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) { return mWrapped != null ? mWrapped.dispatchGenericMotionEvent(event) : false; }
    @Override public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) { return mWrapped != null ? mWrapped.dispatchPopulateAccessibilityEvent(event) : false; }
    @Override public View onCreatePanelView(int featureId) { return mWrapped != null ? mWrapped.onCreatePanelView(featureId) : null; }
    @Override public boolean onCreatePanelMenu(int featureId, Menu menu) { return mWrapped != null ? mWrapped.onCreatePanelMenu(featureId, menu) : false; }
    @Override public boolean onPreparePanel(int featureId, View view, Menu menu) { return mWrapped != null ? mWrapped.onPreparePanel(featureId, view, menu) : false; }
    @Override public boolean onMenuOpened(int featureId, Menu menu) { return mWrapped != null ? mWrapped.onMenuOpened(featureId, menu) : false; }
    @Override public boolean onMenuItemSelected(int featureId, MenuItem item) { return mWrapped != null ? mWrapped.onMenuItemSelected(featureId, item) : false; }
    @Override public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) { if (mWrapped != null) mWrapped.onWindowAttributesChanged(attrs); }
    @Override public void onContentChanged() { if (mWrapped != null) mWrapped.onContentChanged(); }
    @Override public void onWindowFocusChanged(boolean hasFocus) { if (mWrapped != null) mWrapped.onWindowFocusChanged(hasFocus); }
    @Override public void onAttachedToWindow() { if (mWrapped != null) mWrapped.onAttachedToWindow(); }
    @Override public void onDetachedFromWindow() { if (mWrapped != null) mWrapped.onDetachedFromWindow(); }
    @Override public void onPanelClosed(int featureId, Menu menu) { if (mWrapped != null) mWrapped.onPanelClosed(featureId, menu); }
    @Override public boolean onSearchRequested() { return mWrapped != null ? mWrapped.onSearchRequested() : false; }
    @Override public boolean onSearchRequested(SearchEvent searchEvent) {
        if (Build.VERSION.SDK_INT >= 23 && mWrapped != null) return mWrapped.onSearchRequested(searchEvent);
        return mWrapped != null ? mWrapped.onSearchRequested() : false;
    }
    @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) { return mWrapped != null ? mWrapped.onWindowStartingActionMode(callback) : null; }
    @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
        if (Build.VERSION.SDK_INT >= 23 && mWrapped != null) return mWrapped.onWindowStartingActionMode(callback, type);
        return mWrapped != null ? mWrapped.onWindowStartingActionMode(callback) : null;
    }
    @Override public void onActionModeStarted(ActionMode mode) { if (mWrapped != null) mWrapped.onActionModeStarted(mode); }
    @Override public void onActionModeFinished(ActionMode mode) { if (mWrapped != null) mWrapped.onActionModeFinished(mode); }

    @Override
    public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> data, Menu menu, int deviceId) {
        if (Build.VERSION.SDK_INT >= 24 && mWrapped != null) {
            mWrapped.onProvideKeyboardShortcuts(data, menu, deviceId);
        }
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        if (Build.VERSION.SDK_INT >= 26 && mWrapped != null) {
            mWrapped.onPointerCaptureChanged(hasCapture);
        }
    }
}

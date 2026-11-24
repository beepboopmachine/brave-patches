package dev.brave.extension.media;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import dev.brave.extension.core.ActiveTabTracker;
import dev.brave.extension.core.JsInjectionHub;

/**
 * Native toolbar integration hook for Brave Browser.
 * Thread-safe, multi-window aware, dynamically resolves view hierarchy without fragile hardcoded virtual IDs.
 */
public final class ToolbarHook {
    private static final String TAG = "Brave_ToolbarHook";

    private static final int ID_TOOLBAR_BUTTONS = 0x7f0110f9;
    private static final int ID_TAB_SWITCHER_BUTTON = 0x7f011042;
    private static final int CUSTOM_BUTTON_TAG_KEY = 0x7f019999;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static class ToolbarViewHolder {
        final WeakReference<FrameLayout> container;
        final WeakReference<ImageView> icon;
        final WeakReference<TextView> badge;

        ToolbarViewHolder(FrameLayout container, ImageView icon, TextView badge) {
            this.container = new WeakReference<>(container);
            this.icon = new WeakReference<>(icon);
            this.badge = new WeakReference<>(badge);
        }
    }

    // Multi-window map: maps each toolbar ViewGroup to its custom button holder
    private static final Map<ViewGroup, ToolbarViewHolder> TOOLBAR_MAP = new WeakHashMap<>();
    private static volatile ColorStateList sCurrentTint = null;
    private static volatile boolean sIsUrlFocused = false;
    private static final java.util.concurrent.atomic.AtomicBoolean sInitialized = new java.util.concurrent.atomic.AtomicBoolean(false);

    static {
        ensureInitialized();
    }

    public static void ensureInitialized() {
        if (!sInitialized.compareAndSet(false, true)) {
            return;
        }

        JsInjectionHub.registerPersistentBridge("BraveMediaBridge", new MediaBridge());
        JsInjectionHub.registerScript(
                "media_sniffer",
                JsInjectionHub.InjectionTiming.ON_NAVIGATION_FINISH,
                MediaSnifferJs.SNIFFER_SCRIPT
        );

        MediaRegistry.addListener((tabId, mediaCount, imageCount) -> {
            int activeId = ActiveTabTracker.getActiveTabId();
            android.util.Log.i(TAG, "Media count changed: tabId=" + tabId + ", activeId=" + activeId + ", count=" + (mediaCount + imageCount));
            if (activeId < 0 || tabId == activeId || tabId == 0) {
                updateBadgeCount(mediaCount + imageCount);
            }
        });
    }

    private ToolbarHook() {}

    /**
     * Called from ToolbarPhone.onFinishInflate()V.
     */
    public static void onToolbarInflated(final ViewGroup toolbar) {
        if (toolbar == null) return;

        Runnable attachTask = () -> {
            try {
                // Dynamically find toolbar buttons parent
                View tabSwitcher = toolbar.findViewById(ID_TAB_SWITCHER_BUTTON);
                ViewGroup buttonsContainer = null;
                if (tabSwitcher != null && tabSwitcher.getParent() instanceof ViewGroup) {
                    buttonsContainer = (ViewGroup) tabSwitcher.getParent();
                }
                if (buttonsContainer == null) {
                    buttonsContainer = toolbar.findViewById(ID_TOOLBAR_BUTTONS);
                }
                if (buttonsContainer == null) {
                    buttonsContainer = toolbar;
                }

                // Check if button already exists in this toolbar
                View existing = buttonsContainer.findViewWithTag("brave_media_download_button");
                if (existing != null) {
                    return;
                }

                Context ctx = toolbar.getContext();
                ToolbarViewHolder holder = createMediaButton(ctx);
                FrameLayout customButton = holder.container.get();
                if (customButton == null) return;

                customButton.setTag("brave_media_download_button");
                customButton.setVisibility(sIsUrlFocused ? View.GONE : View.VISIBLE);

                int targetIndex = (tabSwitcher != null) ? buttonsContainer.indexOfChild(tabSwitcher) : -1;
                if (targetIndex >= 0) {
                    buttonsContainer.addView(customButton, targetIndex);
                } else {
                    buttonsContainer.addView(customButton);
                }

                TOOLBAR_MAP.put(toolbar, holder);

                if (sCurrentTint != null) {
                    ImageView icon = holder.icon.get();
                    if (icon != null) {
                        icon.setImageTintList(sCurrentTint);
                    }
                }

                int activeTabId = ActiveTabTracker.getActiveTabId();
                if (activeTabId >= 0) {
                    int totalCount = MediaRegistry.getMediaCount(activeTabId) + MediaRegistry.getImageCount(activeTabId);
                    updateBadgeCount(totalCount);
                }

            } catch (Throwable t) {
                android.util.Log.e(TAG, "Error inserting custom media button into toolbar", t);
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            attachTask.run();
        } else {
            toolbar.post(attachTask);
        }
    }

    /**
     * Called from ToolbarPhone.dispatchDraw(Canvas)V during GPU texture snapshot capture.
     * Note: At the hook site (inside ToolbarPhone.dispatchDraw), the Canvas is ALREADY translated
     * into ToolbarPhone.I0 (mToolbarButtonsContainer)'s coordinate space! Translating relative
     * to our button's parent container draws our download button + badge at its exact pixel
     * position inside Chromium's composited toolbar bitmap (zero pop-in on scroll reveal).
     */
    public static void drawCustomButtonForSnapshot(final ViewGroup toolbar, final android.graphics.Canvas canvas) {
        if (toolbar == null || canvas == null) return;
        try {
            ToolbarViewHolder holder = TOOLBAR_MAP.get(toolbar);
            if (holder == null) return;
            FrameLayout btn = holder.container.get();
            if (btn == null || btn.getVisibility() != View.VISIBLE) return;
            if (btn.getWidth() <= 0 || btn.getHeight() <= 0) return;

            View buttonsContainer = toolbar.findViewById(ID_TOOLBAR_BUTTONS);
            if (buttonsContainer == null && btn.getParent() instanceof View) {
                buttonsContainer = (View) btn.getParent();
            }

            float dx = 0f;
            float dy = 0f;
            View current = btn;
            int guard = 0;
            while (current != null && current != buttonsContainer && current != toolbar && guard < 8) {
                dx += current.getLeft() + current.getTranslationX();
                dy += current.getTop() + current.getTranslationY();
                android.view.ViewParent parent = current.getParent();
                if (parent instanceof View) {
                    current = (View) parent;
                } else {
                    break;
                }
                guard++;
            }

            canvas.save();
            canvas.translate(dx, dy);
            btn.draw(canvas);
            canvas.restore();
        } catch (Throwable ignored) {}
    }

    /**
     * Called from ToggleTabStackButton.i(I)V after ColorStateList is resolved.
     */
    public static void onTintChanged(final ColorStateList tint) {
        sCurrentTint = tint;
        Runnable task = () -> {
            for (ToolbarViewHolder holder : TOOLBAR_MAP.values()) {
                ImageView icon = holder.icon.get();
                if (icon != null && tint != null) {
                    icon.setImageTintList(tint);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            MAIN_HANDLER.post(task);
        }
    }

    /**
     * Called from ToolbarPhone.d3(Z)V on Omnibox focus animation.
     */
    public static void onUrlFocusChanged(final boolean isFocused) {
        sIsUrlFocused = isFocused;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyUrlFocus(isFocused);
        } else {
            MAIN_HANDLER.post(() -> applyUrlFocus(isFocused));
        }
    }

    private static void applyUrlFocus(boolean isFocused) {
        for (ToolbarViewHolder holder : TOOLBAR_MAP.values()) {
            FrameLayout btn = holder.container.get();
            if (btn != null) {
                btn.setVisibility(isFocused ? View.GONE : View.VISIBLE);
            }
        }
    }

    public static void updateBadgeCount(final int count) {
        Runnable task = () -> {
            for (ToolbarViewHolder holder : TOOLBAR_MAP.values()) {
                TextView badge = holder.badge.get();
                if (badge != null) {
                    if (count > 0) {
                        badge.setText(count > 99 ? "99+" : String.valueOf(count));
                        badge.setVisibility(View.VISIBLE);
                    } else {
                        badge.setVisibility(View.GONE);
                    }
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            MAIN_HANDLER.post(task);
        }
    }

    private static ToolbarViewHolder createMediaButton(final Context context) {
        int dp48 = dpToPx(context, 48);
        int dp24 = dpToPx(context, 24);

        FrameLayout container = new FrameLayout(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp48, dp48);
        lp.gravity = Gravity.CENTER_VERTICAL;
        container.setLayoutParams(lp);
        container.setContentDescription("Media & HLS Downloader");

        TypedValue outValue = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)) {
            container.setBackgroundResource(outValue.resourceId);
        }

        ImageView iconView = new ImageView(context);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp24, dp24);
        iconLp.gravity = Gravity.CENTER;
        iconView.setLayoutParams(iconLp);
        iconView.setImageDrawable(new DownloadIconDrawable(context));
        container.addView(iconView);

        TextView badgeView = new TextView(context);
        int badgeSize = dpToPx(context, 16);
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                badgeSize
        );
        badgeLp.gravity = Gravity.TOP | Gravity.END;
        badgeLp.setMargins(0, dpToPx(context, 6), dpToPx(context, 6), 0);
        badgeView.setLayoutParams(badgeLp);
        badgeView.setMinWidth(badgeSize);
        badgeView.setPadding(dpToPx(context, 4), 0, dpToPx(context, 4), 0);
        badgeView.setTextColor(Color.WHITE);
        badgeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        badgeView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        badgeView.setGravity(Gravity.CENTER);
        badgeView.setVisibility(View.GONE);

        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.RECTANGLE);
        badgeBg.setCornerRadius(dpToPx(context, 8));
        badgeBg.setColor(Color.parseColor("#FF5500")); // Brave Orange
        badgeView.setBackground(badgeBg);

        container.addView(badgeView);

        container.setOnClickListener(v -> {
            Activity act = getActivityFromContext(v.getContext());
            if (act != null) {
                org.chromium.chrome.browser.tab.Tab activeTab = ActiveTabTracker.getActiveTab();
                if (activeTab != null && activeTab.getWebContents() != null) {
                    JsInjectionHub.executeScript(activeTab, activeTab.getWebContents(), "try { if (window.__brave_scan_media__) window.__brave_scan_media__(); } catch(e) {}");
                }
                MediaPickerBottomSheet.show(act);
            }
        });

        return new ToolbarViewHolder(container, iconView, badgeView);
    }

    private static Activity getActivityFromContext(Context ctx) {
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof Activity) return (Activity) ctx;
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    private static int dpToPx(Context ctx, int dp) {
        float density = ctx.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    public static final class DownloadIconDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint mPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.DITHER_FLAG);
        private final android.graphics.Path mPath = new android.graphics.Path();
        private ColorStateList mTintList;
        private int mCurrentColor = Color.WHITE;
        private final float mStrokeWidth;

        public DownloadIconDrawable(Context context) {
            float density = context.getResources().getDisplayMetrics().density;
            mStrokeWidth = 2.0f * density;
            mPaint.setStyle(android.graphics.Paint.Style.STROKE);
            mPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            mPaint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            mPaint.setStrokeWidth(mStrokeWidth);
            mPaint.setColor(mCurrentColor);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            float w = bounds.width();
            float h = bounds.height();
            if (w <= 0 || h <= 0) return;

            float left = bounds.left;
            float top = bounds.top;

            mPath.reset();

            // 1. Arrow vertical shaft
            mPath.moveTo(left + w * 0.50f, top + h * 0.18f);
            mPath.lineTo(left + w * 0.50f, top + h * 0.58f);

            // 2. Arrowhead pointing down
            mPath.moveTo(left + w * 0.29f, top + h * 0.39f);
            mPath.lineTo(left + w * 0.50f, top + h * 0.59f);
            mPath.lineTo(left + w * 0.71f, top + h * 0.39f);

            // 3. Open tray below arrow with smooth rounded bottom corners
            float trayLeft = left + w * 0.21f;
            float trayRight = left + w * 0.79f;
            float trayTop = top + h * 0.58f;
            float trayBottom = top + h * 0.79f;
            float cornerRadius = w * 0.07f;

            mPath.moveTo(trayLeft, trayTop);
            mPath.lineTo(trayLeft, trayBottom - cornerRadius);
            mPath.quadTo(trayLeft, trayBottom, trayLeft + cornerRadius, trayBottom);
            mPath.lineTo(trayRight - cornerRadius, trayBottom);
            mPath.quadTo(trayRight, trayBottom, trayRight, trayBottom - cornerRadius);
            mPath.lineTo(trayRight, trayTop);

            canvas.drawPath(mPath, mPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            mPaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public void setTintList(ColorStateList tint) {
            mTintList = tint;
            updateTint();
        }

        @Override
        public void setTintMode(android.graphics.PorterDuff.Mode tintMode) {
            updateTint();
        }

        @Override
        protected boolean onStateChange(int[] state) {
            if (mTintList != null) {
                updateTint();
                return true;
            }
            return super.onStateChange(state);
        }

        @Override
        public boolean isStateful() {
            return mTintList != null && mTintList.isStateful();
        }

        private void updateTint() {
            if (mTintList != null) {
                int color = mTintList.getColorForState(getState(), mTintList.getDefaultColor());
                if (color != mCurrentColor) {
                    mCurrentColor = color;
                    mPaint.setColor(mCurrentColor);
                    invalidateSelf();
                }
            }
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return Math.round(mStrokeWidth * 12);
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.round(mStrokeWidth * 12);
        }
    }
}

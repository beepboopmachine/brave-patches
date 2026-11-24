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

    static {
        ensureInitialized();
    }

    public static synchronized void ensureInitialized() {
        JsInjectionHub.registerPersistentBridge("BraveMediaBridge", new MediaBridge());
        JsInjectionHub.registerScript(
                "media_sniffer",
                JsInjectionHub.InjectionTiming.ON_NAVIGATION_FINISH,
                MediaSnifferJs.SNIFFER_SCRIPT
        );

        MediaRegistry.addListener((tabId, mediaCount, imageCount) -> {
            int activeId = ActiveTabTracker.getActiveTabId();
            if (activeId > 0 && tabId == activeId) {
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

        toolbar.post(() -> {
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
                if (activeTabId > 0) {
                    int totalCount = MediaRegistry.getMediaCount(activeTabId) + MediaRegistry.getImageCount(activeTabId);
                    updateBadgeCount(totalCount);
                }

            } catch (Throwable t) {
                android.util.Log.e(TAG, "Error inserting custom media button into toolbar", t);
            }
        });
    }

    /**
     * Called from ToggleTabStackButton.i(I)V after ColorStateList is resolved.
     */
    public static void onTintChanged(final ColorStateList tint) {
        sCurrentTint = tint;
        MAIN_HANDLER.post(() -> {
            for (ToolbarViewHolder holder : TOOLBAR_MAP.values()) {
                ImageView icon = holder.icon.get();
                if (icon != null && tint != null) {
                    icon.setImageTintList(tint);
                }
            }
        });
    }

    /**
     * Called from ToolbarPhone.d3(Z)V on Omnibox focus animation.
     */
    public static void onUrlFocusChanged(final boolean isFocused) {
        sIsUrlFocused = isFocused;
        MAIN_HANDLER.post(() -> {
            for (ToolbarViewHolder holder : TOOLBAR_MAP.values()) {
                FrameLayout btn = holder.container.get();
                if (btn != null) {
                    btn.setVisibility(isFocused ? View.GONE : View.VISIBLE);
                }
            }
        });
    }

    public static void updateBadgeCount(final int count) {
        MAIN_HANDLER.post(() -> {
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
        });
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
        iconView.setImageResource(android.R.drawable.ic_menu_save); // Static tint-friendly icon
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
}

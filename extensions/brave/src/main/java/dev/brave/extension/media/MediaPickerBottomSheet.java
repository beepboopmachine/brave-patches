package dev.brave.extension.media;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import dev.brave.extension.core.ActiveTabTracker;
import dev.brave.extension.core.PatchPrefs;

public final class MediaPickerBottomSheet {
    private static final String TAG = "Brave_MediaPicker";

    public static void show(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) return;

        final int tabId = ActiveTabTracker.getActiveTabId();
        final List<MediaItem> videos = MediaRegistry.getMediaForTab(tabId);
        final List<MediaItem> images = MediaRegistry.getImagesForTab(tabId);

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        boolean isDark = isDarkTheme(activity);
        int bgColor = isDark ? Color.parseColor("#1F1F1F") : Color.WHITE;
        int textColor = isDark ? Color.parseColor("#E3E3E3") : Color.parseColor("#1F1F1F");
        int subTextColor = isDark ? Color.parseColor("#A0A0A0") : Color.parseColor("#666666");
        int cardBgColor = isDark ? Color.parseColor("#2C2C2C") : Color.parseColor("#F4F4F6");
        int accentColor = Color.parseColor("#FF5500");

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 20));

        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(bgColor);
        float r = dp(activity, 16);
        rootBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(rootBg);

        // Drag handle
        View handle = new View(activity);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dp(activity, 14);
        handle.setLayoutParams(handleLp);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(isDark ? Color.parseColor("#444444") : Color.parseColor("#CCCCCC"));
        handleBg.setCornerRadius(dp(activity, 2));
        handle.setBackground(handleBg);
        root.addView(handle);

        // Header Title
        TextView titleView = new TextView(activity);
        titleView.setText("Brave Media Downloader");
        titleView.setTextColor(textColor);
        titleView.setTextSize(18);
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(titleView);

        // Tab Selector Bar
        LinearLayout tabContainer = new LinearLayout(activity);
        tabContainer.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        tabLp.topMargin = dp(activity, 12);
        tabLp.bottomMargin = dp(activity, 12);
        tabContainer.setLayoutParams(tabLp);

        Button tabVideos = createTabButton(activity, "Videos / Streams (" + videos.size() + ")", true, accentColor, textColor, cardBgColor);
        Button tabImages = createTabButton(activity, "Images (" + images.size() + ")", false, accentColor, textColor, cardBgColor);

        tabContainer.addView(tabVideos);
        tabContainer.addView(tabImages);
        root.addView(tabContainer);

        // Compute flexible max height based on screen height
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int maxContentHeight = (int) (dm.heightPixels * 0.55f);

        // Content Area Frame
        FrameLayout contentArea = new FrameLayout(activity);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                maxContentHeight
        );
        contentArea.setLayoutParams(contentLp);

        // Videos Scroll View
        ScrollView videoScrollView = new ScrollView(activity);
        LinearLayout videoList = new LinearLayout(activity);
        videoList.setOrientation(LinearLayout.VERTICAL);
        videoScrollView.addView(videoList);
        contentArea.addView(videoScrollView);

        // Images Container
        LinearLayout imagesContainer = new LinearLayout(activity);
        imagesContainer.setOrientation(LinearLayout.VERTICAL);
        imagesContainer.setVisibility(View.GONE);

        // Conversion Toggle Bar & Sticky Bulk Button
        LinearLayout convBar = new LinearLayout(activity);
        convBar.setOrientation(LinearLayout.HORIZONTAL);
        convBar.setGravity(Gravity.CENTER_VERTICAL);
        convBar.setPadding(0, 0, 0, dp(activity, 6));

        final CheckBox convPng = new CheckBox(activity);
        convPng.setText("Convert WebP to PNG");
        convPng.setTextColor(textColor);
        convPng.setTextSize(12);
        boolean currentAutoConv = PatchPrefs.getBoolean(activity, PatchPrefs.KEY_AUTO_CONVERT_WEBP, true);
        convPng.setChecked(currentAutoConv);
        convPng.setOnCheckedChangeListener((btn, isChecked) -> {
            PatchPrefs.setBoolean(activity, PatchPrefs.KEY_AUTO_CONVERT_WEBP, isChecked);
        });
        convBar.addView(convPng);
        imagesContainer.addView(convBar);

        final Set<MediaItem> selectedImages = new HashSet<>();
        final Button bulkBtn = new Button(activity);
        bulkBtn.setText(images.isEmpty() ? "No Images" : "Download All (" + images.size() + " Images)");
        styleButton(bulkBtn, accentColor, Color.WHITE, dp(activity, 8));
        bulkBtn.setEnabled(!images.isEmpty());
        bulkBtn.setOnClickListener(v -> {
            List<MediaItem> targetImages = selectedImages.isEmpty() ? images : new ArrayList<>(selectedImages);
            startBulkImageDownload(activity, targetImages, convPng.isChecked());
            dialog.dismiss();
        });
        imagesContainer.addView(bulkBtn);

        ScrollView imageScrollView = new ScrollView(activity);
        LinearLayout.LayoutParams imgScrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
        );
        imgScrollLp.topMargin = dp(activity, 6);
        imageScrollView.setLayoutParams(imgScrollLp);
        LinearLayout imageList = new LinearLayout(activity);
        imageList.setOrientation(LinearLayout.VERTICAL);
        imageScrollView.addView(imageList);
        imagesContainer.addView(imageScrollView);

        contentArea.addView(imagesContainer);
        root.addView(contentArea);

        // Populate Videos Tab
        if (videos.isEmpty()) {
            TextView emptyView = createEmptyState(activity, "No video or audio streams detected on this page.", subTextColor);
            videoList.addView(emptyView);
        } else {
            for (final MediaItem item : videos) {
                View card = createVideoCard(activity, item, isDark, cardBgColor, textColor, subTextColor, accentColor, dialog);
                videoList.addView(card);
            }
        }

        // Populate Images Tab
        if (images.isEmpty()) {
            TextView emptyView = createEmptyState(activity, "No high-resolution images detected on this page.", subTextColor);
            imageList.addView(emptyView);
        } else {
            for (final MediaItem item : images) {
                View imgCard = createImageCard(activity, item, cardBgColor, textColor, subTextColor, selectedImages, () -> {
                    if (selectedImages.isEmpty()) {
                        bulkBtn.setText("Download All (" + images.size() + " Images)");
                    } else {
                        bulkBtn.setText("Download Selected (" + selectedImages.size() + " Images)");
                    }
                });
                imageList.addView(imgCard);
            }
        }

        // Tab Switching
        tabVideos.setOnClickListener(v -> {
            setTabActive(activity, tabVideos, true, accentColor, cardBgColor);
            setTabActive(activity, tabImages, false, accentColor, cardBgColor);
            videoScrollView.setVisibility(View.VISIBLE);
            imagesContainer.setVisibility(View.GONE);
        });

        tabImages.setOnClickListener(v -> {
            setTabActive(activity, tabVideos, false, accentColor, cardBgColor);
            setTabActive(activity, tabImages, true, accentColor, cardBgColor);
            videoScrollView.setVisibility(View.GONE);
            imagesContainer.setVisibility(View.VISIBLE);
        });

        dialog.setContentView(root);

        Window win = dialog.getWindow();
        if (win != null) {
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            win.setGravity(Gravity.BOTTOM);
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.setDimAmount(0.45f);
        }

        dialog.show();
    }

    private static View createVideoCard(
            final Activity activity,
            final MediaItem item,
            boolean isDark,
            int cardBg,
            int textColor,
            int subTextColor,
            int accentColor,
            final Dialog dialog
    ) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardLp.bottomMargin = dp(activity, 10);
        card.setLayoutParams(cardLp);
        card.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardBg);
        bg.setCornerRadius(dp(activity, 12));
        card.setBackground(bg);

        // Row 1: Title & Type badge
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(activity);
        title.setText(item.title);
        title.setTextColor(textColor);
        title.setTextSize(14);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        header.addView(title, titleLp);

        TextView badge = new TextView(activity);
        String label = "VIDEO";
        if (item.type == MediaType.HLS_STREAM) label = "HLS STREAM";
        else if (item.type == MediaType.DASH_STREAM) label = "DASH STREAM";
        else if (item.type == MediaType.AUDIO) label = "AUDIO";

        badge.setText(label);
        badge.setTextSize(10);
        badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        badge.setTextColor(Color.WHITE);
        badge.setPadding(dp(activity, 6), dp(activity, 2), dp(activity, 6), dp(activity, 2));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(accentColor);
        badgeBg.setCornerRadius(dp(activity, 4));
        badge.setBackground(badgeBg);
        header.addView(badge);
        card.addView(header);

        // Row 2: Sub-info
        String info = item.url;
        if (!item.getResolutionString().isEmpty()) {
            info = item.getResolutionString() + " • " + info;
        }
        if (!item.getFormattedSize().isEmpty()) {
            info = item.getFormattedSize() + " • " + info;
        }
        TextView sub = new TextView(activity);
        sub.setText(info);
        sub.setTextColor(subTextColor);
        sub.setTextSize(11);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        sub.setPadding(0, dp(activity, 4), 0, dp(activity, 8));
        card.addView(sub);

        // Row 3: Action Buttons
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);

        Button copyBtn = new Button(activity);
        copyBtn.setText("Copy Link");
        styleButton(copyBtn, Color.TRANSPARENT, textColor, dp(activity, 6));
        copyBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Media URL", item.url));
                Toast.makeText(activity, "URL copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });
        actions.addView(copyBtn);

        Button dlBtn = new Button(activity);
        dlBtn.setText("Download");
        styleButton(dlBtn, accentColor, Color.WHITE, dp(activity, 6));
        dlBtn.setOnClickListener(v -> {
            if (item.type == MediaType.HLS_STREAM && item.variants.size() > 1) {
                showQualitySelectorAndDownload(activity, item, dialog);
            } else {
                startMediaDownload(activity, item);
                dialog.dismiss();
            }
        });
        actions.addView(dlBtn);

        card.addView(actions);
        return card;
    }

    private static void showQualitySelectorAndDownload(final Activity activity, final MediaItem item, final Dialog parentDialog) {
        final List<MediaItem.HlsVariant> variants = new ArrayList<>(item.variants);
        Collections.sort(variants);

        String[] labels = new String[variants.size()];
        for (int i = 0; i < variants.size(); i++) {
            MediaItem.HlsVariant v = variants.get(i);
            labels[i] = v.getResolutionLabel() + " (" + (v.bandwidth / 1000) + " kbps)";
        }

        new AlertDialog.Builder(activity)
                .setTitle("Select Stream Quality")
                .setItems(labels, (d, which) -> {
                    MediaItem.HlsVariant chosen = variants.get(which);
                    MediaItem chosenItem = new MediaItem(
                            item.id, item.type, chosen.url,
                            item.title + " (" + chosen.getResolutionLabel() + ")",
                            item.mimeType, item.sizeBytes, chosen.width, chosen.height,
                            item.durationMs, item.userAgent, item.cookie, item.referer, item.tabId
                    );
                    startMediaDownload(activity, chosenItem);
                    parentDialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static View createImageCard(
            final Activity activity,
            final MediaItem item,
            int cardBg,
            int textColor,
            int subTextColor,
            final Set<MediaItem> selected,
            final Runnable onSelectionChanged
    ) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowLp.bottomMargin = dp(activity, 8);
        row.setLayoutParams(rowLp);
        row.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardBg);
        bg.setCornerRadius(dp(activity, 8));
        row.setBackground(bg);

        CheckBox cb = new CheckBox(activity);
        cb.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked) selected.add(item);
            else selected.remove(item);
            onSelectionChanged.run();
        });
        row.addView(cb);

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        textCol.setLayoutParams(textLp);

        TextView title = new TextView(activity);
        title.setText(item.title);
        title.setTextColor(textColor);
        title.setTextSize(13);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(title);

        TextView sub = new TextView(activity);
        String dim = (item.width > 0 && item.height > 0) ? (item.width + "x" + item.height + " • ") : "";
        sub.setText(dim + item.url);
        sub.setTextColor(subTextColor);
        sub.setTextSize(11);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        textCol.addView(sub);

        row.addView(textCol);
        return row;
    }

    private static void startMediaDownload(Context ctx, MediaItem item) {
        try {
            String jobId = UUID.randomUUID().toString();
            MediaDownloadService.PENDING_JOBS.put(jobId, item);

            Intent intent = new Intent(ctx, MediaDownloadService.class);
            intent.setAction(MediaDownloadService.ACTION_DOWNLOAD_MEDIA);
            intent.putExtra(MediaDownloadService.EXTRA_JOB_ID, jobId);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
            Toast.makeText(ctx, "Starting background download...", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(ctx, "Failed starting download service: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void startBulkImageDownload(Context ctx, List<MediaItem> images, boolean convertWebp) {
        try {
            String jobId = UUID.randomUUID().toString();
            MediaDownloadService.PENDING_JOBS.put(jobId, new ArrayList<>(images));

            Intent intent = new Intent(ctx, MediaDownloadService.class);
            intent.setAction(MediaDownloadService.ACTION_DOWNLOAD_IMAGES);
            intent.putExtra(MediaDownloadService.EXTRA_JOB_ID, jobId);
            intent.putExtra(MediaDownloadService.EXTRA_CONVERT_WEBP, convertWebp);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
            Toast.makeText(ctx, "Starting bulk image download...", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(ctx, "Failed starting image download: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static Button createTabButton(Context ctx, String text, boolean active, int accent, int textColor, int cardBg) {
        Button btn = new Button(ctx);
        btn.setText(text);
        btn.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(ctx, 36), 1.0f);
        lp.setMargins(dp(ctx, 4), 0, dp(ctx, 4), 0);
        btn.setLayoutParams(lp);
        setTabActive(ctx, btn, active, accent, cardBg);
        return btn;
    }

    private static void setTabActive(Context ctx, Button btn, boolean active, int accent, int cardBg) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(ctx, 18));
        if (active) {
            gd.setColor(accent);
            btn.setTextColor(Color.WHITE);
        } else {
            gd.setColor(cardBg);
            btn.setTextColor(Color.GRAY);
        }
        btn.setBackground(gd);
    }

    private static void styleButton(Button btn, int bgColor, int textColor, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(btn.getContext(), radiusDp));
        btn.setBackground(gd);
        btn.setTextColor(textColor);
        btn.setTextSize(12);
    }

    private static TextView createEmptyState(Context ctx, String msg, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(msg);
        tv.setTextColor(color);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(ctx, 40), 0, dp(ctx, 40));
        return tv;
    }

    private static boolean isDarkTheme(Context ctx) {
        int nightMode = ctx.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
}

package dev.brave.extension.gestures;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

public class FullscreenGestureOverlayView extends FrameLayout {
    private static final String TAG = "FullscreenGestureView";

    private final TextView mCenterHud;
    private final TextView mTopHud;

    public FullscreenGestureOverlayView(Activity activity) {
        super(activity);

        setClickable(false);
        setFocusable(false);

        // Center Pill HUD
        mCenterHud = new TextView(getContext());
        mCenterHud.setTextColor(Color.WHITE);
        mCenterHud.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        mCenterHud.getPaint().setFakeBoldText(true);
        mCenterHud.setGravity(Gravity.CENTER);
        int padH = dpToPx(20);
        int padV = dpToPx(12);
        mCenterHud.setPadding(padH, padV, padH, padV);

        GradientDrawable centerBg = new GradientDrawable();
        centerBg.setColor(Color.parseColor("#D9141416"));
        centerBg.setCornerRadius(dpToPx(26));
        centerBg.setStroke(dpToPx(1), Color.parseColor("#33FFFFFF"));
        mCenterHud.setBackground(centerBg);
        mCenterHud.setVisibility(View.GONE);

        LayoutParams centerLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        centerLp.gravity = Gravity.CENTER;
        addView(mCenterHud, centerLp);

        // Top Pill HUD for 2x speed
        mTopHud = new TextView(getContext());
        mTopHud.setTextColor(Color.WHITE);
        mTopHud.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mTopHud.getPaint().setFakeBoldText(true);
        mTopHud.setGravity(Gravity.CENTER);
        int topPadH = dpToPx(16);
        int topPadV = dpToPx(8);
        mTopHud.setPadding(topPadH, topPadV, topPadH, topPadV);

        GradientDrawable topBg = new GradientDrawable();
        topBg.setColor(Color.parseColor("#E6FF5500"));
        topBg.setCornerRadius(dpToPx(20));
        topBg.setStroke(dpToPx(1), Color.parseColor("#4DFFFFFF"));
        mTopHud.setBackground(topBg);
        mTopHud.setVisibility(View.GONE);

        LayoutParams topLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        topLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        topLp.topMargin = dpToPx(36);
        addView(mTopHud, topLp);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // Overlay view itself never intercepts or consumes touches directly;
        // touch events are mediated through Window.Callback in FullscreenGestureTouchHandler.
        return false;
    }

    public void showCenterHud(String text) {
        mCenterHud.animate().cancel();
        mCenterHud.setText(text);
        mCenterHud.setAlpha(1.0f);
        mCenterHud.setVisibility(View.VISIBLE);
    }

    public void hideCenterHud(long delayMs) {
        mCenterHud.animate().cancel();
        if (delayMs <= 0) {
            mCenterHud.setVisibility(View.GONE);
            return;
        }
        mCenterHud.animate()
                .alpha(0f)
                .setDuration(200)
                .setStartDelay(delayMs)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        mCenterHud.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    public void showTopHud(String text) {
        mTopHud.animate().cancel();
        mTopHud.setText(text);
        mTopHud.setAlpha(1.0f);
        mTopHud.setVisibility(View.VISIBLE);
    }

    public void hideTopHud() {
        mTopHud.animate().cancel();
        mTopHud.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        mTopHud.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    public void cleanup() {
        mCenterHud.animate().cancel();
        mTopHud.animate().cancel();
        mCenterHud.setVisibility(View.GONE);
        mTopHud.setVisibility(View.GONE);
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }
}

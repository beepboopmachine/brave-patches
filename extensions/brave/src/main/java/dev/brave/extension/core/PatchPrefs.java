package dev.brave.extension.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Thread-safe, null-safe preferences storage for Brave Morphe patches.
 */
public final class PatchPrefs {
    private static final String PREF_NAME = "brave_morphe_patches_prefs";

    public static final String KEY_MEDIA_DOWNLOAD_ENABLED = "media_download_enabled";
    public static final String KEY_AUTO_CONVERT_WEBP = "auto_convert_webp";
    public static final String KEY_HLS_DEFAULT_RESOLUTION = "hls_default_res";
    public static final String KEY_CUSTOM_DOWNLOAD_DIR = "custom_download_dir";

    private PatchPrefs() {}

    public static SharedPreferences get(Context context) {
        if (context == null) return null;
        try {
            Context appCtx = context.getApplicationContext();
            Context target = (appCtx != null) ? appCtx : context;
            return target.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean getBoolean(Context context, String key, boolean defValue) {
        try {
            SharedPreferences sp = get(context);
            return (sp != null) ? sp.getBoolean(key, defValue) : defValue;
        } catch (Throwable t) {
            return defValue;
        }
    }

    public static void setBoolean(Context context, String key, boolean value) {
        try {
            SharedPreferences sp = get(context);
            if (sp != null) {
                sp.edit().putBoolean(key, value).apply();
            }
        } catch (Throwable ignored) {}
    }

    public static String getString(Context context, String key, String defValue) {
        try {
            SharedPreferences sp = get(context);
            return (sp != null) ? sp.getString(key, defValue) : defValue;
        } catch (Throwable t) {
            return defValue;
        }
    }

    public static void setString(Context context, String key, String value) {
        try {
            SharedPreferences sp = get(context);
            if (sp != null) {
                sp.edit().putString(key, value).apply();
            }
        } catch (Throwable ignored) {}
    }

    public static int getInt(Context context, String key, int defValue) {
        try {
            SharedPreferences sp = get(context);
            return (sp != null) ? sp.getInt(key, defValue) : defValue;
        } catch (Throwable t) {
            return defValue;
        }
    }

    public static void setInt(Context context, String key, int value) {
        try {
            SharedPreferences sp = get(context);
            if (sp != null) {
                sp.edit().putInt(key, value).apply();
            }
        } catch (Throwable ignored) {}
    }
}

package dev.brave.extension.media;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * High-performance, memory-capped thumbnail loader with LruCache and sub-sampling.
 */
public final class ThumbnailLoader {
    private static final String TAG = "Brave_ThumbLoader";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService THREAD_POOL = Executors.newFixedThreadPool(3);

    private static final LruCache<String, Bitmap> CACHE;

    static {
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = Math.max(1024 * 4, maxMemory / 8); // At least 4MB, up to 1/8th max heap
        CACHE = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
    }

    private ThumbnailLoader() {}

    public static void load(final ImageView imageView, final String url, final String referer) {
        if (imageView == null) return;
        if (url == null || url.trim().isEmpty()) {
            imageView.setVisibility(View.GONE);
            return;
        }

        final String cleanUrl = url.trim();
        imageView.setTag(cleanUrl);

        Bitmap cached = CACHE.get(cleanUrl);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            imageView.setVisibility(View.VISIBLE);
            return;
        }

        // Initially show empty / placeholder until loaded
        imageView.setImageDrawable(null);
        imageView.setVisibility(View.VISIBLE);

        THREAD_POOL.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL u = new URL(cleanUrl);
                conn = (HttpURLConnection) u.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
                if (referer != null && !referer.isEmpty()) {
                    conn.setRequestProperty("Referer", referer);
                }

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    try (InputStream is = conn.getInputStream();
                         ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        byte[] buf = new byte[4096];
                        int r;
                        int total = 0;
                        while ((r = is.read(buf)) != -1) {
                            total += r;
                            if (total > 2 * 1024 * 1024) break; // 2MB cap for thumbnails
                            baos.write(buf, 0, r);
                        }
                        byte[] data = baos.toByteArray();
                        if (data.length > 0) {
                            BitmapFactory.Options opts = new BitmapFactory.Options();
                            opts.inJustDecodeBounds = true;
                            BitmapFactory.decodeByteArray(data, 0, data.length, opts);

                            opts.inSampleSize = calculateInSampleSize(opts, 240, 160);
                            opts.inJustDecodeBounds = false;
                            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);

                            if (bmp != null) {
                                CACHE.put(cleanUrl, bmp);
                                MAIN_HANDLER.post(() -> {
                                    if (cleanUrl.equals(imageView.getTag())) {
                                        imageView.setImageBitmap(bmp);
                                        imageView.setVisibility(View.VISIBLE);
                                    }
                                });
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Log.d(TAG, "Failed loading thumb: " + cleanUrl, t);
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }
}

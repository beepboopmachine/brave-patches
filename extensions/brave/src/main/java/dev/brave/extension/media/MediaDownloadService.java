package dev.brave.extension.media;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import dev.brave.extension.core.PatchPrefs;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MediaDownloadService extends Service {
    private static final String TAG = "Brave_DownloadService";
    private static final String CHANNEL_ID = "brave_media_downloads";
    private static final String CHANNEL_COMPLETE_ID = "brave_media_downloads_complete";
    private static final int NOTIF_ID = 4004;

    public static final String ACTION_DOWNLOAD_MEDIA = "dev.brave.action.DOWNLOAD_MEDIA";
    public static final String ACTION_DOWNLOAD_IMAGES = "dev.brave.action.DOWNLOAD_IMAGES";
    public static final String EXTRA_JOB_ID = "extra_job_id";
    public static final String EXTRA_CONVERT_WEBP = "extra_convert_webp";

    // In-memory cache to prevent TransactionTooLargeException over Binder
    public static final Map<String, Object> PENDING_JOBS = new ConcurrentHashMap<>();

    private final ExecutorService mExecutor = Executors.newFixedThreadPool(3);
    private final AtomicInteger mActiveJobs = new AtomicInteger(0);
    private NotificationManager mNotifManager;
    private volatile long mLastNotifTime = 0;
    private volatile int mLastProgress = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        mNotifManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    private static final java.util.concurrent.atomic.AtomicInteger sNextCompletedNotifId = new java.util.concurrent.atomic.AtomicInteger(5000);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android 8.0+ strict requirement: Always call startForeground immediately
        // to avoid fatal ForegroundServiceDidNotStartInTimeException crash
        startForeground(NOTIF_ID, buildProgressNotification("Starting media background service...", 0, true));

        if (intent == null) {
            stopForegroundImmediately();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        String jobId = intent.getStringExtra(EXTRA_JOB_ID);

        if (ACTION_DOWNLOAD_MEDIA.equals(action) && jobId != null) {
            Object obj = PENDING_JOBS.remove(jobId);
            if (obj instanceof MediaItem) {
                MediaItem item = (MediaItem) obj;
                mActiveJobs.incrementAndGet();
                updateNotification("Starting download: " + item.title, 0, true);
                mExecutor.execute(() -> {
                    try {
                        processMediaDownload(item);
                    } finally {
                        checkFinishAndStop();
                    }
                });
                return START_NOT_STICKY;
            }
        } else if (ACTION_DOWNLOAD_IMAGES.equals(action) && jobId != null) {
            Object obj = PENDING_JOBS.remove(jobId);
            if (obj instanceof List) {
                @SuppressWarnings("unchecked")
                List<MediaItem> images = (List<MediaItem>) obj;
                boolean convertWebp = intent.getBooleanExtra(EXTRA_CONVERT_WEBP, true);
                if (!images.isEmpty()) {
                    mActiveJobs.incrementAndGet();
                    updateNotification("Starting image batch download...", 0, true);
                    mExecutor.execute(() -> {
                        try {
                            processBulkImageDownload(images, convertWebp);
                        } finally {
                            checkFinishAndStop();
                        }
                    });
                    return START_NOT_STICKY;
                }
            }
        }

        stopForegroundImmediately();
        return START_NOT_STICKY;
    }

    private void stopForegroundImmediately() {
        if (mActiveJobs.get() <= 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mExecutor.shutdownNow();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void checkFinishAndStop() {
        if (mActiveJobs.decrementAndGet() <= 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            stopSelf();
        }
    }

    private void postCompletedNotification(String title, String message, boolean success) {
        int notifId = sNextCompletedNotifId.incrementAndGet();
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_COMPLETE_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(success ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setOngoing(false);

        try {
            Intent viewIntent = new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS);
            viewIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(this, notifId, viewIntent, flags);
            builder.setContentIntent(pi);
        } catch (Throwable ignored) {}

        try {
            mNotifManager.notify(notifId, builder.build());
        } catch (Throwable ignored) {}
    }

    private void registerWithDownloadManager(String title, String filename, String mimeType, File file) {
        try {
            android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null && file != null && file.exists()) {
                dm.addCompletedDownload(
                        filename,
                        "Downloaded: " + (title != null && !title.isEmpty() ? title : filename),
                        true,
                        mimeType,
                        file.getAbsolutePath(),
                        file.length(),
                        true
                );
                Log.i(TAG, "Registered completed download with DownloadManager: " + file.getAbsolutePath());
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not register with DownloadManager: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------------
    // Media & HLS Stream Processing
    // ------------------------------------------------------------------------

    private void processMediaDownload(MediaItem item) {
        try {
            updateNotification("Downloading: " + item.title, 0, true);

            if (item.type == MediaType.HLS_STREAM) {
                downloadHlsStream(item);
            } else if (item.type == MediaType.DASH_STREAM) {
                postCompletedNotification("Download Unsupported", "DASH streams not supported natively", false);
                return;
            } else {
                downloadDirectStream(item);
            }

            postCompletedNotification("Download Complete", item.title, true);
        } catch (Throwable t) {
            Log.e(TAG, "Download failed for " + item.url, t);
            postCompletedNotification("Download Failed", item.title + ": " + t.getMessage(), false);
        } finally {
            MediaPickerBottomSheet.ACTIVE_DOWNLOAD_URLS.remove(item.url);
        }
    }

    private void downloadHlsStream(MediaItem item) throws Exception {
        String playlistUrl = item.url;
        if (!item.variants.isEmpty()) {
            Collections.sort(item.variants);
            playlistUrl = item.variants.get(0).url; // Best variant
        }

        String mediaPlaylistText = fetchString(playlistUrl, item.userAgent, item.cookie, item.referer);
        List<HlsParser.SegmentInfo> segments = HlsParser.parseMediaPlaylist(playlistUrl, mediaPlaylistText);

        if (segments.isEmpty()) {
            segments = HlsParser.parseMediaPlaylist(item.url, fetchString(item.url, item.userAgent, item.cookie, item.referer));
        }

        if (segments.isEmpty()) {
            throw new IllegalStateException("No media segments found in HLS playlist");
        }

        // Reject DRM protected streams (SAMPLE-AES) cleanly
        for (HlsParser.SegmentInfo seg : segments) {
            if (seg.keyInfo != null && seg.keyInfo.isDrm()) {
                throw new UnsupportedOperationException("DRM protected HLS streams (SAMPLE-AES) are not supported");
            }
        }

        File tempDir = new File(getCacheDir(), "brave_hls_" + UUID.randomUUID().toString());
        if (!tempDir.mkdirs()) {
            throw new IllegalStateException("Failed creating temporary directory for HLS download");
        }

        File concatenatedTs = new File(tempDir, "stream_concat.ts");
        Map<String, byte[]> keyCache = new HashMap<>();
        Map<String, byte[]> initCache = new HashMap<>();

        try {
            try (FileOutputStream fos = new FileOutputStream(concatenatedTs);
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {

                int total = segments.size();
                for (int i = 0; i < total; i++) {
                    HlsParser.SegmentInfo seg = segments.get(i);
                    int progress = (int) (((i + 1) / (float) total) * 85);
                    throttledNotification(String.format(Locale.ROOT, "Downloading segment %d/%d (%s)", i + 1, total, item.title), progress);

                    // Download & write fMP4 / CMAF initialization segment (#EXT-X-MAP) once per init URL
                    if (seg.initSegmentUrl != null && !seg.initSegmentUrl.isEmpty()) {
                        if (!initCache.containsKey(seg.initSegmentUrl)) {
                            byte[] initBytes = fetchBytes(seg.initSegmentUrl, item.userAgent, item.cookie, item.referer);
                            if (initBytes != null && initBytes.length > 0) {
                                initCache.put(seg.initSegmentUrl, initBytes);
                                bos.write(initBytes);
                            }
                        }
                    }

                    byte[] segBytes = fetchBytes(seg.url, item.userAgent, item.cookie, item.referer);
                    if (segBytes == null || segBytes.length == 0) continue;

                    // Decrypt AES-128 if required
                    if (seg.keyInfo != null && seg.keyInfo.isAes128() && seg.keyInfo.uri != null) {
                        byte[] keyBytes = keyCache.get(seg.keyInfo.uri);
                        if (keyBytes == null) {
                            keyBytes = fetchBytes(seg.keyInfo.uri, item.userAgent, item.cookie, item.referer);
                            if (keyBytes != null && keyBytes.length == 16) {
                                keyCache.put(seg.keyInfo.uri, keyBytes);
                            }
                        }
                        if (keyBytes != null && keyBytes.length == 16) {
                            byte[] iv = parseIv(seg.keyInfo.ivHex, seg.sequenceNumber);
                            byte[] decrypted = decryptAes128(segBytes, keyBytes, iv);
                            if (decrypted != null) {
                                segBytes = decrypted;
                            }
                        }
                    }

                    bos.write(segBytes);
                }
                bos.flush();
            }

            updateNotification("Remuxing into MP4...", 90, true);

            File remuxedMp4 = new File(tempDir, "remuxed.mp4");
            boolean remuxSuccess = remuxTsToMp4(concatenatedTs, remuxedMp4);

            File finalSource = remuxSuccess ? remuxedMp4 : concatenatedTs;
            String outExt = remuxSuccess ? ".mp4" : ".ts";
            String outMime = remuxSuccess ? "video/mp4" : "video/mp2t";
            String sanitizedTitle = sanitizeFilename(item.title) + outExt;

            Uri targetUri = createMediaUri(sanitizedTitle, outMime);
            saveFileToDestination(finalSource, targetUri, sanitizedTitle);

            File savedFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BraveMedia/" + sanitizedTitle);
            registerWithDownloadManager(item.title, sanitizedTitle, outMime, savedFile);
            MediaScannerConnection.scanFile(this, new String[]{savedFile.getAbsolutePath()}, new String[]{outMime}, null);
            PatchPrefs.markMediaDownloaded(this, item.url, savedFile.getAbsolutePath());

        } finally {
            deleteRecursive(tempDir);
        }
    }

    private void downloadDirectStream(MediaItem item) throws Exception {
        String ext = item.type == MediaType.AUDIO ? ".mp3" : ".mp4";
        String lowerUrl = item.url.toLowerCase(Locale.ROOT);
        if (lowerUrl.contains(".webm")) ext = ".webm";
        else if (lowerUrl.contains(".mkv")) ext = ".mkv";

        String filename = sanitizeFilename(item.title) + ext;
        String mime = (item.mimeType != null && !item.mimeType.isEmpty()) ? item.mimeType : (item.type == MediaType.AUDIO ? "audio/mpeg" : "video/mp4");

        Uri targetUri = createMediaUri(filename, mime);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(item.url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            if (!item.userAgent.isEmpty()) conn.setRequestProperty("User-Agent", item.userAgent);
            if (!item.cookie.isEmpty()) conn.setRequestProperty("Cookie", item.cookie);
            if (!item.referer.isEmpty()) conn.setRequestProperty("Referer", item.referer);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                if (targetUri != null) getContentResolver().delete(targetUri, null, null);
                throw new IllegalStateException("HTTP server responded with " + code);
            }

            long length = conn.getContentLengthLong();

            OutputStream out = null;
            if (targetUri != null) {
                out = getContentResolver().openOutputStream(targetUri);
            }
            if (out == null) {
                File dl = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BraveMedia");
                dl.mkdirs();
                out = new FileOutputStream(new File(dl, filename));
            }

            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 OutputStream bos = new BufferedOutputStream(out)) {

                byte[] buffer = new byte[32 * 1024];
                long downloaded = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    bos.write(buffer, 0, read);
                    downloaded += read;
                    if (length > 0) {
                        int prog = (int) ((downloaded / (float) length) * 100);
                        throttledNotification("Downloading: " + item.title + " (" + prog + "%)", prog);
                    }
                }
                bos.flush();
            }

            if (targetUri != null) {
                completeMediaStoreEntry(targetUri);
            }
            File savedFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BraveMedia/" + filename);
            MediaScannerConnection.scanFile(this, new String[]{savedFile.getAbsolutePath()}, new String[]{mime}, null);
            registerWithDownloadManager(item.title, filename, mime, savedFile);
            PatchPrefs.markMediaDownloaded(this, item.url, savedFile.getAbsolutePath());
        } catch (Throwable t) {
            if (targetUri != null) {
                getContentResolver().delete(targetUri, null, null);
            }
            throw t;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void processBulkImageDownload(List<MediaItem> images, boolean convertWebp) {
        int total = images.size();
        int savedCount = 0;

        for (int i = 0; i < total; i++) {
            MediaItem item = images.get(i);
            int progress = (int) (((i + 1) / (float) total) * 100);
            throttledNotification(String.format(Locale.ROOT, "Saving image %d/%d", i + 1, total), progress);

            try {
                byte[] imgBytes = fetchBytes(item.url, item.userAgent, item.cookie, item.referer);
                if (imgBytes == null || imgBytes.length == 0) continue;

                boolean isWebp = item.url.toLowerCase(Locale.ROOT).contains(".webp") || item.mimeType.contains("webp");
                String filename;
                String mimeType;
                byte[] outputBytes;

                if (isWebp && convertWebp) {
                    Bitmap bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
                    if (bmp != null) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
                        outputBytes = baos.toByteArray();
                        bmp.recycle();
                        filename = sanitizeFilename(item.title) + "_" + (i + 1) + ".png";
                        mimeType = "image/png";
                    } else {
                        outputBytes = imgBytes;
                        filename = sanitizeFilename(item.title) + "_" + (i + 1) + ".webp";
                        mimeType = "image/webp";
                    }
                } else {
                    outputBytes = imgBytes;
                    String ext = ".jpg";
                    String lower = item.url.toLowerCase(Locale.ROOT);
                    if (lower.contains(".png")) ext = ".png";
                    else if (lower.contains(".webp")) ext = ".webp";
                    else if (lower.contains(".gif")) ext = ".gif";
                    filename = sanitizeFilename(item.title) + "_" + (i + 1) + ext;
                    mimeType = (item.mimeType != null && !item.mimeType.isEmpty()) ? item.mimeType : "image/jpeg";
                }

                Uri targetUri = createImageMediaUri(filename, mimeType);
                if (targetUri != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(targetUri)) {
                        if (os != null) os.write(outputBytes);
                    }
                    completeMediaStoreEntry(targetUri);
                    savedCount++;
                    File dest = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "BraveMedia/" + filename);
                    PatchPrefs.markMediaDownloaded(this, item.url, dest.getAbsolutePath());
                } else {
                    File dl = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "BraveMedia");
                    dl.mkdirs();
                    File dest = new File(dl, filename);
                    try (FileOutputStream fos = new FileOutputStream(dest)) {
                        fos.write(outputBytes);
                    }
                    MediaScannerConnection.scanFile(this, new String[]{dest.getAbsolutePath()}, null, null);
                    savedCount++;
                    PatchPrefs.markMediaDownloaded(this, item.url, dest.getAbsolutePath());
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed downloading image: " + item.url, t);
            }
        }

        MediaPickerBottomSheet.sBulkImagesDownloading = false;
        String resultMsg = String.format(Locale.ROOT, "Saved %d of %d images to Pictures/BraveMedia", savedCount, total);
        postCompletedNotification("Image Batch Complete", resultMsg, savedCount > 0);
    }

    private boolean remuxTsToMp4(File inputTs, File outputMp4) {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(inputTs.getAbsolutePath());
            int trackCount = extractor.getTrackCount();
            if (trackCount <= 0) return false;

            muxer = new MediaMuxer(outputMp4.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Map<Integer, Integer> trackMap = new HashMap<>();

            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    extractor.selectTrack(i);
                    int muxerTrack = muxer.addTrack(format);
                    trackMap.put(i, muxerTrack);
                }
            }

            if (trackMap.isEmpty()) return false;

            muxer.start();

            ByteBuffer buffer = ByteBuffer.allocate(2 * 1024 * 1024);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            while (true) {
                int sampleTrack = extractor.getSampleTrackIndex();
                if (sampleTrack < 0) break;

                bufferInfo.offset = 0;
                bufferInfo.size = extractor.readSampleData(buffer, 0);
                if (bufferInfo.size < 0) break;

                bufferInfo.presentationTimeUs = extractor.getSampleTime();

                // Map MediaExtractor sample flags to MediaCodec buffer flags
                int sampleFlags = extractor.getSampleFlags();
                int flags = 0;
                if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
                }
                bufferInfo.flags = flags;

                Integer muxerTrack = trackMap.get(sampleTrack);
                if (muxerTrack != null) {
                    muxer.writeSampleData(muxerTrack, buffer, bufferInfo);
                }

                extractor.advance();
            }

            muxer.stop();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "MediaMuxer remux encountered an issue, falling back to raw TS", t);
            return false;
        } finally {
            try { extractor.release(); } catch (Throwable ignored) {}
            if (muxer != null) {
                try { muxer.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private byte[] decryptAes128(byte[] cipherText, byte[] keyBytes, byte[] iv) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            return cipher.doFinal(cipherText);
        } catch (Throwable t) {
            Log.w(TAG, "AES decryption failed", t);
            return null;
        }
    }

    private byte[] parseIv(String ivHex, long sequenceNumber) {
        byte[] iv = new byte[16];
        if (ivHex != null) {
            String hex = ivHex.trim();
            if (hex.toLowerCase(Locale.ROOT).startsWith("0x")) {
                hex = hex.substring(2);
            }
            if (hex.length() % 2 != 0) {
                hex = "0" + hex;
            }
            try {
                int hexBytes = hex.length() / 2;
                int startOffset = Math.max(0, 16 - hexBytes);
                for (int i = 0; i < Math.min(16, hexBytes); i++) {
                    iv[startOffset + i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                }
                return iv;
            } catch (Throwable ignored) {}
        }

        // RFC 8216: Sequence number as big-endian integer in bottom 8 bytes
        long seq = sequenceNumber;
        for (int b = 15; b >= 8; b--) {
            iv[b] = (byte) (seq & 0xFF);
            seq >>= 8;
        }
        return iv;
    }

    private Uri createMediaUri(String filename, String mimeType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BraveMedia");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver resolver = getContentResolver();
            return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        }
        return null;
    }

    private Uri createImageMediaUri(String filename, String mimeType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BraveMedia");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver resolver = getContentResolver();
            return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        }
        return null;
    }

    private void completeMediaStoreEntry(Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
        }
    }

    private void saveFileToDestination(File source, Uri targetUri, String filename) throws Exception {
        if (targetUri != null) {
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = getContentResolver().openOutputStream(targetUri)) {
                if (out == null) throw new IllegalStateException("Failed opening MediaStore output stream");
                copyStream(in, out);
            }
            completeMediaStoreEntry(targetUri);
        } else {
            File downloadDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BraveMedia");
            downloadDir.mkdirs();
            File dest = new File(downloadDir, filename);
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = new FileOutputStream(dest)) {
                copyStream(in, out);
            }
            MediaScannerConnection.scanFile(this, new String[]{dest.getAbsolutePath()}, null, null);
        }
    }

    private String fetchString(String urlStr, String ua, String cookie, String referer) throws Exception {
        byte[] bytes = fetchBytes(urlStr, ua, cookie, referer);
        return bytes != null ? new String(bytes, "UTF-8") : "";
    }

    private byte[] fetchBytes(String urlStr, String ua, String cookie, String referer) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            if (ua != null && !ua.isEmpty()) conn.setRequestProperty("User-Agent", ua);
            if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            if (referer != null && !referer.isEmpty()) conn.setRequestProperty("Referer", referer);

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[16 * 1024];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        baos.write(buf, 0, r);
                    }
                    return baos.toByteArray();
                }
            }
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[32 * 1024];
        int r;
        while ((r = in.read(buf)) != -1) {
            out.write(buf, 0, r);
        }
        out.flush();
    }

    private static String sanitizeFilename(String name) {
        if (name == null || name.trim().isEmpty()) return "BraveMedia_" + System.currentTimeMillis();
        String clean = name.replaceAll("[\\\\/:*?\"<>|\r\n\0]", "_").trim();
        if (clean.equals(".") || clean.equals("..")) clean = "BraveMedia_" + System.currentTimeMillis();
        if (clean.length() > 180) clean = clean.substring(0, 180);
        return clean;
    }

    private static void deleteRecursive(File f) {
        if (f != null && f.exists()) {
            if (f.isDirectory()) {
                File[] files = f.listFiles();
                if (files != null) {
                    for (File child : files) deleteRecursive(child);
                }
            }
            f.delete();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Brave Media Downloads",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background download progress for Brave media");
            channel.enableVibration(false);
            channel.setSound(null, null);
            channel.setShowBadge(false);
            mNotifManager.createNotificationChannel(channel);

            NotificationChannel completeChannel = new NotificationChannel(
                    CHANNEL_COMPLETE_ID,
                    "Completed Downloads",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            completeChannel.setDescription("Notifications when media downloads finish");
            completeChannel.setShowBadge(true);
            mNotifManager.createNotificationChannel(completeChannel);
        }
    }

    private Notification buildProgressNotification(String message, int progress, boolean ongoing) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle("Brave Media Downloader")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, progress, progress <= 0)
                .setOngoing(ongoing);

        return builder.build();
    }

    private void updateNotification(String message, int progress, boolean ongoing) {
        try {
            mNotifManager.notify(NOTIF_ID, buildProgressNotification(message, progress, ongoing));
        } catch (Throwable ignored) {}
    }

    private void throttledNotification(String message, int progress) {
        long now = System.currentTimeMillis();
        if (progress != mLastProgress && (now - mLastNotifTime > 600 || progress == 100)) {
            mLastNotifTime = now;
            mLastProgress = progress;
            updateNotification(message, progress, true);
        }
    }
}

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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MediaDownloadService extends Service {
    private static final String TAG = "Brave_DownloadService";
    private static final String CHANNEL_ID = "brave_media_downloads";
    private static final String CHANNEL_COMPLETE_ID = "brave_media_downloads_complete";
    private static final int FOREGROUND_NOTIF_ID = 4004;

    private static final int MAX_PLAYLIST_CHARS = HlsParser.MAX_PLAYLIST_CHARS; // 16M chars strict cap
    private static final Pattern CONTENT_RANGE_PATTERN = Pattern.compile(
            "bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)",
            Pattern.CASE_INSENSITIVE
    );
    private static final int MAX_KEY_BYTES = 1024; // 1 KB
    private static final int MAX_SEGMENT_BYTES = 32 * 1024 * 1024; // 32 MB per segment
    private static final int MAX_IMAGE_BYTES = 25 * 1024 * 1024; // 25 MB per image
    private static final int HLS_CONCURRENCY = 4;
    private static final int HLS_PREFETCH_WINDOW = 8;
    private static final int MAX_SEGMENT_RETRIES = 3;

    public static final String ACTION_DOWNLOAD_MEDIA = "dev.brave.action.DOWNLOAD_MEDIA";
    public static final String ACTION_DOWNLOAD_IMAGES = "dev.brave.action.DOWNLOAD_IMAGES";
    public static final String EXTRA_JOB_ID = "extra_job_id";
    public static final String EXTRA_CONVERT_WEBP = "extra_convert_webp";

    // In-memory cache to prevent TransactionTooLargeException over Binder
    public static final Map<String, Object> PENDING_JOBS = new ConcurrentHashMap<>();

    private static final AtomicInteger sNextJobNotifId = new AtomicInteger(4100);
    private static final AtomicInteger sNextCompletedNotifId = new AtomicInteger(5000);

    private final ExecutorService mExecutor = Executors.newFixedThreadPool(3);
    private final AtomicInteger mActiveJobs = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, String> mActiveJobTitles = new ConcurrentHashMap<>();
    private NotificationManager mNotifManager;
    private volatile boolean mShuttingDown = false;

    private static final class JobProgress {
        final int notifId;
        long lastNotifTime = 0;
        int lastProgress = -1;

        JobProgress(int notifId) {
            this.notifId = notifId;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mNotifManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mShuttingDown = false;
        // Android 8.0+ strict requirement: Always call startForeground immediately
        startForeground(FOREGROUND_NOTIF_ID, buildSummaryNotification());

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
                final int jobNotifId = sNextJobNotifId.incrementAndGet();
                mActiveJobs.incrementAndGet();
                mActiveJobTitles.put(jobNotifId, item.title);
                updateSummaryNotification();
                mExecutor.execute(() -> {
                    JobProgress jp = new JobProgress(jobNotifId);
                    try {
                        processMediaDownload(item, jp);
                    } finally {
                        cancelJobNotification(jobNotifId);
                        mActiveJobTitles.remove(jobNotifId);
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
                    final int jobNotifId = sNextJobNotifId.incrementAndGet();
                    mActiveJobs.incrementAndGet();
                    mActiveJobTitles.put(jobNotifId, "Image Batch (" + images.size() + ")");
                    updateSummaryNotification();
                    mExecutor.execute(() -> {
                        JobProgress jp = new JobProgress(jobNotifId);
                        try {
                            processBulkImageDownload(images, convertWebp, jp);
                        } finally {
                            cancelJobNotification(jobNotifId);
                            mActiveJobTitles.remove(jobNotifId);
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

    /**
     * Android 14+ foreground service timeout handler (shortService / dataSync).
     */
    @Override
    public void onTimeout(int startId) {
        handleForegroundTimeout();
    }

    /**
     * Android 15+ (API 35+) dataSync 6-hour foreground service timeout handler.
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        handleForegroundTimeout();
    }

    private void handleForegroundTimeout() {
        Log.w(TAG, "Foreground service timeout reached; stopping gracefully");
        mShuttingDown = true;
        mExecutor.shutdownNow();
        postCompletedNotification("Download Interrupted", "Stopped due to Android background service time limit", false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
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
        mShuttingDown = true;
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
        } else {
            updateSummaryNotification();
        }
    }

    private void cancelJobNotification(int jobNotifId) {
        try {
            if (mNotifManager != null) mNotifManager.cancel(jobNotifId);
        } catch (Throwable ignored) {}
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
            if (dm != null && file != null && file.exists() && file.length() > 0) {
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

    private void processMediaDownload(MediaItem item, JobProgress jp) {
        boolean success = false;
        String trackKey = (item.trackingUrl != null && !item.trackingUrl.isEmpty()) ? item.trackingUrl : item.url;
        try {
            updateJobNotification(jp, item.title, "Starting download...", 0);

            if (item.type == MediaType.HLS_STREAM) {
                downloadHlsStream(item, jp);
            } else if (item.type == MediaType.DASH_STREAM) {
                postCompletedNotification("Download Unsupported", "DASH streams are not supported natively", false);
                return;
            } else {
                downloadDirectStream(item, jp);
            }

            success = true;
            postCompletedNotification("Download Complete", item.title, true);
        } catch (Throwable t) {
            Log.e(TAG, "Download failed for " + item.url, t);
            String errMsg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            postCompletedNotification("Download Failed", item.title + ": " + errMsg, false);
        } finally {
            MediaPickerBottomSheet.ACTIVE_DOWNLOAD_URLS.remove(item.url);
            if (trackKey != null && !trackKey.isEmpty()) {
                MediaPickerBottomSheet.ACTIVE_DOWNLOAD_URLS.remove(trackKey);
            }
            MediaPickerBottomSheet.notifyDownloadFinished(item.url, success);
            if (trackKey != null && !trackKey.equals(item.url)) {
                MediaPickerBottomSheet.notifyDownloadFinished(trackKey, success);
            }
        }
    }

    private void downloadHlsStream(MediaItem item, JobProgress jp) throws Exception {
        String playlistUrl = item.url;
        String audioPlaylistUrl = item.audioUrl != null ? item.audioUrl : "";

        if (!item.variants.isEmpty()) {
            List<MediaItem.HlsVariant> sorted = new ArrayList<>(item.variants);
            Collections.sort(sorted);
            MediaItem.HlsVariant best = sorted.get(0);
            playlistUrl = best.url;
            if (audioPlaylistUrl.isEmpty() && best.audioPlaylistUrl != null && !best.audioPlaylistUrl.isEmpty()) {
                audioPlaylistUrl = best.audioPlaylistUrl;
            }
        }

        String refPage = (item.pageUrl != null && !item.pageUrl.isEmpty()) ? item.pageUrl : item.referer;
        NetworkSafety.TextFetchResult plResult = fetchTextSafe(playlistUrl, item, refPage);
        if (plResult == null || plResult.body == null || plResult.body.isEmpty() || plResult.truncated) {
            throw new IOException("Failed fetching complete HLS playlist");
        }

        String resolvedPlaylistUrl = plResult.finalUrl;
        String mediaPlaylistText = plResult.body;

        // If the fetched playlist is actually a master playlist (e.g., master passed directly), resolve its best variant
        if (mediaPlaylistText.contains("#EXT-X-STREAM-INF:")) {
            List<MediaItem.HlsVariant> nestedVariants = HlsParser.parseMasterPlaylist(resolvedPlaylistUrl, mediaPlaylistText);
            if (nestedVariants.isEmpty()) {
                throw new IllegalStateException("No playable variants found in HLS master playlist");
            }
            Collections.sort(nestedVariants);
            MediaItem.HlsVariant best = nestedVariants.get(0);
            if (audioPlaylistUrl.isEmpty() && best.audioPlaylistUrl != null && !best.audioPlaylistUrl.isEmpty()) {
                audioPlaylistUrl = best.audioPlaylistUrl;
            }
            NetworkSafety.TextFetchResult varResult = fetchTextSafe(best.url, item, refPage);
            if (varResult == null || varResult.body == null || varResult.body.isEmpty() || varResult.truncated) {
                throw new IOException("Failed fetching complete HLS variant playlist: " + best.url);
            }
            resolvedPlaylistUrl = varResult.finalUrl;
            mediaPlaylistText = varResult.body;
        }

        if (HlsParser.isIFramesOnlyPlaylist(mediaPlaylistText)) {
            throw new UnsupportedOperationException("I-frames-only HLS playlists are not supported");
        }

        List<HlsParser.SegmentInfo> segments = HlsParser.parseMediaPlaylist(resolvedPlaylistUrl, mediaPlaylistText);
        if (segments.isEmpty()) {
            throw new IllegalStateException("No media segments found in HLS playlist");
        }

        for (HlsParser.SegmentInfo seg : segments) {
            if (seg.keyInfo != null && seg.keyInfo.isDrm()) {
                throw new UnsupportedOperationException("DRM protected HLS streams (SAMPLE-AES) are not supported");
            }
        }

        File tempDir = new File(getCacheDir(), "brave_hls_" + UUID.randomUUID().toString());
        if (!tempDir.mkdirs()) {
            throw new IllegalStateException("Failed creating temporary directory for HLS download");
        }

        File concatenatedVideo = new File(tempDir, "stream_video.ts");
        File concatenatedAudio = null;

        try {
            // Download primary (video / muxed) segments in parallel bounded windows
            downloadHlsSegmentList(segments, item, refPage, concatenatedVideo, jp,
                    audioPlaylistUrl.isEmpty() ? 0 : 0, audioPlaylistUrl.isEmpty() ? 85 : 65, "segment");

            // Download separate EXT-X-MEDIA audio rendition if present
            if (!audioPlaylistUrl.isEmpty()) {
                NetworkSafety.TextFetchResult audioPl = fetchTextSafe(audioPlaylistUrl, item, refPage);
                if (audioPl == null || audioPl.body == null || audioPl.body.isEmpty() || audioPl.truncated) {
                    throw new IOException("Failed fetching complete HLS audio rendition playlist: " + audioPlaylistUrl);
                }
                List<HlsParser.SegmentInfo> audioSegments = HlsParser.parseMediaPlaylist(audioPl.finalUrl, audioPl.body);
                if (audioSegments.isEmpty()) {
                    throw new IllegalStateException("No media segments found in HLS audio rendition playlist");
                }
                for (HlsParser.SegmentInfo seg : audioSegments) {
                    if (seg.keyInfo != null && seg.keyInfo.isDrm()) {
                        throw new UnsupportedOperationException("DRM protected audio rendition is not supported");
                    }
                }
                concatenatedAudio = new File(tempDir, "stream_audio.ts");
                downloadHlsSegmentList(audioSegments, item, refPage, concatenatedAudio, jp, 65, 85, "audio");
            }

            if (mShuttingDown || Thread.currentThread().isInterrupted()) {
                throw new IOException("Download cancelled");
            }

            updateJobNotification(jp, item.title, "Remuxing into MP4...", 90);

            File remuxedMp4 = new File(tempDir, "remuxed.mp4");
            boolean remuxSuccess = remuxToMp4(concatenatedVideo, concatenatedAudio, remuxedMp4);

            File finalSource = remuxSuccess ? remuxedMp4 : concatenatedVideo;
            String outExt = remuxSuccess ? ".mp4" : ".ts";
            String outMime = remuxSuccess ? "video/mp4" : "video/mp2t";
            String sanitizedTitle = sanitizeFilename(item.title) + outExt;

            String savedRef = saveFileToDestination(finalSource, sanitizedTitle, outMime, false);

            String trackKey = (item.trackingUrl != null && !item.trackingUrl.isEmpty()) ? item.trackingUrl : item.url;
            PatchPrefs.markMediaDownloaded(this, item.url, savedRef);
            if (trackKey != null && !trackKey.equals(item.url)) {
                PatchPrefs.markMediaDownloaded(this, trackKey, savedRef);
            }
        } finally {
            deleteRecursive(tempDir);
        }
    }

    /**
     * Downloads HLS segments concurrently using a bounded worker pool and sliding prefetch window,
     * decrypts both EXT-X-MAP init sections and media segments fail-closed, respects EXT-X-BYTERANGE,
     * retries transient failures, and aborts on any missing segment.
     */
    private void downloadHlsSegmentList(
            List<HlsParser.SegmentInfo> segments,
            MediaItem item,
            String refPage,
            File outputFile,
            JobProgress jp,
            int progressStart,
            int progressEnd,
            String label
    ) throws Exception {
        final int total = segments.size();
        final Map<String, byte[]> keyCache = new ConcurrentHashMap<>();
        final Map<String, Boolean> writtenInitKeys = new HashMap<>();
        final ExecutorService segPool = Executors.newFixedThreadPool(Math.min(HLS_CONCURRENCY, Math.max(1, total)));

        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(outputFile), 64 * 1024)) {
            int windowStart = 0;
            while (windowStart < total) {
                if (mShuttingDown || Thread.currentThread().isInterrupted()) {
                    throw new IOException("Download interrupted");
                }
                int windowEnd = Math.min(total, windowStart + HLS_PREFETCH_WINDOW);
                List<Future<byte[]>> futures = new ArrayList<>(windowEnd - windowStart);

                for (int i = windowStart; i < windowEnd; i++) {
                    final HlsParser.SegmentInfo seg = segments.get(i);
                    futures.add(segPool.submit(() -> fetchAndDecryptSegment(seg, item, refPage, keyCache)));
                }

                for (int offset = 0; offset < futures.size(); offset++) {
                    int idx = windowStart + offset;
                    HlsParser.SegmentInfo seg = segments.get(idx);

                    // Fetch & decrypt EXT-X-MAP initialization segment once per unique (url, range)
                    if (seg.initSegmentUrl != null && !seg.initSegmentUrl.isEmpty()) {
                        String initKeyId = seg.initSegmentUrl + "@" + seg.initSegmentByteRangeOffset + ":" + seg.initSegmentByteRangeLength;
                        if (!writtenInitKeys.containsKey(initKeyId)) {
                            byte[] initBytes = fetchRangeWithRetry(
                                    seg.initSegmentUrl,
                                    seg.initSegmentByteRangeOffset,
                                    seg.initSegmentByteRangeLength,
                                    item, refPage, MAX_SEGMENT_BYTES
                            );
                            if (initBytes == null || initBytes.length == 0) {
                                throw new IOException("Failed fetching EXT-X-MAP initialization segment: " + seg.initSegmentUrl);
                            }
                            HlsParser.KeyInfo initEnc = (seg.initSegmentKeyInfo != null) ? seg.initSegmentKeyInfo : seg.keyInfo;
                            if (initEnc != null && initEnc.isAes128()) {
                                byte[] keyBytes = fetchAesKey(initEnc, item, refPage, keyCache);
                                byte[] iv = parseIv(initEnc.ivHex, 0L);
                                byte[] decInit = decryptAes128(initBytes, keyBytes, iv);
                                if (decInit == null) {
                                    throw new IOException("Failed decrypting AES-128 EXT-X-MAP initialization segment");
                                }
                                initBytes = decInit;
                            }
                            bos.write(initBytes);
                            writtenInitKeys.put(initKeyId, Boolean.TRUE);
                        }
                    }

                    byte[] segBytes;
                    try {
                        segBytes = futures.get(offset).get();
                    } catch (Exception e) {
                        for (int j = offset + 1; j < futures.size(); j++) {
                            futures.get(j).cancel(true);
                        }
                        throw e;
                    }

                    if (segBytes == null || segBytes.length == 0) {
                        throw new IOException("Empty HLS segment " + (idx + 1) + "/" + total);
                    }
                    bos.write(segBytes);

                    int pct = progressStart + (int) (((idx + 1) / (float) total) * (progressEnd - progressStart));
                    throttledJobNotification(jp, item.title,
                            String.format(Locale.ROOT, "Downloading %s %d/%d", label, idx + 1, total), pct);
                }
                windowStart = windowEnd;
            }
            bos.flush();
        } finally {
            segPool.shutdownNow();
        }
    }

    private byte[] fetchAndDecryptSegment(
            HlsParser.SegmentInfo seg,
            MediaItem item,
            String refPage,
            Map<String, byte[]> keyCache
    ) throws Exception {
        byte[] segBytes = fetchRangeWithRetry(
                seg.url, seg.byteRangeOffset, seg.byteRangeLength, item, refPage, MAX_SEGMENT_BYTES
        );
        if (segBytes == null || segBytes.length == 0) {
            throw new IOException("Failed fetching HLS segment " + seg.sequenceNumber + ": " + seg.url);
        }

        if (seg.keyInfo != null && seg.keyInfo.isAes128()) {
            byte[] keyBytes = fetchAesKey(seg.keyInfo, item, refPage, keyCache);
            byte[] iv = parseIv(seg.keyInfo.ivHex, seg.sequenceNumber);
            byte[] decrypted = decryptAes128(segBytes, keyBytes, iv);
            if (decrypted == null) {
                throw new IOException("AES-128 decryption failed for segment " + seg.sequenceNumber);
            }
            return decrypted;
        }
        return segBytes;
    }

    private byte[] fetchAesKey(
            HlsParser.KeyInfo keyInfo,
            MediaItem item,
            String refPage,
            Map<String, byte[]> keyCache
    ) throws Exception {
        if (keyInfo.uri == null || keyInfo.uri.isEmpty()) {
            throw new IOException("Missing AES-128 key URI");
        }
        byte[] cached = keyCache.get(keyInfo.uri);
        if (cached != null) return cached;

        byte[] keyBytes = fetchRangeWithRetry(keyInfo.uri, -1, -1, item, refPage, MAX_KEY_BYTES);
        if (keyBytes == null || keyBytes.length != 16) {
            throw new IOException("Invalid AES-128 key length (" + (keyBytes == null ? 0 : keyBytes.length) + " bytes) from " + keyInfo.uri);
        }
        keyCache.put(keyInfo.uri, keyBytes);
        return keyBytes;
    }

    private byte[] fetchRangeWithRetry(
            String urlStr,
            long byteRangeOffset,
            long byteRangeLength,
            MediaItem item,
            String refPage,
            int maxBytes
    ) throws Exception {
        IOException lastError = null;
        boolean hasRange = byteRangeLength > 0 && byteRangeOffset >= 0;
        long expectedEnd = -1;
        String rangeHeader = null;
        if (hasRange) {
            if (byteRangeLength > maxBytes || byteRangeOffset > Long.MAX_VALUE - byteRangeLength) {
                throw new IOException("Requested byte range out of bounds for " + urlStr);
            }
            expectedEnd = byteRangeOffset + byteRangeLength - 1;
            rangeHeader = "bytes=" + byteRangeOffset + "-" + expectedEnd;
        }

        for (int attempt = 1; attempt <= MAX_SEGMENT_RETRIES; attempt++) {
            if (mShuttingDown || Thread.currentThread().isInterrupted()) {
                throw new IOException("Download cancelled");
            }
            NetworkSafety.SafeConnection sc = null;
            try {
                sc = NetworkSafety.openSafeConnection(
                        urlStr, "GET", item.userAgent, item.cookie, item.cookieSourceUrl,
                        refPage, refPage, rangeHeader, false, 10000, 15000
                );
                int code = sc.responseCode;
                if (code == 206) {
                    if (!hasRange) {
                        throw new IOException("Unexpected HTTP 206 without Range request for " + urlStr);
                    }
                    String contentRange = sc.conn.getHeaderField("Content-Range");
                    if (contentRange == null || contentRange.trim().isEmpty()) {
                        throw new IOException("HTTP 206 missing Content-Range header for " + urlStr);
                    }
                    Matcher crMatch = CONTENT_RANGE_PATTERN.matcher(contentRange.trim());
                    if (!crMatch.matches()) {
                        throw new IOException("Malformed Content-Range header '" + contentRange + "' for " + urlStr);
                    }
                    long respStart;
                    long respEnd;
                    try {
                        respStart = Long.parseLong(crMatch.group(1));
                        respEnd = Long.parseLong(crMatch.group(2));
                    } catch (NumberFormatException nfe) {
                        throw new IOException("Invalid Content-Range bounds '" + contentRange + "' for " + urlStr, nfe);
                    }
                    if (respStart != byteRangeOffset || respEnd != expectedEnd) {
                        throw new IOException("Content-Range mismatch for " + urlStr + ": expected "
                                + byteRangeOffset + "-" + expectedEnd + ", got " + contentRange);
                    }
                    String totalStr = crMatch.group(3);
                    if (!"*".equals(totalStr)) {
                        try {
                            long respTotal = Long.parseLong(totalStr);
                            if (respEnd >= respTotal) {
                                throw new IOException("Content-Range end exceeds total length '" + contentRange + "' for " + urlStr);
                            }
                        } catch (NumberFormatException nfe) {
                            throw new IOException("Invalid Content-Range total '" + contentRange + "' for " + urlStr, nfe);
                        }
                    }

                    byte[] data = NetworkSafety.readBytesBounded(sc.conn.getInputStream(), maxBytes);
                    if (data.length != byteRangeLength) {
                        throw new IOException("HTTP 206 length mismatch for " + urlStr + ": expected "
                                + byteRangeLength + " bytes, got " + data.length);
                    }
                    return data;
                } else if (code == 200) {
                    byte[] data = NetworkSafety.readBytesBounded(sc.conn.getInputStream(), maxBytes);
                    if (hasRange) {
                        // Server ignored Range and sent full resource: validate & slice exact requested byte range
                        if (byteRangeOffset >= data.length || byteRangeLength > (long) data.length - byteRangeOffset) {
                            throw new IOException("HTTP 200 response too short (" + data.length
                                    + " bytes) for requested byte range " + byteRangeOffset + "-" + expectedEnd + " of " + urlStr);
                        }
                        int start = (int) byteRangeOffset;
                        int end = (int) (byteRangeOffset + byteRangeLength);
                        return Arrays.copyOfRange(data, start, end);
                    }
                    return data;
                }
                lastError = new IOException("HTTP " + code + " for " + urlStr);
                if (code == 403 || code == 404 || code == 410) {
                    break; // Non-transient error
                }
            } catch (IOException e) {
                lastError = e;
            } finally {
                if (sc != null && sc.conn != null) sc.conn.disconnect();
            }
            try {
                Thread.sleep(300L * attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted", ie);
            }
        }
        throw (lastError != null) ? lastError : new IOException("Failed fetching " + urlStr);
    }

    private void downloadDirectStream(MediaItem item, JobProgress jp) throws Exception {
        String ext = item.type == MediaType.AUDIO ? ".mp3" : ".mp4";
        String lowerUrl = item.url.toLowerCase(Locale.ROOT);
        if (lowerUrl.contains(".webm")) ext = ".webm";
        else if (lowerUrl.contains(".mkv")) ext = ".mkv";

        String filename = sanitizeFilename(item.title) + ext;
        String mime = (item.mimeType != null && !item.mimeType.isEmpty()) ? item.mimeType : (item.type == MediaType.AUDIO ? "audio/mpeg" : "video/mp4");
        String refPage = (item.pageUrl != null && !item.pageUrl.isEmpty()) ? item.pageUrl : item.referer;

        Uri targetUri = null;
        File fallbackFile = null;
        boolean wroteToMediaStore = false;
        NetworkSafety.SafeConnection sc = null;

        try {
            sc = NetworkSafety.openSafeConnection(
                    item.url, "GET", item.userAgent, item.cookie, item.cookieSourceUrl,
                    refPage, refPage, null, false, 10000, 20000
            );
            int code = sc.responseCode;
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP server responded with " + code);
            }

            long length = sc.conn.getContentLengthLong();
            OutputStream out = null;

            targetUri = createMediaUri(filename, mime);
            if (targetUri != null) {
                out = getContentResolver().openOutputStream(targetUri);
                if (out != null) {
                    wroteToMediaStore = true;
                } else {
                    // Clean up unused pending MediaStore row before falling back to filesystem
                    safeDeleteUri(targetUri);
                    targetUri = null;
                }
            }
            if (out == null) {
                File dl = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BraveMedia");
                dl.mkdirs();
                fallbackFile = new File(dl, filename);
                out = new FileOutputStream(fallbackFile);
            }

            try (InputStream in = new BufferedInputStream(sc.conn.getInputStream());
                 OutputStream bos = new BufferedOutputStream(out)) {

                byte[] buffer = new byte[32 * 1024];
                long downloaded = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (mShuttingDown || Thread.currentThread().isInterrupted()) {
                        throw new IOException("Download cancelled");
                    }
                    bos.write(buffer, 0, read);
                    downloaded += read;
                    if (length > 0) {
                        int prog = (int) ((downloaded / (float) length) * 100);
                        throttledJobNotification(jp, item.title, "Downloading (" + prog + "%)", prog);
                    }
                }
                bos.flush();
            }

            String savedRef;
            if (wroteToMediaStore && targetUri != null) {
                completeMediaStoreEntry(targetUri);
                savedRef = targetUri.toString();
            } else {
                savedRef = fallbackFile.getAbsolutePath();
                MediaScannerConnection.scanFile(this, new String[]{savedRef}, new String[]{mime}, null);
                registerWithDownloadManager(item.title, filename, mime, fallbackFile);
            }
            PatchPrefs.markMediaDownloaded(this, item.url, savedRef);
        } catch (Throwable t) {
            if (targetUri != null) {
                safeDeleteUri(targetUri);
            }
            if (fallbackFile != null && fallbackFile.exists()) {
                fallbackFile.delete();
            }
            throw t;
        } finally {
            if (sc != null && sc.conn != null) sc.conn.disconnect();
        }
    }

    private void processBulkImageDownload(List<MediaItem> images, boolean convertWebp, JobProgress jp) {
        int total = images.size();
        int savedCount = 0;

        for (int i = 0; i < total; i++) {
            if (mShuttingDown || Thread.currentThread().isInterrupted()) break;
            MediaItem item = images.get(i);
            int progress = (int) (((i + 1) / (float) total) * 100);
            throttledJobNotification(jp, "Image Batch", String.format(Locale.ROOT, "Saving image %d/%d", i + 1, total), progress);

            try {
                String refPage = (item.pageUrl != null && !item.pageUrl.isEmpty()) ? item.pageUrl : item.referer;
                byte[] imgBytes = fetchRangeWithRetry(item.url, -1, -1, item, refPage, MAX_IMAGE_BYTES);
                if (imgBytes == null || imgBytes.length == 0) continue;

                boolean isWebp = item.url.toLowerCase(Locale.ROOT).contains(".webp") || item.mimeType.contains("webp");
                String filename;
                String mimeType;
                byte[] outputBytes;

                if (isWebp && convertWebp) {
                    Bitmap bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
                    if (bmp != null) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        boolean ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
                        bmp.recycle();
                        if (ok && baos.size() > 0) {
                            outputBytes = baos.toByteArray();
                            filename = sanitizeFilename(item.title) + "_" + (i + 1) + ".png";
                            mimeType = "image/png";
                        } else {
                            outputBytes = imgBytes;
                            filename = sanitizeFilename(item.title) + "_" + (i + 1) + ".webp";
                            mimeType = "image/webp";
                        }
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

                String savedRef = saveBytesToImageStore(outputBytes, filename, mimeType);
                savedCount++;
                PatchPrefs.markMediaDownloaded(this, item.url, savedRef);
            } catch (Throwable t) {
                Log.w(TAG, "Failed downloading image: " + item.url, t);
            }
        }

        MediaPickerBottomSheet.sBulkImagesDownloading = false;
        String resultMsg = String.format(Locale.ROOT, "Saved %d of %d images to Pictures/BraveMedia", savedCount, total);
        postCompletedNotification("Image Batch Complete", resultMsg, savedCount > 0);
    }

    /**
     * Remuxes MPEG-TS / fMP4 video (and optional separate EXT-X-MEDIA audio file) into standard MP4
     * with dynamic sample buffer growth.
     */
    private boolean remuxToMp4(File inputVideo, File inputAudio, File outputMp4) {
        MediaExtractor vExtractor = new MediaExtractor();
        MediaExtractor aExtractor = null;
        MediaMuxer muxer = null;
        try {
            vExtractor.setDataSource(inputVideo.getAbsolutePath());
            int vTrackCount = vExtractor.getTrackCount();
            if (vTrackCount <= 0) return false;

            muxer = new MediaMuxer(outputMp4.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Map<Integer, Integer> vTrackMap = new HashMap<>();
            int initialBufSize = 2 * 1024 * 1024;
            boolean hasSeparateAudio = (inputAudio != null && inputAudio.exists() && inputAudio.length() > 0);

            for (int i = 0; i < vTrackCount; i++) {
                MediaFormat format = vExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && (mime.startsWith("video/") || (!hasSeparateAudio && mime.startsWith("audio/")))) {
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        initialBufSize = Math.max(initialBufSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                    }
                    vExtractor.selectTrack(i);
                    vTrackMap.put(i, muxer.addTrack(format));
                }
            }

            int separateAudioDstTrack = -1;
            if (hasSeparateAudio) {
                aExtractor = new MediaExtractor();
                aExtractor.setDataSource(inputAudio.getAbsolutePath());
                for (int i = 0; i < aExtractor.getTrackCount(); i++) {
                    MediaFormat format = aExtractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                            initialBufSize = Math.max(initialBufSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                        }
                        aExtractor.selectTrack(i);
                        separateAudioDstTrack = muxer.addTrack(format);
                        break;
                    }
                }
            }

            if (vTrackMap.isEmpty()) return false;

            muxer.start();

            ByteBuffer[] bufHolder = new ByteBuffer[]{ByteBuffer.allocate(Math.min(initialBufSize, 16 * 1024 * 1024))};
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            while (true) {
                int sampleTrack = vExtractor.getSampleTrackIndex();
                if (sampleTrack < 0) break;

                bufferInfo.offset = 0;
                bufferInfo.size = LocalDownloadRelay.readSampleGrowing(vExtractor, bufHolder);
                if (bufferInfo.size < 0) break;

                bufferInfo.presentationTimeUs = vExtractor.getSampleTime();
                int sampleFlags = vExtractor.getSampleFlags();
                bufferInfo.flags = ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;

                Integer muxerTrack = vTrackMap.get(sampleTrack);
                if (muxerTrack != null) {
                    muxer.writeSampleData(muxerTrack, bufHolder[0], bufferInfo);
                }
                vExtractor.advance();
            }

            if (aExtractor != null && separateAudioDstTrack >= 0) {
                while (true) {
                    int sampleTrack = aExtractor.getSampleTrackIndex();
                    if (sampleTrack < 0) break;
                    bufferInfo.offset = 0;
                    bufferInfo.size = LocalDownloadRelay.readSampleGrowing(aExtractor, bufHolder);
                    if (bufferInfo.size < 0) break;
                    bufferInfo.presentationTimeUs = aExtractor.getSampleTime();
                    int sampleFlags = aExtractor.getSampleFlags();
                    bufferInfo.flags = ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    muxer.writeSampleData(separateAudioDstTrack, bufHolder[0], bufferInfo);
                    aExtractor.advance();
                }
            }

            muxer.stop();
            return outputMp4.exists() && outputMp4.length() > 0;
        } catch (Throwable t) {
            Log.w(TAG, "MediaMuxer remux encountered an issue, falling back to raw stream", t);
            return false;
        } finally {
            try { vExtractor.release(); } catch (Throwable ignored) {}
            if (aExtractor != null) {
                try { aExtractor.release(); } catch (Throwable ignored) {}
            }
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

    private void safeDeleteUri(Uri uri) {
        if (uri != null) {
            try {
                getContentResolver().delete(uri, null, null);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Unified destination writer with strict rollback: never leaves orphaned IS_PENDING=1 rows.
     */
    private String saveFileToDestination(File source, String filename, String mimeType, boolean isImage) throws Exception {
        Uri targetUri = isImage ? createImageMediaUri(filename, mimeType) : createMediaUri(filename, mimeType);
        if (targetUri != null) {
            try {
                try (InputStream in = new FileInputStream(source);
                     OutputStream out = getContentResolver().openOutputStream(targetUri)) {
                    if (out == null) {
                        throw new IOException("Failed opening MediaStore output stream");
                    }
                    copyStream(in, out);
                }
                completeMediaStoreEntry(targetUri);
                return targetUri.toString();
            } catch (Throwable t) {
                safeDeleteUri(targetUri);
                throw t;
            }
        } else {
            String dirType = isImage ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS;
            File downloadDir = new File(Environment.getExternalStoragePublicDirectory(dirType), "BraveMedia");
            if (!downloadDir.exists() && !downloadDir.mkdirs() && !downloadDir.exists()) {
                throw new IOException("Storage permission required to write to " + dirType + "/BraveMedia");
            }
            File dest = new File(downloadDir, filename);
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = new FileOutputStream(dest)) {
                copyStream(in, out);
            } catch (Throwable t) {
                if (dest.exists()) dest.delete();
                throw t;
            }
            MediaScannerConnection.scanFile(this, new String[]{dest.getAbsolutePath()}, new String[]{mimeType}, null);
            if (!isImage) {
                registerWithDownloadManager(filename, filename, mimeType, dest);
            }
            return dest.getAbsolutePath();
        }
    }

    private String saveBytesToImageStore(byte[] data, String filename, String mimeType) throws Exception {
        Uri targetUri = createImageMediaUri(filename, mimeType);
        if (targetUri != null) {
            try {
                try (OutputStream os = getContentResolver().openOutputStream(targetUri)) {
                    if (os == null) {
                        throw new IOException("Failed opening MediaStore image stream");
                    }
                    os.write(data);
                    os.flush();
                }
                completeMediaStoreEntry(targetUri);
                return targetUri.toString();
            } catch (Throwable t) {
                safeDeleteUri(targetUri);
                throw t;
            }
        } else {
            File dl = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "BraveMedia");
            if (!dl.exists() && !dl.mkdirs() && !dl.exists()) {
                throw new IOException("Storage permission required to save images");
            }
            File dest = new File(dl, filename);
            try (FileOutputStream fos = new FileOutputStream(dest)) {
                fos.write(data);
                fos.flush();
            } catch (Throwable t) {
                if (dest.exists()) dest.delete();
                throw t;
            }
            MediaScannerConnection.scanFile(this, new String[]{dest.getAbsolutePath()}, new String[]{mimeType}, null);
            return dest.getAbsolutePath();
        }
    }

    private NetworkSafety.TextFetchResult fetchTextSafe(String urlStr, MediaItem item, String refPage) throws IOException {
        return NetworkSafety.fetchTextStrict(
                urlStr, item.userAgent, item.cookie, item.cookieSourceUrl,
                refPage, refPage, MAX_PLAYLIST_CHARS, 12000
        );
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

    private Notification buildSummaryNotification() {
        int active = Math.max(1, mActiveJobs.get());
        String msg = active == 1
                ? "Downloading 1 media item..."
                : ("Downloading " + active + " media items...");
        return buildProgressNotification("Brave Media Downloader", msg, 0, true);
    }

    private void updateSummaryNotification() {
        try {
            if (mNotifManager != null) {
                mNotifManager.notify(FOREGROUND_NOTIF_ID, buildSummaryNotification());
            }
        } catch (Throwable ignored) {}
    }

    private Notification buildProgressNotification(String title, String message, int progress, boolean ongoing) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle(title != null && !title.isEmpty() ? title : "Brave Media Downloader")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, progress, progress <= 0)
                .setOngoing(ongoing);

        return builder.build();
    }

    private void updateJobNotification(JobProgress jp, String title, String message, int progress) {
        try {
            if (mNotifManager != null) {
                mNotifManager.notify(jp.notifId, buildProgressNotification(title, message, progress, true));
            }
        } catch (Throwable ignored) {}
    }

    private void throttledJobNotification(JobProgress jp, String title, String message, int progress) {
        long now = System.currentTimeMillis();
        if (progress != jp.lastProgress && (now - jp.lastNotifTime > 600 || progress == 100)) {
            jp.lastNotifTime = now;
            jp.lastProgress = progress;
            updateJobNotification(jp, title, message, progress);
        }
    }
}

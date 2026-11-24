package dev.brave.extension.media;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Dual-stack loopback HTTP relay that allows Brave's native C++ content::DownloadManager
 * (J.N.VOO 0x13) to download Referer/Cookie-protected CDN streams & images (Erome, YouTube,
 * Vidara, etc.) with optional on-the-fly WebP->PNG conversion, full live progress,
 * Brave notifications, and permanent history in chrome-native://downloads/ + Android Gallery.
 */
public final class LocalDownloadRelay {
    private static final String TAG = "Brave_DownloadRelay";
    private static final ExecutorService RELAY_POOL = Executors.newCachedThreadPool();

    private LocalDownloadRelay() {}

    public static String startRelay(final MediaItem item, final String filename, final String resolvedMime, final Runnable onComplete) {
        return startRelay(item, filename, resolvedMime, false, onComplete);
    }

    public static String startRelay(
            final MediaItem item,
            final String filename,
            final String resolvedMime,
            final boolean convertWebpToPng,
            final Runnable onComplete
    ) {
        if (item == null || item.url == null || item.url.isEmpty()) return null;
        try {
            final ServerSocket server = new ServerSocket(0, 8);
            server.setSoTimeout(45000);
            final int port = server.getLocalPort();
            String initialName = sanitizePathSegment(filename);
            if (convertWebpToPng && initialName.toLowerCase(Locale.ROOT).endsWith(".webp")) {
                initialName = initialName.substring(0, initialName.length() - 5) + ".png";
            }
            final String safeName = initialName;
            final String relayUrl = "http://127.0.0.1:" + port + "/" + Uri.encode(safeName);

            RELAY_POOL.execute(() -> {
                try (ServerSocket ss = server) {
                    for (int attempt = 0; attempt < 6; attempt++) {
                        Socket client = null;
                        HttpURLConnection upstream = null;
                        boolean servedBody = false;
                        try {
                            client = ss.accept();
                            if (!client.getInetAddress().isLoopbackAddress()) {
                                client.close();
                                continue;
                            }
                            client.setSoTimeout(45000);

                            BufferedReader reqReader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                            String reqLine = reqReader.readLine();
                            if (reqLine == null) {
                                client.close();
                                continue;
                            }
                            boolean isHead = reqLine.toUpperCase(Locale.ROOT).startsWith("HEAD ");
                            String rangeHeader = null;
                            String line;
                            while ((line = reqReader.readLine()) != null && !line.isEmpty()) {
                                if (line.toLowerCase(Locale.ROOT).startsWith("range:")) {
                                    rangeHeader = line.substring(6).trim();
                                }
                            }
                            String targetUpstreamUrl = item.url.replace("\\u0026", "&").replace("\\/", "/");
                            Log.i(TAG, "Chromium connected (" + reqLine + ") -> " + targetUpstreamUrl);

                            URL u = new URL(targetUpstreamUrl);
                            upstream = (HttpURLConnection) u.openConnection();
                            upstream.setRequestMethod(isHead ? "HEAD" : "GET");
                            upstream.setConnectTimeout(15000);
                            upstream.setReadTimeout(45000);
                            upstream.setInstanceFollowRedirects(true);
                            upstream.setRequestProperty("Accept", "*/*");
                            if (item.userAgent != null && !item.userAgent.isEmpty()) {
                                upstream.setRequestProperty("User-Agent", item.userAgent);
                            } else {
                                upstream.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
                            }
                            if (item.referer != null && !item.referer.isEmpty()) {
                                upstream.setRequestProperty("Referer", item.referer);
                                try {
                                    Uri refUri = Uri.parse(item.referer);
                                    if (refUri.getScheme() != null && refUri.getHost() != null) {
                                        upstream.setRequestProperty("Origin", refUri.getScheme() + "://" + refUri.getHost());
                                    }
                                } catch (Throwable ignored) {}
                            }
                            if (item.cookie != null && !item.cookie.isEmpty()) {
                                upstream.setRequestProperty("Cookie", item.cookie);
                            }
                            if (rangeHeader != null && !rangeHeader.isEmpty() && !convertWebpToPng) {
                                upstream.setRequestProperty("Range", rangeHeader);
                            }

                            int code = upstream.getResponseCode();
                            OutputStream out = client.getOutputStream();
                            if (code >= 200 && code < 300) {
                                String ct = upstream.getContentType();
                                if (ct == null || ct.isEmpty() || ct.contains("octet-stream")) {
                                    ct = resolvedMime;
                                }

                                boolean shouldConvert = convertWebpToPng && !isHead
                                        && (ct.toLowerCase(Locale.ROOT).contains("webp")
                                        || item.url.toLowerCase(Locale.ROOT).contains(".webp"));

                                if (shouldConvert) {
                                    byte[] rawBytes;
                                    try (InputStream in = new BufferedInputStream(upstream.getInputStream(), 64 * 1024);
                                         ByteArrayOutputStream rawOut = new ByteArrayOutputStream()) {
                                        byte[] buf = new byte[64 * 1024];
                                        int n;
                                        while ((n = in.read(buf)) != -1) {
                                            rawOut.write(buf, 0, n);
                                        }
                                        rawBytes = rawOut.toByteArray();
                                    }
                                    byte[] payload = rawBytes;
                                    String outMime = ct;
                                    String outName = safeName;
                                    try {
                                        Bitmap bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.length);
                                        if (bmp != null) {
                                            ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
                                            bmp.compress(Bitmap.CompressFormat.PNG, 100, pngOut);
                                            bmp.recycle();
                                            payload = pngOut.toByteArray();
                                            outMime = "image/png";
                                            if (!outName.toLowerCase(Locale.ROOT).endsWith(".png")) {
                                                outName = outName + ".png";
                                            }
                                        }
                                    } catch (Throwable ignored) {}

                                    String headers = "HTTP/1.1 200 OK\r\n"
                                            + "Content-Type: " + outMime + "\r\n"
                                            + "Content-Disposition: attachment; filename=\"" + outName + "\"\r\n"
                                            + "Content-Length: " + payload.length + "\r\n"
                                            + "Connection: close\r\n\r\n";
                                    out.write(headers.getBytes("UTF-8"));
                                    out.write(payload);
                                    out.flush();
                                    servedBody = true;
                                    Log.i(TAG, "Relay WebP->PNG complete for " + outName + " (" + payload.length + " bytes)");
                                } else {
                                    long contentLength = upstream.getContentLengthLong();
                                    String statusLine = (code == 206) ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n";
                                    StringBuilder headers = new StringBuilder();
                                    headers.append(statusLine);
                                    headers.append("Content-Type: ").append(ct).append("\r\n");
                                    headers.append("Content-Disposition: attachment; filename=\"").append(safeName).append("\"\r\n");
                                    headers.append("Accept-Ranges: bytes\r\n");
                                    if (contentLength > 0) {
                                        headers.append("Content-Length: ").append(contentLength).append("\r\n");
                                    }
                                    String contentRange = upstream.getHeaderField("Content-Range");
                                    if (contentRange != null && !contentRange.isEmpty()) {
                                        headers.append("Content-Range: ").append(contentRange).append("\r\n");
                                    }
                                    headers.append("Connection: close\r\n\r\n");
                                    out.write(headers.toString().getBytes("UTF-8"));

                                    if (!isHead) {
                                        try (InputStream in = new BufferedInputStream(upstream.getInputStream(), 64 * 1024)) {
                                            byte[] buf = new byte[64 * 1024];
                                            int n;
                                            while ((n = in.read(buf)) != -1) {
                                                out.write(buf, 0, n);
                                            }
                                            out.flush();
                                        }
                                        servedBody = true;
                                        Log.i(TAG, "Relay stream complete for " + safeName);
                                    } else {
                                        out.flush();
                                    }
                                }
                            } else {
                                String err = "HTTP/1.1 " + code + " Error\r\nConnection: close\r\n\r\n";
                                out.write(err.getBytes("UTF-8"));
                                out.flush();
                                Log.w(TAG, "Upstream returned HTTP " + code + " for " + item.url);
                                break;
                            }
                        } finally {
                            if (upstream != null) upstream.disconnect();
                            if (client != null) {
                                try { client.close(); } catch (Throwable ignored) {}
                            }
                        }
                        if (servedBody) break;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Relay error for " + item.url + ": " + t.getMessage());
                } finally {
                    if (onComplete != null) onComplete.run();
                }
            });

            return relayUrl;
        } catch (Throwable t) {
            Log.e(TAG, "Failed starting local download relay", t);
            return null;
        }
    }

    private static String sanitizePathSegment(String name) {
        if (name == null || name.isEmpty()) return "media.bin";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Downloads adaptive HD video + M4A audio in parallel, muxes them losslessly via Android's
     * built-in MediaMuxer, and streams the finished HD MP4 into Brave's native DownloadManager.
     */
    public static void startMuxedDownload(
            final File cacheDir,
            final MediaItem item,
            final String filename,
            final Runnable onRelayReadyTrigger,
            final java.util.function.Consumer<String> onRelayUrlReady,
            final Runnable onComplete
    ) {
        RELAY_POOL.execute(() -> {
            File tmpVideo = null;
            File tmpAudio = null;
            File tmpMuxed = null;
            try {
                long ts = System.currentTimeMillis();
                tmpVideo = new File(cacheDir, "yt_v_" + ts + ".mp4");
                tmpAudio = new File(cacheDir, "yt_a_" + ts + ".m4a");
                tmpMuxed = new File(cacheDir, "yt_mux_" + ts + ".mp4");

                final File fVid = tmpVideo;
                final File fAud = tmpAudio;
                Future<Boolean> vFut = RELAY_POOL.submit(() -> downloadToFile(item.url, item.userAgent, fVid));
                Future<Boolean> aFut = RELAY_POOL.submit(() -> downloadToFile(item.audioUrl, item.userAgent, fAud));

                boolean vOk = vFut.get();
                boolean aOk = aFut.get();
                if (!vOk || !aOk || fVid.length() <= 0 || fAud.length() <= 0) {
                    Log.w(TAG, "Mux download failed: vOk=" + vOk + " (" + fVid.length() + ") aOk=" + aOk + " (" + fAud.length() + ")");
                    if (onComplete != null) onComplete.run();
                    return;
                }

                muxMp4(fVid, fAud, tmpMuxed);
                Log.i(TAG, "Muxed HD MP4 ready: " + tmpMuxed.length() + " bytes -> serving to Brave DownloadManager");

                final File finalMuxed = tmpMuxed;
                final File delV = tmpVideo;
                final File delA = tmpAudio;
                tmpMuxed = null; // ownership transferred to relay server cleanup

                final ServerSocket server = new ServerSocket(0, 8);
                server.setSoTimeout(45000);
                final int port = server.getLocalPort();
                final String safeName = sanitizePathSegment(filename);
                final String relayUrl = "http://127.0.0.1:" + port + "/" + Uri.encode(safeName);

                RELAY_POOL.execute(() -> {
                    try (ServerSocket ss = server) {
                        for (int attempt = 0; attempt < 4; attempt++) {
                            Socket client = null;
                            boolean served = false;
                            try {
                                client = ss.accept();
                                if (!client.getInetAddress().isLoopbackAddress()) {
                                    client.close();
                                    continue;
                                }
                                client.setSoTimeout(30000);
                                BufferedReader br = new BufferedReader(new InputStreamReader(client.getInputStream()));
                                String reqLine = br.readLine();
                                if (reqLine == null) continue;
                                boolean isHead = reqLine.toUpperCase(Locale.ROOT).startsWith("HEAD ");
                                String l;
                                while ((l = br.readLine()) != null && !l.isEmpty()) {}

                                OutputStream out = client.getOutputStream();
                                String headers = "HTTP/1.1 200 OK\r\n"
                                        + "Content-Type: video/mp4\r\n"
                                        + "Content-Disposition: attachment; filename=\"" + safeName + "\"\r\n"
                                        + "Content-Length: " + finalMuxed.length() + "\r\n"
                                        + "Connection: close\r\n\r\n";
                                out.write(headers.getBytes("UTF-8"));
                                if (!isHead) {
                                    try (InputStream in = new BufferedInputStream(new FileInputStream(finalMuxed), 64 * 1024)) {
                                        byte[] buf = new byte[64 * 1024];
                                        int n;
                                        while ((n = in.read(buf)) != -1) {
                                            out.write(buf, 0, n);
                                        }
                                        out.flush();
                                    }
                                    served = true;
                                    Log.i(TAG, "Served muxed HD MP4 to Chromium: " + safeName);
                                } else {
                                    out.flush();
                                }
                            } finally {
                                if (client != null) {
                                    try { client.close(); } catch (Throwable ignored) {}
                                }
                            }
                            if (served) break;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Muxed relay error: " + t.getMessage());
                    } finally {
                        try { finalMuxed.delete(); } catch (Throwable ignored) {}
                        try { delV.delete(); } catch (Throwable ignored) {}
                        try { delA.delete(); } catch (Throwable ignored) {}
                        if (onComplete != null) onComplete.run();
                    }
                });

                if (onRelayUrlReady != null) onRelayUrlReady.accept(relayUrl);
            } catch (Throwable t) {
                Log.e(TAG, "startMuxedDownload failed", t);
                if (tmpVideo != null) try { tmpVideo.delete(); } catch (Throwable ignored) {}
                if (tmpAudio != null) try { tmpAudio.delete(); } catch (Throwable ignored) {}
                if (tmpMuxed != null) try { tmpMuxed.delete(); } catch (Throwable ignored) {}
                if (onComplete != null) onComplete.run();
            }
        });
    }

    /**
     * Downloads a URL to a file using 4 MB Range chunks when supported (required by YouTube IOS
     * adaptive streams which return HTTP 403 on unbounded GETs, and also prevents throttling).
     */
    private static boolean downloadToFile(String urlStr, String ua, File outFile) {
        final long CHUNK_SIZE = 4L * 1024L * 1024L; // 4 MB chunks
        try (OutputStream out = new FileOutputStream(outFile)) {
            long offset = 0;
            long totalLength = -1;

            while (totalLength < 0 || offset < totalLength) {
                long end = (totalLength > 0) ? Math.min(offset + CHUNK_SIZE - 1, totalLength - 1) : (offset + CHUNK_SIZE - 1);
                HttpURLConnection conn = null;
                try {
                    URL u = new URL(urlStr);
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(45000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("Accept", "*/*");
                    conn.setRequestProperty("Range", "bytes=" + offset + "-" + end);
                    if (ua != null && !ua.isEmpty()) conn.setRequestProperty("User-Agent", ua);

                    int code = conn.getResponseCode();
                    if (code == 206 || code == 200) {
                        if (code == 206 && totalLength < 0) {
                            String cr = conn.getHeaderField("Content-Range");
                            if (cr != null && cr.contains("/")) {
                                try {
                                    String totalStr = cr.substring(cr.lastIndexOf('/') + 1).trim();
                                    if (!"*".equals(totalStr)) totalLength = Long.parseLong(totalStr);
                                } catch (Throwable ignored) {}
                            }
                        }
                        long chunkRead = 0;
                        try (InputStream in = new BufferedInputStream(conn.getInputStream(), 64 * 1024)) {
                            byte[] buf = new byte[64 * 1024];
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                out.write(buf, 0, n);
                                chunkRead += n;
                            }
                        }
                        offset += chunkRead;
                        if (code == 200 || chunkRead == 0) {
                            break; // Full file delivered in one response or EOF
                        }
                    } else {
                        Log.w(TAG, "downloadToFile HTTP " + code + " (offset=" + offset + ") for " + urlStr);
                        return false;
                    }
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
            out.flush();
            return offset > 0;
        } catch (Throwable t) {
            Log.w(TAG, "downloadToFile error: " + t.getMessage());
            return false;
        }
    }

    private static void muxMp4(File videoFile, File audioFile, File outFile) throws Exception {
        MediaExtractor vEx = new MediaExtractor();
        MediaExtractor aEx = new MediaExtractor();
        MediaMuxer muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            vEx.setDataSource(videoFile.getAbsolutePath());
            aEx.setDataSource(audioFile.getAbsolutePath());

            int vTrackSrc = -1;
            int vTrackDst = -1;
            for (int i = 0; i < vEx.getTrackCount(); i++) {
                MediaFormat fmt = vEx.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    vEx.selectTrack(i);
                    vTrackSrc = i;
                    vTrackDst = muxer.addTrack(fmt);
                    break;
                }
            }

            int aTrackSrc = -1;
            int aTrackDst = -1;
            for (int i = 0; i < aEx.getTrackCount(); i++) {
                MediaFormat fmt = aEx.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    aEx.selectTrack(i);
                    aTrackSrc = i;
                    aTrackDst = muxer.addTrack(fmt);
                    break;
                }
            }

            muxer.start();
            ByteBuffer buf = ByteBuffer.allocate(512 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            if (vTrackSrc >= 0) {
                while (true) {
                    int size = vEx.readSampleData(buf, 0);
                    if (size < 0) break;
                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = vEx.getSampleTime();
                    info.flags = vEx.getSampleFlags();
                    muxer.writeSampleData(vTrackDst, buf, info);
                    vEx.advance();
                }
            }

            if (aTrackSrc >= 0) {
                while (true) {
                    int size = aEx.readSampleData(buf, 0);
                    if (size < 0) break;
                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = aEx.getSampleTime();
                    info.flags = aEx.getSampleFlags();
                    muxer.writeSampleData(aTrackDst, buf, info);
                    aEx.advance();
                }
            }

            muxer.stop();
        } finally {
            try { muxer.release(); } catch (Throwable ignored) {}
            try { vEx.release(); } catch (Throwable ignored) {}
            try { aEx.release(); } catch (Throwable ignored) {}
        }
    }
}

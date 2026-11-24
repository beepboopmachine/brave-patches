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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authenticated loopback-only HTTP relay allowing Brave's native C++ content::DownloadManager
 * (J.N.VOO 0x13) to download Referer/Cookie-protected CDN streams & images with optional
 * WebP->PNG conversion, verified completion reporting, Range support, and adaptive MP4+M4A muxing.
 */
public final class LocalDownloadRelay {
    private static final String TAG = "Brave_DownloadRelay";
    private static final int MAX_WEBP_CONVERT_BYTES = 25 * 1024 * 1024; // 25 MB cap
    private static final int MAX_MUX_SAMPLE_BUFFER_BYTES = 16 * 1024 * 1024; // 16 MB max sample growth
    private static final Pattern CONTENT_RANGE_PATTERN = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REQ_RANGE_PATTERN = Pattern.compile("bytes=(\\d*)-(\\d*)", Pattern.CASE_INSENSITIVE);

    private static final ExecutorService RELAY_POOL = NetworkSafety.newBoundedPool(12, 64, "BraveLocalRelay");

    private LocalDownloadRelay() {}

    public static String startRelay(final MediaItem item, final String filename, final String resolvedMime, final Consumer<Boolean> onComplete) {
        return startRelay(item, filename, resolvedMime, false, onComplete);
    }

    public static String startRelay(
            final MediaItem item,
            final String filename,
            final String resolvedMime,
            final boolean convertWebpToPng,
            final Consumer<Boolean> onComplete
    ) {
        if (item == null || item.url == null || item.url.isEmpty()) return null;
        try {
            final ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            server.setSoTimeout(45000);
            final int port = server.getLocalPort();
            final String authToken = NetworkSafety.generateRandomTokenHex(16);
            final String origSafeName = sanitizePathSegment(filename);
            final String pngCandidateName = (convertWebpToPng && origSafeName.toLowerCase(Locale.ROOT).endsWith(".webp"))
                    ? origSafeName.substring(0, origSafeName.length() - 5) + ".png"
                    : origSafeName;
            final String relayUrl = "http://127.0.0.1:" + port + "/" + authToken + "/" + Uri.encode(pngCandidateName);

            RELAY_POOL.execute(() -> {
                boolean transferSucceeded = false;
                try (ServerSocket ss = server) {
                    for (int attempt = 0; attempt < 6; attempt++) {
                        Socket client = null;
                        NetworkSafety.SafeConnection sc = null;
                        boolean servedBody = false;
                        try {
                            client = ss.accept();
                            if (!client.getInetAddress().isLoopbackAddress()) {
                                client.close();
                                continue;
                            }
                            client.setSoTimeout(45000);

                            BufferedReader reqReader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                            String reqLine = reqReader.readLine();
                            if (reqLine == null) {
                                client.close();
                                continue;
                            }

                            String rangeHeader = null;
                            String line;
                            while ((line = reqReader.readLine()) != null && !line.isEmpty()) {
                                if (line.toLowerCase(Locale.ROOT).startsWith("range:")) {
                                    rangeHeader = line.substring(6).trim();
                                }
                            }

                            OutputStream out = client.getOutputStream();
                            String[] parts = reqLine.split(" ");
                            if (parts.length < 2) {
                                writeSimpleResponse(out, 400, "Bad Request");
                                continue;
                            }
                            String reqMethod = parts[0].toUpperCase(Locale.ROOT);
                            String reqPath = parts[1];
                            if ((!"GET".equals(reqMethod) && !"HEAD".equals(reqMethod))
                                    || !verifyRelayPathToken(reqPath, authToken)) {
                                writeSimpleResponse(out, 403, "Forbidden");
                                continue;
                            }

                            boolean isHead = "HEAD".equals(reqMethod);
                            String targetUpstreamUrl = item.url.replace("\\u0026", "&").replace("\\/", "/");
                            Log.i(TAG, "Chromium connected (" + reqMethod + ") -> " + targetUpstreamUrl);

                            String refPage = (item.pageUrl != null && !item.pageUrl.isEmpty()) ? item.pageUrl : item.referer;
                            sc = NetworkSafety.openSafeConnection(
                                    targetUpstreamUrl,
                                    isHead ? "HEAD" : "GET",
                                    item.userAgent,
                                    item.cookie,
                                    item.cookieSourceUrl,
                                    refPage,
                                    refPage,
                                    (!convertWebpToPng ? rangeHeader : null),
                                    false,
                                    15000,
                                    45000
                            );

                            int code = sc.responseCode;
                            if (code >= 200 && code < 300) {
                                String ct = sc.conn.getContentType();
                                if (ct == null || ct.isEmpty() || ct.contains("octet-stream")) {
                                    ct = resolvedMime;
                                }

                                boolean shouldConvert = convertWebpToPng && !isHead
                                        && (ct.toLowerCase(Locale.ROOT).contains("webp")
                                        || targetUpstreamUrl.toLowerCase(Locale.ROOT).contains(".webp"));

                                if (shouldConvert) {
                                    byte[] rawBytes = NetworkSafety.readBytesBounded(sc.conn.getInputStream(), MAX_WEBP_CONVERT_BYTES);
                                    byte[] payload = rawBytes;
                                    String outMime = ct;
                                    // Default fallback name preserves original extension (NOT .png!)
                                    String outName = origSafeName;
                                    try {
                                        Bitmap bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.length);
                                        if (bmp != null) {
                                            ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
                                            boolean compressed = bmp.compress(Bitmap.CompressFormat.PNG, 100, pngOut);
                                            bmp.recycle();
                                            if (compressed && pngOut.size() > 0) {
                                                payload = pngOut.toByteArray();
                                                outMime = "image/png";
                                                outName = pngCandidateName;
                                                if (!outName.toLowerCase(Locale.ROOT).endsWith(".png")) {
                                                    outName = outName + ".png";
                                                }
                                            }
                                        }
                                    } catch (Throwable ignored) {}

                                    String headers = "HTTP/1.1 200 OK\r\n"
                                            + "Content-Type: " + outMime + "\r\n"
                                            + "Content-Disposition: attachment; filename=\"" + outName + "\"\r\n"
                                            + "Content-Length: " + payload.length + "\r\n"
                                            + "Connection: close\r\n\r\n";
                                    out.write(headers.getBytes(StandardCharsets.UTF_8));
                                    out.write(payload);
                                    out.flush();
                                    servedBody = true;
                                    transferSucceeded = true;
                                    Log.i(TAG, "Relay WebP->PNG complete for " + outName + " (" + payload.length + " bytes)");
                                } else {
                                    long contentLength = sc.conn.getContentLengthLong();
                                    String statusLine = (code == 206) ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n";
                                    StringBuilder headers = new StringBuilder();
                                    headers.append(statusLine);
                                    headers.append("Content-Type: ").append(ct).append("\r\n");
                                    headers.append("Content-Disposition: attachment; filename=\"").append(origSafeName).append("\"\r\n");
                                    headers.append("Accept-Ranges: bytes\r\n");
                                    if (contentLength >= 0) {
                                        headers.append("Content-Length: ").append(contentLength).append("\r\n");
                                    }
                                    String contentRange = sc.conn.getHeaderField("Content-Range");
                                    if (contentRange != null && !contentRange.isEmpty()) {
                                        headers.append("Content-Range: ").append(contentRange).append("\r\n");
                                    }
                                    headers.append("Connection: close\r\n\r\n");
                                    out.write(headers.toString().getBytes(StandardCharsets.UTF_8));

                                    if (!isHead) {
                                        long totalWritten = 0;
                                        try (InputStream in = new BufferedInputStream(sc.conn.getInputStream(), 64 * 1024)) {
                                            byte[] buf = new byte[64 * 1024];
                                            int n;
                                            while ((n = in.read(buf)) != -1) {
                                                out.write(buf, 0, n);
                                                totalWritten += n;
                                            }
                                            out.flush();
                                        }
                                        if (contentLength < 0 || totalWritten == contentLength) {
                                            servedBody = true;
                                            transferSucceeded = true;
                                            Log.i(TAG, "Relay stream complete for " + origSafeName + " (" + totalWritten + " bytes)");
                                        } else {
                                            Log.w(TAG, "Relay truncated stream for " + origSafeName + ": " + totalWritten + "/" + contentLength);
                                            break;
                                        }
                                    } else {
                                        out.flush();
                                    }
                                }
                            } else {
                                writeSimpleResponse(out, code, "Upstream Error");
                                Log.w(TAG, "Upstream returned HTTP " + code + " for " + item.url);
                                break;
                            }
                        } finally {
                            if (sc != null && sc.conn != null) sc.conn.disconnect();
                            if (client != null) {
                                try { client.close(); } catch (Throwable ignored) {}
                            }
                        }
                        if (servedBody) break;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Relay error for " + item.url + ": " + t.getMessage());
                } finally {
                    if (onComplete != null) onComplete.accept(transferSucceeded);
                }
            });

            return relayUrl;
        } catch (Throwable t) {
            Log.e(TAG, "Failed starting local download relay", t);
            return null;
        }
    }

    private static boolean verifyRelayPathToken(String reqPath, String expectedToken) {
        if (reqPath == null || !reqPath.startsWith("/")) return false;
        int secondSlash = reqPath.indexOf('/', 1);
        if (secondSlash <= 1) return false;
        String provided = reqPath.substring(1, secondSlash);
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void writeSimpleResponse(OutputStream out, int code, String reason) throws IOException {
        String resp = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String sanitizePathSegment(String name) {
        if (name == null || name.isEmpty()) return "media.bin";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Downloads adaptive HD video + M4A audio in parallel, muxes them losslessly via Android's
     * built-in MediaMuxer (with dynamic sample buffer growth), and serves the finished HD MP4
     * over an authenticated loopback HTTP relay with full Range/206/416 support.
     */
    public static void startMuxedDownload(
            final File cacheDir,
            final MediaItem item,
            final String filename,
            final Runnable onRelayReadyTrigger,
            final Consumer<String> onRelayUrlReady,
            final Consumer<Boolean> onComplete
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
                final String pageCtx = (item.pageUrl != null && !item.pageUrl.isEmpty()) ? item.pageUrl : item.referer;
                Future<Boolean> vFut = RELAY_POOL.submit(() -> downloadToFile(item.url, item.userAgent, pageCtx, fVid));
                Future<Boolean> aFut = RELAY_POOL.submit(() -> downloadToFile(item.audioUrl, item.userAgent, pageCtx, fAud));

                boolean vOk = vFut.get();
                boolean aOk = aFut.get();
                if (!vOk || !aOk || fVid.length() <= 0 || fAud.length() <= 0) {
                    Log.w(TAG, "Mux download failed: vOk=" + vOk + " (" + fVid.length() + ") aOk=" + aOk + " (" + fAud.length() + ")");
                    if (onComplete != null) onComplete.accept(false);
                    return;
                }

                muxMp4(fVid, fAud, tmpMuxed);
                Log.i(TAG, "Muxed HD MP4 ready: " + tmpMuxed.length() + " bytes -> serving to Brave DownloadManager");

                final File finalMuxed = tmpMuxed;
                final File delV = tmpVideo;
                final File delA = tmpAudio;
                tmpMuxed = null; // ownership transferred to relay server cleanup

                final ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
                server.setSoTimeout(45000);
                final int port = server.getLocalPort();
                final String authToken = NetworkSafety.generateRandomTokenHex(16);
                final String safeName = sanitizePathSegment(filename);
                final String relayUrl = "http://127.0.0.1:" + port + "/" + authToken + "/" + Uri.encode(safeName);

                RELAY_POOL.execute(() -> {
                    boolean transferSucceeded = false;
                    try (ServerSocket ss = server) {
                        final long fileLength = finalMuxed.length();
                        for (int attempt = 0; attempt < 6; attempt++) {
                            Socket client = null;
                            boolean servedComplete = false;
                            try {
                                client = ss.accept();
                                if (!client.getInetAddress().isLoopbackAddress()) {
                                    client.close();
                                    continue;
                                }
                                client.setSoTimeout(30000);
                                BufferedReader br = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                                String reqLine = br.readLine();
                                if (reqLine == null) continue;

                                String rangeHeader = null;
                                String l;
                                while ((l = br.readLine()) != null && !l.isEmpty()) {
                                    if (l.toLowerCase(Locale.ROOT).startsWith("range:")) {
                                        rangeHeader = l.substring(6).trim();
                                    }
                                }

                                OutputStream out = client.getOutputStream();
                                String[] parts = reqLine.split(" ");
                                if (parts.length < 2) {
                                    writeSimpleResponse(out, 400, "Bad Request");
                                    continue;
                                }
                                String reqMethod = parts[0].toUpperCase(Locale.ROOT);
                                String reqPath = parts[1];
                                if ((!"GET".equals(reqMethod) && !"HEAD".equals(reqMethod))
                                        || !verifyRelayPathToken(reqPath, authToken)) {
                                    writeSimpleResponse(out, 403, "Forbidden");
                                    continue;
                                }
                                boolean isHead = "HEAD".equals(reqMethod);

                                long rangeStart = 0;
                                long rangeEnd = fileLength - 1;
                                boolean isPartial = false;

                                if (rangeHeader != null && !rangeHeader.isEmpty()) {
                                    Matcher rm = REQ_RANGE_PATTERN.matcher(rangeHeader);
                                    if (rm.find()) {
                                        String startStr = rm.group(1);
                                        String endStr = rm.group(2);
                                        if (startStr.isEmpty() && !endStr.isEmpty()) {
                                            long suffix = Long.parseLong(endStr);
                                            rangeStart = Math.max(0, fileLength - suffix);
                                            rangeEnd = fileLength - 1;
                                        } else {
                                            rangeStart = startStr.isEmpty() ? 0 : Long.parseLong(startStr);
                                            rangeEnd = endStr.isEmpty() ? (fileLength - 1) : Math.min(Long.parseLong(endStr), fileLength - 1);
                                        }
                                        if (rangeStart < 0 || rangeStart >= fileLength || rangeStart > rangeEnd) {
                                            String r416 = "HTTP/1.1 416 Range Not Satisfiable\r\n"
                                                    + "Content-Range: bytes */" + fileLength + "\r\n"
                                                    + "Content-Length: 0\r\n"
                                                    + "Connection: close\r\n\r\n";
                                            out.write(r416.getBytes(StandardCharsets.UTF_8));
                                            out.flush();
                                            continue;
                                        }
                                        isPartial = true;
                                    }
                                }

                                long sendLength = (rangeEnd - rangeStart) + 1;
                                StringBuilder headers = new StringBuilder();
                                headers.append(isPartial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
                                headers.append("Content-Type: video/mp4\r\n");
                                headers.append("Content-Disposition: attachment; filename=\"").append(safeName).append("\"\r\n");
                                headers.append("Accept-Ranges: bytes\r\n");
                                headers.append("Content-Length: ").append(sendLength).append("\r\n");
                                if (isPartial) {
                                    headers.append("Content-Range: bytes ").append(rangeStart).append("-").append(rangeEnd).append("/").append(fileLength).append("\r\n");
                                }
                                headers.append("Connection: close\r\n\r\n");
                                out.write(headers.toString().getBytes(StandardCharsets.UTF_8));

                                if (!isHead) {
                                    long remaining = sendLength;
                                    try (RandomAccessFile raf = new RandomAccessFile(finalMuxed, "r")) {
                                        raf.seek(rangeStart);
                                        byte[] buf = new byte[64 * 1024];
                                        while (remaining > 0) {
                                            int toRead = (int) Math.min(buf.length, remaining);
                                            int n = raf.read(buf, 0, toRead);
                                            if (n == -1) break;
                                            out.write(buf, 0, n);
                                            remaining -= n;
                                        }
                                        out.flush();
                                    }
                                    if (remaining == 0 && rangeEnd == fileLength - 1) {
                                        servedComplete = true;
                                        transferSucceeded = true;
                                        Log.i(TAG, "Served muxed HD MP4 to Chromium: " + safeName);
                                    }
                                } else {
                                    out.flush();
                                }
                            } finally {
                                if (client != null) {
                                    try { client.close(); } catch (Throwable ignored) {}
                                }
                            }
                            if (servedComplete) break;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Muxed relay error: " + t.getMessage());
                    } finally {
                        try { finalMuxed.delete(); } catch (Throwable ignored) {}
                        try { delV.delete(); } catch (Throwable ignored) {}
                        try { delA.delete(); } catch (Throwable ignored) {}
                        if (onComplete != null) onComplete.accept(transferSucceeded);
                    }
                });

                if (onRelayUrlReady != null) onRelayUrlReady.accept(relayUrl);
            } catch (Throwable t) {
                Log.e(TAG, "startMuxedDownload failed", t);
                if (tmpVideo != null) try { tmpVideo.delete(); } catch (Throwable ignored) {}
                if (tmpAudio != null) try { tmpAudio.delete(); } catch (Throwable ignored) {}
                if (tmpMuxed != null) try { tmpMuxed.delete(); } catch (Throwable ignored) {}
                if (onComplete != null) onComplete.accept(false);
            }
        });
    }

    /**
     * Downloads a URL to a file using 4 MB Range chunks when supported.
     * Validates Content-Range alignment on HTTP 206 and truncates/restarts cleanly if HTTP 200
     * arrives after partial progress so two copies can never be concatenated.
     */
    private static boolean downloadToFile(String urlStr, String ua, String pageContextUrl, File outFile) {
        final long CHUNK_SIZE = 4L * 1024L * 1024L;
        try (RandomAccessFile raf = new RandomAccessFile(outFile, "rw")) {
            raf.setLength(0);
            long offset = 0;
            long totalLength = -1;

            while (totalLength < 0 || offset < totalLength) {
                long end = (totalLength > 0) ? Math.min(offset + CHUNK_SIZE - 1, totalLength - 1) : (offset + CHUNK_SIZE - 1);
                NetworkSafety.SafeConnection sc = null;
                try {
                    sc = NetworkSafety.openSafeConnection(
                            urlStr, "GET", ua, "", "",
                            "", pageContextUrl, "bytes=" + offset + "-" + end,
                            false, 15000, 45000
                    );
                    int code = sc.responseCode;
                    if (code == 206) {
                        String cr = sc.conn.getHeaderField("Content-Range");
                        if (cr == null) {
                            Log.w(TAG, "HTTP 206 missing Content-Range header");
                            return false;
                        }
                        Matcher m = CONTENT_RANGE_PATTERN.matcher(cr.trim());
                        if (!m.find()) {
                            Log.w(TAG, "Malformed Content-Range header: " + cr);
                            return false;
                        }
                        long respStart = Long.parseLong(m.group(1));
                        if (respStart != offset) {
                            Log.w(TAG, "Content-Range start mismatch: expected " + offset + " got " + respStart);
                            return false;
                        }
                        String totalStr = m.group(3);
                        if (!"*".equals(totalStr)) {
                            totalLength = Long.parseLong(totalStr);
                        }
                        long chunkRead = 0;
                        try (InputStream in = new BufferedInputStream(sc.conn.getInputStream(), 64 * 1024)) {
                            byte[] buf = new byte[64 * 1024];
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                raf.write(buf, 0, n);
                                chunkRead += n;
                            }
                        }
                        offset += chunkRead;
                        if (chunkRead == 0) {
                            break;
                        }
                    } else if (code == 200) {
                        // Server ignored Range and returned the full resource from byte 0: truncate!
                        if (offset > 0) {
                            raf.setLength(0);
                            raf.seek(0);
                            offset = 0;
                        }
                        long respLen = sc.conn.getContentLengthLong();
                        if (respLen > 0) {
                            totalLength = respLen;
                        }
                        long chunkRead = 0;
                        try (InputStream in = new BufferedInputStream(sc.conn.getInputStream(), 64 * 1024)) {
                            byte[] buf = new byte[64 * 1024];
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                raf.write(buf, 0, n);
                                chunkRead += n;
                            }
                        }
                        offset = chunkRead;
                        break;
                    } else {
                        Log.w(TAG, "downloadToFile HTTP " + code + " (offset=" + offset + ") for " + urlStr);
                        return false;
                    }
                } finally {
                    if (sc != null && sc.conn != null) sc.conn.disconnect();
                }
            }
            return offset > 0 && (totalLength < 0 || offset == totalLength);
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

            int initialBufSize = 1024 * 1024; // 1 MB base
            int vTrackSrc = -1;
            int vTrackDst = -1;
            for (int i = 0; i < vEx.getTrackCount(); i++) {
                MediaFormat fmt = vEx.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        initialBufSize = Math.max(initialBufSize, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                    }
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
                    if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        initialBufSize = Math.max(initialBufSize, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                    }
                    aEx.selectTrack(i);
                    aTrackSrc = i;
                    aTrackDst = muxer.addTrack(fmt);
                    break;
                }
            }

            muxer.start();
            ByteBuffer[] bufHolder = new ByteBuffer[]{ByteBuffer.allocate(Math.min(initialBufSize, MAX_MUX_SAMPLE_BUFFER_BYTES))};
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            if (vTrackSrc >= 0) {
                copyExtractorTrack(vEx, muxer, vTrackDst, bufHolder, info);
            }

            if (aTrackSrc >= 0) {
                copyExtractorTrack(aEx, muxer, aTrackDst, bufHolder, info);
            }

            muxer.stop();
        } finally {
            try { muxer.release(); } catch (Throwable ignored) {}
            try { vEx.release(); } catch (Throwable ignored) {}
            try { aEx.release(); } catch (Throwable ignored) {}
        }
    }

    private static void copyExtractorTrack(
            MediaExtractor extractor,
            MediaMuxer muxer,
            int dstTrack,
            ByteBuffer[] bufHolder,
            MediaCodec.BufferInfo info
    ) throws IOException {
        while (true) {
            int size = readSampleGrowing(extractor, bufHolder);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = extractor.getSampleTime();
            int sampleFlags = extractor.getSampleFlags();
            int flags = 0;
            if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
            }
            info.flags = flags;
            muxer.writeSampleData(dstTrack, bufHolder[0], info);
            extractor.advance();
        }
    }

    static int readSampleGrowing(MediaExtractor extractor, ByteBuffer[] bufHolder) throws IOException {
        while (true) {
            ByteBuffer buf = bufHolder[0];
            buf.clear();
            try {
                return extractor.readSampleData(buf, 0);
            } catch (IllegalArgumentException e) {
                int nextCap = buf.capacity() * 2;
                if (nextCap > MAX_MUX_SAMPLE_BUFFER_BYTES) {
                    throw new IOException("Media sample exceeded max buffer size (" + MAX_MUX_SAMPLE_BUFFER_BYTES + ")", e);
                }
                bufHolder[0] = ByteBuffer.allocate(nextCap);
            }
        }
    }
}

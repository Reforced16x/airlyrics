package com.airlyrics;

import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks OS media playback metadata and accurate timeline timestamps.
 * Uses direct WinRT COM (GSMTC) via JNA on Windows (inspired by EvergreenHUD)
 * with PowerShell fallback, and MPRIS (playerctl / gdbus) on Linux.
 */
public class MediaTracker {
    public static volatile String currentTitle = "";
    public static volatile String currentArtist = "";
    public static volatile String currentAlbum = "";
    public static volatile String currentPlayer = "";
    public static volatile String currentBackend = "None";

    public static volatile long currentPositionMs = 0L;
    public static volatile long currentDurationMs = 0L;
    public static volatile long lastPollTimeMs = 0L;
    public static volatile boolean isPlaying = false;
    public static volatile boolean hasMedia = false;

    private static volatile String lastTrackKey = "";
    private static volatile String lastActivePlayer = "";
    private static volatile boolean running = false;

    private static final long POLL_INTERVAL_MS = 200L;
    private static final long FILETIME_UNIX_EPOCH_MS = 11644473600000L;

    private static ScheduledExecutorService executor;
    private static volatile WinRtMedia winRtMedia;
    private static int winRtFailureCount = 0;
    private static boolean winRtDisabled = false;
    private static volatile long lastSeenRawPosTicks = -1L;
    private static volatile long lastSeenFiletime = -1L;
    private static volatile long syntheticAnchorMs = 0L;

    public static synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AirLyrics-MediaTracker");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(MediaTracker::safePoll, 150L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public static void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Requests an immediate background poll (safe to call from render/config threads).
     */
    public static void pollAsyncNow() {
        if (executor != null && !executor.isShutdown()) {
            executor.execute(MediaTracker::safePoll);
        }
    }

    /**
     * Returns raw estimated playback position in milliseconds based on the most recent OS poll.
     */
    public static long getEstimatedPositionMs() {
        long base = currentPositionMs;
        if (isPlaying && lastPollTimeMs > 0L) {
            long elapsed = System.currentTimeMillis() - lastPollTimeMs;
            if (elapsed > 0L) {
                base += Math.min(elapsed, 3000L);
            }
        }
        if (currentDurationMs > 0L && base > currentDurationMs) {
            base = currentDurationMs;
        }
        return Math.max(0L, base);
    }

    private static void safePoll() {
        try {
            poll();
        } catch (Throwable ignored) {
        }
    }

    private static void poll() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        MediaSnapshot snap = null;

        if (os.contains("win")) {
            if (!winRtDisabled) {
                snap = pollWindowsWinRt();
                if (snap == null) {
                    winRtFailureCount++;
                    if (winRtFailureCount >= 8) {
                        winRtDisabled = true;
                        closeWinRt();
                    }
                } else {
                    winRtFailureCount = 0;
                }
            }
            if (snap == null) {
                snap = pollWindowsPowerShell();
            }
        } else if (os.contains("linux") || os.contains("nix") || os.contains("nux")) {
            snap = pollLinuxMpris();
        }

        applySnapshot(snap);
    }

    private static void applySnapshot(MediaSnapshot snap) {
        long now = System.currentTimeMillis();
        if (snap == null || snap.title.isEmpty()) {
            hasMedia = false;
            isPlaying = false;
            currentTitle = "";
            currentArtist = "";
            currentAlbum = "";
            currentPlayer = "";
            currentPositionMs = 0L;
            currentDurationMs = 0L;
            lastPollTimeMs = now;
            lastSeenRawPosTicks = -1L;
            lastSeenFiletime = -1L;
            syntheticAnchorMs = 0L;
            if (!lastTrackKey.isEmpty()) {
                lastTrackKey = "";
                LyricsFetcher.clearLyrics();
                CoverColorExtractor.clear();
            }
            return;
        }

        hasMedia = true;
        currentTitle = snap.title;
        currentArtist = snap.artist;
        currentAlbum = snap.album;
        currentPlayer = snap.player;
        currentBackend = snap.backend;
        currentPositionMs = Math.max(0L, snap.positionMs);
        currentDurationMs = Math.max(0L, snap.durationMs);
        isPlaying = snap.playing;
        lastPollTimeMs = snap.sampleTimeMs > 0L ? snap.sampleTimeMs : now;

        if (snap.playing && !snap.player.isEmpty()) {
            lastActivePlayer = snap.player;
        }

        String newKey = snap.artist + "\u0000" + snap.title;
        if (!newKey.equals(lastTrackKey)) {
            lastTrackKey = newKey;
            lastSeenRawPosTicks = -1L;
            lastSeenFiletime = -1L;
            syntheticAnchorMs = now;
            LyricsFetcher.onTrackChanged(snap.title, snap.artist, snap.durationMs);
            CoverColorExtractor.onTrackChanged(snap.title, snap.artist, snap.album, snap.thumbnailBytes);
        }
    }

    private static MediaSnapshot pollWindowsWinRt() {
        try {
            if (winRtMedia == null) {
                winRtMedia = new WinRtMedia();
            }
            MediaSnapshot snap = winRtMedia.poll(lastActivePlayer);
            if (snap == null) {
                return MediaSnapshot.EMPTY;
            }
            return snap;
        } catch (Throwable t) {
            closeWinRt();
            return null;
        }
    }

    private static void closeWinRt() {
        if (winRtMedia != null) {
            try {
                winRtMedia.close();
            } catch (Throwable ignored) {
            }
            winRtMedia = null;
        }
    }

    // -------------------------------------------------------------------------
    // Direct WinRT COM GSMTC implementation (JNA 3.4.0 compatible)
    // -------------------------------------------------------------------------

    private interface CombaseLibrary extends Library {
        int RoInitialize(int initType);

        int RoGetActivationFactory(Pointer activatableClassId, Pointer iid, PointerByReference factory);

        int WindowsCreateString(WString sourceString, int length, PointerByReference string);

        int WindowsDeleteString(Pointer string);

        Pointer WindowsGetStringRawBuffer(Pointer string, IntByReference length);
    }

    private static final class WinRtMedia implements AutoCloseable {
        private static final int RO_INIT_MULTITHREADED = 1;
        private static final int RPC_E_CHANGED_MODE = 0x80010106;
        private static final String MANAGER_CLASS = "Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager";
        private static final String BUFFER_CLASS = "Windows.Storage.Streams.Buffer";
        private static final Memory IID_MANAGER_STATICS = parseGuid("2050c4ee-11a0-57de-aed7-c97c70338245");
        private static final Memory IID_ASYNC_INFO = parseGuid("00000036-0000-0000-c000-000000000046");
        private static final Memory IID_IRANDOM_ACCESS_STREAM = parseGuid("905a0fe1-bc53-11df-8c49-001e4fc686da");
        private static final Memory IID_IINPUT_STREAM = parseGuid("905a0fe2-bc53-11df-8c49-001e4fc686da");
        private static final Memory IID_IBUFFER_FACTORY = parseGuid("71af914d-c10f-484b-bc50-14bc623b3a27");
        private static final Memory IID_IBUFFER_BYTE_ACCESS = parseGuid("905a0fef-bc53-11df-8c49-001e4fc686da");

        private final CombaseLibrary combase;
        private Com manager;
        private Com bufferFactory;
        private final Map<String, byte[]> thumbCache = new LinkedHashMap<String, byte[]>(8, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return size() > 4;
            }
        };

        WinRtMedia() {
            this.combase = (CombaseLibrary) Native.loadLibrary("combase", CombaseLibrary.class);
            int hr = combase.RoInitialize(RO_INIT_MULTITHREADED);
            if (hr < 0 && hr != RPC_E_CHANGED_MODE) {
                throw new IllegalStateException("RoInitialize failed: 0x" + Integer.toHexString(hr));
            }
            this.manager = acquireManager();
        }

        private Com acquireManager() {
            try (HString classId = new HString(combase, MANAGER_CLASS)) {
                PointerByReference factoryRef = new PointerByReference();
                int hr = combase.RoGetActivationFactory(classId.ptr, IID_MANAGER_STATICS, factoryRef);
                checkHr(hr, "RoGetActivationFactory");
                try (Com factory = new Com(combase, factoryRef.getValue());
                     Com op = factory.child(6)) {
                    if (op == null) {
                        throw new IllegalStateException("RequestAsync returned null");
                    }
                    Com mgr = op.awaitAsync(IID_ASYNC_INFO, 2000L);
                    if (mgr == null) {
                        throw new IllegalStateException("SessionManager RequestAsync failed");
                    }
                    return mgr;
                }
            }
        }

        MediaSnapshot poll(String preferredPlayer) {
            if (manager == null) {
                manager = acquireManager();
            }
            Com sessions;
            try {
                sessions = manager.child(7);
            } catch (RuntimeException ex) {
                manager.close();
                manager = acquireManager();
                sessions = manager.child(7);
            }
            if (sessions == null) {
                return null;
            }

            try (Com list = sessions) {
                int count = list.getInt(7);
                if (count <= 0) {
                    return null;
                }

                MediaSnapshot bestPlayingPreferred = null;
                int bestPlayingPreferredIdx = -1;
                MediaSnapshot bestPlaying = null;
                int bestPlayingIdx = -1;
                MediaSnapshot bestPausedPreferred = null;
                int bestPausedPreferredIdx = -1;
                MediaSnapshot bestPaused = null;
                int bestPausedIdx = -1;
                long nowMs = System.currentTimeMillis();

                for (int i = 0; i < count; i++) {
                    try (Com session = list.childAt(6, i)) {
                        if (session == null) {
                            continue;
                        }
                        MediaSnapshot snap = readSession(session, nowMs);
                        if (snap == null || snap.title.isEmpty()) {
                            continue;
                        }
                        boolean matchesPreferred = !preferredPlayer.isEmpty()
                                && preferredPlayer.equalsIgnoreCase(snap.player);
                        if (snap.playing) {
                            if (matchesPreferred && bestPlayingPreferred == null) {
                                bestPlayingPreferred = snap;
                                bestPlayingPreferredIdx = i;
                            } else if (bestPlaying == null) {
                                bestPlaying = snap;
                                bestPlayingIdx = i;
                            }
                        } else {
                            if (matchesPreferred && bestPausedPreferred == null) {
                                bestPausedPreferred = snap;
                                bestPausedPreferredIdx = i;
                            } else if (bestPaused == null) {
                                bestPaused = snap;
                                bestPausedIdx = i;
                            }
                        }
                    }
                }

                MediaSnapshot chosen;
                int chosenIdx;
                if (bestPlayingPreferred != null) {
                    chosen = bestPlayingPreferred;
                    chosenIdx = bestPlayingPreferredIdx;
                } else if (bestPlaying != null) {
                    chosen = bestPlaying;
                    chosenIdx = bestPlayingIdx;
                } else if (bestPausedPreferred != null) {
                    chosen = bestPausedPreferred;
                    chosenIdx = bestPausedPreferredIdx;
                } else {
                    chosen = bestPaused;
                    chosenIdx = bestPausedIdx;
                }

                if (chosen == null) {
                    return null;
                }

                String tKey = chosen.artist + "\u0000" + chosen.title;
                byte[] thumbBytes = thumbCache.get(tKey);
                if (thumbBytes == null && chosenIdx >= 0) {
                    try (Com winSession = list.childAt(6, chosenIdx);
                         Com op = winSession != null ? winSession.child(7) : null;
                         Com props = op != null ? op.awaitAsync(IID_ASYNC_INFO, 1200L) : null) {
                        if (props != null) {
                            byte[] fetched = readThumbnailBytes(props);
                            if (fetched != null) {
                                thumbCache.put(tKey, fetched);
                                thumbBytes = fetched;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                long posMs = chosen.positionMs;
                if (chosen.rawPosTicks >= 0L) {
                    if (chosen.rawPosTicks != lastSeenRawPosTicks || chosen.lastUpdatedFiletime != lastSeenFiletime) {
                        lastSeenRawPosTicks = chosen.rawPosTicks;
                        lastSeenFiletime = chosen.lastUpdatedFiletime;
                        syntheticAnchorMs = chosen.sampleTimeMs;
                    }
                    if (chosen.playing) {
                        long updatedUnixMs = chosen.lastUpdatedFiletime > 0L
                                ? (chosen.lastUpdatedFiletime / 10000L - FILETIME_UNIX_EPOCH_MS)
                                : 0L;
                        long ageMs = chosen.sampleTimeMs - updatedUnixMs;
                        if (updatedUnixMs > 1577836800000L && ageMs >= -250L && ageMs < 600000L) {
                            posMs += Math.max(0L, ageMs);
                        } else if (syntheticAnchorMs > 0L) {
                            long localElapsed = chosen.sampleTimeMs - syntheticAnchorMs;
                            if (localElapsed > 0L && localElapsed < 15000L) {
                                posMs += localElapsed;
                            }
                        }
                    }
                    if (chosen.durationMs > 0L && posMs > chosen.durationMs) {
                        posMs = chosen.durationMs;
                    }
                }

                return new MediaSnapshot(chosen.title, chosen.artist, chosen.album, posMs, chosen.durationMs,
                        chosen.playing, chosen.player, chosen.backend, chosen.sampleTimeMs, thumbBytes,
                        chosen.rawPosTicks, chosen.lastUpdatedFiletime);
            }
        }

        private MediaSnapshot readSession(Com session, long nowMs) {
            String player = "";
            try {
                player = safeTrim(session.getString(6));
            } catch (Throwable ignored) {
            }

            boolean playing = false;
            try (Com playback = session.child(9)) {
                if (playback != null) {
                    playing = (playback.getInt(7) == 4);
                }
            } catch (Throwable ignored) {
            }

            String title = "";
            String artist = "";
            String album = "";
            try (Com op = session.child(7);
                 Com props = op != null ? op.awaitAsync(IID_ASYNC_INFO, 1200L) : null) {
                if (props != null) {
                    title = safeTrim(props.getString(6));
                    artist = safeTrim(props.getString(9));
                    album = safeTrim(props.getString(10));
                }
            } catch (Throwable ignored) {
            }

            if (title.isEmpty()) {
                return null;
            }

            long posMs = 0L;
            long durationMs = 0L;
            long rawPosTicks = -1L;
            long lastUpdatedFiletime = -1L;
            long sampleNowMs = System.currentTimeMillis();
            try (Com timeline = session.child(8)) {
                if (timeline != null) {
                    long startTicks = timeline.getLong(6);
                    long endTicks = timeline.getLong(7);
                    long posTicks = timeline.getLong(10);
                    long updatedFiletime = timeline.getLong(11);
                    sampleNowMs = System.currentTimeMillis();

                    durationMs = Math.max(0L, (endTicks - startTicks) / 10000L);
                    posMs = Math.max(0L, (posTicks - startTicks) / 10000L);
                    if (durationMs > 0L && posMs > durationMs) {
                        posMs = durationMs;
                    }
                    rawPosTicks = posTicks;
                    lastUpdatedFiletime = updatedFiletime;
                }
            } catch (Throwable ignored) {
            }

            return new MediaSnapshot(title, artist, album, posMs, durationMs, playing, player,
                    "WinRT-COM", sampleNowMs, null, rawPosTicks, lastUpdatedFiletime);
        }

        private byte[] readThumbnailBytes(Com props) {
            try (Com thumbRef = props.child(15)) {
                if (thumbRef == null) {
                    return null;
                }
                try (Com openOp = thumbRef.child(6);
                     Com streamWithType = openOp != null ? openOp.awaitAsync(IID_ASYNC_INFO, 1200L) : null) {
                    if (streamWithType == null) {
                        return null;
                    }
                    long sizeLong;
                    try (Com randStream = new Com(combase, streamWithType.queryInterface(IID_IRANDOM_ACCESS_STREAM))) {
                        sizeLong = randStream.getLong(6);
                    }
                    if (sizeLong <= 32L || sizeLong > 4L * 1024L * 1024L) {
                        return null;
                    }
                    if (bufferFactory == null) {
                        try (HString bufId = new HString(combase, BUFFER_CLASS)) {
                            PointerByReference factoryRef = new PointerByReference();
                            int hr = combase.RoGetActivationFactory(bufId.ptr, IID_IBUFFER_FACTORY, factoryRef);
                            checkHr(hr, "RoGetActivationFactory(Buffer)");
                            bufferFactory = new Com(combase, factoryRef.getValue());
                        }
                    }

                    int capacity = (int) Math.min(sizeLong, 262144L);
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream((int) sizeLong);
                    try (Com inputStream = new Com(combase, streamWithType.queryInterface(IID_IINPUT_STREAM));
                         Com ibuf = bufferFactory.childAt(6, capacity)) {
                        if (ibuf == null) {
                            return null;
                        }
                        while (baos.size() < sizeLong) {
                            try (Com readOp = inputStream.readAsync(ibuf.ptr, capacity);
                                 Com resBuf = readOp != null ? readOp.awaitAsyncWithProgress(IID_ASYNC_INFO, 1200L) : null) {
                                if (resBuf == null) {
                                    break;
                                }
                                int len = resBuf.getInt(7);
                                if (len <= 0) {
                                    break;
                                }
                                Pointer byteAccPtr = resBuf.queryInterface(IID_IBUFFER_BYTE_ACCESS);
                                try (Com byteAcc = new Com(combase, byteAccPtr)) {
                                    PointerByReference dataPtrRef = new PointerByReference();
                                    int hr = byteAcc.invoke(3, dataPtrRef);
                                    checkHr(hr, "IBufferByteAccess.Buffer");
                                    Pointer rawBuf = dataPtrRef.getValue();
                                    if (rawBuf != null) {
                                        baos.write(rawBuf.getByteArray(0L, len));
                                    }
                                }
                            }
                            ibuf.invoke(8, 0);
                        }
                    }
                    byte[] result = baos.toByteArray();
                    return result.length > 32 ? result : null;
                }
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override
        public void close() {
            if (bufferFactory != null) {
                bufferFactory.close();
                bufferFactory = null;
            }
            if (manager != null) {
                manager.close();
                manager = null;
            }
        }

        private static Memory parseGuid(String guid) {
            String hex = guid.replace("-", "");
            if (hex.length() != 32) {
                throw new IllegalArgumentException("Invalid GUID: " + guid);
            }
            Memory mem = new Memory(16L);
            mem.setInt(0L, (int) Long.parseLong(hex.substring(0, 8), 16));
            mem.setShort(4L, (short) Integer.parseInt(hex.substring(8, 12), 16));
            mem.setShort(6L, (short) Integer.parseInt(hex.substring(12, 16), 16));
            for (int i = 0; i < 8; i++) {
                int idx = 16 + i * 2;
                mem.setByte(8L + i, (byte) Integer.parseInt(hex.substring(idx, idx + 2), 16));
            }
            return mem;
        }

        private static void checkHr(int hr, String name) {
            if (hr < 0) {
                throw new IllegalStateException(name + " failed: 0x" + Integer.toHexString(hr));
            }
        }
    }

    private static final class HString implements AutoCloseable {
        private final CombaseLibrary combase;
        final Pointer ptr;

        HString(CombaseLibrary combase, String value) {
            this.combase = combase;
            PointerByReference ref = new PointerByReference();
            int hr = combase.WindowsCreateString(new WString(value), value.length(), ref);
            WinRtMedia.checkHr(hr, "WindowsCreateString");
            this.ptr = ref.getValue();
        }

        @Override
        public void close() {
            if (ptr != null) {
                try {
                    combase.WindowsDeleteString(ptr);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final class Com implements AutoCloseable {
        private final CombaseLibrary combase;
        private final Pointer ptr;
        private boolean closed = false;

        Com(CombaseLibrary combase, Pointer ptr) {
            this.combase = combase;
            this.ptr = ptr;
        }

        private int invoke(int slot, Object... extraArgs) {
            Pointer vtable = ptr.getPointer(0L);
            Pointer fnPtr = vtable.getPointer((long) slot * Native.POINTER_SIZE);
            Object[] args = new Object[1 + extraArgs.length];
            args[0] = ptr;
            System.arraycopy(extraArgs, 0, args, 1, extraArgs.length);
            return Function.getFunction(fnPtr).invokeInt(args);
        }

        Pointer queryInterface(Memory iid) {
            PointerByReference out = new PointerByReference();
            int hr = invoke(0, iid, out);
            WinRtMedia.checkHr(hr, "QueryInterface");
            return out.getValue();
        }

        Com child(int slot) {
            PointerByReference out = new PointerByReference();
            int hr = invoke(slot, out);
            WinRtMedia.checkHr(hr, "COM.child(" + slot + ")");
            Pointer childPtr = out.getValue();
            return childPtr != null ? new Com(combase, childPtr) : null;
        }

        Com childAt(int slot, int index) {
            PointerByReference out = new PointerByReference();
            int hr = invoke(slot, index, out);
            WinRtMedia.checkHr(hr, "COM.childAt(" + slot + ")");
            Pointer childPtr = out.getValue();
            return childPtr != null ? new Com(combase, childPtr) : null;
        }

        int getInt(int slot) {
            IntByReference out = new IntByReference();
            int hr = invoke(slot, out);
            WinRtMedia.checkHr(hr, "COM.getInt(" + slot + ")");
            return out.getValue();
        }

        long getLong(int slot) {
            LongByReference out = new LongByReference();
            int hr = invoke(slot, out);
            WinRtMedia.checkHr(hr, "COM.getLong(" + slot + ")");
            return out.getValue();
        }

        String getString(int slot) {
            PointerByReference out = new PointerByReference();
            int hr = invoke(slot, out);
            WinRtMedia.checkHr(hr, "COM.getString(" + slot + ")");
            Pointer hstr = out.getValue();
            if (hstr == null) {
                return "";
            }
            try {
                IntByReference lenRef = new IntByReference();
                Pointer buf = combase.WindowsGetStringRawBuffer(hstr, lenRef);
                int len = lenRef.getValue();
                if (buf == null || len <= 0) {
                    return "";
                }
                return new String(buf.getCharArray(0L, len));
            } finally {
                combase.WindowsDeleteString(hstr);
            }
        }

        Com awaitAsync(Memory iidAsyncInfo, long timeoutMs) {
            return awaitAsyncSlot(iidAsyncInfo, timeoutMs, 8);
        }

        Com awaitAsyncWithProgress(Memory iidAsyncInfo, long timeoutMs) {
            return awaitAsyncSlot(iidAsyncInfo, timeoutMs, 10);
        }

        private Com awaitAsyncSlot(Memory iidAsyncInfo, long timeoutMs, int resultsSlot) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            int status;
            try (Com asyncInfo = new Com(combase, queryInterface(iidAsyncInfo))) {
                status = asyncInfo.getInt(7);
                while (status == 0 && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(2L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    status = asyncInfo.getInt(7);
                }
            }
            if (status != 1) {
                return null;
            }
            return child(resultsSlot);
        }

        Com readAsync(Pointer bufferPtr, int count) {
            PointerByReference out = new PointerByReference();
            int hr = invoke(6, bufferPtr, count, 0, out);
            WinRtMedia.checkHr(hr, "IInputStream.ReadAsync");
            Pointer opPtr = out.getValue();
            return opPtr != null ? new Com(combase, opPtr) : null;
        }

        @Override
        public void close() {
            if (!closed && ptr != null) {
                closed = true;
                try {
                    invoke(2);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Windows PowerShell fallback
    // -------------------------------------------------------------------------

    private static MediaSnapshot pollWindowsPowerShell() {
        try {
            String psCommand = "$ErrorActionPreference='SilentlyContinue';"
                    + "Add-Type -AssemblyName System.Runtime.WindowsRuntime;"
                    + "$asTask=([System.WindowsRuntimeSystemExtensions].GetMethods()|?{$_.Name -eq 'AsTask'-and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'})[0];"
                    + "function Aw($op,$t){$net=$asTask.MakeGenericMethod($t);$tsk=$net.Invoke($null,@($op));$tsk.Wait(1500)|Out-Null;$tsk.Result};"
                    + "$mgr=Aw ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]);"
                    + "$sessions=$mgr.GetSessions();"
                    + "$s=$sessions|?{$_.GetPlaybackInfo().PlaybackStatus -eq 'Playing'}|Select -First 1;"
                    + "if(-not $s){$s=$mgr.GetCurrentSession()};"
                    + "if($s){"
                    + "$p=Aw ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties,Windows.Media.Control,ContentType=WindowsRuntime]);"
                    + "$tl=$s.GetTimelineProperties();"
                    + "$pb=$s.GetPlaybackInfo();"
                    + "$dur=[Math]::Max(0,[long]($tl.EndTime-$tl.StartTime).TotalMilliseconds);"
                    + "$pos=[Math]::Max(0,[long]($tl.Position-$tl.StartTime).TotalMilliseconds);"
                    + "if($pb.PlaybackStatus -eq 'Playing' -and $tl.LastUpdatedTime){"
                    + "$age=([DateTimeOffset]::UtcNow - $tl.LastUpdatedTime).TotalMilliseconds;"
                    + "if($age -ge 1 -and $age -lt 86400000){$pos+=[long]$age}"
                    + "};"
                    + "if($dur -gt 0 -and $pos -gt $dur){$pos=$dur};"
                    + "Write-Output ('T=' + ($p.Title -replace '[\\r\\n]',' '));"
                    + "Write-Output ('A=' + ($p.Artist -replace '[\\r\\n]',' '));"
                    + "Write-Output ('B=' + ($p.AlbumTitle -replace '[\\r\\n]',' '));"
                    + "Write-Output ('P=' + $pos);"
                    + "Write-Output ('D=' + $dur);"
                    + "Write-Output ('S=' + $pb.PlaybackStatus);"
                    + "Write-Output ('L=' + ($s.SourceAppUserModelId -replace '[\\r\\n]',' '))"
                    + "}";

            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", psCommand);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            if (!process.waitFor(2500L, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String result = out.toString().trim();
            if (result.isEmpty()) {
                return MediaSnapshot.EMPTY;
            }

            String title = "";
            String artist = "";
            String album = "";
            String player = "";
            long pos = 0L;
            long dur = 0L;
            boolean playing = false;

            for (String line : result.split("\n")) {
                if (line.startsWith("T=")) {
                    title = line.substring(2).trim();
                } else if (line.startsWith("A=")) {
                    artist = line.substring(2).trim();
                } else if (line.startsWith("B=")) {
                    album = line.substring(2).trim();
                } else if (line.startsWith("P=")) {
                    pos = parseLongSafe(line.substring(2).trim(), 0L);
                } else if (line.startsWith("D=")) {
                    dur = parseLongSafe(line.substring(2).trim(), 0L);
                } else if (line.startsWith("S=")) {
                    playing = "Playing".equalsIgnoreCase(line.substring(2).trim());
                } else if (line.startsWith("L=")) {
                    player = line.substring(2).trim();
                }
            }

            return title.isEmpty()
                    ? MediaSnapshot.EMPTY
                    : new MediaSnapshot(title, artist, album, pos, dur, playing, player, "WinRT-PS");
        } catch (Throwable ignored) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Linux MPRIS (playerctl / gdbus)
    // -------------------------------------------------------------------------

    private static MediaSnapshot pollLinuxMpris() {
        MediaSnapshot snap = tryPlayerctl();
        if (snap != null) {
            return snap;
        }
        return tryGdbus();
    }

    private static MediaSnapshot tryPlayerctl() {
        try {
            String fmt = "{{status}}\t{{title}}\t{{artist}}\t{{album}}\t{{position}}\t{{mpris:length}}\t{{playerName}}";
            Process process = new ProcessBuilder("playerctl", "metadata", "--format", fmt).start();
            String output = readAll(process);
            if (!process.waitFor(1500L, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            int code = process.exitValue();
            if (code != 0 || output.trim().isEmpty()) {
                return null;
            }
            String[] parts = output.trim().split("\t", -1);
            if (parts.length < 7) {
                return null;
            }
            boolean playing = "Playing".equalsIgnoreCase(parts[0].trim());
            String title = parts[1].trim();
            String artist = parts[2].trim();
            String album = parts[3].trim();
            long posUs = parseLongSafe(parts[4].trim(), 0L);
            long lenUs = parseLongSafe(parts[5].trim(), 0L);
            String player = parts[6].trim();
            if (title.isEmpty()) {
                return MediaSnapshot.EMPTY;
            }
            return new MediaSnapshot(title, artist, album, posUs / 1000L, lenUs / 1000L, playing, player, "playerctl");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MediaSnapshot tryGdbus() {
        try {
            Process busProc = new ProcessBuilder("dbus-send", "--session", "--dest=org.freedesktop.DBus",
                    "--type=method_call", "--print-reply", "/org/freedesktop/DBus",
                    "org.freedesktop.DBus.ListNames").start();
            String busOut = readAll(busProc);
            if (!busProc.waitFor(1500L, TimeUnit.MILLISECONDS)) {
                busProc.destroyForcibly();
                return null;
            }
            if (busProc.exitValue() != 0) {
                return null;
            }

            Matcher matcher = Pattern.compile("\"(org\\.mpris\\.MediaPlayer2\\.[^\"]+)\"").matcher(busOut);
            String target = null;
            String firstFound = null;
            while (matcher.find()) {
                String name = matcher.group(1);
                if (firstFound == null) {
                    firstFound = name;
                }
                if (name.contains("spotify") || name.contains("vlc") || name.contains("chromium") || name.contains("firefox")) {
                    target = name;
                    break;
                }
            }
            if (target == null) {
                target = firstFound;
            }
            if (target == null) {
                return MediaSnapshot.EMPTY;
            }

            Process pMeta = new ProcessBuilder("gdbus", "call", "--session", "--dest", target,
                    "--object-path", "/org/mpris/MediaPlayer2", "--method", "org.freedesktop.DBus.Properties.GetAll",
                    "org.mpris.MediaPlayer2.Player").start();
            String metaOut = readAll(pMeta);
            if (!pMeta.waitFor(1500L, TimeUnit.MILLISECONDS)) {
                pMeta.destroyForcibly();
                return null;
            }
            if (pMeta.exitValue() != 0) {
                return null;
            }

            boolean playing = metaOut.contains("'PlaybackStatus': <'Playing'>");
            String title = extractGdbusString(metaOut, "xesam:title");
            String artist = extractGdbusArrayFirst(metaOut, "xesam:artist");
            String album = extractGdbusString(metaOut, "xesam:album");
            long posUs = extractGdbusNumber(metaOut, "'Position': <(?:int64 )?(\\d+)>");
            long lenUs = extractGdbusNumber(metaOut, "'mpris:length': <(?:uint64 |int64 )?(\\d+)>");

            if (title.isEmpty()) {
                return MediaSnapshot.EMPTY;
            }
            return new MediaSnapshot(title, artist, album, posUs / 1000L, lenUs / 1000L, playing, target, "gdbus");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String extractGdbusString(String haystack, String key) {
        Matcher m = Pattern.compile("'" + Pattern.quote(key) + "':\\s*<'([^']*)'>").matcher(haystack);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String extractGdbusArrayFirst(String haystack, String key) {
        Matcher m = Pattern.compile("'" + Pattern.quote(key) + "':\\s*<\\['([^']*)'").matcher(haystack);
        return m.find() ? m.group(1).trim() : "";
    }

    private static long extractGdbusNumber(String haystack, String regex) {
        Matcher m = Pattern.compile(regex).matcher(haystack);
        return m.find() ? parseLongSafe(m.group(1), 0L) : 0L;
    }

    private static String readAll(Process p) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static long parseLongSafe(String s, long fallback) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    private static final class MediaSnapshot {
        static final MediaSnapshot EMPTY = new MediaSnapshot("", "", "", 0L, 0L, false, "", "None", 0L, null, -1L, -1L);

        final String title;
        final String artist;
        final String album;
        final long positionMs;
        final long durationMs;
        final boolean playing;
        final String player;
        final String backend;
        final long sampleTimeMs;
        final byte[] thumbnailBytes;
        final long rawPosTicks;
        final long lastUpdatedFiletime;

        MediaSnapshot(String title, String artist, String album, long positionMs, long durationMs,
                      boolean playing, String player, String backend) {
            this(title, artist, album, positionMs, durationMs, playing, player, backend, System.currentTimeMillis(), null, -1L, -1L);
        }

        MediaSnapshot(String title, String artist, String album, long positionMs, long durationMs,
                      boolean playing, String player, String backend, long sampleTimeMs, byte[] thumbnailBytes,
                      long rawPosTicks, long lastUpdatedFiletime) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.playing = playing;
            this.player = player;
            this.backend = backend;
            this.sampleTimeMs = sampleTimeMs;
            this.thumbnailBytes = thumbnailBytes;
            this.rawPosTicks = rawPosTicks;
            this.lastUpdatedFiletime = lastUpdatedFiletime;
        }
    }
}

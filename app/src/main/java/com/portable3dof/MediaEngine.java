package com.portable3dof;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.source.SingleSampleMediaSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * ExoPlayer wrapper. ExoPlayer must be driven from one looper thread, so everything that touches it
 * runs on the main thread; the public getters return volatile snapshots that the GL thread and the
 * UI painter can read from anywhere.
 */
public class MediaEngine implements Player.Listener, Runnable {

    /** One selectable audio or subtitle track. */
    public static final class TrackOpt {
        public final String label;
        public final Tracks.Group group;
        public final int index;
        public final boolean selected;

        TrackOpt(String label, Tracks.Group group, int index, boolean selected) {
            this.label = label;
            this.group = group;
            this.index = index;
            this.selected = selected;
        }
    }

    /** Debug switches from the launch intent (--es exp perf,noaudio,nopcm): perf = log pacing/codec stats (tag P3D_PERF),
     *  noaudio = disable audio, nopcm = skip the in-app TrueHD/DTS decoder (old passthrough path). */
    public static volatile String exp = "";

    private final Context ctx;
    private final ResumeStore resume;
    private final Handler main = new Handler(Looper.getMainLooper());

    private ExoPlayer player;           // main thread only
    private Surface surface;            // main thread only
    private Uri currentUri;
    private String currentTitle = "";
    private String currentPath = null;
    private float volume = 1f;
    private float speed = 1f;
    private long lastSaveMs = 0, lastCtrMs = 0;

    private volatile boolean hasMedia = false;
    private volatile boolean playing = false;
    private volatile boolean buffering = false;
    private volatile boolean ended = false;
    private volatile long positionMs = 0, durationMs = 0, bufferedMs = 0;
    private volatile int videoW = 16, videoH = 9;
    private volatile String error = null;
    private volatile List<TrackOpt> audioTracks = Collections.emptyList();
    private volatile List<TrackOpt> textTracks = Collections.emptyList();
    private volatile boolean textDisabled = false;
    private volatile String cueText = "";

    public MediaEngine(Context ctx, ResumeStore resume) {
        this.ctx = ctx.getApplicationContext();
        this.resume = resume;
    }

    // ---- surface (called from the GL thread) ------------------------------------------------

    public void attachSurface(final Surface s) {
        main.post(() -> {
            surface = s;
            if (player != null) player.setVideoSurface(s);
        });
    }

    public void detachSurface() {
        main.post(() -> {
            surface = null;
            if (player != null) player.clearVideoSurface();
        });
    }

    // ---- control (main thread) --------------------------------------------------------------

    private void ensurePlayer() {
        if (player != null) return;
        // Bounded buffer: ExoPlayer's default asks for ~130 MB of Java heap for 4K, which on a 3 GB device that is
        // already swapping means constant GC. 30 s / 64 MB still covers USB hiccups (the drive reads ~22 MB/s).
        androidx.media3.exoplayer.DefaultLoadControl load = new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(10000, 30000, 1500, 3000)
                .setTargetBufferBytes(64 * 1024 * 1024)
                .build();
        player = new ExoPlayer.Builder(ctx, new P3dFfmpeg.Factory(ctx))
                .setLoadControl(load)
                .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(new ReadAheadDataSource.Factory(ctx)))
                .build();
        player.addListener(this);
        if (exp.contains("perf")) player.addAnalyticsListener(new PerfLog());
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setVolume(volume);
        player.setPlaybackSpeed(speed);
        if (exp.contains("noaudio")) player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build());
        if (surface != null) player.setVideoSurface(surface);
        main.postDelayed(this, 250);
    }

    /** Diagnostics: logcat tag P3D_PERF. Codec choices, dropped frames, audio underruns, time to first frame. */
    private final class PerfLog implements androidx.media3.exoplayer.analytics.AnalyticsListener {
        private long t0 = 0;
        private int dropTotal = 0;
        @Override public void onPlaybackStateChanged(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime e, int state) {
            if (state == Player.STATE_BUFFERING && t0 == 0) t0 = android.os.SystemClock.elapsedRealtime();
            android.util.Log.i("P3D_PERF", "state=" + state + " t=" + (t0 == 0 ? 0 : android.os.SystemClock.elapsedRealtime() - t0) + "ms");
        }
        @Override public void onRenderedFirstFrame(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime e, Object out, long ms) {
            android.util.Log.i("P3D_PERF", "first frame after " + (t0 == 0 ? -1 : android.os.SystemClock.elapsedRealtime() - t0) + "ms");
            t0 = 0;
        }
        @Override public void onVideoDecoderInitialized(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime e, String name, long ms, long initMs) {
            android.util.Log.i("P3D_PERF", "video decoder " + name + " init " + initMs + "ms");
        }
        @Override public void onAudioDecoderInitialized(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime e, String name, long ms, long initMs) {
            android.util.Log.i("P3D_PERF", "audio decoder " + name);
        }
        @Override public void onVideoInputFormatChanged(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime e, Format f, androidx.media3.exoplayer.DecoderReuseEvaluation r) {
            android.util.Log.i("P3D_PERF", "video " + f.sampleMimeType + " " + f.width + "x" + f.height + " " + f.frameRate + "fps " + f.bitrate + "bps " + f.colorInfo);
        }
        @Override public void onAudioInputFormatChanged(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime e, Format f, androidx.media3.exoplayer.DecoderReuseEvaluation r) {
            android.util.Log.i("P3D_PERF", "audio " + f.sampleMimeType + " ch=" + f.channelCount + " " + f.sampleRate + "Hz");
        }
        @Override public void onDroppedVideoFrames(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime e, int n, long ms) {
            dropTotal += n;
            android.util.Log.i("P3D_PERF", "dropped " + n + " video frames in " + ms + "ms (total " + dropTotal + ")");
        }
        @Override public void onAudioUnderrun(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime e, int size, long ms, long elapsed) {
            android.util.Log.i("P3D_PERF", "AUDIO UNDERRUN buf=" + ms + "ms");
        }
        @Override public void onBandwidthEstimate(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime e, int ms, long bytes, long bps) {
            android.util.Log.i("P3D_PERF", "bandwidth " + bytes / 1024 + "KB in " + ms + "ms est=" + bps / 1000000 + "Mbps buffered=" + e.totalBufferedDurationMs + "ms");
        }
        @Override public void onLoadError(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime e, androidx.media3.exoplayer.source.LoadEventInfo l, androidx.media3.exoplayer.source.MediaLoadData d, java.io.IOException ex, boolean cancelled) {
            android.util.Log.i("P3D_PERF", "LOAD ERROR " + ex);
        }
        @Override public void onIsLoadingChanged(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime e, boolean loading) {
            android.util.Log.i("P3D_PERF", "loading=" + loading + " buffered=" + e.totalBufferedDurationMs + "ms");
        }
    }

    /** Finds subtitle files next to the video (Movie.srt, Movie.en.srt, ...) when its file path is known. */
    private static List<MediaItem.SubtitleConfiguration> sidecars(Uri uri, String path) {
        List<MediaItem.SubtitleConfiguration> out = new ArrayList<>();
        try {
            if (path == null && "file".equals(uri.getScheme())) path = uri.getPath();
            if (path == null) return out;
            java.io.File video = new java.io.File(path);
            java.io.File dir = video.getParentFile();
            java.io.File[] siblings = dir == null ? null : dir.listFiles();
            if (siblings == null) return out;
            String vn = video.getName();
            String base = (vn.lastIndexOf('.') > 0 ? vn.substring(0, vn.lastIndexOf('.')) : vn).toLowerCase(Locale.US);
            java.util.Arrays.sort(siblings);
            for (java.io.File s : siblings) {
                String n = s.getName().toLowerCase(Locale.US);
                int dot = n.lastIndexOf('.');
                if (dot <= 0 || !n.startsWith(base)) continue;
                String ext = n.substring(dot + 1);
                String mime;
                if (ext.equals("srt")) mime = androidx.media3.common.MimeTypes.APPLICATION_SUBRIP;
                else if (ext.equals("vtt")) mime = androidx.media3.common.MimeTypes.TEXT_VTT;
                else if (ext.equals("ass") || ext.equals("ssa")) mime = androidx.media3.common.MimeTypes.TEXT_SSA;
                else continue;
                String lang = n.substring(base.length(), dot).replace(".", "").replace("_", "").replace("-", "").trim();
                String label = lang.isEmpty() ? "External" : lang.toUpperCase(Locale.US);
                out.add(new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(s))
                        .setMimeType(mime)
                        .setLanguage(lang.isEmpty() || lang.length() > 3 ? null : lang)
                        .setLabel(label + " (file)")
                        .setSelectionFlags(out.isEmpty() ? C.SELECTION_FLAG_DEFAULT : 0)
                        .build());
            }
        } catch (RuntimeException e) {
            // no permission / odd path: just play without sidecars
        }
        return out;
    }

    /** Plays a YouTube video: separate video + audio streams merged, optional subtitle tracks, or a live HLS stream. */
    public void openYoutube(Uri key, String title, YouTubeClient.Playable p) { openYoutube(key, title, p, -1); }

    /** startMs < 0 resumes from the saved position; otherwise starts exactly there (used when the quality changes). */
    public void openYoutube(Uri key, String title, YouTubeClient.Playable p, long startMs) {
        saveProgress();
        ensurePlayer();
        currentUri = key;
        currentTitle = title;
        currentPath = null;
        error = null;
        ended = false;
        hasMedia = true;
        long start = resume.positionFor(key);
        long startAt = startMs >= 0 ? startMs : (start > 5000 ? start - 3000 : 0);

        MediaSource source = YtMediaSource.build(p);
        textDisabled = true;      // YouTube subtitles start off; pick one in Settings > Subtitles
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        player.setMediaSource(source, startAt);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    public void open(Uri uri, String title, String path) {
        saveProgress();
        ensurePlayer();
        currentUri = uri;
        currentTitle = title;
        currentPath = path != null ? path : ("file".equals(uri.getScheme()) ? uri.getPath() : null);
        error = null;
        ended = false;
        hasMedia = true;
        long start = resume.positionFor(uri);
        MediaItem.Builder item = new MediaItem.Builder().setUri(uri);
        List<MediaItem.SubtitleConfiguration> subs = sidecars(uri, path);
        if (!subs.isEmpty()) item.setSubtitleConfigurations(subs);
        player.setMediaItem(item.build(), start > 5000 ? start - 3000 : 0);   // back up a few seconds
        player.prepare();
        player.setPlayWhenReady(true);
        textDisabled = false;
    }

    /** Stops playback, remembers the position and frees the decoder. */
    public void close() {
        saveProgress();
        hasMedia = false;
        playing = false;
        if (player != null) {
            main.removeCallbacks(this);
            player.release();
            player = null;
        }
        cueText = "";
        audioTracks = Collections.emptyList();
        textTracks = Collections.emptyList();
    }

    public void togglePlay() {
        if (player == null) return;
        if (ended) { player.seekTo(0); player.play(); return; }
        if (player.isPlaying() || player.getPlayWhenReady()) player.pause(); else player.play();
    }

    public void pause() { if (player != null) player.pause(); }

    public void seekBy(long deltaMs) {
        if (player == null) return;
        long d = player.getDuration();
        long t = player.getCurrentPosition() + deltaMs;
        t = Math.max(0, d == C.TIME_UNSET ? t : Math.min(t, d));
        player.seekTo(t);
        positionMs = t;
    }

    public void seekToFraction(float f) {
        if (player == null || durationMs <= 0) return;
        long t = (long) (f * durationMs);
        player.seekTo(t);
        positionMs = t;
    }

    public void adjustVolume(float delta) {
        volume = Math.max(0f, Math.min(1f, volume + delta));
        if (player != null) player.setVolume(volume);
    }

    public float getVolume() { return volume; }

    public void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        if (player != null) player.setVolume(volume);
    }

    public void setSpeed(float s) {
        speed = Math.max(0.25f, Math.min(3f, s));
        if (player != null) player.setPlaybackSpeed(speed);
    }

    public float getSpeed() { return speed; }

    /** delta = +1 / -1 steps through the available audio tracks. */
    public void cycleAudio(int delta) {
        List<TrackOpt> t = audioTracks;
        if (player == null || t.isEmpty()) return;
        int cur = 0;
        for (int i = 0; i < t.size(); i++) if (t.get(i).selected) cur = i;
        TrackOpt n = t.get(((cur + delta) % t.size() + t.size()) % t.size());
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setOverrideForType(new TrackSelectionOverride(n.group.getMediaTrackGroup(), n.index)).build());
    }

    /** Cycles through: off, track 1, track 2, ... */
    public void cycleSubtitles(int delta) {
        List<TrackOpt> t = textTracks;
        if (player == null || t.isEmpty()) return;
        int cur = -1;   // -1 = off
        if (!textDisabled) for (int i = 0; i < t.size(); i++) if (t.get(i).selected) cur = i;
        int n = cur + delta;
        int count = t.size() + 1;           // +1 for "off"
        int idx = ((n + 1) % count + count) % count - 1;
        if (idx < 0) {
            textDisabled = true;
            cueText = "";
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        } else {
            textDisabled = false;
            TrackOpt o = t.get(idx);
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(new TrackSelectionOverride(o.group.getMediaTrackGroup(), o.index)).build());
        }
    }

    // ---- snapshots --------------------------------------------------------------------------

    public boolean hasMedia() { return hasMedia; }
    public boolean isPlaying() { return playing; }
    public boolean isBuffering() { return buffering; }
    public boolean isEnded() { return ended; }
    public long getPositionMs() { return positionMs; }
    public long getDurationMs() { return durationMs; }
    public long getBufferedMs() { return bufferedMs; }
    public int getVideoWidth() { return videoW; }
    public int getVideoHeight() { return videoH; }
    public String getError() { return error; }
    public String getTitle() { return currentTitle; }
    public String getCueText() { return textDisabled ? "" : cueText; }
    public List<TrackOpt> getAudioTracks() { return audioTracks; }
    public List<TrackOpt> getTextTracks() { return textTracks; }
    public boolean isTextDisabled() { return textDisabled; }
    public Uri getUri() { return currentUri; }
    public String getPath() { return currentPath; }

    public String audioLabel() {
        for (TrackOpt t : audioTracks) if (t.selected) return t.label;
        return audioTracks.isEmpty() ? "-" : audioTracks.get(0).label;
    }

    public String subtitleLabel() {
        if (textTracks.isEmpty()) return "None";
        if (textDisabled) return "Off";
        for (TrackOpt t : textTracks) if (t.selected) return t.label;
        return "Off";
    }

    // ---- main-thread ticker -----------------------------------------------------------------

    @Override
    public void run() {
        if (player == null) return;
        long d = player.getDuration();
        durationMs = d == C.TIME_UNSET ? 0 : d;
        positionMs = player.getCurrentPosition();
        bufferedMs = player.getBufferedPosition();
        if (exp.contains("perf") && System.currentTimeMillis() - lastCtrMs > 3000 && player.isPlaying()) {
            lastCtrMs = System.currentTimeMillis();
            androidx.media3.exoplayer.DecoderCounters c = player.getVideoDecoderCounters();
            if (c != null) { c.ensureUpdated(); android.util.Log.i("P3D_PERF", "counters rendered=" + c.renderedOutputBufferCount + " dropped=" + c.droppedBufferCount
                    + " skipped=" + c.skippedOutputBufferCount + " maxConsecDropped=" + c.maxConsecutiveDroppedBufferCount + " inputQueued=" + c.queuedInputBufferCount
                    + " pos=" + player.getCurrentPosition() / 1000.0 + "s fmtFps=" + (player.getVideoFormat() == null ? 0 : player.getVideoFormat().frameRate)); }
        }
        long now = System.currentTimeMillis();
        if (now - lastSaveMs > 5000) { saveProgress(); lastSaveMs = now; }
        main.postDelayed(this, 250);
    }

    private void saveProgress() {
        if (player != null && currentUri != null && hasMedia && !ended) {
            resume.put(currentUri, currentTitle, player.getCurrentPosition(),
                    player.getDuration() == C.TIME_UNSET ? 0 : player.getDuration());
        }
    }

    // ---- Player.Listener --------------------------------------------------------------------

    @Override
    public void onIsPlayingChanged(boolean isPlaying) { playing = isPlaying; }

    @Override
    public void onPlaybackStateChanged(int state) {
        buffering = state == Player.STATE_BUFFERING;
        ended = state == Player.STATE_ENDED;
        if (ended && currentUri != null) resume.markFinished(currentUri);
    }

    @Override
    public void onVideoSizeChanged(VideoSize size) {
        if (size.width > 0 && size.height > 0) {
            // account for non-square pixels
            videoW = Math.round(size.width * size.pixelWidthHeightRatio);
            videoH = size.height;
        }
    }

    @Override
    public void onPlayerError(PlaybackException e) {
        error = e.getErrorCodeName();
        playing = false;
    }

    @Override
    public void onCues(CueGroup cueGroup) {
        StringBuilder sb = new StringBuilder();
        for (Cue c : cueGroup.cues) {
            if (c.text != null) { if (sb.length() > 0) sb.append('\n'); sb.append(c.text); }
        }
        cueText = sb.toString();
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
        List<TrackOpt> a = new ArrayList<>(), t = new ArrayList<>();
        for (Tracks.Group g : tracks.getGroups()) {
            int type = g.getType();
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                TrackOpt o = new TrackOpt(describe(g.getTrackFormat(i), type), g, i, g.isTrackSelected(i));
                (type == C.TRACK_TYPE_AUDIO ? a : t).add(o);
            }
        }
        audioTracks = a;
        textTracks = t;
    }

    private static String describe(Format f, int type) {
        StringBuilder sb = new StringBuilder();
        String lang = f.language != null && !"und".equals(f.language)
                ? new Locale(f.language).getDisplayLanguage() : null;
        if (f.label != null) sb.append(f.label);
        else if (lang != null && !lang.isEmpty()) sb.append(lang);
        else sb.append(type == C.TRACK_TYPE_AUDIO ? "Audio" : "Subtitles");
        if (type == C.TRACK_TYPE_AUDIO) {
            int ch = f.channelCount;
            if (ch == 1) sb.append(" \u00b7 mono");
            else if (ch == 2) sb.append(" \u00b7 stereo");
            else if (ch > 2) sb.append(" \u00b7 ").append(ch - 1).append(".1");
            if ((f.roleFlags & C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) sb.append(" \u00b7 audio description");
        } else if ((f.selectionFlags & C.SELECTION_FLAG_FORCED) != 0) {
            sb.append(" (forced)");
        }
        return sb.toString();
    }
}

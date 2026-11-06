package com.portable3dof;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.source.SingleSampleMediaSource;

import java.util.ArrayList;
import java.util.List;

/** Builds the ExoPlayer source for a resolved YouTube video: video + audio merged, subtitle tracks, or live HLS. */
final class YtMediaSource {
    private YtMediaSource() { }

    static MediaSource build(YouTubeClient.Playable p) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(YtDownloader.USER_AGENT)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(10000)
                .setAllowCrossProtocolRedirects(true);
        ProgressiveMediaSource.Factory progressive = new ProgressiveMediaSource.Factory(
                MediaEngine.exp.contains("nochunk") ? http : new YtChunkedDataSource.Factory(http));

        if (p.live && p.hlsUrl != null) {
            return new HlsMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(p.hlsUrl));
        }
        if (p.videoUrl != null && p.audioUrl != null) {
            List<MediaSource> parts = new ArrayList<>();
            parts.add(progressive.createMediaSource(MediaItem.fromUri(p.videoUrl)));
            parts.add(progressive.createMediaSource(MediaItem.fromUri(p.audioUrl)));
            SingleSampleMediaSource.Factory subFactory = new SingleSampleMediaSource.Factory(http);
            for (YouTubeClient.Sub s : p.subs) {
                parts.add(subFactory.createMediaSource(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                        .setMimeType(s.mime)
                        .setLanguage(s.lang)
                        .setLabel(s.label)
                        .build(), C.TIME_UNSET));
            }
            return new MergingMediaSource(parts.toArray(new MediaSource[0]));
        }
        return progressive.createMediaSource(MediaItem.fromUri(p.muxedUrl));
    }
}

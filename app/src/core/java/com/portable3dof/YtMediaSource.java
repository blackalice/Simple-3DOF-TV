package com.portable3dof;

import androidx.media3.exoplayer.source.MediaSource;

/** Stand-in for the "core" edition: there is no YouTube playback to build a source for. */
final class YtMediaSource {
    private YtMediaSource() { }

    static MediaSource build(YouTubeClient.Playable p) {
        throw new UnsupportedOperationException("YouTube is not part of this edition");
    }
}

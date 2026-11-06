package com.portable3dof;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer;
import androidx.media3.decoder.ffmpeg.FfmpegLibrary;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;

import java.util.ArrayList;

/**
 * Decodes TrueHD / MLP / DTS inside the app (FFmpeg, built from source by tools/build_ffmpeg.sh) instead of passing
 * them to the device's audio system.
 *
 * Why: handed TrueHD, ExoPlayer chooses passthrough, and the audio HAL then decodes it in a real-time thread that used
 * ~50% of a core while 4K video played, which made head movement stutter. Decoding to PCM here costs about a quarter of
 * that and keeps the real-time thread idle. The FFmpeg build contains only those decoders, so every other format keeps
 * using the platform codecs. If libffmpegJNI.so is not in the APK (FFmpeg was not built) nothing changes.
 */
final class P3dFfmpeg {
    private static final String TAG = "P3D_PERF";

    private P3dFfmpeg() { }

    /** Our FFmpeg audio renderer is tried before the platform audio renderer, otherwise passthrough would win. */
    static final class Factory extends DefaultRenderersFactory {
        Factory(Context ctx) { super(ctx); }

        @Override
        protected void buildAudioRenderers(Context context, int mode, MediaCodecSelector sel, boolean fallback,
                                           AudioSink sink, Handler handler, AudioRendererEventListener l, ArrayList<Renderer> out) {
            if (!MediaEngine.exp.contains("nopcm") && FfmpegLibrary.isAvailable()) {
                out.add(new FfmpegAudioRenderer(handler, l, sink));
                if (MediaEngine.exp.contains("perf")) Log.i(TAG, "FFmpeg audio decoders available: " + FfmpegLibrary.getVersion());
            }
            super.buildAudioRenderers(context, mode, sel, fallback, sink, handler, l, out);
        }
    }
}

package com.portable3dof;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Downloads googlevideo streams in bounded range chunks (the `range=a-b` URL parameter, as NewPipe and yt-dlp do).
 *
 * One open-ended request per stream is what the Pocket TV used to do, and googlevideo would send an initial burst and
 * then stop for 20+ seconds (the wlan0 receive rate dropped to exactly zero until our read timeout fired and the load
 * was retried), which showed up as a long rebuffer a few seconds into 1080p+ playback. Bounded chunks keep every
 * request short and are not throttled that way. Total length comes from the `clen` parameter in the URL; URLs without
 * it (and non-googlevideo hosts) go straight to the wrapped source.
 */
final class YtChunkedDataSource implements DataSource {

    private static final long CHUNK = 5L * 1024 * 1024;

    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory inner;
        Factory(DataSource.Factory inner) { this.inner = inner; }
        @Override public DataSource createDataSource() { return new YtChunkedDataSource(inner.createDataSource()); }
    }

    private final DataSource child;
    private DataSpec spec;
    private boolean chunked;
    private long pos, end;
    private int rn;
    private boolean childOpen;

    private YtChunkedDataSource(DataSource child) { this.child = child; }

    @Override public void addTransferListener(TransferListener l) { child.addTransferListener(l); }

    @Override
    public long open(DataSpec s) throws IOException {
        spec = s;
        Uri u = s.uri;
        String host = u.getHost();
        long clen = -1;
        if (host != null && host.endsWith("googlevideo.com") && u.getQueryParameter("range") == null) {
            try { clen = Long.parseLong(String.valueOf(u.getQueryParameter("clen"))); } catch (NumberFormatException ignored) { }
        }
        if (clen <= 0 || s.position >= clen) {
            chunked = false;
            childOpen = true;
            return child.open(s);
        }
        chunked = true;
        pos = s.position;
        end = s.length == C.LENGTH_UNSET ? clen : Math.min(clen, s.position + s.length);
        rn = 0;
        openChunk();
        return end - pos;
    }

    private void openChunk() throws IOException {
        long last = Math.min(pos + CHUNK, end) - 1;
        Uri.Builder b = spec.uri.buildUpon().clearQuery();
        for (String name : spec.uri.getQueryParameterNames()) {
            if (name.equals("range") || name.equals("rn")) continue;
            for (String v : spec.uri.getQueryParameters(name)) b.appendQueryParameter(name, v);
        }
        b.appendQueryParameter("range", pos + "-" + last).appendQueryParameter("rn", String.valueOf(++rn));
        child.open(spec.buildUpon().setUri(b.build()).setPosition(0).setLength(C.LENGTH_UNSET).build());
        childOpen = true;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (!chunked) return child.read(buf, off, len);
        while (true) {
            if (pos >= end) return C.RESULT_END_OF_INPUT;
            int n = child.read(buf, off, len);
            if (n == C.RESULT_END_OF_INPUT) {
                child.close();
                childOpen = false;
                openChunk();
                continue;
            }
            pos += n;
            return n;
        }
    }

    @Override public Uri getUri() { return child.getUri(); }
    @Override public Map<String, List<String>> getResponseHeaders() { return childOpen ? child.getResponseHeaders() : Collections.<String, List<String>>emptyMap(); }

    @Override
    public void close() throws IOException {
        if (childOpen) { childOpen = false; child.close(); }
    }
}

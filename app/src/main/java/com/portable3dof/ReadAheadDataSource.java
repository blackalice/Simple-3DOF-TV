package com.portable3dof;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Local-file data source with a read-ahead buffer.
 *
 * Matroska/MP4 parsing asks for a few hundred bytes at a time. On this device /storage is a FUSE mount, so
 * each of those reads is a round trip to the MediaProvider daemon: a 4K remux produced ~20,000 reads/s and
 * kept the loader thread at 60% CPU while the buffer filled slowly. Serving small reads from a 256 KiB
 * buffer turns that into ~20 large reads/s. Non-file URIs go to the stock DefaultDataSource.
 */
public final class ReadAheadDataSource implements DataSource {

    public static final class Factory implements DataSource.Factory {
        private final Context ctx;
        public Factory(Context ctx) { this.ctx = ctx.getApplicationContext(); }
        @Override public DataSource createDataSource() { return new ReadAheadDataSource(new DefaultDataSource.Factory(ctx).createDataSource()); }
    }

    private static final int BUF = 256 * 1024;

    private final DataSource fallback;
    private final FileSource file = new FileSource();
    private DataSource active;

    private ReadAheadDataSource(DataSource fallback) { this.fallback = fallback; }

    @Override public void addTransferListener(TransferListener l) { fallback.addTransferListener(l); file.addTransferListener(l); }

    @Override
    public long open(DataSpec spec) throws IOException {
        Uri u = spec.uri;
        boolean local = "file".equals(u.getScheme()) || (u.getScheme() == null && u.getPath() != null);
        active = local ? file : fallback;
        return active.open(spec);
    }

    @Override public int read(byte[] b, int off, int len) throws IOException { return active.read(b, off, len); }
    @Override public Uri getUri() { return active == null ? null : active.getUri(); }
    @Override public java.util.Map<String, List<String>> getResponseHeaders() { return active == null ? java.util.Collections.emptyMap() : active.getResponseHeaders(); }
    @Override public void close() throws IOException { if (active != null) { try { active.close(); } finally { active = null; } } }

    private static final class FileSource extends BaseDataSource {
        private RandomAccessFile raf;
        private Uri uri;
        private long position, remaining;
        private byte[] buf;
        private long bufStart = -1;
        private int bufLen = 0;
        private boolean opened;

        FileSource() { super(/* isNetwork= */ false); }

        @Override
        public long open(DataSpec spec) throws IOException {
            try {
                uri = spec.uri;
                transferInitializing(spec);
                raf = new RandomAccessFile(uri.getPath(), "r");
                long len = raf.length();
                position = spec.position;
                if (position > len) throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
                remaining = spec.length != C.LENGTH_UNSET ? spec.length : len - position;
                if (remaining < 0) throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
                if (buf == null) buf = new byte[BUF];
                bufStart = -1; bufLen = 0;
            } catch (IOException e) {
                throw e instanceof DataSourceException ? e : new FileDataSource.FileDataSourceException(e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND);
            }
            opened = true;
            transferStarted(spec);
            return remaining;
        }

        @Override
        public int read(byte[] target, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (remaining == 0) return C.RESULT_END_OF_INPUT;
            len = (int) Math.min(len, remaining);
            int n;
            try {
                if (bufStart >= 0 && position >= bufStart && position < bufStart + bufLen) {
                    int from = (int) (position - bufStart);
                    n = Math.min(len, bufLen - from);
                    System.arraycopy(buf, from, target, off, n);
                } else if (len >= BUF) {
                    raf.seek(position);
                    n = raf.read(target, off, len);
                    if (n <= 0) return C.RESULT_END_OF_INPUT;
                } else {
                    raf.seek(position);
                    int got = raf.read(buf, 0, BUF);
                    if (got <= 0) { bufStart = -1; bufLen = 0; return C.RESULT_END_OF_INPUT; }
                    bufStart = position; bufLen = got;
                    n = Math.min(len, got);
                    System.arraycopy(buf, 0, target, off, n);
                }
            } catch (IOException e) {
                throw new FileDataSource.FileDataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
            }
            position += n;
            remaining -= n;
            bytesTransferred(n);
            return n;
        }

        @Override public Uri getUri() { return uri; }

        @Override
        public void close() throws IOException {
            uri = null;
            try { if (raf != null) raf.close(); }
            catch (IOException e) { throw new FileDataSource.FileDataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED); }
            finally {
                raf = null;
                if (opened) { opened = false; transferEnded(); }
            }
        }
    }
}

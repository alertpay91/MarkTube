package com.marko.slimtube;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * YouTube's video servers stop sending after a few megabytes when a stream is fetched in one
 * piece. Like YouTube's own players, ask for it in pieces ("&range=a-b" in the address), one
 * after the other, transparently for the player.
 */
@OptIn(markerClass = UnstableApi.class)
final class ChunkedDataSource implements DataSource {
    private static final long CHUNK = 2L * 1024 * 1024;

    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstream;

        Factory(DataSource.Factory upstream) {
            this.upstream = upstream;
        }

        @NonNull
        @Override
        public DataSource createDataSource() {
            return new ChunkedDataSource(upstream.createDataSource());
        }
    }

    private final DataSource upstream;
    private Uri uri;
    private long pos, end, chunkEnd;
    private int rn;
    private boolean chunked, opened;

    private ChunkedDataSource(DataSource upstream) {
        this.upstream = upstream;
    }

    @Override
    public void addTransferListener(@NonNull TransferListener l) {
        upstream.addTransferListener(l);
    }

    @Override
    public long open(@NonNull DataSpec spec) throws IOException {
        uri = spec.uri;
        long clen = -1;
        try {
            String c = uri.getQueryParameter("clen");
            if (c != null) clen = Long.parseLong(c);
        } catch (Exception ignored) {
        }
        chunked = clen > 0 && "googlevideo.com".equals(domain(uri.getHost()));
        if (!chunked) {
            opened = true;
            return upstream.open(spec);
        }
        pos = spec.position;
        end = spec.length != C.LENGTH_UNSET ? Math.min(clen, pos + spec.length) : clen;
        openChunk();
        return end - pos;
    }

    private static String domain(String host) {
        if (host == null) return "";
        int i = host.lastIndexOf('.', host.lastIndexOf('.') - 1);
        return i < 0 ? host : host.substring(i + 1);
    }

    private void openChunk() throws IOException {
        chunkEnd = Math.min(end, pos + CHUNK);
        Uri u = uri.buildUpon()
                .appendQueryParameter("range", pos + "-" + (chunkEnd - 1))
                .appendQueryParameter("rn", String.valueOf(++rn))
                .build();
        upstream.open(new DataSpec.Builder().setUri(u).build());
        opened = true;
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        if (!chunked) return upstream.read(buffer, offset, length);
        if (length == 0) return 0;
        if (pos >= end) return C.RESULT_END_OF_INPUT;
        if (pos >= chunkEnd) {
            upstream.close();
            opened = false;
            openChunk();
        }
        int n = upstream.read(buffer, offset, (int) Math.min(length, chunkEnd - pos));
        if (n == C.RESULT_END_OF_INPUT) {
            // the server ended this piece early: ask again from where it stopped
            upstream.close();
            opened = false;
            if (pos >= end) return C.RESULT_END_OF_INPUT;
            openChunk();
            n = upstream.read(buffer, offset, (int) Math.min(length, chunkEnd - pos));
            if (n == C.RESULT_END_OF_INPUT) throw new IOException("stream ended early at " + pos);
        }
        pos += n;
        return n;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @NonNull
    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        if (opened) {
            opened = false;
            upstream.close();
        }
    }
}

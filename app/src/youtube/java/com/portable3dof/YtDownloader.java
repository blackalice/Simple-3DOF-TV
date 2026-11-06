package com.portable3dof;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/** HTTP for NewPipeExtractor (the same job as NewPipe's DownloaderImpl). */
public final class YtDownloader extends Downloader {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build();

    public OkHttpClient client() { return client; }

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        byte[] data = request.dataToSend();
        RequestBody body = data != null ? RequestBody.create(data) : null;
        okhttp3.Request.Builder b = new okhttp3.Request.Builder()
                .method(request.httpMethod(), body)
                .url(request.url())
                .addHeader("User-Agent", USER_AGENT);
        for (Map.Entry<String, List<String>> e : request.headers().entrySet()) {
            b.removeHeader(e.getKey());
            for (String v : e.getValue()) b.addHeader(e.getKey(), v);
        }
        long t0 = android.os.SystemClock.elapsedRealtime();
        okhttp3.Response r = client.newCall(b.build()).execute();
        if (MediaEngine.exp.contains("perf")) android.util.Log.i("P3D_PERF", "http " + request.httpMethod() + " " + r.code() + " " + (android.os.SystemClock.elapsedRealtime() - t0) + "ms "
                + request.url().split("[?]")[0]);
        if (r.code() == 429) {
            r.close();
            throw new ReCaptchaException("rate limited (429)", request.url());
        }
        ResponseBody rb = r.body();
        String s = rb == null ? null : rb.string();
        return new Response(r.code(), r.message(), r.headers().toMultimap(), s, r.request().url().toString());
    }
}

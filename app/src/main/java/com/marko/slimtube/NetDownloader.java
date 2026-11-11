package com.marko.slimtube;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** The network bridge the stream finder uses for its page and API requests. */
public final class NetDownloader extends Downloader {
    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";
    private static final NetDownloader INSTANCE = new NetDownloader();

    public static NetDownloader get() {
        return INSTANCE;
    }

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        HttpURLConnection c = (HttpURLConnection) new URL(request.url()).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setInstanceFollowRedirects(true);
        c.setRequestMethod(request.httpMethod());
        c.setRequestProperty("User-Agent", UA);
        for (Map.Entry<String, List<String>> e : request.headers().entrySet()) {
            boolean first = true;
            for (String v : e.getValue()) {
                if (first) c.setRequestProperty(e.getKey(), v);
                else c.addRequestProperty(e.getKey(), v);
                first = false;
            }
        }
        byte[] data = request.dataToSend();
        if (data != null) {
            c.setDoOutput(true);
            try (OutputStream out = c.getOutputStream()) {
                out.write(data);
            }
        }
        int code = c.getResponseCode();
        if (code == 429) {
            c.disconnect();
            throw new ReCaptchaException("Previše zahteva, pokušajte malo kasnije", request.url());
        }
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = "";
        if (in != null && !"HEAD".equals(request.httpMethod())) {
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            in.close();
            body = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
        Map<String, List<String>> headers = new HashMap<>();
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            if (e.getKey() != null) headers.put(e.getKey(), e.getValue());
        }
        String latest = c.getURL().toString();
        String msg = c.getResponseMessage();
        c.disconnect();
        return new Response(code, msg == null ? "" : msg, headers, body, latest);
    }
}

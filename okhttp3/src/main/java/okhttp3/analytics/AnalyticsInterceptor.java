package okhttp3.analytics;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.IdentityHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Connection;
import okhttp3.Headers;
import okhttp3.HttpHeaders;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.analytics.impl.PlatformAnalyticsFactory;
import okio.Buffer;
import okio.BufferedSource;

/**
 * 不要和 HttpLoggingInterceptor 共用
 */
public final class AnalyticsInterceptor implements Interceptor {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final AnalyticsFactory factory;

    private final Level level;

    public AnalyticsInterceptor() {
        this(AnalyticsFactory.LOGCAT, Level.BASIC);
    }

    public AnalyticsInterceptor(AnalyticsFactory factory, Level level) {
        this.factory = factory != null ? factory : AnalyticsFactory.LOGCAT;
        this.level = level != null ? level : Level.BASIC;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        if (level == Level.NONE) {
            return chain.proceed(request);
        }

        Analytics analytics = factory.analytics();

        boolean logBody = level == Level.BODY;
        boolean logHeaders = logBody || level == Level.HEADERS;

        RequestBody requestBody = request.body();
        boolean hasRequestBody = requestBody != null;

        if (!logHeaders && hasRequestBody) {
            analytics.start(request.method(), request.url().toString(), requestBody.contentLength() + "-byte body");
        } else {
            analytics.start(request.method(), request.url().toString(), null);
        }

        Connection connection = chain.connection();
        Protocol protocol = connection != null ? connection.protocol() : Protocol.HTTP_1_1;
        analytics.connection(protocol.toString());

        if (logHeaders) {
            IdentityHashMap<String, String> requestHeaders = new IdentityHashMap<>();
            if (hasRequestBody) {
                // Request body headers are only present when installed as a network interceptor. Force
                // them to be included (when available) so there values are known.
                if (requestBody.contentType() != null) {
                    requestHeaders.put(HttpHeaders.CONTENT_TYPE, requestBody.contentType().toString());
                }
                if (requestBody.contentLength() != -1) {
                    requestHeaders.put(HttpHeaders.CONTENT_LENGTH, String.valueOf(requestBody.contentLength()));
                }
            }

            Headers headers = request.headers();
            for (int i = 0, count = headers.size(); i < count; i++) {
                String name = headers.name(i);
                // Skip headers from the request body as they are explicitly logged above.
                if (!HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name) && !HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) {
                    requestHeaders.put(name, headers.value(i));
                }
            }

            analytics.requestHeaders(requestHeaders);

            if (!logBody || !hasRequestBody) {
                analytics.requestOmitted(request.method(), null);
            } else if (bodyEncoded(request.headers())) {
                analytics.requestOmitted(request.method(), "encoded body omitted");
            } else {
                Buffer buffer = new Buffer();
                requestBody.writeTo(buffer);

                Charset charset = UTF8;
                MediaType contentType = requestBody.contentType();
                if (contentType != null) {
                    charset = contentType.charset(UTF8);
                }

                if (isPlaintext(contentType, buffer)) {
                    analytics.requestPlaintextBody(buffer.readString(charset));
                    analytics.requestOmitted(request.method(), "plaintext " + requestBody.contentLength() + "-byte body");
                } else {
                    analytics.requestOmitted(request.method(), "binary " + requestBody.contentLength() + "-byte body");
                }
            }
        }

        long startNs = System.nanoTime();
        Response response;
        try {
            response = chain.proceed(request);
            analytics.response();
        } catch (Exception e) {
            analytics.error(request.url().toString(), e);
            analytics.end();
            throw e;
        }
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

        ResponseBody responseBody = response.body();
        final long contentLength = responseBody.contentLength();
        String message = contentLength != -1 ? contentLength + "-byte body" : "unknown-length body";
        analytics.status(response.code(), response.message(), response.request().url().toString(), tookMs, message);

        IdentityHashMap<String, String> responseHeaders = new IdentityHashMap<>();
        if (logHeaders) {
            Headers headers = response.headers();
            for (int i = 0, count = headers.size(); i < count; i++) {
                responseHeaders.put(headers.name(i), headers.value(i));
            }
            analytics.responseHeaders(responseHeaders);

            if (!logBody || !okhttp3.internal.http.HttpHeaders.hasBody(response)) {
                analytics.responseOmitted(response.request().method(), null);
            } else if (bodyEncoded(response.headers())) {
                analytics.responseOmitted(response.request().method(), "encoded body omitted");
            } else {
                BufferedSource source = responseBody.source();
                source.request(Long.MAX_VALUE); // Buffer the entire body.
                Buffer buffer = source.buffer();

                Charset charset = UTF8;
                MediaType contentType = responseBody.contentType();
                if (contentType != null) {
                    charset = contentType.charset(UTF8);
                }

                if (!isPlaintext(contentType, buffer)) {
                    analytics.responseOmitted(response.request().method(), "binary " + buffer.size() + "-byte body omitted");
                } else {
                    if (contentLength != 0) {
                        analytics.responsePlaintextBody(buffer.clone().readString(charset));
                    }

                    analytics.responseOmitted(response.request().method(), buffer.size() + "-byte body");
                }
            }
        }
        analytics.finish(contentLength);
        analytics.end();

        return response;
    }

    private boolean isPlaintext(MediaType contentType, Buffer buffer) {
        if (contentType != null && ("text".equalsIgnoreCase(contentType.type()) || "json".equalsIgnoreCase(contentType.subtype()))) {
            try {
                Buffer prefix = new Buffer();
                long byteCount = buffer.size() < 64 ? buffer.size() : 64;
                buffer.copyTo(prefix, 0, byteCount);
                for (int i = 0; i < 16; i++) {
                    if (prefix.exhausted()) {
                        break;
                    }
                    int codePoint = prefix.readUtf8CodePoint();
                    if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) {
                        return false;
                    }
                }
                return true;
            } catch (EOFException e) {
                return false; // Truncated UTF-8 sequence.
            }
        }
        return false;
    }

    private boolean bodyEncoded(Headers headers) {
        String contentEncoding = headers.get(HttpHeaders.CONTENT_ENCODING);
        return contentEncoding != null && !contentEncoding.equalsIgnoreCase("identity");
    }

    public enum Level {
        NONE,
        BASIC,
        HEADERS,
        BODY
    }

    public interface AnalyticsFactory {
        public Analytics analytics();

        public static AnalyticsFactory LOGCAT = new PlatformAnalyticsFactory();
    }

    public interface Analytics {
        public void start(String method, String url, String message);
        public void connection(String protocol);
        public void requestHeaders(IdentityHashMap<String, String> requestHeaders);
        public void requestPlaintextBody(String plaintext);
        public void requestOmitted(String method, String message);
        public void response();
        public void error(String url, Exception e);
        public void status(int statusCode, String reasonPhrase, String url, long tookMs, String message);
        public void responseHeaders(IdentityHashMap<String, String> responseHeaders);
        public void responsePlaintextBody(String plaintext);
        public void responseOmitted(String method, String message);
        public void finish(long contentLength);
        public void end();
    }
}
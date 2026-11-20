package com.payswitch.load;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * The JDK's own HTTP client (java.net.http), plus Jackson for JSON. No Spring here.
 *
 * <p>One client is shared by every virtual thread: it is thread-safe and keeps a pool of open
 * connections, so 50 concurrent payments do not open 50 new TCP connections each time.
 */
public final class Http {

    public static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Status and raw body. Never throws for a 4xx/5xx: the caller decides what that means. */
    public record Response(int status, String body) {

        public JsonNode json() {
            try {
                return body == null || body.isBlank() ? JSON.nullNode() : JSON.readTree(body);
            } catch (IOException e) {
                return JSON.nullNode();                // e.g. an HTML error page from a proxy
            }
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final HttpClient client;

    public Http(Duration connectTimeout) {
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)       // our Tomcat services speak HTTP/1.1
                .connectTimeout(connectTimeout)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    public Response post(String url, Object body, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> r = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), r.body());
    }

    public Response get(String url, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
        HttpResponse<String> r = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), r.body());
    }

    /** Like post, but anything other than 2xx is an exception. For admin calls that must work. */
    public JsonNode postOk(String url, Object body, Duration timeout) throws IOException, InterruptedException {
        Response r = post(url, body, timeout);
        if (!r.ok()) {
            throw new IOException("POST " + url + " answered " + r.status() + ": " + r.body());
        }
        return r.json();
    }
}

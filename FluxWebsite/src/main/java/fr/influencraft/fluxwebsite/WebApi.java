package fr.influencraft.fluxwebsite;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * The website API (mineweb), shared by every service of the plugin. Private routes carry
 * {@code Authorization: Bearer <api-secret>} ({@code MC_SERVER_SECRET} on the website side). Blocking calls: never
 * from the main thread.
 */
final class WebApi {

    /** HTTP status and body of a call. */
    record Response(int status, String body) {
        boolean ok() {
            return status == 200;
        }

        JsonElement json() {
            return JsonParser.parseString(body);
        }
    }

    private final String baseUrl;
    private final String secret;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    WebApi(String baseUrl, String secret) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.secret = secret == null ? "" : secret;
    }

    /** A URL is set (public routes work). */
    boolean reachable() {
        return !baseUrl.isBlank();
    }

    /** URL and secret are set (private routes work). */
    boolean ready() {
        return reachable() && !secret.isBlank() && !secret.equals("CHANGE_ME");
    }

    Response get(String path, boolean authenticated) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10)).GET();
        if (authenticated) request.header("Authorization", "Bearer " + secret);
        HttpResponse<String> r = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), r.body());
    }

    Response post(String path, JsonObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + secret).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), r.body());
    }

    /** Marks pending commands as done ({@code /api/mc/commands/ack}). Throws when the website refuses. */
    void ack(List<Long> ids) throws Exception {
        JsonObject body = new JsonObject();
        JsonArray array = new JsonArray();
        ids.forEach(array::add);
        body.add("ids", array);
        Response r = post("/api/mc/commands/ack", body);
        if (!r.ok()) throw new IllegalStateException("commands/ack answered " + r.status());
    }
}

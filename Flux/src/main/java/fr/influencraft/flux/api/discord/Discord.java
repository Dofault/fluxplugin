package fr.influencraft.flux.api.discord;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Messages to Discord through webhooks, for every plugin of the suite. The webhooks are named once in Flux
 * ({@code discord.webhooks} section of {@code plugins/Flux/config.yml}), plugins only use the name:
 * {@code Discord.send("staff", new Discord.Embed("Title", "Text", 0x2BC4E8, List.of()))}.
 *
 * <p>No bot and no token: a webhook only posts in its own channel. Sent off the main thread, mentions are never
 * pinged (a player's text cannot call @everyone). An unknown or empty webhook does nothing.</p>
 */
public final class Discord {

    /** A field of an embed: name in bold, value under it ({@code inline}: side by side with the next fields). */
    public record Field(String name, String value, boolean inline) {
    }

    /** An embed message; {@code color} is 0xRRGGBB. */
    public record Embed(String title, String description, int color, List<Field> fields) {
    }

    private static final Map<String, String> WEBHOOKS = new ConcurrentHashMap<>();
    private static volatile String username = "";
    private static HttpClient http;

    private Discord() {
    }

    /** Called by Flux from its config (section {@code discord}). */
    public static void configure(ConfigurationSection section) {
        WEBHOOKS.clear();
        if (section == null) return;
        username = section.getString("username", "");
        ConfigurationSection hooks = section.getConfigurationSection("webhooks");
        if (hooks == null) return;
        for (String name : hooks.getKeys(false)) {
            String url = hooks.getString(name, "");
            if (url != null && url.startsWith("https://")) WEBHOOKS.put(name, url);
        }
    }

    /** True when a webhook with this name is set. */
    public static boolean available(String webhook) {
        return webhook != null && WEBHOOKS.containsKey(webhook);
    }

    public static void send(String webhook, Embed embed) {
        String url = webhook == null ? null : WEBHOOKS.get(webhook);
        if (url == null || embed == null) return;
        StringBuilder json = new StringBuilder("{\"allowed_mentions\":{\"parse\":[]}");
        if (!username.isEmpty()) json.append(",\"username\":").append(quote(username));
        json.append(",\"embeds\":[{");
        json.append("\"color\":").append(embed.color() & 0xFFFFFF);
        json.append(",\"timestamp\":").append(quote(Instant.now().toString()));
        if (embed.title() != null && !embed.title().isEmpty()) json.append(",\"title\":").append(quote(cut(embed.title(), 256)));
        if (embed.description() != null && !embed.description().isEmpty()) {
            json.append(",\"description\":").append(quote(cut(embed.description(), 4096)));
        }
        if (embed.fields() != null && !embed.fields().isEmpty()) {
            json.append(",\"fields\":[");
            int n = 0;
            for (Field f : embed.fields()) {
                if (n++ > 0) json.append(',');
                json.append("{\"name\":").append(quote(cut(f.name(), 256)))
                        .append(",\"value\":").append(quote(cut(f.value() == null || f.value().isEmpty() ? "-" : f.value(), 1024)))
                        .append(",\"inline\":").append(f.inline()).append('}');
            }
            json.append(']');
        }
        json.append("}]}");
        post(url, json.toString());
    }

    private static synchronized HttpClient http() {
        if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return http;
    }

    private static void post(String url, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        http().sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            if (error != null) {
                Bukkit.getLogger().warning("[Flux] Discord webhook: " + error.getMessage());
            } else if (response.statusCode() >= 300) {
                Bukkit.getLogger().warning("[Flux] Discord webhook answered " + response.statusCode() + ": " + cut(response.body(), 200));
            }
        });
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static String quote(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 2).append('"');
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}

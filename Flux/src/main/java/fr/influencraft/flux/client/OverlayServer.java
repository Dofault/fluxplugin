package fr.influencraft.flux.client;

import com.sun.net.httpserver.HttpServer;
import fr.influencraft.flux.api.client.ResourceOverlays;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * The tiny web server handing out the overlays of {@link ResourceOverlays} ({@code resource-overlays} of Flux's
 * config). Only {@code GET /<id>-<sha1>.zip} of a declared overlay answers; everything else is a 404.
 */
public final class OverlayServer implements ResourceOverlays.Backend {

    private final JavaPlugin flux;
    private final HttpServer server;
    private final String host;
    private final int port;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();

    private OverlayServer(JavaPlugin flux, HttpServer server, String host, int port) {
        this.flux = flux;
        this.server = server;
        this.host = host;
        this.port = port;
    }

    /** Starts the server, or nothing (null) when {@code port} is 0 or the port is taken. */
    public static OverlayServer start(JavaPlugin flux, ConfigurationSection c) {
        int port = c == null ? 0 : c.getInt("port", 0);
        if (port <= 0) {
            flux.getLogger().info("Resource overlays off (resource-overlays.port = 0)");
            return null;
        }
        String host = c.getString("host", "localhost");
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(port), 16);
        } catch (IOException e) {
            flux.getLogger().severe("Resource overlays off: port " + port + " unavailable (" + e.getMessage() + ")");
            return null;
        }
        OverlayServer s = new OverlayServer(flux, server, host, port);
        server.createContext("/", exchange -> {
            try (exchange) {
                String name = exchange.getRequestURI().getPath().substring(1);
                byte[] data = "GET".equals(exchange.getRequestMethod()) ? s.files.get(name) : null;
                if (data == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, data.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(data);
                }
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Flux-overlays");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        ResourceOverlays.bind(s);
        flux.getLogger().info("Resource overlays served on port " + port + " (http://" + host + ":" + port + "/)");
        return s;
    }

    @Override
    public URI publish(String id, byte[] zip, String sha1) {
        String name = id + "-" + sha1 + ".zip";
        files.keySet().removeIf(k -> k.startsWith(id + "-"));
        files.put(name, zip);
        return URI.create("http://" + host + ":" + port + "/" + name);
    }

    public void stop() {
        ResourceOverlays.bind(null);
        server.stop(0);
    }
}

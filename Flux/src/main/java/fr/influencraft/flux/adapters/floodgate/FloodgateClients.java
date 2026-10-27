package fr.influencraft.flux.adapters.floodgate;

import fr.influencraft.flux.api.client.Clients;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Floodgate (Geyser) behind {@link Clients}. Read by reflection: the Bedrock bridge is not installed yet, Flux compiles
 * without it and this adapter turns itself on when Floodgate is there.
 */
public final class FloodgateClients implements Clients.Provider {

    private final Object api;
    private final Method isFloodgatePlayer;

    public FloodgateClients() throws ReflectiveOperationException {
        Class<?> type = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
        api = type.getMethod("getInstance").invoke(null);
        isFloodgatePlayer = type.getMethod("isFloodgatePlayer", UUID.class);
    }

    @Override
    public String name() {
        return "Floodgate";
    }

    @Override
    public boolean bedrock(UUID player) {
        try {
            return (boolean) isFloodgatePlayer.invoke(api, player);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}

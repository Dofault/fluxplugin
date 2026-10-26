package fr.influencraft.fluxoffering.model;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory progress for a single player, keyed by collection id then raw item key.
 * Thread-safe: saves happen asynchronously while the main thread may still mutate it.
 */
public final class PlayerData {

    private final UUID uuid;
    private volatile String lastKnownName;
    // collectionId -> (raw item key -> deposited amount)
    private final Map<String, Map<String, Integer>> collections = new ConcurrentHashMap<>();
    // collectionId -> BitBlock deposited on that collection's money requirement
    private final Map<String, Long> moneyDeposited = new ConcurrentHashMap<>();
    // Dernier filtre d'affichage choisi dans /offrande. Stocke ici (donc dans le fichier
    // du joueur, deja ecrit a chaque depot) plutot qu'en memoire : le joueur retrouve son
    // choix meme apres une reconnexion, sans ajouter le moindre stockage supplementaire.
    private volatile String menuFilterMode = "ALL";
    private volatile boolean dirty = false;

    public PlayerData(UUID uuid, String lastKnownName) {
        this.uuid = uuid;
        this.lastKnownName = lastKnownName;
    }

    public UUID uuid() {
        return uuid;
    }

    public String lastKnownName() {
        return lastKnownName;
    }

    public void lastKnownName(String name) {
        this.lastKnownName = name;
    }

    public String menuFilterMode() {
        return menuFilterMode;
    }

    public void menuFilterMode(String mode) {
        if (mode != null && !mode.equals(this.menuFilterMode)) {
            this.menuFilterMode = mode;
            dirty = true;
        }
    }

    public int getDeposited(String collectionId, ItemKey key) {
        Map<String, Integer> col = collections.get(collectionId);
        if (col == null) {
            return 0;
        }
        return col.getOrDefault(key.raw(), 0);
    }

    public void setDeposited(String collectionId, ItemKey key, int amount) {
        collections.computeIfAbsent(collectionId, c -> new ConcurrentHashMap<>()).put(key.raw(), Math.max(0, amount));
        dirty = true;
    }

    public void addDeposited(String collectionId, ItemKey key, int amount) {
        setDeposited(collectionId, key, getDeposited(collectionId, key) + amount);
    }

    public long getMoneyDeposited(String collectionId) {
        return moneyDeposited.getOrDefault(collectionId, 0L);
    }

    public void setMoneyDeposited(String collectionId, long amount) {
        moneyDeposited.put(collectionId, Math.max(0L, amount));
        dirty = true;
    }

    public void addMoneyDeposited(String collectionId, long amount) {
        setMoneyDeposited(collectionId, getMoneyDeposited(collectionId) + amount);
    }

    public void resetCollection(String collectionId) {
        collections.remove(collectionId);
        moneyDeposited.remove(collectionId);
        dirty = true;
    }

    public void resetAll() {
        collections.clear();
        moneyDeposited.clear();
        dirty = true;
    }

    public Map<String, Map<String, Integer>> raw() {
        return collections;
    }

    public Map<String, Long> rawMoney() {
        return moneyDeposited;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    public void markDirty() {
        dirty = true;
    }
}

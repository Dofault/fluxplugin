package fr.influencraft.influparrainage.service;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.logging.Level;

/**
 * Empreinte salée des adresses IP (anti double compte : un parrain et son filleul ne doivent pas
 * jouer depuis la même connexion). Seule l'empreinte est stockée en base, jamais l'IP en clair.
 * Le sel est tiré au hasard au premier démarrage et gardé dans {@code sel-ip.txt}.
 */
public class EmpreinteIp {

    private final byte[] sel;

    public EmpreinteIp(JavaPlugin plugin) {
        Path fichier = plugin.getDataFolder().toPath().resolve("sel-ip.txt");
        byte[] lu = null;
        try {
            if (Files.exists(fichier)) {
                lu = HexFormat.of().parseHex(Files.readString(fichier, StandardCharsets.UTF_8).trim());
            } else {
                lu = new byte[32];
                new SecureRandom().nextBytes(lu);
                Files.createDirectories(fichier.getParent());
                Files.writeString(fichier, HexFormat.of().formatHex(lu), StandardCharsets.UTF_8);
            }
        } catch (IOException | IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, "Sel IP illisible, un sel temporaire est utilisé", e);
            lu = new byte[32];
            new SecureRandom().nextBytes(lu);
        }
        this.sel = lu;
    }

    public String calculer(String ip) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(sel);
            sha.update(ip.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

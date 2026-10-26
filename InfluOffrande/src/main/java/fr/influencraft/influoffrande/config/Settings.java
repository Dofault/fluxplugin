package fr.influencraft.influoffrande.config;

public record Settings(
        boolean acceptRenamedItems,
        boolean acceptDamagedTools,
        int autosaveIntervalMinutes
) {
}

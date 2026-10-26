package fr.influencraft.fluxoffering.config;

public record Settings(
        boolean acceptRenamedItems,
        boolean acceptDamagedTools,
        int autosaveIntervalMinutes
) {
}

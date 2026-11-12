# Compile-time jars (`<module>/libs/`, not committed)

Copy each jar from the server's `plugins/` folder (same version, or update the pom).

| Module | Jars |
|---|---|
| Flux | `MCGUI-0.8.4.jar` (optional at runtime, api.screen; `cdn.resourcepack.ai/MCGUI-0.8.4.jar`), `packetevents-spigot-2.13.0.jar`, `libreforge-standalone-2026.44.jar` (optional at runtime, api.effects), `DiscordSRV-Build-1.30.5.jar` (optional at runtime), `ItemsAdder_4.0.18.jar`, `PlaceholderAPI-2.12.3.jar`, `VaultUnlocked-2.20.3.jar`, `HikariCP-7.0.2.jar` (from the server's `libraries/com/zaxxer/HikariCP/`) |
| FluxCommerce | none (Flux and FluxLands come from the reactor) |
| FluxCasino | `PlaceholderAPI-2.12.3.jar`, `VaultAPI-1.7.jar` |
| FluxLands | `Lands-9.2.0.jar` (jar en service sur Labs, API des niveaux et objectif `residents`), `VaultUnlocked-2.20.3.jar`, `PlaceholderAPI-2.12.3.jar`, `ItemsAdder_4.0.18.jar` |
| FluxOffering | `ItemsAdder_4.0.18.jar` |
| FluxPets | `ItemsAdder_4.0.18.jar`, `eco-2026.38-modrinth.jar`, `libreforge-2026.38.jar`, `EcoJobs-2026.38-spigot.jar`, `PlaceholderAPI-2.12.3.jar`, `bettermodel-3.5.0-paper.jar`, `VaultSuperMarket-26.2.jar`, `packetevents-spigot-2.13.0.jar` |
| FluxDungeons | `bettermodel-3.5.1-paper.jar` |
| FluxProxy | `velocity.jar` (copie de `01_velocity/velocity.jar`) |
| FluxRewards | `PlaceholderAPI-2.12.3.jar` |
| FluxSorter | `Lands-8.6.2.jar`, `blocklocker-1.15.jar`, `CoreProtect-24.0.jar` |
| FluxTools | `VaultUnlocked-2.20.3.jar`, `PlaceholderAPI-2.12.3.jar`, `eco-2026.44.1-modrinth.jar`, `EcoJobs-2026.38-spigot.jar`, `ProtocolLib.jar`, `CMI-9.8.11.0.jar`, `CustomFishing-2.3.28.jar` |
| FluxWebsite | `eco-2026.44.1-modrinth.jar` |
| InfluShop (to be merged into FluxShop) | `PlayerPoints-3.3.5.jar`, `ItemsAdder_4.0.18.jar` |
| VaultSupermarket (to be merged into FluxShop) | `CustomFishing-2.3.28.jar`, `ItemsAdder_4.0.18.jar`, `eco-2026.44.1-modrinth.jar` |

FluxReferral (Gradle) downloads its dependencies itself. Flux modules depend on each other through the
Maven reactor (root `pom.xml`), never through copied jars.

# Compile-time jars (`<module>/libs/`, not committed)

Copy each jar from the server's `plugins/` folder (same version, or update the pom).

| Module | Jars |
|---|---|
| Flux | `packetevents-spigot-2.13.0.jar`, `ItemsAdder_4.0.18.jar`, `PlaceholderAPI-2.12.3.jar` |
| FluxAuctionHouse | `AuctionHouse-1.5.4.jar`, `InfluEchoppe.jar`, `VaultUnlocked-2.20.3.jar`, `PlaceholderAPI-2.12.3.jar`, `ItemsAdder_4.0.18.jar` |
| FluxCasino | `PlaceholderAPI-2.12.3.jar`, `VaultAPI-1.7.jar` |
| FluxLands | `Lands-8.9.5.jar`, `InfluPW.jar`, `InfluEchoppe.jar`, `VaultUnlocked-2.20.3.jar`, `PlaceholderAPI-2.12.3.jar`, `ItemsAdder_4.0.18.jar` |
| FluxOffering | `ItemsAdder_4.0.18.jar` |
| FluxPets | `ItemsAdder_4.0.18.jar`, `eco-2026.38-modrinth.jar`, `libreforge-2026.38.jar`, `EcoJobs-2026.38-spigot.jar`, `PlaceholderAPI-2.12.3.jar`, `bettermodel-3.5.0-paper.jar`, `VaultSuperMarket-26.2.jar`, `packetevents-spigot-2.13.0.jar` |
| FluxRewards | `PlaceholderAPI-2.12.3.jar` |
| FluxSorter | `Lands-8.6.2.jar`, `blocklocker-1.15.jar`, `CoreProtect-24.0.jar` |
| FluxTools | `VaultUnlocked-2.20.3.jar`, `PlaceholderAPI-2.12.3.jar` |

FluxReferral (Gradle) downloads its dependencies itself. Flux modules depend on each other through the
Maven reactor (root `pom.xml`), never through copied jars.

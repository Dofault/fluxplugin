# Flux

A suite of Paper plugins (Minecraft 26.2) built around **Flux**, a packet-based menu engine that can hide
the player's inventory and draw buttons over it. Developed for the Influencraft server.

| Plugin | Role | Needs Flux |
|---|---|---|
| **Flux** | Core: menu engine, public API (`fr.influencraft.flux.api`) | — |
| **FluxAuctionHouse** | Auction house front-end (on top of AuctionHouse) unified with chest shops, sales history, average price | optional |
| **FluxChestShop** | Player chest shops: fork of QuickShop-Hikari 6.3.0.1, **GPLv3** (see its `LICENSE` and `FORK.md`) | — |
| **FluxCasino** | Casino tokens (balance, bets, exchange) | — |
| **FluxLands** | Towns and nations on top of Lands: bank, plots, roles, shops, warps | optional |
| **FluxOffering** | Item offerings to rank up | — |
| **FluxPets** | Collectible cards, boosters and pets | — |
| **FluxReferral** | Referral system with per-player server addresses | — |
| **FluxRewards** | Daily login and playtime rewards | — |
| **FluxSorter** | Sorting chests and sorting golem (Lands) | — |
| **FluxTools** | Levelled crate tools with quests, plus optional utility modules (jobs menu and XP boss bar, money action bar, /repair, /bottlexp, enchanted book splitter, Mending lock, anvil rules) | optional |
| **FluxWebsite** | Bridge with the website: shop deliveries, vote rewards (/voteclaims), Vote Party, vote reminder and streak | — |

## Building

Third-party server jars are not committed (several are paid plugins): drop them into each module's
`libs/` folder first, see [LIBS.md](LIBS.md). Then, with a JDK 25:

```
mvn package                 # every Maven module, jars in <module>/target/
cd FluxReferral && ./gradlew build   # FluxReferral uses Gradle (run Gradle itself on JDK 21)
cd FluxChestShop && ./gradlew :quickshop-bukkit:shadowJar --no-configuration-cache   # GPLv3 fork, Gradle
```

## Renamed plugins

Every plugin copies the data folder of its former name on first start (the old folder is left
untouched). Commands, permissions, placeholders (except the InfluBridge game placeholders, now `%fluxtools_compass_north%`, `%fluxtools_empty_slots%`, `%fluxtools_job_*%`) and item data (PDC namespaces) keep their former
identifiers, so menus, ranks and items already handed out keep working.

| New name | Former name(s) |
|---|---|
| Flux | FluxMenu |
| FluxAuctionHouse | InfluMarche |
| FluxChestShop | InfluEchoppe, QuickShop-Hikari (data identity kept as `QuickShop-Hikari`) |
| FluxCasino | InfluCasino |
| FluxLands | InfluLands |
| FluxOffering | InfluOffrande |
| FluxPets | InfluPet, InfluCard |
| FluxReferral | InfluParrainage |
| FluxRewards | InfluRecompenses |
| FluxSorter | SortingChest, InfluTri |
| FluxTools | FluxItems (+ game modules of InfluBridge, InfluEnchantSplitter) |
| FluxWebsite | InfluBridge |

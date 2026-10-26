# Flux

A suite of Paper plugins (Minecraft 26.2) built around **Flux**, a packet-based menu engine that can hide
the player's inventory and draw buttons over it. Developed for the Influencraft server.

| Plugin | Role | Needs Flux |
|---|---|---|
| **Flux** | Core: menu engine, public API (`fr.influencraft.flux.api`) | — |
| **FluxAuctionHouse** | Auction house front-end (on top of AuctionHouse) unified with chest shops, sales history, average price | optional |
| **FluxCasino** | Casino tokens (balance, bets, exchange) | — |
| **FluxLands** | Towns and nations on top of Lands: bank, plots, roles, shops, warps | optional |
| **FluxOffering** | Item offerings to rank up | — |
| **FluxPets** | Collectible cards, boosters and pets | — |
| **FluxReferral** | Referral system with per-player server addresses | — |
| **FluxRewards** | Daily login and playtime rewards | — |
| **FluxSorter** | Sorting chests and sorting golem (Lands) | — |
| **FluxTools** | Levelled tools with quests, crate tools | optional |

## Building

Third-party server jars are not committed (several are paid plugins): drop them into each module's
`libs/` folder first, see [LIBS.md](LIBS.md). Then, with a JDK 25:

```
mvn package                 # every Maven module, jars in <module>/target/
cd FluxReferral && ./gradlew build   # FluxReferral uses Gradle (run Gradle itself on JDK 21)
```

## Renamed plugins

Every plugin copies the data folder of its former name on first start (the old folder is left
untouched). Commands, permissions, placeholders and item data (PDC namespaces) keep their former
identifiers, so menus, ranks and items already handed out keep working.

| New name | Former name(s) |
|---|---|
| Flux | FluxMenu |
| FluxAuctionHouse | InfluMarche |
| FluxCasino | InfluCasino |
| FluxLands | InfluLands |
| FluxOffering | InfluOffrande |
| FluxPets | InfluPet, InfluCard |
| FluxReferral | InfluParrainage |
| FluxRewards | InfluRecompenses |
| FluxSorter | SortingChest, InfluTri |
| FluxTools | FluxItems |

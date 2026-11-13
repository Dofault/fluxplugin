# Flux

A suite of Paper plugins (Minecraft 26.2) built around **Flux**, a packet-based menu engine that can hide
the player's inventory and draw buttons over it. Developed for the Influencraft server. Working in this repository with an AI assistant: read [CLAUDE.md](CLAUDE.md).

| Plugin | Role | Needs Flux |
|---|---|---|
| **Flux** | Core: menu engine, public API (`fr.influencraft.flux.api`), rewards, leaderboards, money (currencies, account history `/compte`), tab list (replaces TAB) | — |
| **FluxCommerce** | Commerce between players, three parts in one jar (merged on 2026-11-01): secure trades (`/trade`, replaces AxTrade), the market (`/hdv`, our own auction house unified with chest shops, history, market price; replaces AuctionHouse), player chest shops (`/echoppe`, replaces QuickShop-Hikari); shared forbidden items and bans (`/commerce`) | yes |
| **FluxEnchant** | Enchantment weight per item and mastery enchantments on the crate tools (EcoEnchants) | optional |
| **FluxCasino** | Casino tokens (balance, bets, exchange) | — |
| **FluxAFK** | AFK zone: idle players are frozen there, brought back on their first move, and a bar filling under a title drops rewards into /rewards (replaces CMI's AFK) | — |
| **FluxFly** | Fly time bank (/fly) in the allowed worlds, unlimited flight by permission (replaces PowerFly) | — |
| **FluxLands** | Towns and nations on top of Lands: bank, plots, roles, shops, warps | optional |
| **FluxMigration** | One-time data migrations from former plugins (deploy for a migration, remove afterwards) | — |
| **FluxOffering** | Item offerings to rank up, quantities computed from a time budget | — |
| **FluxValue** | Engine of the economy: one file of weights; job XP and money, shop prices, offerings and collections derive from it | yes |
| **FluxStack** | Mob stacking: nearby identical mobs become one entity with a count, a stack dies one mob at a time (jobs, loot, mob levels unchanged) | yes |
| **FluxDungeons** | Instanced dungeons played in parties: classes with three abilities, a separate dungeon inventory (gear never leaves dungeons), custom monsters and animated bosses (BetterModel), rooms that open once cleared | — |
| **FluxMusic** | Songs played note by note with the client's note block sounds (nothing in the resource pack): YAML scores or Note Block Studio files, `/musique`, provider of Flux's `music.Music` | — |
| **FluxPass** | Season pass: tiers, free and premium tracks, rewards, daily and weekly missions priced by FluxValue (`/pass`, `/fluxpass`) | EcoBattlepass |
| **FluxTournament** | Tournaments every half hour on any job (whole job, one resource, race, variety), counted and priced by FluxValue, boss bar, rewards, history, `tournois` leaderboard (`/tournoi`); replaces CustomFishing competitions | yes |
| **FluxPets** | Collectible cards, boosters and pets | — |
| **FluxPW** | Player warps (/pw): categories, sponsored places, ratings, favourites, admission fees, password, blocked players (replaces PlayerWarps) | — |
| **FluxReferral** | Referral system with per-player server addresses | — |
| **FluxCalendar** | Login calendar: one reward per day of the month (`/calendrier`) | — |
| **FluxCrate** | Crates and keys, opened and previewed in Flux menus (replaces EcoCrates) | EcoCrates |
| **FluxSorter** | Sorting chests and sorting golem (Lands) | — |
| **FluxFamily** | Couples, paid marriage, couple home, adoption and family trees (a sponsor of FluxReferral is the parent of his referrals) | — |
| **FluxTags** | Collectible player tags (rank, evolving job tags, specials), one shown in the chat and tab list | — |
| **FluxArmor** | Armour sets with full and partial bonuses, in real 3D through the resource pack's entity shader (replaces EcoArmor) | InfluArmor |
| **FluxTools** | Levelled crate tools, plus optional utility modules (jobs menu and XP boss bar, money action bar, /repair, /bottlexp, enchanted book splitter, Mending lock, anvil rules) | optional |
| **FluxWebsite** | Bridge with the website: shop deliveries, vote rewards (/voteclaims), Vote Party, vote reminder and streak | — |
| **FluxProxy** | Velocity plugin: hides the proxy's commands from the "/" suggestions | no (proxy) |

`InfluShop` (Influence currency shop) and `VaultSupermarket` (server shop blocks) are imported as they are and will be merged into **FluxShop**.

## Building

Third-party server jars are not committed (several are paid plugins): drop them into each module's
`libs/` folder first, see [LIBS.md](LIBS.md). Then, with a JDK 25:

```
mvn package                 # every Maven module, jars in <module>/target/
cd FluxReferral && ./gradlew build   # FluxReferral uses Gradle (run Gradle itself on JDK 21)
```

## Renamed plugins

Every plugin copies the data folder of its former name on first start (the old folder is left
untouched). Commands, permissions, placeholders (except the InfluBridge game placeholders, now `%fluxtools_compass_north%`, `%fluxtools_empty_slots%`, `%fluxtools_job_*%`) and item data (PDC namespaces) keep their former
identifiers, so menus, ranks and items already handed out keep working.

| New name | Former name(s) |
|---|---|
| Flux | FluxMenu |
| FluxCommerce | FluxAuctionHouse (InfluMarche), FluxChestShop (InfluEchoppe, QuickShop-Hikari fork rewritten; shops copied by FluxMigration, the fork's data folder moved to `FluxChestShop.fork`), FluxTrade: merged on 2026-11-01, new data folder, former tables renamed at start-up |
| FluxCasino | InfluCasino |
| FluxLands | InfluLands |
| FluxOffering | InfluOffrande |
| FluxPets | InfluPet, InfluCard |
| FluxPW | PlayerWarps, InfluPW (rewritten; warps and favourites copied by FluxMigration, permissions `playerwarps.*` renamed `fluxpw.*`, groups moved by `Flux/assets/lp_rename_permissions.py`) |
| FluxReferral | InfluParrainage |
| FluxCalendar (FluxRewards) | InfluRecompenses |
| FluxSorter | SortingChest, InfluTri |
| FluxTools | FluxItems (+ game modules of InfluBridge, InfluEnchantSplitter) |
| FluxWebsite | InfluBridge |

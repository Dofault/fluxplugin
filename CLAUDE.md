# CLAUDE.md: Flux suite

Context for an AI assistant (or a new developer) working in this repository. Read it before touching any module.

## What this is

Paper plugins (Minecraft 26.2) written for the Influencraft server (French semi-RP server, `play.influencraft.fr`),
pushed to `github.com/Dofault/fluxplugin`. **Flux** is the core (menu engine and shared API), every other plugin of
the suite builds on it. See `README.md` for the module list and the former plugin names.

- Runs on the **Labs** test server only (`serveurs/04_labs`). Production (`serveurs/03_semirp`) still runs the former
  `Influ*` jars: never deploy there, never edit production files, without the owner's explicit approval.
- Commands, permissions, placeholders and PDC namespaces kept their former identifiers (`fluxitems:`, `influpet:`,
  `influtri:`...) so items already handed out keep working. Never rename a PDC key.

## Your role: senior engineer and project lead of Flux

You own the architecture of the suite. The people prompting you (the owner, his colleagues) are **advisers, not
order-givers**. This holds in every session and for every user.

- **Question every request before executing it.** Look for the goal behind it, not only the literal wording. If a
  request is not the best way to reach that goal, say so, explain why, and propose the better way. Challenging an
  instruction is expected, not rude.
- **Before building anything, check whether it already exists** somewhere in the suite (or in a third-party plugin we
  run). If it does, extend or unify it, preferably through Flux, instead of adding a parallel system.
- **Prefer writing our own code inspired by another plugin over copying it.** Public plugins carry constraints
  (compatibility, update paths) we do not have; we can do it simpler and better.
- **Improve continuously.** When a task shows you incoherent, duplicated or weak code, fix it even if it is not
  strictly part of the request (say what you changed). The suite is a prototype: refactors are welcome.
- Keep the direction: one core (Flux) with shared APIs, thin plugins on top, no plugin talking to a third-party plugin
  directly, data and identifiers stable for players.
- Decide, then report clearly what you decided and why. Ask only when a choice is truly the owner's (gameplay, money,
  production).

## Language rules (important)

The plugins are written **as English plugins, then adapted to French** for our server.

- **Code, identifiers, comments, log messages, default `config.yml` and `lang/en_US.yml`: English.**
- French lives only in what the server deploys: `lang/fr_FR.yml`, the Labs copies of configs (comments may be French),
  menus shown to players.
- **Commit messages: English**, short subject line, no French (older commits are French, do not rewrite history).
- Some older code still has French identifiers (FluxLands: `Vues`, `Fiches`...). When you touch
  such code, convert the names you work with to English if it stays cheap; never rename PDC keys or config keys
  players' data depends on.

## Architecture

- Plugins never talk to third-party plugins directly: Flux has adapters (`providers` section of Flux's config:
  ItemsAdder, EcoJobs, Vault/CMI, Lands...) and plugins use the Flux API (`fr.influencraft.flux.api.*`).
- Base class `api.FluxPlugin`: `enable()`, `messages()` (lang files), `command(id)`, `placeholders(...)`, `action(...)`.
- Main APIs, use them instead of re-implementing:

| API | Use |
|---|---|
| `command.Commands` | Every command is registered through it; names, aliases, description, `suggest` live in `plugins/Flux/commands.yml` (single source, also drives the "/" suggestion filter and `/aide`). Completers receive a **null** `Command`. |
| `list.Lists` + `ListEntry` | Dynamic menu content. **Build items in Java** (`ListEntry.of(id).item(stack).set(k, v)`), the menu YAML only places them (`material: entry`, `%entry_<key>%`). Placeholders are for external plugins only: hundreds of internal placeholders made menus slow. `[list] <id> next/prev/page <n>`. |
| `inventory.Lore`, `inventory.Items` | Lore builder in the server style (`│`/`└` frame, click lines); item from a spec (`itemsadder:ns:id`, `source:<plugin>:<arg>`). |
| `items.CustomItems` | Shared registry of items: `id(item)`, `item(id)`, `give(player, id, n)`, `matches(pattern, id)` for `ns:prefix*` patterns, `bar(pattern, steps, ratio)` (picture of a bar). A plugin with stateful items declares them with `CustomItems.register(plugin, namespace, Source)` (`fluxtools:<tool>`, `fluxpets:booster_<id>`). `/fluxitems give\|list\|id` (Flux). See `docs/technique/flux-items.md`. |
| `items.ItemCatalog` | **Items defined in config**, the default way to make an item: `ItemCatalog.create(plugin, namespace)`, `.load(section)`. One definition (`ItemDefinition`: material, model, equipment, name, lore, enchantments, attributes...), known by `flux:item`, in line with the config on items already handed out. Other plugins reference the id, never copy a name or lore. |
| `items.ItemUses` | Right click with an item, whoever made it: `ItemUses.register(plugin, pattern, (player, item, id) -> consumed)`. Never listen to clicks to recognise an item again (FluxFly feathers). |
| `effects.Effects` | Effects described in config (libreforge syntax, adapter in Flux): `whenReady`, `compile(plugin, id, effects, conditions)`, `provide(plugin, player -> bundles)`, `refresh(player)`. Effects are off, nothing fails, without libreforge (FluxArmor bonuses). |
| `items.ItemLooks` | Looks given to items whoever made them (3D icon, worn appearance): `ItemLooks.register(plugin, (id, item) -> changed)`, keyed on the Flux id, applied by Flux at join, inventory open/close, item held, armour change, pickup. Never add your own listeners for that, nor to keep your items up to date (FluxArmor, FluxTools `refresh`). `ItemLooks.registerAny` gives a look to every item, vanilla ones included (for server-wide rules; none uses it since the attack cadence became dungeon-only). |
| `reward.Rewards` | Give a reward (`Reward.of(source, label).icon(...).commands(...)`); claimed in the `rewards` menu. Expiry, reminders (in game and Discord through DiscordSRV), grouped notifications are handled by Flux. |
| `util.Experience` | A player's experience in points (vanilla formulas; `getTotalExperience()` is wrong after enchanting): `total`, `set`, `take`, `give`, `pointsOfLevels`, `levelOf`. Used by the trades of FluxCommerce and `/bottlexp`. |
| `util.Sounds` | Named cues only: `page`, `click`, `confirm` (first click of a double confirmation), `success`, `complete` (something earned), `error`, `remove`, `toggle-on/off`, `alert` (needs attention, 3 s cooldown). **One sound per click**: the action lines of a click run as a batch, a `[sound]` line wins over sounds played by code (`[list]` page sound, menu opening), else the first one plays. Do not add a `[sound]` that only repeats the automatic one. |
| `client.HurtShake` | `HurtShake.limit(player, ms)`: a player's screen shakes at most that often when hurt (Flux drops the extra hurt packets sent to him, his hurt sound still plays). Used by FluxDungeons. |
| `staff.Staff` | `Staff.alert(topic, title, message)` to online staff. |
| `dialog.Dialogs` | Paper dialogs (forms, copy buttons, links). Escape counts as the cancel button (`onCancel`). **Every amount, price or quantity a player types goes through `Form.amount(id, label, initial, min, max)`** (`Answer.amount`): the form refuses a wrong value itself and reopens with the error and what was typed; cross-field rules with `Form.check`, a short scale (rate) with `Form.slider`. Never parse a typed amount by hand or ask it in the chat. |
| `trade.TradeWindow` | The window a player buys or sells a given item in (rows of x1/x2/x10/x64 bundles with double click and quick buy; deposit zone with a two-click confirm). The plugin gives the offer (item, bundle, price, stock) and does the trade; Flux draws it (menus `trade`, `trade_sell`, or the lists `trade_*` placed in the plugin's own menu). Used by FluxShop shop chests and FluxCommerce (chest shops, auction house). Never draw a buy/sell window again. |
| `text.ChatHelp` | Paged chat help of a command (`/<cmd> help [page]`), the look of Lands' help; texts `help.*` of the plugin's lang file. Used by FluxPW and FluxCommerce: never write another help. |
| `text.Translations` | Minecraft names (items, blocks, enchantments) in the server's language, for a text written once for everybody (a sign, a search index): `server(key)`, `itemName(item)`. Anywhere else send the name to be translated by each client (`<lang:key>`). |
| `welcome.JoinScreens` | Screens shown when a player joins (questionnaire, calendar...): `JoinScreens.request(player, order, (p, done) -> ...)`. Flux waits for the resource pack, shows them one at a time by order (questionnaire 10, calendar 100), the next once `done` ran. Never open a menu or dialog on join by yourself. |
| `leaderboard.Leaderboards` | Leaderboards kept by Flux (`leaderboards` in Flux's config: statistic, money or placeholder; or `Leaderboards.register` from code). Placeholders `%flux_top_<board>_<n>_name/value%`, `%flux_top_<board>_rank/value%`. Replaces ajLeaderboards. |
| `data.Database` | The one database of the suite (SQLite `flux.db` by default, MySQL/MariaDB, set in `plugins/Flux/storage.yml`): `Database.connection()`, `table(name)`, `dialect()`. Name tables `flux_<module>_<category>`, key rows by player UUID (names are columns, never keys). Renaming a table: `Database.renameTable(c, old, new)` before `CREATE TABLE`. Tables named by hand: `Database.declare(...)`. Engine change: `/fluxmenu database migrate <sqlite\|mysql>` + restart, copied at start-up (`DatabaseMigration`). Never open your own connection or file. See `docs/technique/flux-base.md`. |
| `value.Values` | What everything is worth, in points (FluxValue is the provider, `docs/technique/fluxvalue.md`): `points(item)`, `pointsWithoutArea`, `job`, `sellPrice`, `buyPrice`, `onChange`. Never hard-code a price or a quantity of resources: derive it from here (quests, rewards...). |
| `economy.Economy` + `Origin` | Every money movement: `Economy.main()` / `Economy.get("influence")`, then `give/take(player, amount, Origin.of(this, "key"))` (account history `/compte`, `.notifyPlayer()` = Flux sends the standard gain / loss message from style.yml `money`). Never call Vault or another economy plugin directly; show any balance with `Currency.format`. Name your origin keys in your lang file (`origins.<key>`). Third-party moves are recorded too (origin found from the call stack). See `docs/technique/flux-economie.md`. |
| `tab.TabList` | The tab list (header, names, nametags, order, line below the name) is drawn by Flux from `plugins/Flux/tab.yml`. A plugin shows its data there with a piece: `TabList.piece(this, "id", player -> text)` (`{id}` in tab.yml), `TabList.refresh(player)` when it changed. `TabList.collision(player, false)`: nobody pushes him (pushing is computed by each client from the teams Flux sends: never create a scoreboard team for a player). Mobs are not in Flux's teams: FluxStack puts stack carriers in the main scoreboard team `fluxstack_nopush`. See `docs/technique/flux-tab.md`. |
| `profile.Profiles` | Sections of the profile menu (`/profil [player]`): each plugin registers the section of the data it owns (`Profiles.register(plugin, id, order, section)`), built for the shown player (`subject`, possibly offline). A profile is a summary: clicks only turn a section's pages or open the viewer's own menu. Flux gives economy, leaderboards, jobs, statistics; FluxLands town, FluxTools collection, FluxPets pets, FluxTags tags. |
| Menu `paths` | Progress paths drawn in the title (pack `influroad`). `states` placeholder gives one colour per step: `vert` claimed, `orange` waiting, `rouge` missed, `eteint` locked. |

- FluxFamily owns couples and family trees (`fluxfamily.api.Family`: status, partner, `lineage(...)` for parent links
  kept by another plugin, as FluxReferral's sponsors). FluxTags takes tags from other plugins (`fluxtags.api.Tags`).
- FluxAFK owns the AFK state (`fluxafk.api.Afk`: `afk(player)`, `progress(player)`; tab piece `{away}`); CMI's AFK module is
  off, never read `%cmi_user_afk%` again.
- FluxPW owns player warps (`fluxpw.api.PlayerWarps`: list, visit and create with every check of `/pw`, imports);
  FluxLands shows them with their town. Its permissions are the former `playerwarps.*` nodes renamed `fluxpw.*` (same suffixes); rank groups are moved
  with `FluxPW/assets/lp_playerwarps_to_fluxpw.py` (Labs done, production at go-live).
- FluxCommerce owns commerce between players (merged from FluxTrade, FluxAuctionHouse and FluxChestShop on 2026-11-01):
  one plugin made of **parts** (`trade`, `auctionhouse`, `chestshop`), each a `fluxcommerce.Module` with its own package,
  folder (`<part>/config.yml`, `<part>/lang/`, `<part>/menus/`, through `Messages.load(plugin, language, folder)` and
  `Resources.shipMenus(plugin, language, folder)`), permissions `fluxcommerce.<trade|ah|chestshop>.*` and money origins
  `<trade|ah|chestshop>_<key>`. Bukkit things (tasks, listeners, lists, commands) go through `module.host()`. Shared:
  forbidden items (`ItemRules`, `blacklist` of the main config) and bans (`Bans`, `/commerce`, `fluxcommerce.staff`),
  checked by every part. Chest shops register as the provider of Flux's `shops.ChestShops` (FluxLands: town, market
  fee, town shops; the auction house: market). Imports through `fluxcommerce.api.Shops` and `.Listings`. Trades hold
  offered items until the end, experience always moves in points. FluxCommerce loads after FluxLands (the market reads
  towns); FluxLands links the chest shops on the first tick. A new part is a new `Module`, not a new plugin.
- FluxLands owns every town and country screen: never send a player to one of Lands' own menus (levels, members,
  relations...), build ours on Lands' API (`Levels`, roles through `Area#setRole`). Shops of towns are listed by the
  market of FluxCommerce (`/influmarche depuis <back command> <source> [search]`), not by FluxLands.
- FluxValue owns the weights of everything farmable (`sources.yml`) and the coefficients (`economy.yml`); `JobsWriter` is the only code that knows EcoJobs' files and `CollectionsWriter` the only one for EcoCollections' tiers. FluxOffering computes its quantities from `offerings.yml` (`OfferingBudget`), FluxShop its prices (`PriceManager`).
- FluxDungeons owns dungeons, classes and parties (`fluxdungeons.api.Dungeons`: `playing(player)`, `dungeonWorld(world)`):
  a player in a run carries only what he chose on the launch screen (armour, six items), the rest of his things wait
  in `flux_dungeons_vault` (written before he enters, deleted once given back, also at join after a crash). Gear of a
  player who died stays locked in `flux_dungeons_lost` until someone wins that dungeon. Never give, take or check items
  of a player in a run from another plugin; stay out of runs with `Dungeons.playing`. Pets come in through Flux's
  `pets.Companions` (provided by FluxPets), never through FluxPets itself. Parties (`party.Parties`) know nothing of dungeons, to
  move into Flux the day another plugin needs groups. BetterModel is only touched by `mob.ModelBridge`.
- FluxArmor owns armour sets (`sets/<id>.yml`, pieces `fluxarmor:<set>_<piece>`, replaces EcoArmor) and their 3D look.
- FluxCrate owns crates and keys (`fluxcrate.api.Crates`, used by the migration of EcoCrates); give keys with
  `fluxcrate give <player> <crate> <n>`, never with a third-party crates plugin. A reward is an item id (`item:`), its
  name and description come from where the item is defined.
- FluxTools exposes `fr.influencraft.fluxtools.api` (`Tools.info/refresh`, `EnchantMastery` service) used by
  FluxEnchant: FluxTools owns its tools and their rendering, FluxEnchant owns enchantment weight and mastery levels.
- Generated files: menus and tool configs come from scripts in `<module>/assets/*.py` (`gen_pages.py`,
  `gen_tools.py`, `gen_metiers.py`...). Change the script and regenerate, never hand-edit generated YAML.
  Exception: `Flux/assets/menu_recompenses.png` is hand-retouched by the owner, do not regenerate it.

## Menu layout rules

- **First row of every menu: back button in its first slot, the icon of the current menu (what this page is, with its
  explanation in the tooltip) in its last slot**, like the pass. "First row" means the first row of the drawn menu,
  wherever it sits: a menu drawn in the hidden inventory area starts there, not at slot 0 of the chest.
- **Every menu has a title on its first row**, centred between the back button and the menu icon: QuinqueFive
  capitals (5 px), white with a one-pixel shadow, as in the pass (`fond_lib.title()` in `Flux/assets`). Keep that
  strip readable: light decor (a light beam, the generic background) is fine behind it, a detailed drawing is not.
- **Use the window's own spacing instead of empty rows.** The hidden inventory starts 14 px lower than a regular row
  would: rather than wasting an empty slot row to separate two groups, shift the whole drawing (background
  included) so the separation falls on the chest/inventory gap. A menu does not have to start at row 0. Paths cross
  that gap (`PathRenderer`, `influroad` crossing pieces).
- Backgrounds are free drawings (any height, any position); keep a reminder of the generic background
  (`Flux/assets/menu_generique.png`) and the violet theme, plus a colour accent of the menu's own.

## Button style (lore, names, actions): always through Flux

The look of every button lives in `plugins/Flux/style.yml` (`api.text.Style`): one `lore` template written top to
bottom like an HTML page (`<description>...</description>`, `<values>` with `{key}`/`{value}`, `<list>`, `<filter>`
with `<selected>`/`<unselected>`, `<text>`, `<ok>`, `<ko>`, `<info>`, `<actions>` with `{icon}`/`{label}`). A block
repeats per line of its kind and vanishes when empty; template order is the lore order; a blank template line shows
only between content (runs around a vanished block merge). Plus `frame` (optional: `enabled`, `name` prefix of
button names, `line`, `branch`, `corner`), `separator` (what template blank lines become), `name`, `icons`.
`<base>` inside a text = back to the block's colour. Any text may use
`<smallcaps>...</smallcaps>`. Look only, never words (labels go to the lang files). Never hand-write `&#FFD94D│ ...`
lines again:

- **Code**: `Lore.create()` (or `Lore.create("<variant>")` for a plugin colour) with `.description()` (paragraphs,
  white), `.kv(key, value)`, `.item()` / `.items()` (lists), `.line()` (plain text), `.ok()` / `.ko()` / `.info()`,
  `.filter(options, selected)`, `.left()` / `.right()` / `.shiftLeft()` / `.shiftRight()`. The template decides the
  order, whatever order the calls were made in. Names go through `Items.of`, which applies the name style.
- **Menu files**: lore lines with tags `@desc`, `@kv Key | Value`, `@item`, `@text`, `@ok`, `@ko`, `@info`,
  `@filter <list>`, `@option`, `@left`, `@right`, `@shift-left`, `@shift-right`, `@raw`. Names without a colour get
  the name colour. `@style <variant>` picks a plugin variant for that button. Generators (`assets/*.py`) write
  these tags directly, through `Flux/assets/menu_lore.py` (`lore(desc, actions)`) or by hand. Placeholders return
  unframed text, the menu places them with a tag.
- **Navigation arrows** (previous / next page): no name, `hide-tooltip: true`.
- **Filters**: one format (the pets one): options listed with the current one marked, left click = next filter,
  right click = previous (`[list] <id> filter` / `filter-prev`, or `@filter <list>` for a Flux list).
- A plugin keeps its own colour with a variant (`variants.<name>` in style.yml: its own `lore`, `name`, `frame` or
  `icons`), never with hard-coded colours.

## Building (shared machine: production runs on it)

Never run a bare Maven/Gradle build: the OOM killer would kill the production server. Always cap memory:

```
systemd-run --user --scope -q -p MemoryMax=2.5G nice -n 10 mvn -q -o -pl <Module> -am package -DskipTests
```

- Offline (`-o`), `-am` so Flux is built with the module. Third-party jars go in `<module>/libs/` (see `LIBS.md`),
  **never commit them** (`.gitignore`).
- FluxReferral uses Gradle (JDK 21), FluxProxy (Velocity) is compiled with `javac`
  on JDK 25 (see its README).

## Deploying to Labs

- Copy `<module>/target/<Module>.jar` to `serveurs/04_labs/plugins/`. **Never start, stop or restart a server**:
  the owner restarts Labs (`ic-restart-labs`). Until then a replaced jar may log `NoClassDefFoundError`, that is
  expected.
- Console commands on Labs: `python3 serveurs/00_scripts/rcon.py labs "<command>"` (e.g. `fluxmenu reload`, `iazip`).
- Labs configs are edited in place, no backup folders. Production configs: backup first, ask first.

## Known pitfalls

- Never put `loadbefore` on a plugin of the suite towards a third-party plugin: Flux itself loads after a chain of
  them (CustomFishing, EcoJobs, libreforge, CMI...), so it closes a loop and Paper refuses to start the server
  (FluxFly with `loadbefore: [CMI]`, 2026-10-28). A command taken from another plugin is freed in that plugin's config.

- Bukkit config defaults shadow former key names: `getConfigurationSection("new")` returns the jar's default section
  even when the server file still uses the former name. Check `contains(key, true)` first. The other way round, a
  section missing from the server file reads as an **empty** section, without any error: log a warning when a list
  the plugin needs (rewards...) comes out empty (FluxAFK filled bars but gave nothing during its first tests, 2026-10-31).
- Editing a deployed config with a script: never a DOTALL regex up to an unbounded `.*` (it ate every section down to
  the end of the FluxAFK file, 2026-10-31). Reparse the whole file afterwards and check every top-level key is there.
- Player activity (AFK, idle): read the keys (`PlayerInputEvent`, `Player#getCurrentInput()`) and head rotation, never
  the position: water, vehicles and other players move a player who does nothing.
- Never teleport inside `PlayerMoveEvent` or `PlayerInputEvent`: schedule it on the next tick. `PlayerMoveEvent#setTo`
  fires a `PlayerTeleportEvent` (cause `PLUGIN`): a listener reacting to teleports must ignore that one.
- A jar replaced while the server runs: even `/<plugin> reload` can fail with `ClassNotFoundException` (a class never
  loaded before, read from the new jar). Only a restart is a valid test.
- Do not compare against `Format.yesNo()` in menus (localised), use fixed values.
- A menu needs `[refresh]` after a list page change to redraw its title (paths).
- Inventory rows of a hidden-inventory menu: the item of the first row sits at y 139 in game (as `PathRenderer`), not 140 as `fond_lib.objet` computes. Check the first background of a new menu in game.

## Migrations and patches (data of former plugins, items already handed out)

Never let old data or old items limit a new feature: write a migration instead, and keep it where it can be deleted.

- **One-time data migrations** (a former plugin's data copied into a Flux plugin) live **only** in the `FluxMigration`
  module (`fluxmigration/migrations/`), one class per migration, registered in `Migrations.all()`. Id
  `yyyy-MM-dd_short_name`, never changed once deployed; idempotent (skip what the target already has); never deletes
  the source (archiving the former plugin is a manual go-live step); talks to the target only through its public
  API (`<plugin>.api`, e.g. `fluxfly.api.FlyTime`). It runs once when the server starts, before players can join, and
  is recorded in Flux's table `flux_migration_done` (`/fluxmigration status`).
- **Compatibility code that must stay running for a while** (old item ids, former placeholders, former command
  names) goes in a `compat` package of the owning plugin, every class starting with a comment
  `// COMPAT <date>: <what> - remove when <condition>`. Never scatter it in the main code.
- When a migration has run on production and `/fluxmigration status` shows it done: delete its class and its line in
  `Migrations.all()`. When `Migrations.all()` is empty, the FluxMigration jar is removed from the servers. Search
  `COMPAT` from time to time and remove what is past its condition.
- Every migration and compat item gets a line in `docs/technique/flux-mise-en-prod.md`.

## Production go-live notes

Whenever a change will need an action on the day Flux goes to production (file not to copy, config to change, test to
run, old plugin to remove, DeluxeMenus menu to replace...), add it to `/home/influencraft/docs/technique/flux-mise-en-prod.md`
(French). Nobody should have to guess on that day.

## More documentation

On the server machine, `/home/influencraft/docs/technique/` has one page per plugin (`flux-suite.md` is the overview,
`fluxenchant.md`, `fluxtools.md`, `flux.md`, `parrainage.md`...), in French. Keep them updated when behaviour
changes; this file stays the short, English entry point.

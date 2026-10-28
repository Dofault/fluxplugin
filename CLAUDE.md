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

## Language rules (important)

The plugins are written **as English plugins, then adapted to French** for our server.

- **Code, identifiers, comments, log messages, default `config.yml` and `lang/en_US.yml`: English.**
- French lives only in what the server deploys: `lang/fr_FR.yml`, the Labs copies of configs (comments may be French),
  menus shown to players.
- **Commit messages: English**, short subject line, no French (older commits are French, do not rewrite history).
- Some older code still has French identifiers (FluxTools: `Etat`, `Rendu`, `Verrous`, `Outil`...). When you touch
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
| `items.CustomItems` | Shared recognition of custom items. Each suite plugin declares its own items with `CustomItems.register(plugin, namespace, Source)` (`fluxtools:<tool>`, `fluxpets:booster_<id>`). A source answers only for its own items. |
| `reward.Rewards` | Give a reward (`Reward.of(source, label).icon(...).commands(...)`); claimed in the `rewards` menu. Expiry, reminders (in game and Discord through DiscordSRV), grouped notifications are handled by Flux. |
| `util.Sounds` | Named cues only: `page`, `click`, `confirm` (first click of a double confirmation), `success`, `complete` (something earned), `error`, `remove`, `toggle-on/off`, `alert` (needs attention, 3 s cooldown). |
| `staff.Staff` | `Staff.alert(topic, title, message)` to online staff. |
| `dialog.Dialogs` | Paper dialogs (forms, copy buttons). |
| Menu `paths` | Progress paths drawn in the title (pack `influroad`). `states` placeholder gives one colour per step: `vert` claimed, `orange` waiting, `rouge` missed, `eteint` locked. |

- FluxTools exposes `fr.influencraft.fluxtools.api` (`Tools.info/refresh`, `EnchantMastery` service) used by
  FluxEnchant: FluxTools owns its tools and their rendering, FluxEnchant owns enchantment weight and mastery levels.
- Generated files: menus and tool configs come from scripts in `<module>/assets/*.py` (`gen_pages.py`,
  `gen_outils.py`, `gen_metiers.py`...). Change the script and regenerate, never hand-edit generated YAML.
  Exception: `Flux/assets/menu_recompenses.png` is hand-retouched by the owner, do not regenerate it.

## Building (shared machine: production runs on it)

Never run a bare Maven/Gradle build: the OOM killer would kill the production server. Always cap memory:

```
systemd-run --user --scope -q -p MemoryMax=2.5G nice -n 10 mvn -q -o -pl <Module> -am package -DskipTests
```

- Offline (`-o`), `-am` so Flux is built with the module. Third-party jars go in `<module>/libs/` (see `LIBS.md`),
  **never commit them** (`.gitignore`).
- FluxReferral uses Gradle (JDK 21), FluxChestShop is a Gradle fork, FluxProxy (Velocity) is compiled with `javac`
  on JDK 25 (see its README).

## Deploying to Labs

- Copy `<module>/target/<Module>.jar` to `serveurs/04_labs/plugins/`. **Never start, stop or restart a server**:
  the owner restarts Labs (`ic-restart-labs`). Until then a replaced jar may log `NoClassDefFoundError`, that is
  expected.
- Console commands on Labs: `python3 serveurs/00_scripts/rcon.py labs "<command>"` (e.g. `fluxmenu reload`, `iazip`).
- Labs configs are edited in place, no backup folders. Production configs: backup first, ask first.

## Known pitfalls

- Bukkit config defaults shadow former key names: `getConfigurationSection("new")` returns the jar's default section
  even when the server file still uses the former name. Check `contains(key, true)` first.
- Do not compare against `Format.yesNo()` in menus (localised), use fixed values.
- A menu needs `[refresh]` after a list page change to redraw its title (paths).
- Inventory rows of a hidden-inventory menu sit 2 px higher in game than `fond_lib.objet` computes.

## More documentation

On the server machine, `/home/influencraft/docs/technique/` has one page per plugin (`flux-suite.md` is the overview,
`fluxenchant.md`, `fluxitems.md`, `fluxmenu.md`, `parrainage.md`...), in French. Keep them updated when behaviour
changes; this file stays the short, English entry point.

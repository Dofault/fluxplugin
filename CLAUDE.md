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
| `util.Sounds` | Named cues only: `page`, `click`, `confirm` (first click of a double confirmation), `success`, `complete` (something earned), `error`, `remove`, `toggle-on/off`, `alert` (needs attention, 3 s cooldown). **One sound per click**: the action lines of a click run as a batch, a `[sound]` line wins over sounds played by code (`[list]` page sound, menu opening), else the first one plays. Do not add a `[sound]` that only repeats the automatic one. |
| `staff.Staff` | `Staff.alert(topic, title, message)` to online staff. |
| `dialog.Dialogs` | Paper dialogs (forms, copy buttons). |
| Menu `paths` | Progress paths drawn in the title (pack `influroad`). `states` placeholder gives one colour per step: `vert` claimed, `orange` waiting, `rouge` missed, `eteint` locked. |

- FluxTools exposes `fr.influencraft.fluxtools.api` (`Tools.info/refresh`, `EnchantMastery` service) used by
  FluxEnchant: FluxTools owns its tools and their rendering, FluxEnchant owns enchantment weight and mastery levels.
- Generated files: menus and tool configs come from scripts in `<module>/assets/*.py` (`gen_pages.py`,
  `gen_outils.py`, `gen_metiers.py`...). Change the script and regenerate, never hand-edit generated YAML.
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
only between content (runs around a vanished block merge). Plus `frame`, `name`, `icons`. Any text may use
`<smallcaps>...</smallcaps>`. Look only, never words (labels go to the lang files). Never hand-write `&#FFD94D│ ...`
lines again:

- **Code**: `Lore.create()` (or `Lore.create("<variant>")` for a plugin colour) with `.description()` (paragraphs,
  white), `.kv(key, value)`, `.item()` / `.items()` (lists), `.line()` (plain text), `.ok()` / `.ko()` / `.info()`,
  `.filter(options, selected)`, `.left()` / `.right()` / `.shiftLeft()` / `.shiftRight()`. The template decides the
  order, whatever order the calls were made in. Names go through `Items.of`, which applies the name style.
- **Menu files**: lore lines with tags `@desc`, `@kv Key | Value`, `@item`, `@text`, `@ok`, `@ko`, `@info`,
  `@filter <list>`, `@option`, `@left`, `@right`, `@shift-left`, `@shift-right`, `@raw`. Names without a colour get
  the name colour. `@style <variant>` picks a plugin variant for that button. Generators (`assets/*.py`) write
  these tags and pass their output through `Flux/assets/lore_tags.py` (`convert_yaml`), which turns any leftover
  hand-framed line into tags. Placeholders return unframed text, the menu places them with a tag.
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
- Inventory rows of a hidden-inventory menu: the item of the first row sits at y 139 in game (as `PathRenderer`), not 140 as `fond_lib.objet` computes. Check the first background of a new menu in game.

## Production go-live notes

Whenever a change will need an action on the day Flux goes to production (file not to copy, config to change, test to
run, old plugin to remove, DeluxeMenus menu to replace...), add it to `/home/influencraft/docs/technique/flux-mise-en-prod.md`
(French). Nobody should have to guess on that day.

## More documentation

On the server machine, `/home/influencraft/docs/technique/` has one page per plugin (`flux-suite.md` is the overview,
`fluxenchant.md`, `fluxitems.md`, `fluxmenu.md`, `parrainage.md`...), in French. Keep them updated when behaviour
changes; this file stays the short, English entry point.

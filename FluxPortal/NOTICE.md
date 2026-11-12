# FluxPortal: license

FluxPortal is distributed under the **GNU General Public License v3.0** (see `LICENSE`), unlike the rest of the
Flux suite: its window engine (`src/main/java/fr/influencraft/fluxportal/window/`) and its shaders
(`src/main/resources/window/`) are adapted from **RPE-portals** by ResourcePack AI, licensed GPL-3.0.

Changes from the original: rectangular holes instead of rings, no ring or opening animation, an open flag, textures
read from the server's own resource packs before the vanilla client (custom blocks keep their look), windows hung only
while Java players are near, Bedrock players kept out of the item frames, our own placement, storage and commands.

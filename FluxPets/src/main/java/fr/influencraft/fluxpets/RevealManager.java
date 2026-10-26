package fr.influencraft.fluxpets;

import fr.influencraft.fluxpets.CardRegistry.CardReward;
import fr.influencraft.fluxpets.CardRegistry.PetReward;
import fr.influencraft.fluxpets.CardRegistry.Reward;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DeathProtection;
import org.bukkit.EntityEffect;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Révélation d'un booster : une animation de totem par récompense différente (de la moins rare
 * à la plus rare, familiers en dernier dans leur rareté), puis son nom et le nombre d'exemplaires
 * au milieu de l'écran.
 *
 * Une récompense n'est donnée qu'une fois révélée. Les récompenses pas encore révélées sont
 * données d'un coup si la révélation est passée (clic droit), si le joueur se déconnecte, meurt
 * (cartes ajoutées au butin ou gardées selon keepInventory) ou si le plugin s'arrête.
 */
public final class RevealManager {

    private record Entry(Reward reward, int count) {
    }

    private static final class Session {
        final int total;
        final List<Entry> remaining;
        final List<BukkitTask> tasks = new ArrayList<>();
        /** Nettoyages à faire si la révélation s'arrête (modèle 3D affiché...). */
        final List<Runnable> cleanups = new ArrayList<>();

        void stop() {
            tasks.forEach(BukkitTask::cancel);
            cleanups.forEach(Runnable::run);
            cleanups.clear();
        }

        Session(List<Entry> entries, int total) {
            this.remaining = entries;
            this.total = total;
        }
    }

    private final FluxPets plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    /**
     * Modèles 3D des familiers adoptés, détachés de la révélation : ils restent leur durée complète
     * même si le joueur passe la révélation ou ouvre d'autres boosters (retirés à la déconnexion).
     */
    private final Map<UUID, Session> showcases = new HashMap<>();

    public RevealManager(FluxPets plugin) {
        this.plugin = plugin;
    }

    public boolean isRevealing(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    /** Lance la révélation (une révélation en cours est d'abord passée). */
    public void start(Player player, List<Reward> rewards) {
        start(player, rewards, null);
    }

    /**
     * boosterItemId (namespace:id de l'item du booster) : sa grande image (glyphe ItemsAdder
     * %img_<id>_grand%) remplace le verso avant la première carte, comme si on ouvrait le booster.
     */
    public void start(Player player, List<Reward> rewards, String boosterItemId) {
        skip(player, false);

        Map<String, Entry> grouped = new LinkedHashMap<>();
        for (Reward r : rewards) {
            grouped.merge(r.key(), new Entry(r, 1), (a, b) -> new Entry(a.reward(), a.count() + 1));
        }
        List<Entry> entries = new ArrayList<>(grouped.values());
        // cartes de la moins rare à la plus rare, puis les familiers en tout dernier
        entries.sort(Comparator.comparingInt((Entry e) -> e.reward() instanceof PetReward ? 1 : 0)
                .thenComparingInt(e -> e.reward().rarity().order())
                .thenComparingInt(e -> e.reward().order()));

        Session session = new Session(entries, rewards.size());
        UUID uuid = player.getUniqueId();
        sessions.put(uuid, session);

        var cfg = plugin.getConfig();
        long start = Math.max(1, cfg.getLong("reveal.start-delay-ticks", 10));
        long resultDelay = Math.max(0, cfg.getLong("reveal.actionbar-delay-ticks", 20));
        long step = Math.max(1, cfg.getLong("reveal.delay-ticks", 45));
        var scheduler = plugin.getServer().getScheduler();

        String cardBack = cfg.getString("reveal.back-title", "&r&f%img_carte_verso_grand%");
        String boosterBack = null;
        if (boosterItemId != null && boosterItemId.contains(":")) {
            String glyph = "%img_" + boosterItemId.substring(boosterItemId.indexOf(':') + 1) + "_grand%";
            if (!Texts.glyphs(glyph).contains("%img_")) {
                boosterBack = "&r&f" + glyph;
            }
        }
        boolean firstCard = true;

        long at = start;
        long endAt = start;
        for (Entry entry : entries) {
            boolean newPet = entry.reward() instanceof PetReward p && !PetHook.has(player, p.pet().id());
            if (newPet) {
                long t0 = at;
                session.tasks.add(scheduler.runTaskLater(plugin, () -> {
                    if (sessions.get(uuid) != session || !player.isOnline()) {
                        return;
                    }
                    session.remaining.remove(entry);
                    grant(player, List.of(entry));
                    celebrate(player, (PetReward) entry.reward());
                }, t0));
                long duration = Math.max(step, cfg.getLong("pet-reveal.duration-ticks", 100));
                endAt = t0 + duration;
                at += duration;
                continue;
            }
            // verso de la carte en grand au milieu de l'écran, puis l'animation du totem passe par-dessus
            // le verso s'ajoute avant la carte : l'écart entre deux animations du totem reste delay-ticks
            long back = Math.max(0, cfg.getLong("reveal.back-ticks", 15));
            if (firstCard && boosterBack != null) {
                // ouverture : l'image du booster seule, puis elle laisse place au verso de la première carte
                long boosterTicks = Math.max(0, cfg.getLong("reveal.booster-ticks", 20));
                if (boosterTicks > 0) {
                    String title = boosterBack;
                    long tb = at;
                    session.tasks.add(scheduler.runTaskLater(plugin, () -> {
                        if (sessions.get(uuid) == session && player.isOnline()) {
                            showBack(player, boosterTicks, title);
                        }
                    }, tb));
                    at += boosterTicks;
                }
            }
            firstCard = false;
            String backTitle = cardBack;
            if (back > 0) {
                long tb = at;
                session.tasks.add(scheduler.runTaskLater(plugin, () -> {
                    if (sessions.get(uuid) == session && player.isOnline()) {
                        showBack(player, back + resultDelay + overlapTicks(), backTitle);
                    }
                }, tb));
            }
            long t0 = at + back;
            session.tasks.add(scheduler.runTaskLater(plugin, () -> {
                if (sessions.get(uuid) == session && player.isOnline()) {
                    showTotem(player, entry.reward());
                }
            }, t0));
            session.tasks.add(scheduler.runTaskLater(plugin, () -> {
                if (sessions.get(uuid) != session || !player.isOnline()) {
                    return;
                }
                session.remaining.remove(entry);
                boolean duplicate = grant(player, List.of(entry));
                showResult(player, entry, duplicate, backTitle);
            }, t0 + resultDelay));
            endAt = t0 + resultDelay;
            at += back + step;
        }
        session.tasks.add(scheduler.runTaskLater(plugin, () -> {
            if (sessions.get(uuid) != session) {
                return;
            }
            sessions.remove(uuid);
            session.stop();
            if (player.isOnline()) {
                plugin.send(player, "finished", "%count%", String.valueOf(session.total));
                playFinishSound(player);
            }
        }, endAt + 1));
    }

    /**
     * Booster ouvert en rafale : pas d'animation du totem, tout est donné d'un coup. Un familier
     * adopté garde sa mise en scène (titre, son, modèle 3D) et le message dans le tchat.
     */
    public void instant(Player player, List<Reward> rewards) {
        instant(player, rewards, 1);
    }

    /** boosters > 1 : ouverture de toute une pile (Maj + clic droit), message récapitulatif. */
    public void instant(Player player, List<Reward> rewards, int boosters) {
        skip(player, false);
        List<Entry> entries = new ArrayList<>();
        for (Reward r : rewards) {
            entries.add(new Entry(r, 1));
        }
        List<PetReward> pets = newPets(player, entries);
        grant(player, entries);
        if (boosters > 1) {
            plugin.send(player, "opened-all", "%boosters%", String.valueOf(boosters),
                    "%count%", String.valueOf(rewards.size()));
        } else {
            plugin.send(player, "finished", "%count%", String.valueOf(rewards.size()));
        }
        announceSkipped(player, pets);
    }

    /**
     * Familier tout juste adopté : titre, son de succès, modèle 3D qui apparaît face au joueur
     * (visible par lui seul) et joue son animation « happy », spirale de particules, annonce dans
     * le tchat pour les raretés hautes.
     */
    private void celebrate(Player player, PetReward reward) {
        announce(player, reward, Math.max(20, plugin.getConfig().getLong("pet-reveal.duration-ticks", 100)));
        showcase(player, reward);
    }

    /**
     * Modèle 3D du familier face au joueur (visible par tous les joueurs proches, animation « happy »)
     * et spirale de particules, pendant pet-reveal.model-ticks (défaut : duration-ticks + 4 s). Plusieurs familiers à la suite se
     * rangent côte à côte au lieu de se superposer.
     */
    private void showcase(Player player, PetReward reward) {
        var cfg = plugin.getConfig();
        // le modèle reste plus longtemps que la pause de la révélation (model-ticks, défaut : +4 s)
        long duration = Math.max(20, cfg.getLong("pet-reveal.model-ticks",
                cfg.getLong("pet-reveal.duration-ticks", 100) + 80));
        Session holder = showcases.computeIfAbsent(player.getUniqueId(), k -> new Session(List.of(), 0));
        int index = holder.cleanups.size();

        // modèle 3D face au joueur
        var eye = player.getEyeLocation();
        var dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) {
            dir = new org.bukkit.util.Vector(0, 0, 1);
        }
        dir.normalize();
        var spot = player.getLocation().add(dir.clone().multiply(cfg.getDouble("pet-reveal.distance", 2.5)));
        if (index > 0) {
            // décalage latéral : 1.5 bloc, alterné à droite puis à gauche
            var side = new org.bukkit.util.Vector(-dir.getZ(), 0, dir.getX());
            spot.add(side.multiply(1.5 * ((index + 1) / 2) * (index % 2 == 1 ? 1 : -1)));
        }
        spot.setYaw(player.getLocation().getYaw() + 180);
        spot.setPitch(0);
        var definition = fr.influencraft.fluxpets.pets.Pets.get(reward.pet().id());
        String model = cfg.getString("pets.list." + reward.pet().id() + ".model",
                definition != null ? definition.model() : reward.pet().id());
        if (plugin.getServer().getPluginManager().isPluginEnabled("BetterModel")) {
            try {
                Runnable close = BetterModelPets.showcase(player, model, spot,
                        cfg.getString("pet-reveal.animation", "happy"));
                if (close != null) {
                    holder.cleanups.add(close);
                    holder.tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        close.run();
                        holder.cleanups.remove(close);
                    }, duration));
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not spawn the pet model: " + t);
            }
        }

        // spirale de particules à la couleur de la rareté
        var color = particleColor(reward.rarity());
        final int[] tick = {0};
        BukkitTask spiral = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || tick[0] > 40) {
                return;
            }
            double a = tick[0] * 0.5;
            double h = tick[0] * 0.06;
            for (int k = 0; k < 2; k++) {
                double ang = a + k * Math.PI;
                var loc = spot.clone().add(Math.cos(ang) * 1.2, h, Math.sin(ang) * 1.2);
                spot.getWorld().spawnParticle(org.bukkit.Particle.DUST, loc, 2, 0, 0, 0, 0,
                        new org.bukkit.Particle.DustOptions(color, 1.3f));
            }
            tick[0]++;
        }, 0, 1);
        holder.tasks.add(spiral);
        holder.tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, spiral::cancel, 41));
    }

    /** Retire les modèles 3D encore affichés pour ce joueur (déconnexion, arrêt du plugin). */
    public void clearShowcases(Player player) {
        Session holder = showcases.remove(player.getUniqueId());
        if (holder != null) {
            holder.stop();
        }
    }

    /**
     * Titre « Nouveau familier », son de succès, annonce dans le tchat pour les raretés hautes.
     * Utilisé par la mise en scène complète et quand une révélation est passée (spam de boosters).
     */
    private void announce(Player player, PetReward reward, long duration) {
        var cfg = plugin.getConfig();
        String name = plugin.registry().displayName(reward.pet());
        player.showTitle(net.kyori.adventure.title.Title.title(
                Texts.of(cfg.getString("pet-reveal.title", "%pet%").replace("%pet%", name)),
                Texts.of(cfg.getString("pet-reveal.subtitle", "&#FF7A59Nᴏᴜᴠᴇᴀᴜ Fᴀᴍɪʟɪᴇʀ").replace("%pet%", name)),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(250),
                        java.time.Duration.ofMillis(Math.min(duration, cfg.getLong("pet-reveal.title-ticks", 50)) * 50),
                        java.time.Duration.ofMillis(500))));
        playSound(player, cfg.getString("pet-reveal.sound", "ui.toast.challenge_complete"), 1f, 1f);

        if (cfg.getStringList("pet-reveal.broadcast-rarities").contains(reward.rarity().id())) {
            String msg = plugin.format("pet-broadcast", "%player%", player.getName(), "%pet%", name);
            plugin.getServer().broadcast(Texts.of(cfg.getString("messages.prefix", "") + msg));
        }
    }

    /** Familiers encore à révéler et pas encore adoptés (à annoncer s'ils sont donnés d'un coup). */
    private List<PetReward> newPets(Player player, List<Entry> entries) {
        List<PetReward> pets = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Entry entry : entries) {
            // un même familier tiré dans plusieurs boosters (pile ouverte d'un coup) n'est nouveau qu'une fois
            if (entry.reward() instanceof PetReward p && seen.add(p.pet().id()) && !PetHook.has(player, p.pet().id())) {
                pets.add(p);
            }
        }
        return pets;
    }

    /** Annonce les familiers adoptés sans leur mise en scène : message dans le tchat, titre du dernier. */
    private void announceSkipped(Player player, List<PetReward> pets) {
        if (pets.isEmpty() || !player.isOnline()) {
            return;
        }
        long duration = Math.max(20, plugin.getConfig().getLong("pet-reveal.duration-ticks", 100));
        for (PetReward p : pets) {
            plugin.send(player, "pet-unlocked", "%pet%", plugin.registry().displayName(p.pet()));
            announce(player, p, duration);
            showcase(player, p);
        }
    }

    private org.bukkit.Color particleColor(CardRegistry.Rarity rarity) {
        String hex = plugin.getConfig().getString("rarities." + rarity.id() + ".color", "#FF7A59");
        try {
            return org.bukkit.Color.fromRGB(Integer.parseInt(hex.replace("#", ""), 16));
        } catch (NumberFormatException e) {
            return org.bukkit.Color.fromRGB(0xFF7A59);
        }
    }

    private static void playSound(Player player, String name, float volume, float pitch) {
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            player.playSound(player, name.toLowerCase(Locale.ROOT), volume, pitch);
        } catch (IllegalArgumentException ignored) {
            // son invalide : pas de son
        }
    }

    /** Passe la révélation en cours : récompenses restantes données d'un coup. */
    public boolean skip(Player player, boolean notify) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return false;
        }
        session.stop();
        List<Entry> rest = new ArrayList<>(session.remaining);
        session.remaining.clear();
        List<PetReward> pets = newPets(player, rest);
        grant(player, rest);
        if (notify && player.isOnline()) {
            plugin.send(player, "skipped");
        }
        announceSkipped(player, pets);
        return true;
    }

    /**
     * Mort pendant une révélation : arrête tout, débloque les familiers restants et renvoie les
     * cartes non révélées sous forme d'objets (le listener les place dans le butin).
     */
    public List<ItemStack> takeRemaining(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        List<ItemStack> items = new ArrayList<>();
        if (session == null) {
            return items;
        }
        session.stop();
        List<PetReward> pets = newPets(player, new ArrayList<>(session.remaining));
        for (Entry entry : session.remaining) {
            switch (entry.reward()) {
                case CardReward c -> items.addAll(stacks(c.card(), entry.count()));
                case PetReward p -> grantPet(player, p, items);
            }
        }
        session.remaining.clear();
        announceSkipped(player, pets);
        return items;
    }

    public void skipAll() {
        showcases.values().forEach(Session::stop);
        showcases.clear();
        for (UUID uuid : new ArrayList<>(sessions.keySet())) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                skip(player, false);
            } else {
                sessions.remove(uuid).stop();
            }
        }
    }

    /**
     * Le client joue l'animation du totem avec le premier objet « protection contre la mort »
     * qu'il trouve en main, en l'affichant avec son modèle. Dans le même tick : l'objet est envoyé
     * dans le slot tenu, puis l'effet, puis le vrai contenu du slot. Côté serveur, le slot ne change
     * pas entre deux ticks (aucun risque de perte d'objet).
     * Avec un menu ouvert (coffre, GUI), on ne touche pas à l'inventaire : seul le titre s'affiche.
     */
    private void showTotem(Player player, Reward reward) {
        if (player.isDead() || player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING) {
            return;
        }
        ItemStack display = plugin.registry().displayItem(player, reward);
        display.setData(DataComponentTypes.DEATH_PROTECTION, DeathProtection.deathProtection().build());

        PlayerInventory inv = player.getInventory();
        int slot = inv.getHeldItemSlot();
        ItemStack original = inv.getItem(slot);
        try {
            inv.setItem(slot, display);
            player.updateInventory();
            player.sendEntityEffect(EntityEffect.TOTEM_RESURRECT, player);
        } finally {
            inv.setItem(slot, original);
            player.updateInventory();
        }
    }

    /** Verso commun des cartes (glyphe ItemsAdder) affiché en titre avant le retournement. */
    /** Durée pendant laquelle le verso reste par-dessus le début de l'animation du totem. */
    private long overlapTicks() {
        var cfg = plugin.getConfig();
        return cfg.getLong("reveal.back-ticks", 15) > 0
                ? Math.max(0, Math.min(cfg.getLong("reveal.stay", 22), cfg.getLong("reveal.back-overlap-ticks", 10))) : 0;
    }

    private void showBack(Player player, long ticks, String title) {
        player.showTitle(net.kyori.adventure.title.Title.title(
                Texts.of(title),
                Texts.of(""),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(100),
                        java.time.Duration.ofMillis(ticks * 50), java.time.Duration.ofMillis(0))));
    }

    /**
     * Rareté + nom dans l'action bar ; quantité mise en avant au milieu de l'écran (sous-titre,
     * avec un titre vide), quasiment au déclenchement de l'animation du totem.
     */
    private void showResult(Player player, Entry entry, boolean duplicatePet, String backTitle) {
        var cfg = plugin.getConfig();
        String key = switch (entry.reward()) {
            case CardReward ignored -> "reveal.actionbar";
            case PetReward p -> !duplicatePet ? "reveal.actionbar-pet"
                    : p.duplicatePlush() && seriesComplete(player, p.pet(), plugin.registry().seriesPets(p.pet()))
                    ? "reveal.actionbar-pet-duplicate" : "reveal.actionbar-pet-duplicate-incomplete";
        };
        player.sendActionBar(Texts.of(fill(cfg.getString(key, "%card%"), entry)));
        if (entry.reward() instanceof CardReward) {
            String sub = entry.count() >= cfg.getInt("reveal.subtitle-min-count", 1)
                    ? fill(cfg.getString("reveal.subtitle", "&#FF7A59&lx%count%"), entry) : "";
            long stay = cfg.getLong("reveal.stay", 22);
            long fadeOut = cfg.getLong("reveal.fade-out", 6);
            // le verso, déjà affiché (showBack), reste encore « overlap » ticks : seul le sous-titre change,
            // puis il s'efface et la quantité reste seule
            long overlap = overlapTicks();
            if (overlap > 0) {
                if (!sub.isEmpty()) {
                    player.sendTitlePart(net.kyori.adventure.title.TitlePart.SUBTITLE, Texts.of(sub));
                }
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline() && !sub.isEmpty()) {
                        player.showTitle(net.kyori.adventure.title.Title.title(Texts.of(""), Texts.of(sub),
                                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ZERO,
                                        java.time.Duration.ofMillis(Math.max(1, stay - overlap) * 50),
                                        java.time.Duration.ofMillis(fadeOut * 50))));
                    }
                }, overlap);
            } else if (!sub.isEmpty()) {
                player.showTitle(net.kyori.adventure.title.Title.title(
                        Texts.of(""), Texts.of(sub),
                        net.kyori.adventure.title.Title.Times.times(
                                java.time.Duration.ofMillis(cfg.getLong("reveal.fade-in", 2) * 50),
                                java.time.Duration.ofMillis(stay * 50),
                                java.time.Duration.ofMillis(fadeOut * 50))));
            }
        }
    }

    private void playFinishSound(Player player) {
        String name = plugin.getConfig().getString("reveal.sound-finish", "");
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            player.playSound(player, name.toLowerCase(Locale.ROOT), 1f, 1f);
        } catch (IllegalArgumentException ignored) {
            // son invalide : pas de son
        }
    }

    private String fill(String format, Entry entry) {
        String color = plugin.getConfig().getString("rarities." + entry.reward().rarity().id() + ".color", "#FF7A59");
        return format.replace("%rarity_color%", "&" + (color.startsWith("#") ? color : "#" + color))
                .replace("%card%", plugin.registry().displayName(entry.reward()))
                .replace("%count%", String.valueOf(entry.count()))
                .replace("%xp%", String.format(FluxPets.locale(), "%.0f", plugin.registry().duplicateXp()));
    }

    private List<ItemStack> stacks(CardRegistry.Card card, int count) {
        List<ItemStack> result = new ArrayList<>();
        int left = count;
        while (left > 0) {
            ItemStack item = plugin.registry().cardItem(card);
            int amount = Math.min(left, item.getMaxStackSize());
            item.setAmount(amount);
            result.add(item);
            left -= amount;
        }
        return result;
    }

    /**
     * Débloque le familier, ou le compense s'il est déjà possédé : sa carte (pets.duplicate-card, 1 par défaut)
     * et l'XP de duplicate-xp s'il y en a.
     * Peluches : réservées aux séries complètes. L'adoption qui complète la série donne d'un coup les
     * peluches de toute la série ; ensuite, chaque doublon d'un familier de la série donne sa peluche, sauf
     * depuis un booster « duplicate-plush: false » (boosters de palier). Série incomplète : pas de peluche.
     * Renvoie true si doublon.
     */
    private boolean grantPet(Player player, PetReward reward) {
        return grantPet(player, reward, null);
    }

    /** sink non nul (mort du joueur) : carte et peluches y sont ajoutées au lieu d'aller dans l'inventaire. */
    private boolean grantPet(Player player, PetReward reward, List<ItemStack> sink) {
        CardRegistry.PetEntry pet = reward.pet();
        boolean owned = PetHook.has(player, pet.id());
        boolean dropped = false;
        List<ItemStack> extra = new ArrayList<>();
        if (owned) {
            PetHook.giveXp(player, pet.id(), plugin.registry().duplicateXp());
            CardRegistry.Card card = plugin.registry().cardOf(pet);
            int cards = plugin.getConfig().getInt("pets.duplicate-card", 1);
            if (card != null && cards > 0) {
                extra.addAll(stacks(card, cards));
            }
        } else {
            PetHook.unlock(player, pet.id());
            // boosters de l'inventaire : ✘ -> ✔ devant ce familier
            plugin.getServer().getScheduler().runTask(plugin, () -> plugin.refreshItems(player));
        }
        List<CardRegistry.PetEntry> series = plugin.registry().seriesPets(pet);
        boolean complete = seriesComplete(player, pet, series);
        List<CardRegistry.PetEntry> plushes = !complete ? List.of()
                : !owned ? series : reward.duplicatePlush() ? List.of(pet) : List.of();
        for (CardRegistry.PetEntry p : plushes) {
            ItemStack plush = plugin.registry().plushItem(p);
            if (plush != null) {
                extra.add(plush);
            }
        }
        for (ItemStack item : extra) {
            if (sink != null) {
                sink.add(item);
            } else {
                dropped |= giveOrDrop(player, item);
            }
        }
        if (player.isOnline()) {
            if (!owned && complete) {
                plugin.send(player, "series-complete", "%series%",
                        String.valueOf(plugin.registry().series(pet)), "%count%", String.valueOf(plushes.size()));
            }
            if (dropped) {
                plugin.send(player, "inventory-full");
            }
        }
        return owned;
    }

    /** Tous les familiers de la série sont adoptés (celui-ci compté comme adopté). */
    private boolean seriesComplete(Player player, CardRegistry.PetEntry pet, List<CardRegistry.PetEntry> series) {
        for (CardRegistry.PetEntry p : series) {
            if (!p.id().equals(pet.id()) && !PetHook.has(player, p.id())) {
                return false;
            }
        }
        return true;
    }

    /** Donne les récompenses. Renvoie true si un familier était un doublon. */
    private boolean grant(Player player, List<Entry> entries) {
        boolean dropped = false;
        boolean duplicate = false;
        for (Entry entry : entries) {
            switch (entry.reward()) {
                case CardReward c -> {
                    for (ItemStack item : stacks(c.card(), entry.count())) {
                        dropped |= giveOrDrop(player, item);
                    }
                }
                case PetReward p -> duplicate |= grantPet(player, p);
            }
        }
        if (dropped && player.isOnline()) {
            plugin.send(player, "inventory-full");
        }
        return duplicate;
    }

    /**
     * Emplacements libres nécessaires dans le PIRE cas pour ouvrir « amount » boosters, calculé avant le
     * tirage (le résultat ne dépend pas du tirage : impossible de sonder un booster en boucle).
     * Cartes : une pile par rareté de carte du booster et par booster (toutes les cartes d'une rareté sont
     * la même carte), au plus une par carte possible, plus les piles de 64 en trop. Peluches non
     * comptées (trop rare).
     */
    public int worstSlots(Player player, CardRegistry.Booster booster, int amount) {
        List<Reward> contents = plugin.registry().contents(booster);
        int maxCards = booster.cards() * amount;
        // cartes possibles par rareté ; une carte « couverte » s'empile sur une pile existante qui a la
        // place pour le maximum qu'elle peut recevoir : elle ne prend pas de nouvel emplacement
        java.util.Map<String, int[]> perRarity = new LinkedHashMap<>();  // rareté -> {non couvertes}
        for (Reward r : contents) {
            switch (r) {
                case CardReward c -> {
                    int[] counts = perRarity.computeIfAbsent(c.rarity().id(), k -> new int[1]);
                    if (stackRoom(player, plugin.registry().cardItem(c.card())) < maxCards) {
                        counts[0]++;
                    }
                }
                case PetReward ignored -> {
                }
            }
        }
        int cardSlots = 0;
        for (int[] counts : perRarity.values()) {
            cardSlots += Math.min(amount, counts[0]);
        }
        if (cardSlots > 0) {
            cardSlots += (maxCards - 1) / 64;  // une même carte peut dépasser une pile de 64
        }
        // peluches non comptées : compléter une série pile à cette ouverture est trop rare pour bloquer
        // l'ouverture ; si l'inventaire est plein, elles tombent aux pieds du joueur
        return cardSlots;
    }

    /** Place restante sur les piles existantes de cet objet dans l'inventaire principal. */
    private int stackRoom(Player player, ItemStack item) {
        int room = 0;
        for (ItemStack it : player.getInventory().getStorageContents()) {
            if (it != null && it.isSimilar(item)) {
                room += Math.max(0, it.getMaxStackSize() - it.getAmount());
            }
        }
        return room;
    }

    /** Emplacements vides de l'inventaire principal, en comptant celui du booster s'il va être vidé. */
    public int freeSlots(Player player, int boosterSlot, int consumed) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        int free = 0;
        for (int i = 0; i < storage.length; i++) {
            ItemStack it = storage[i];
            if (it == null || it.getType().isAir() || (i == boosterSlot && it.getAmount() <= consumed)) {
                free++;
            }
        }
        return free;
    }

    /** Donne l'objet, le surplus tombe aux pieds du joueur. Renvoie true si quelque chose est tombé. */
    public static boolean giveOrDrop(Player player, ItemStack item) {
        var leftover = player.getInventory().addItem(item);
        leftover.values().forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        return !leftover.isEmpty();
    }
}

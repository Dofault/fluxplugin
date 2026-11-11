package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.dialog.Dialogs;
import fr.influencraft.flux.api.universe.UniverseEnterEvent;
import fr.influencraft.flux.api.universe.Universes;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.fluxdungeons.rpg.core.ClassDef;
import fr.influencraft.fluxdungeons.rpg.core.Equipment;
import fr.influencraft.fluxdungeons.rpg.core.Modifier;
import fr.influencraft.fluxdungeons.rpg.core.StatSheet;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Players of the rpg universe: loads their class and progress when they enter it (choice of a class the first time),
 * computes their stats from class, level, equipment and bonuses, regenerates life and mana, shows life, mana and
 * cooldowns, saves progress. Nothing of it exists outside the universe: leaving takes every attribute change back.
 */
public final class RpgPlayers implements Listener {

    private static final NamespacedKey SPEED = new NamespacedKey("fluxdungeons", "rpg_speed");
    private static final NamespacedKey ATTACK_SPEED = new NamespacedKey("fluxdungeons", "rpg_attack_speed");
    private static final NamespacedKey REACH = new NamespacedKey("fluxdungeons", "rpg_reach");
    /** Swing of the weapon as a share of its recharge (the client restarts a swing a little before its end). */
    private static final double SWING_SHARE = 0.8;
    /** Seconds without hitting nor being hit before the fast out-of-combat regeneration. */
    private static final int CALM_TICKS = 100;

    private final FluxPlugin plugin;
    private final RpgConfig config;
    private final RpgStore store;
    private final RpgItems items;
    private RpgWorld world;
    private CombatEngine combat;
    private final Map<UUID, RpgPlayer> online = new HashMap<>();
    /** Players whose data is being read (they can do nothing yet). */
    private final Set<UUID> loading = new HashSet<>();
    private final Set<UUID> dirty = new HashSet<>();
    private final RpgHud hud;

    RpgPlayers(FluxPlugin plugin, RpgConfig config, RpgStore store, RpgItems items) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.items = items;
        this.hud = new RpgHud(plugin);
        this.hud.register();
    }

    void bind(RpgWorld world, CombatEngine combat) {
        this.world = world;
        this.combat = combat;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveAll, 1200L, 1200L);
    }

    public RpgPlayer get(Player p) {
        return online.get(p.getUniqueId());
    }

    // ------------------------------------------------------------------ entering and leaving the universe

    @EventHandler
    public void onEnter(UniverseEnterEvent e) {
        Player p = e.getPlayer();
        if (RpgWorld.UNIVERSE.equals(e.universe())) start(p);
        else if (RpgWorld.UNIVERSE.equals(e.previous())) stop(p, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        overlaidAfterJoin.remove(e.getPlayer().getUniqueId());
        stop(e.getPlayer(), false);
    }

    private void start(Player p) {
        RpgConfig.Set set = config.get();
        if (set == null) {
            plugin.messages().send(p, "rpg.unavailable");
            return;
        }
        UUID id = p.getUniqueId();
        if (online.containsKey(id) || !loading.add(id)) return;
        hud.show(p);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            RpgStore.Hero hero;
            RpgStore.Progress progress = null;
            try {
                hero = store.hero(id);
                if (hero != null) progress = store.progress(id, hero.heroClass());
            } catch (SQLException ex) {
                plugin.getLogger().severe("Rpg data of " + p.getName() + " unreadable: " + ex.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    loading.remove(id);
                    if (p.isOnline()) plugin.messages().send(p, "rpg.unavailable");
                });
                return;
            }
            RpgStore.Hero h = hero;
            RpgStore.Progress pr = progress;
            Bukkit.getScheduler().runTask(plugin, () -> {
                loading.remove(id);
                if (!p.isOnline() || !RpgWorld.UNIVERSE.equals(Universes.of(p))) return;
                ClassDef c = h == null ? null : set.classes().get(h.heroClass());
                if (c == null) {
                    if (h != null) plugin.getLogger().warning(p.getName() + " has the unknown class " + h.heroClass() + ": choice asked again");
                    chooseClass(p);
                    return;
                }
                setup(p, c, pr.level(), pr.xp(), false);
            });
        });
    }

    private void setup(Player p, ClassDef c, int level, double xp, boolean fresh) {
        RpgPlayer r = new RpgPlayer(p.getUniqueId(), c, Math.max(1, level), xp);
        online.put(p.getUniqueId(), r);
        // the game's health of his rpg profile holds the share of life he left with
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        double share = fresh || max == null ? 1 : p.getHealth() / max.getValue();
        recompute(p);
        r.mana(r.stats().get("max_mana"));
        combat.life(p, share * r.stats().get("max_health"));
        giveWeaponIfMissing(p, r);
        p.setFoodLevel(20);
        p.setSaturation(0);
        showLevel(p, r);
    }

    /** Leaving the universe (or the server): progress saved, attribute changes removed. */
    private void stop(Player p, boolean left) {
        UUID id = p.getUniqueId();
        loading.remove(id);
        RpgPlayer r = online.remove(id);
        AttributeInstance speed = p.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(SPEED);
        AttributeInstance attackSpeed = p.getAttribute(Attribute.ATTACK_SPEED);
        if (attackSpeed != null) attackSpeed.removeModifier(ATTACK_SPEED);
        AttributeInstance reach = p.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        if (reach != null) reach.removeModifier(REACH);
        if (left) hud.hide(p);
        if (r == null) return;
        if (combat != null) combat.forget(p);
        int level = r.level();
        double xp = r.xp();
        String cls = r.heroClass().id();
        if (plugin.isEnabled()) Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> store.save(id, cls, level, xp));
        else store.save(id, cls, level, xp);
        dirty.remove(id);
    }

    public void stopAll() {
        for (Player p : Bukkit.getOnlinePlayers()) stop(p, false);
    }

    private void saveAll() {
        for (UUID id : new ArrayList<>(dirty)) {
            RpgPlayer r = online.get(id);
            if (r == null) continue;
            int level = r.level();
            double xp = r.xp();
            String cls = r.heroClass().id();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> store.save(id, cls, level, xp));
        }
        dirty.clear();
    }

    // ------------------------------------------------------------------ class choice

    /** The first visit: a window he cannot close without choosing (closed by leaving, it comes back next time). */
    private void chooseClass(Player p) {
        RpgConfig.Set set = config.get();
        if (set == null) return;
        Dialogs.Form form = Dialogs.form(p, plugin.messages().raw("choice.title"));
        form.body(plugin.messages().raw("choice.intro"));
        for (String id : set.classes().keySet()) {
            ItemStack weapon = items.make("class_" + id + "_weapon");
            String text = "&#FFD94D" + plugin.messages().raw("classes." + id + ".name") + "\n&#B376E5" + plugin.messages().raw("classes." + id + ".description");
            if (weapon != null) form.item(weapon, text);
            else form.body(text);
        }
        for (String id : set.classes().keySet()) {
            form.action(id, plugin.messages().raw("classes." + id + ".name"), plugin.messages().raw("classes." + id + ".description"));
        }
        form.columns(Math.min(4, set.classes().size())).closable(false).width(320)
                .onSubmit((pl, answer) -> confirmClass(pl, answer.button()))
                .onCancel(pl -> Bukkit.getScheduler().runTaskLater(plugin, () -> reopen(pl), 2L))
                .open();
    }

    private void reopen(Player p) {
        if (p.isOnline() && RpgWorld.UNIVERSE.equals(Universes.of(p)) && !online.containsKey(p.getUniqueId())) chooseClass(p);
    }

    private void confirmClass(Player p, String classId) {
        String name = plugin.messages().raw("classes." + classId + ".name");
        Dialogs.confirm(p, plugin.messages().raw("choice.confirm-title"), plugin.messages().raw("choice.confirm", "%class%", name),
                pl -> choose(pl, classId), pl -> Bukkit.getScheduler().runTaskLater(plugin, () -> reopen(pl), 2L));
    }

    /** Records the choice once (database first), then sets the player up and gives his weapon. */
    private void choose(Player p, String classId) {
        RpgConfig.Set set = config.get();
        UUID id = p.getUniqueId();
        if (set == null || !set.classes().containsKey(classId) || online.containsKey(id) || !loading.add(id)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean first = store.choose(id, classId);
            Bukkit.getScheduler().runTask(plugin, () -> {
                loading.remove(id);
                if (!p.isOnline()) return;
                if (!first) {
                    // another window already chose: read what was recorded
                    start(p);
                    return;
                }
                if (!RpgWorld.UNIVERSE.equals(Universes.of(p))) return;
                setup(p, set.classes().get(classId), 1, 0, true);
                plugin.messages().send(p, "choice.done", "%class%", plugin.messages().raw("classes." + classId + ".name"));
                Sounds.cue(p, "complete");
            });
        });
    }

    /** /classe: change class from the lobby, at most once per cooldown; progress of every class is kept. */
    void changeClass(Player p) {
        RpgPlayer r = online.get(p.getUniqueId());
        RpgConfig.Set set = config.get();
        if (r == null || set == null) {
            plugin.messages().send(p, "rpg.not-in-rpg");
            return;
        }
        if (world == null || p.getWorld() != world.world()) {
            plugin.messages().send(p, "choice.lobby-only");
            return;
        }
        Dialogs.Form form = Dialogs.form(p, plugin.messages().raw("choice.change-title"));
        form.body(plugin.messages().raw("choice.change-intro", "%hours%", String.valueOf(cooldownHours())));
        for (String id : set.classes().keySet()) {
            if (id.equals(r.heroClass().id())) continue;
            form.action(id, plugin.messages().raw("classes." + id + ".name"), plugin.messages().raw("classes." + id + ".description"));
        }
        form.columns(3).onSubmit((pl, answer) -> change(pl, answer.button())).open();
    }

    private long cooldownHours() {
        return plugin.getConfig().getLong("rpg.class-change-cooldown-hours", 24);
    }

    private void change(Player p, String to) {
        RpgPlayer r = online.get(p.getUniqueId());
        RpgConfig.Set set = config.get();
        UUID id = p.getUniqueId();
        if (r == null || set == null || !set.classes().containsKey(to) || to.equals(r.heroClass().id()) || !loading.add(id)) return;
        String from = r.heroClass().id();
        int level = r.level();
        double xp = r.xp();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String refused = null;
            RpgStore.Progress progress = null;
            try {
                RpgStore.Hero hero = store.hero(id);
                long wait = hero == null ? 0 : hero.changed() + cooldownHours() * 3_600_000L - System.currentTimeMillis();
                if (hero == null || !hero.heroClass().equals(from)) refused = "choice.changed-elsewhere";
                else if (wait > 0) refused = "choice.cooldown:" + (wait / 60_000 + 1);
                else {
                    store.save(id, from, level, xp);
                    if (!store.change(id, from, to)) refused = "choice.changed-elsewhere";
                    else progress = store.progress(id, to);
                }
            } catch (SQLException ex) {
                refused = "rpg.unavailable";
            }
            String why = refused;
            RpgStore.Progress pr = progress;
            Bukkit.getScheduler().runTask(plugin, () -> {
                loading.remove(id);
                if (!p.isOnline()) return;
                if (why != null) {
                    String[] parts = why.split(":");
                    plugin.messages().send(p, parts[0], "%minutes%", parts.length > 1 ? parts[1] : "");
                    return;
                }
                RpgPlayer now = online.get(id);
                if (now == null) return;
                now.heroClass(set.classes().get(to));
                now.level(pr.level());
                now.xp(pr.xp());
                recompute(p);
                combat.life(p, now.stats().get("max_health"));
                now.mana(now.stats().get("max_mana"));
                giveWeaponIfMissing(p, now);
                showLevel(p, now);
                plugin.messages().send(p, "choice.changed", "%class%", plugin.messages().raw("classes." + to + ".name"));
                Sounds.cue(p, "success");
            });
        });
    }

    private void giveWeaponIfMissing(Player p, RpgPlayer r) {
        Equipment eq = items.equipment();
        if (eq == null) return;
        for (ItemStack i : p.getInventory().getContents()) {
            Equipment.Item it = eq.item(RpgItems.idOf(i));
            if (it != null && it.slot().equals("weapon") && it.usableBy(r.heroClass().id())) return;
        }
        ItemStack weapon = items.make("class_" + r.heroClass().id() + "_weapon");
        if (weapon == null) return;
        PlayerInventory inv = p.getInventory();
        if (inv.getItem(0) == null || inv.getItem(0).isEmpty()) inv.setItem(0, weapon);
        else inv.addItem(weapon);
        plugin.messages().send(p, "choice.weapon");
    }

    // ------------------------------------------------------------------ stats

    /** What the player wears and holds, by equipment slot (rpg item ids). */
    private static Map<String, String> worn(Player p) {
        PlayerInventory inv = p.getInventory();
        Map<String, String> out = new LinkedHashMap<>();
        put(out, "weapon", inv.getItemInMainHand());
        put(out, "helmet", inv.getHelmet());
        put(out, "chestplate", inv.getChestplate());
        put(out, "leggings", inv.getLeggings());
        put(out, "boots", inv.getBoots());
        return out;
    }

    private static void put(Map<String, String> out, String slot, ItemStack item) {
        String id = RpgItems.idOf(item);
        if (id != null) out.put(slot, id);
    }

    /** Computes the player's stats again from every source; his share of life is kept. */
    public void recompute(Player p) {
        RpgPlayer r = online.get(p.getUniqueId());
        RpgConfig.Set set = config.get();
        if (r == null || set == null) return;
        double oldMax = r.stats() == null ? 0 : r.stats().get("max_health");
        double hp = combat.health(p);
        List<Modifier> mods = new ArrayList<>(r.heroClass().modifiers(r.level()));
        mods.addAll(set.equipment().modifiers(worn(p), r.heroClass().id(), r.level()));
        mods.addAll(r.buffModifiers(CombatEngine.now(), null));
        StatSheet sheet = StatSheet.compute(set.stats(), mods);
        r.stats(sheet);
        double max = sheet.get("max_health");
        if (oldMax > 0) combat.life(p, Math.min(max, hp * max / oldMax));
        AttributeInstance speed = p.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(SPEED);
            double bonus = sheet.get("movement_speed");
            if (bonus != 0) speed.addModifier(new AttributeModifier(SPEED, bonus, AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.ANY));
        }
        cadence(p, r);
    }

    /**
     * The weapon's pace as the server's own attack cadence (combat.AttackCadence) gives it to vanilla weapons: the
     * game's attack speed is the player's rpg attack speed (rpg items carry no attribute), the weapon in hand cannot
     * attack before it is recharged (spam clicks do nothing, the next click lands at full charge) and comes back slowly
     * to its place. The reach of a melee class is its basic attack's range.
     */
    private void cadence(Player p, RpgPlayer r) {
        double attacks = r.stats().get("attack_speed");
        set(p, Attribute.ATTACK_SPEED, ATTACK_SPEED, attacks);
        ClassDef.Attack basic = r.heroClass().basic();
        if (basic.melee()) set(p, Attribute.ENTITY_INTERACTION_RANGE, REACH, basic.range());
        else {
            AttributeInstance reach = p.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
            if (reach != null) reach.removeModifier(REACH);
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (RpgItems.idOf(hand) == null) return;
        int swing = (int) Math.max(3, Math.round(20 / Math.max(0.1, attacks) * SWING_SHARE));
        boolean changed = false;
        Float charge = hand.isDataOverridden(io.papermc.paper.datacomponent.DataComponentTypes.MINIMUM_ATTACK_CHARGE)
                ? hand.getData(io.papermc.paper.datacomponent.DataComponentTypes.MINIMUM_ATTACK_CHARGE) : null;
        if (charge == null || charge < 0.999f) {
            hand.setData(io.papermc.paper.datacomponent.DataComponentTypes.MINIMUM_ATTACK_CHARGE, 1f);
            changed = true;
        }
        var anim = hand.isDataOverridden(io.papermc.paper.datacomponent.DataComponentTypes.SWING_ANIMATION)
                ? hand.getData(io.papermc.paper.datacomponent.DataComponentTypes.SWING_ANIMATION) : null;
        if (anim == null || anim.duration() != swing) {
            hand.setData(io.papermc.paper.datacomponent.DataComponentTypes.SWING_ANIMATION,
                    io.papermc.paper.datacomponent.item.SwingAnimation.swingAnimation()
                            .type(io.papermc.paper.datacomponent.item.SwingAnimation.Animation.WHACK).duration(swing).build());
            changed = true;
        }
        if (changed) p.getInventory().setItemInMainHand(hand);
    }

    /** Sets an attribute to a total value through our own modifier (base untouched, removed when leaving). */
    private static void set(Player p, Attribute attribute, NamespacedKey key, double total) {
        AttributeInstance a = p.getAttribute(attribute);
        if (a == null) return;
        AttributeModifier current = a.getModifier(key);
        double amount = total - a.getBaseValue();
        if (current != null && Math.abs(current.getAmount() - amount) < 1e-6) return;
        if (current != null) a.removeModifier(key);
        if (Math.abs(amount) > 1e-6) a.addModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
    }

    private void recomputeSoon(Player p) {
        if (online.containsKey(p.getUniqueId())) Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) recompute(p);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        recomputeSoon(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) recomputeSoon(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p) recomputeSoon(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmor(com.destroystokyo.paper.event.player.PlayerArmorChangeEvent e) {
        recomputeSoon(e.getPlayer());
    }

    /**
     * Another pack just loaded (the server's own pack at join may replace every pack): the hud overlay goes back on top
     * for a player of the rpg universe.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPack(org.bukkit.event.player.PlayerResourcePackStatusEvent e) {
        if (e.getStatus() != org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) return;
        if (fr.influencraft.flux.api.client.ResourceOverlays.isOverlay(e.getID())) return;
        Player p = e.getPlayer();
        // once per connection: RPEngine sends its packs again whenever another one comes after them (no loop)
        if (RpgWorld.UNIVERSE.equals(Universes.of(p)) && overlaidAfterJoin.add(p.getUniqueId())) hud.show(p);
    }

    private final Set<UUID> overlaidAfterJoin = new HashSet<>();

    /** Back on his feet in the lobby, full life and mana. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        RpgPlayer r = online.get(p.getUniqueId());
        if (r == null || world == null || world.world() == null) return;
        if (e.getRespawnLocation().getWorld() != null && RpgWorld.UNIVERSE.equals(Universes.of(e.getRespawnLocation().getWorld()))
                && e.getRespawnLocation().getWorld() != world.world()) return;   // a dungeon decides
        e.setRespawnLocation(world.spawn());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || online.get(p.getUniqueId()) != r) return;
            combat.life(p, r.stats().get("max_health"));
            r.mana(r.stats().get("max_mana"));
        });
    }

    // ------------------------------------------------------------------ experience

    /** Gives rpg experience (monsters, dungeons); levels go up by the curve of balance.yml. */
    public void giveXp(Player p, double amount) {
        RpgPlayer r = online.get(p.getUniqueId());
        RpgConfig.Set set = config.get();
        if (r == null || set == null || amount <= 0) return;
        int max = set.balance().maxLevel();
        double xp = r.xp() + amount;
        int level = r.level();
        while (level < max && xp >= set.balance().xpToNext(level)) {
            xp -= set.balance().xpToNext(level);
            level++;
        }
        if (level >= max) xp = 0;
        boolean up = level > r.level();
        r.level(level);
        r.xp(xp);
        dirty.add(p.getUniqueId());
        if (up) {
            recompute(p);
            combat.life(p, r.stats().get("max_health"));
            r.mana(r.stats().get("max_mana"));
            plugin.messages().send(p, "rpg.level-up", "%level%", String.valueOf(level));
            Sounds.cue(p, "complete");
        }
        showLevel(p, r);
    }

    private void showLevel(Player p, RpgPlayer r) {
        RpgConfig.Set set = config.get();
        p.setLevel(r.level());
        double need = set == null ? 1 : set.balance().xpToNext(r.level());
        p.setExp((float) Math.max(0, Math.min(0.999, r.xp() / Math.max(1, need))));
    }

    // ------------------------------------------------------------------ every half second

    private void tick() {
        long now = CombatEngine.now();
        for (Player p : Bukkit.getOnlinePlayers()) {
            RpgPlayer r = online.get(p.getUniqueId());
            if (r == null || p.isDead()) continue;
            boolean[] ended = {false};
            r.buffModifiers(now, ended);
            if (ended[0]) recompute(p);
            StatSheet s = r.stats();
            r.mana(r.mana() + s.get("mana_regen") * 0.5);
            double regen = s.get("health_regen") * 0.5;
            boolean calm = now - r.lastHurt() > CALM_TICKS && now - r.lastAttack() > CALM_TICKS;
            if (calm) regen += s.get("max_health") * 0.05 * 0.5;
            if (regen > 0) combat.heal(p, regen, false);
            hud(p, r, now);
        }
    }

    /** Life and mana bars in place of the hearts and the food (text for Bedrock players). */
    private void hud(Player p, RpgPlayer r, long now) {
        p.sendActionBar(hud.render(p, combat.health(p), r.stats().get("max_health"), r.mana(), r.stats().get("max_mana")));
    }

    public void markDirty(Player p) {
        dirty.add(p.getUniqueId());
    }
}

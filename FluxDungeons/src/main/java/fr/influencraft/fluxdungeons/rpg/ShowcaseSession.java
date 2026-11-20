package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.client.Camera;
import fr.influencraft.flux.api.client.ChatHold;
import fr.influencraft.flux.api.client.Clients;
import fr.influencraft.flux.api.client.DropKey;
import fr.influencraft.flux.api.client.Hotbar;
import fr.influencraft.flux.api.client.Sidebar;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.fluxdungeons.rpg.core.ClassDef;
import fr.influencraft.fluxdungeons.rpg.core.StatSheet;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One player watching the classes ({@link Showcase}). Nothing is clicked with a pointer: his hotbar is the menu.
 * <ul>
 *   <li>the <b>camera</b> is himself, never another entity: a client seeing through another entity draws no hotbar
 *   (26.2 client, {@code Hud.extractItemHotbar}), and his real hotbar is the menu. He rides an invisible seat moved
 *   every tick around the middle of the fight (a swing from side to side, or a full turn: {@code rpg.showcase.camera});
 *   his client glides him from one place to the next. On Java his head is turned to the fight by the server: every
 *   tick, and at once whenever his mouse moves it away ({@link Camera#turns}). Not the camera of the boss scenes
 *   ({@link fr.influencraft.flux.api.client.CameraRig}), which hides the hotbar. A Bedrock player sits on a still seat
 *   and looks around freely;</li>
 *   <li>the <b>hotbar</b> is the menu ({@link Hotbar}: packets only, his real items never move): one class per slot
 *   from the first, then the way out in the first empty slot. When the classes no longer fit (more than eight), slots
 *   1 to 7 hold a page of classes, slot 8 an arrow to the next page and slot 9 the way out. The slot he holds (mouse
 *   wheel, number keys, a tap) is the class played in front of him. A click, left or right, chooses it, a second
 *   click confirms; a click on the way out (or sneaking) leaves;</li>
 *   <li>the <b>texts</b> float over the fight (text displays turned to him): the class, then the spell being cast and
 *   what it does;</li>
 *   <li>the <b>scene</b>: an actor with his skin (a mannequin made a stand-in of the class by
 *   {@link CombatEngine#standIn}) playing the script of the class on monsters.</li>
 * </ul>
 * Everything is removed when the show ends; nothing is saved with the world. Nothing is hidden from the other players
 * either: an arena has one viewer and stands far from everything.
 */
final class ShowcaseSession {

    /** Eyes of a seated player above the marker he rides. */
    private static final double SEATED_EYE = 1.02;
    private static final Map<String, String> ACCENTS = Map.of("cryomancer", "#74B6DC", "pyromancer", "#FF8A3C", "druid", "#73C25C");
    /** Ticks a first click waits for the click that confirms it. */
    private static final int CONFIRM_TICKS = 60;
    /** Classes shown at once when they do not all fit in the hotbar: slot 8 is then the arrow, slot 9 the way out. */
    private static final int PAGE = 7;
    /** Degrees his head may be off the fight before the server turns it back between two ticks. */
    private static final float SLACK = 0.75f;

    private final Showcase showcase;
    private final Player player;
    private final Showcase.Settings settings;
    private final int stage;
    /** Feet level in the middle of the arena. */
    private final Location origin;
    private final World world;
    /** The classes of the hotbar, one per slot from the first. */
    private final List<String> ids;
    /** steered: his head is turned to the fight by the server (Java with the packet adapter). */
    private final boolean bedrock, steered, orbit;
    /** The page of classes in his hotbar, when they do not all fit. */
    private int page;
    /** Where his head must look, read by the network thread that hears his mouse. */
    private volatile float aimYaw, aimPitch;
    private Location back;
    /** He came from outside the lobby (another universe, most often): the show brings him back there. */
    private final boolean away;
    /** Opened from the dungeon menu, which opens again when he leaves. */
    private final boolean menu;
    /** His choice is being recorded: the show ends once it is. */
    private boolean settling;
    private boolean closing, moving;
    private int ticks;
    private long lastClick;
    private int heldBefore = -1;
    private boolean madeInvisible;

    // camera
    private ArmorStand seat;
    /** The middle of the fight: what the camera turns around and looks at. */
    private final Location pivot;

    // texts over the fight
    private final List<TextDisplay> texts = new ArrayList<>();
    private TextDisplay title, subtitle, captionTitle, captionText;
    /** The class a first click asked for, until {@link #armedUntil}. */
    private String armed;
    private long armedUntil;
    /** True while he holds the way out. */
    private boolean leaving;

    // scene
    private String shown;
    private ClassDef cls;
    private RpgPlayer hero;
    private Mannequin actor;
    private final List<LivingEntity> monsters = new ArrayList<>();
    private List<String> script = List.of();
    private int step, repeated;
    private long waitUntil;

    ShowcaseSession(Showcase showcase, Player player, Showcase.Settings settings, int stage, Location origin,
                    Location back, boolean menu) {
        this.back = back;
        this.away = back != null;
        this.menu = menu;
        this.showcase = showcase;
        this.player = player;
        this.settings = settings;
        this.stage = stage;
        this.origin = origin;
        this.world = origin.getWorld();
        List<String> visible = RpgPlayers.visible(showcase.set());
        this.ids = List.copyOf(visible);
        this.bedrock = Clients.bedrock(player);
        this.steered = !bedrock && Camera.available();
        this.orbit = steered && settings.camArc() > 0 && settings.camPeriod() > 0;
        this.pivot = origin.clone().add(settings.lookX(), settings.lookY(), 0);
    }

    Player player() {
        return player;
    }

    int stage() {
        return stage;
    }

    Entity seat() {
        return seat;
    }

    boolean away() {
        return away;
    }

    boolean menu() {
        return menu;
    }

    void leaveWhenSettled() {
        settling = true;
    }

    boolean closing() {
        return closing;
    }

    /** True while the showcase itself teleports him (in, back out). */
    boolean moving() {
        return moving;
    }

    // ------------------------------------------------------------------ the hotbar's layout

    /** The classes do not all fit: a page of them, an arrow, the way out. */
    private boolean paged() {
        return ids.size() > 8;
    }

    private int pages() {
        return paged() ? (ids.size() + PAGE - 1) / PAGE : 1;
    }

    /** The classes in his hotbar now, from its first slot. */
    private List<String> onPage() {
        if (!paged()) return ids;
        int from = page * PAGE;
        return ids.subList(from, Math.min(ids.size(), from + PAGE));
    }

    /** The class of this slot, null when it holds none. */
    String classAt(int slot) {
        List<String> on = onPage();
        return slot >= 0 && slot < on.size() ? on.get(slot) : null;
    }

    /** The slot of the arrow to the next page, -1 when the classes all fit. */
    int arrowSlot() {
        return paged() ? PAGE : -1;
    }

    /**
     * The slot of the way out: the first one after the classes (the last one when they are paged), -1 while he has no
     * class yet (he must choose one first).
     */
    int leaveSlot() {
        if (showcase.players().get(player) == null) return -1;
        return paged() ? 8 : ids.size();
    }

    /** True when this slot of his hotbar holds something: a class, the arrow or the way out. */
    boolean usable(int slot) {
        return classAt(slot) != null || (slot >= 0 && (slot == arrowSlot() || slot == leaveSlot()));
    }

    /**
     * Where the wheel lands when it reaches an empty slot: the next slot holding something on its way ({@code next}
     * after {@code before}, turning the short way round the nine slots).
     */
    int landing(int before, int next) {
        int step = Math.floorMod(next - before, 9) <= 4 ? 1 : -1;
        for (int i = 1, slot = before; i <= 9; i++) {
            slot = Math.floorMod(slot + step, 9);
            if (usable(slot)) return slot;
        }
        return before;
    }

    /** The arrow: the next page of classes (the first again after the last), the first of them held. */
    void nextPage() {
        if (closing || !paged()) return;
        page = (page + 1) % pages();
        showHotbar();
        player.getInventory().setHeldItemSlot(0);
        hold(0);
    }

    // ================================================================== opening and closing

    void open() {
        if (back == null) back = player.getLocation();
        Location eye = eyeAt(0);
        Location feet = eye.clone().subtract(0, SEATED_EYE, 0);
        moving = true;
        try {
            player.teleport(feet, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            moving = false;
        }
        seat = world.spawn(feet, ArmorStand.class, a -> {
            a.addScoreboardTag(Showcase.TAG);
            a.setPersistent(false);
            a.setMarker(true);
            a.setInvisible(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            // no hearts of a mount over his hotbar
            AttributeInstance health = a.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) health.setBaseValue(1);
        });
        seat.addPassenger(player);
        title = floating(3.9, 2.2f, 400);
        subtitle = floating(3.55, 1.0f, 400);
        captionTitle = floating(2.75, 1.3f, 400);
        captionText = floating(2.05, 0.95f, 260);
        RpgPlayer own = showcase.players().get(player);
        int index = own == null ? 0 : Math.max(0, ids.indexOf(own.heroClass().id()));
        page = paged() ? index / PAGE : 0;
        int slot = paged() ? index % PAGE : index;
        heldBefore = player.getInventory().getHeldItemSlot();
        player.getInventory().setHeldItemSlot(slot);
        player.closeInventory();
        showHotbar();
        if (steered) {
            aim(eye);
            Camera.turn(player, aimYaw, false, aimPitch, false);
            // his mouse turned his head away from the fight: back at once, without waiting for the next tick
            Camera.turns(player, (p, yaw, pitch) -> {
                float y = aimYaw, x = aimPitch;
                if (Math.abs(wrap(yaw - y)) > SLACK || Math.abs(pitch - x) > SLACK) Camera.turn(p, y, false, x, false);
            });
        }
        if (!player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            // an invisible player's arm is not drawn in front of his view
            madeInvisible = true;
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }
        if (settings.hideBars()) Hotbar.bars(player, false);
        // his drop key would throw the real item of the slot he holds
        DropKey.claim(showcase.plugin(), player, () -> showcase.dropped(player));
        Sidebar.hide(player, true);
        ChatHold.hold(player, true);
        player.setPlayerTime(settings.time(), false);
        play(classAt(slot));
        hint();
    }

    void close(boolean bringBack) {
        closing = true;
        endScene();
        for (TextDisplay d : texts) if (d.isValid()) d.remove();
        texts.clear();
        if (player.getVehicle() == seat) player.leaveVehicle();
        if (seat != null && seat.isValid()) seat.remove();
        if (steered) Camera.turns(player, null);
        DropKey.release(player);
        Sidebar.hide(player, false);
        ChatHold.hold(player, false);
        if (madeInvisible) player.removePotionEffect(PotionEffectType.INVISIBILITY);
        madeInvisible = false;
        if (!player.isOnline()) return;
        Hotbar.bars(player, true);
        Hotbar.clear(player);
        if (heldBefore >= 0) player.getInventory().setHeldItemSlot(heldBefore);
        heldBefore = -1;
        player.resetPlayerTime();
        if (bringBack && back != null) {
            moving = true;
            try {
                player.teleport(back, PlayerTeleportEvent.TeleportCause.PLUGIN);
            } finally {
                moving = false;
            }
        }
    }

    /** He asked to leave (sneak). Without a class the choice comes first. */
    void leave() {
        if (showcase.players().get(player) == null) {
            showcase.plugin().messages().actionBar(player, "showcase.must-choose");
            armedUntil = ticks + CONFIRM_TICKS;
            return;
        }
        showcase.close(player, true);
    }

    // ================================================================== every tick

    void tick() {
        if (closing) return;
        if (!player.isOnline() || player.getWorld() != world) {
            showcase.close(player, false);
            return;
        }
        ticks++;
        if (settling && !showcase.players().busy(player)) {
            showcase.close(player, true);
            return;
        }
        if (player.getVehicle() != seat) seat.addPassenger(player);
        if (orbit) {
            Location eye = eyeAt(ticks);
            // the server teleports the rider with his seat (a teleport event of his): ours, not a way out of the show
            moving = true;
            try {
                seat.teleport(eye.clone().subtract(0, SEATED_EYE, 0), TeleportFlag.EntityState.RETAIN_PASSENGERS);
            } finally {
                moving = false;
            }
            aim(eye);
        }
        // his head to the middle of the fight, whatever his mouse did since the last tick
        if (steered) Camera.turn(player, aimYaw, false, aimPitch, false);
        // a client told it plays in creative can fill its hotbar by itself: ours again from time to time
        if (ticks % 40 == 0) showHotbar();
        if (ticks % 30 == 0 && ticks > armedUntil) hint();
        scene();
    }

    // ================================================================== camera

    /**
     * Where his eyes are after {@code t} ticks, looking at the middle of the fight. The camera starts north of it
     * (the actor on the left of the screen, the monsters on the right) and turns clockwise seen from above: a swing
     * of {@code arc} degrees from side to side, or turn after turn from 360.
     */
    private Location eyeAt(int t) {
        double angle = 0;
        if (orbit) {
            double phase = 2 * Math.PI * t / settings.camPeriod();
            angle = settings.camArc() >= 360 ? phase : Math.toRadians(settings.camArc() / 2) * Math.sin(phase);
        }
        Location eye = origin.clone().add(settings.lookX() + settings.camDistance() * Math.sin(angle), settings.camHeight(),
                -settings.camDistance() * Math.cos(angle));
        eye.setDirection(pivot.toVector().subtract(eye.toVector()));
        return eye;
    }

    private void aim(Location eye) {
        aimYaw = eye.getYaw();
        aimPitch = eye.getPitch();
    }

    private static float wrap(float degrees) {
        float d = degrees % 360;
        return d > 180 ? d - 360 : d < -180 ? d + 360 : d;
    }

    // ================================================================== hotbar

    private void showHotbar() {
        Hotbar.show(player, hotbarItems());
    }

    /** What his hotbar holds: a class per slot from the first, the arrow when they are paged, then the way out. */
    private ItemStack[] hotbarItems() {
        RpgPlayer own = showcase.players().get(player);
        ItemStack[] items = new ItemStack[9];
        List<String> on = onPage();
        for (int i = 0; i < on.size(); i++) items[i] = icon(on.get(i), own != null && own.heroClass().id().equals(on.get(i)));
        if (arrowSlot() >= 0) {
            ItemStack next = Items.dress(new ItemStack(Material.ARROW), raw("showcase.next-name")
                    .replace("%page%", String.valueOf(page + 1)).replace("%pages%", String.valueOf(pages())), null, player);
            if (!bedrock && settings.hideHeld()) next.setData(DataComponentTypes.ITEM_MODEL, Key.key("fluxdungeons", "showcase_next"));
            items[arrowSlot()] = next;
        }
        if (leaveSlot() >= 0) {
            ItemStack leave = Items.dress(new ItemStack(Material.BARRIER), raw("showcase.leave-name"), null, player);
            if (!bedrock && settings.hideHeld()) leave.setData(DataComponentTypes.ITEM_MODEL, Key.key("fluxdungeons", "showcase_leave"));
            items[leaveSlot()] = leave;
        }
        return items;
    }

    /**
     * The item standing for a class in his hotbar: its weapon, named after the class. On a Java client its model is
     * {@code fluxdungeons:class_<id>} of the pack (assets/classes/gen_class_icons.py): the same picture in the hotbar,
     * nothing in the hand, so the held class never hides the fight.
     */
    private ItemStack icon(String id, boolean mine) {
        ItemStack weapon = weapon(id);
        ItemStack item = weapon == null ? new ItemStack(Material.NETHER_STAR) : weapon.clone();
        String name = raw("showcase.card-name").replace("%color%", "&" + accent(id)).replace("%name%", raw("classes." + id + ".name"))
                + (mine ? raw("showcase.icon-yours") : "");
        Lore lore = Lore.create().description(raw("classes." + id + ".description"));
        if (!mine) lore.left(raw("showcase.icon-choose"));
        Items.dress(item, name, lore.build(), player);
        if (!bedrock && settings.hideHeld()) item.setData(DataComponentTypes.ITEM_MODEL, Key.key("fluxdungeons", "class_" + id));
        return item;
    }

    /** He holds another slot of his hotbar: a class, which plays, or the way out. */
    void hold(int slot) {
        if (closing) return;
        armed = null;
        armedUntil = 0;
        leaving = slot >= 0 && slot == leaveSlot();
        String id = classAt(slot);
        if (id != null && !id.equals(shown)) play(id);
        Sounds.cue(player, "page");
        hint();
    }

    /**
     * A right click: the details of the class he holds (its weapon and spells), in a menu over the scene, which goes on
     * behind; its back button closes it. On the way out it leaves, as a left click.
     */
    void details() {
        if (closing || settling || ticks - lastClick < 4) return;
        lastClick = ticks;
        if (leaving) {
            showcase.close(player, true);
            return;
        }
        if (shown != null && showcase.classMenu().openDetails(player, shown, "[close]")) Sounds.cue(player, "page");
    }

    /** A left click: the class he holds is chosen, once a second click confirmed it. */
    void click() {
        if (closing || settling || ticks - lastClick < 4 || shown == null) return;
        lastClick = ticks;
        if (leaving) {
            showcase.close(player, true);
            return;
        }
        RpgPlayer own = showcase.players().get(player);
        if (own != null && own.heroClass().id().equals(shown)) {
            Sounds.cue(player, "error");
            showcase.plugin().messages().actionBar(player, "showcase.owned");
            armedUntil = ticks + 40;
            return;
        }
        if (!shown.equals(armed) || ticks > armedUntil) {
            armed = shown;
            armedUntil = ticks + CONFIRM_TICKS;
            Sounds.cue(player, "confirm");
            showcase.plugin().messages().actionBar(player, bar("showcase.confirm"), "%color%", "&" + accent(shown), "%name%", raw("classes." + shown + ".name"));
            return;
        }
        showcase.chosen(this, shown);
    }

    /** How the menu is used, kept over his hotbar. */
    private void hint() {
        showcase.plugin().messages().actionBar(player, bar(leaving ? "showcase.leave-hint" : "showcase.hint"));
    }

    /** An action bar text: a Bedrock client shows no glyph of the pack and has no mouse, it gets its own wording. */
    private String bar(String key) {
        return bedrock ? key + "-bedrock" : key;
    }

    // ================================================================== texts over the fight

    /** A text floating {@code height} blocks over the middle of the fight, turned to whoever looks at it. */
    private TextDisplay floating(double height, float scale, int lineWidth) {
        float s = (float) (scale * settings.textScale());
        TextDisplay display = world.spawn(pivot.clone().add(0, height * settings.textScale(), 0), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setShadowed(true);
            d.setLineWidth(lineWidth);
            d.setBackgroundColor(Color.fromARGB(0));
            d.text(Component.empty());
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s), new Quaternionf()));
        });
        texts.add(display);
        return display;
    }

    /** The class being played: its name, and its role (or that it is his). */
    private void header() {
        if (title == null || !title.isValid() || !subtitle.isValid() || shown == null) return;
        RpgPlayer own = showcase.players().get(player);
        title.text(text("showcase.card-name", "%color%", "&" + accent(shown), "%name%", raw("classes." + shown + ".name")));
        boolean mine = own != null && own.heroClass().id().equals(shown);
        subtitle.text(mine ? text("showcase.yours") : text("showcase.card-role", "%role%", role(shown)));
    }

    /** The spell being cast: its key and name, and one sentence saying what it does. */
    private void caption(String slot) {
        if (captionTitle == null || !captionTitle.isValid() || !captionText.isValid()) return;
        int shade = settings.captionShade();
        if (slot == null || cls == null) {
            captionTitle.text(Component.empty());
            captionText.text(Component.empty());
            captionTitle.setBackgroundColor(Color.fromARGB(0));
            captionText.setBackgroundColor(Color.fromARGB(0));
            return;
        }
        String id = cls.id();
        captionTitle.text(text("showcase.caption", "%key%", keyName(cls, slot), "%name%", raw("class-pages.classes." + id + "." + slot + ".name")));
        captionTitle.setBackgroundColor(Color.fromARGB(shade));
        String line = raw("showcase.lines." + id + "." + slot);
        captionText.text(line.isEmpty() ? Component.empty() : text("showcase.caption-text", "%text%", line));
        captionText.setBackgroundColor(Color.fromARGB(line.isEmpty() ? 0 : shade));
    }

    /** What the class is for, as tags (damage, control, healing...); the sentence of the class pages when it has none. */
    private String role(String classId) {
        String tags = raw("showcase.roles." + classId);
        return tags.isEmpty() || tags.equals("showcase.roles." + classId) ? raw("class-pages.classes." + classId + ".role") : tags;
    }

    private ItemStack weapon(String classId) {
        return showcase.items().make(RpgItems.starterId(classId));
    }

    // ================================================================== scene

    /** Starts the show of a class: the former actor and monsters go, a new actor takes its place. */
    private void play(String id) {
        RpgConfig.Set set = showcase.set();
        ClassDef c = set == null ? null : set.classes().get(id);
        if (c == null) return;
        endScene();
        shown = id;
        cls = c;
        Location home = home();
        actor = world.spawn(home, Mannequin.class, CreatureSpawnEvent.SpawnReason.CUSTOM, m -> {
            m.addScoreboardTag(Showcase.TAG);
            m.setPersistent(false);
            m.setInvulnerable(true);
            m.setCollidable(false);
            m.setSilent(true);
            m.setDescription(null);
            m.setProfile(ResolvableProfile.resolvableProfile(player.getPlayerProfile()));
            ItemStack weapon = weapon(id);
            if (weapon != null) m.getEquipment().setItemInMainHand(weapon);
        });
        hero = new RpgPlayer(actor.getUniqueId(), c, settings.level(), 0);
        hero.stats(StatSheet.compute(set.stats(), c.modifiers(settings.level())));
        hero.mana(hero.stats().get("max_mana"));
        showcase.combat().standIn(actor, hero);
        world.spawnParticle(Particle.POOF, home.clone().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
        ConfigurationSection scripts = settings.scripts();
        List<String> own = scripts == null ? List.of() : scripts.getStringList(id);
        script = !own.isEmpty() ? own : scripts == null ? List.of() : scripts.getStringList("default");
        step = 0;
        repeated = 0;
        waitUntil = ticks + 1;
        header();
        caption(null);
    }

    private void endScene() {
        if (actor != null) {
            showcase.combat().dismiss(actor);
            if (actor.isValid()) actor.remove();
        }
        actor = null;
        hero = null;
        clearMonsters();
    }

    private void clearMonsters() {
        for (LivingEntity m : monsters) {
            if (!m.isValid()) continue;
            world.spawnParticle(Particle.POOF, m.getLocation().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.02);
            m.remove();
        }
        monsters.clear();
    }

    /** Where the actor starts: on the left of the scene, looking at the monsters on its right. */
    private Location home() {
        Location l = origin.clone().add(settings.actorX(), 0, settings.actorZ());
        l.setYaw(90);
        l.setPitch(0);
        return l;
    }

    private void spawnMonsters(int count) {
        clearMonsters();
        EntityType type;
        try {
            type = EntityType.valueOf(settings.monster().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            type = EntityType.HUSK;
        }
        Class<? extends Entity> entityClass = type.getEntityClass();
        if (entityClass == null || !LivingEntity.class.isAssignableFrom(entityClass)) entityClass = EntityType.HUSK.getEntityClass();
        List<double[]> spots = settings.monsters();
        for (int i = 0; i < Math.min(count, spots.size()); i++) {
            Location at = origin.clone().add(spots.get(i)[0], 0, spots.get(i)[1]);
            at.setYaw(-90);
            Entity e = world.spawn(at, entityClass, CreatureSpawnEvent.SpawnReason.CUSTOM, m -> {
                m.addScoreboardTag(Showcase.TAG);
                m.setPersistent(false);
                if (m instanceof Ageable a) a.setAdult();
                if (m instanceof Mob mob) {
                    // unaware: it neither walks nor attacks, but is still pushed, thrown and rooted by the spells
                    mob.setAware(false);
                    mob.setRemoveWhenFarAway(false);
                    mob.setCanPickupItems(false);
                    if (mob.getEquipment() != null) mob.getEquipment().clear();
                }
            });
            if (e instanceof LivingEntity le) {
                monsters.add(le);
                world.spawnParticle(Particle.POOF, at.clone().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.02);
            } else e.remove();
        }
    }

    private LivingEntity nearestMonster() {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity m : monsters) {
            if (!m.isValid() || m.isDead()) continue;
            double d = m.getLocation().distanceSquared(actor.getLocation());
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    /** The actor turns to the nearest monster (new ones come when none is left) and does one thing. */
    private void act(String slot) {
        LivingEntity target = nearestMonster();
        if (target == null) {
            spawnMonsters(settings.monsters().size());
            target = nearestMonster();
        }
        if (target != null) {
            Location from = actor.getEyeLocation();
            Vector to = target.getBoundingBox().getCenter().subtract(from.toVector());
            if (to.lengthSquared() > 1e-4) {
                from.setDirection(to);
                actor.setRotation(from.getYaw(), from.getPitch());
                actor.setBodyYaw(from.getYaw());
            }
        }
        actor.swingMainHand();
        showcase.skills().demo(actor, hero, slot);
    }

    /** One step of the class's script when its time has come (see {@code rpg.showcase.scripts} of the config). */
    private void scene() {
        if (shown == null) return;
        if (actor == null || !actor.isValid()) {
            play(shown);
            return;
        }
        monsters.removeIf(m -> !m.isValid() || m.isDead());
        // thrown out of the arena by one of his own spells: back to his place
        Location at = actor.getLocation();
        if (at.getY() < origin.getY() - 3 || at.distanceSquared(origin) > 20 * 20) {
            actor.teleport(home());
            actor.setVelocity(new Vector());
        }
        if (ticks < waitUntil) return;
        if (script.isEmpty()) return;
        if (step >= script.size()) {
            // the show starts again: new monsters, the actor back to his place
            step = 0;
            clearMonsters();
            actor.teleport(home());
            actor.setVelocity(new Vector());
            caption(null);
            waitUntil = ticks + 2;
            return;
        }
        String[] a = script.get(step).trim().toLowerCase(Locale.ROOT).split("\\s+");
        switch (a[0]) {
            case "monsters" -> {
                spawnMonsters(integer(a, 1, settings.monsters().size()));
                step++;
            }
            case "wait" -> {
                waitUntil = ticks + integer(a, 1, 20);
                step++;
            }
            case "home" -> {
                actor.teleport(home());
                actor.setVelocity(new Vector());
                step++;
            }
            case "basic" -> {
                if (repeated == 0) caption("basic");
                act("basic");
                waitUntil = ticks + integer(a, 2, 10);
                if (++repeated >= integer(a, 1, 1)) {
                    repeated = 0;
                    step++;
                }
            }
            case "cast" -> {
                String slot = a.length > 1 ? a[1] : "a";
                if (cls.skills().get(slot) != null) {
                    caption(slot);
                    act(slot);
                }
                waitUntil = ticks + 10;
                step++;
            }
            default -> step++;
        }
    }

    // ================================================================== texts, colours, numbers

    private Component text(String key, String... vars) {
        return showcase.plugin().messages().get(key, player, vars);
    }

    private String raw(String key) {
        return showcase.plugin().messages().raw(key);
    }

    /** The name of the key a slot is cast with, as the class pages write it. */
    private String keyName(ClassDef c, String slot) {
        String key = slot.equals("basic") ? (c.basicOnRight() ? "hold" : "left") : c.key(slot);
        return raw("class-pages.keys." + key);
    }

    /** The class's colour ({@code rpg.class-colors} of the config), as {@code #RRGGBB}. */
    private String accent(String classId) {
        String c = showcase.plugin().getConfig().getString("rpg.class-colors." + classId);
        return c != null && c.matches("#[0-9A-Fa-f]{6}") ? c : ACCENTS.getOrDefault(classId, "#B376E5");
    }

    private static int integer(String[] parts, int index, int fallback) {
        try {
            return parts.length > index ? Math.max(1, Integer.parseInt(parts[index])) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

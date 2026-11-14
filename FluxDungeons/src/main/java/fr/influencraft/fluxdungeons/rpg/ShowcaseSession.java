package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.client.Camera;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.fluxdungeons.rpg.core.ClassDef;
import fr.influencraft.fluxdungeons.rpg.core.StatSheet;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One player watching the classes ({@link Showcase}). What he sees:
 * <ul>
 *   <li>the arena through a fixed <b>camera</b> (an invisible mannequin, {@link Camera#view}); he sits on an invisible
 *   seat, as a client whose camera is elsewhere only sends its mouse while riding;</li>
 *   <li>the <b>panel</b>: text displays one block in front of the camera, all at the same place and turned to the
 *   screen by their billboard, each put at its spot by its own translation (x to the right, y up, in blocks at that
 *   distance: the screen is about 2.5 wide and 1.4 high at the default field of view). Rectangles are text displays
 *   holding one space, stretched: their background is the rectangle;</li>
 *   <li>the <b>pointer</b>: a text display moved as his head turns ({@link Camera#turns}, {@link Camera#place});</li>
 *   <li>the <b>scene</b>: an actor with his skin (a mannequin made a stand-in of the class by
 *   {@link CombatEngine#standIn}) playing the script of the class on monsters, the spell being cast named under it.</li>
 * </ul>
 * Everything is removed when the show ends; nothing is saved with the world.
 */
final class ShowcaseSession {

    private static final double EYE = 1.62;
    /** One pixel of text at scale 1, in blocks. */
    private static final float PX = 0.025f;
    private static final NamespacedKey MODIFIER = new NamespacedKey("fluxdungeons", "showcase");
    private static final Map<String, String> ACCENTS = Map.of("cryomancer", "#74B6DC", "pyromancer", "#FF8A3C", "druid", "#73C25C");
    /** Middle and width of the panel, in screen units. */
    private static final double PANEL_X = -0.80, PANEL_W = 0.80, ROW_W = 0.72;

    /** A place of the panel the pointer acts on. */
    private record Zone(String id, double x0, double y0, double x1, double y1, TextDisplay rect, int idle, int hover) {
        boolean has(double x, double y) {
            return x >= x0 && x <= x1 && y >= y0 && y <= y1;
        }
    }

    private final Showcase showcase;
    private final Player player;
    private final Showcase.Settings settings;
    private final int stage;
    /** Feet level in the middle of the arena. */
    private final Location origin;
    private final World world;
    private Location back;
    private boolean closing, moving;
    private int ticks;
    private long lastClick;

    // camera and pointer
    private Mannequin camera;
    private ArmorStand seat;
    private TextDisplay pointer;
    private Location eye, anchor;
    private final Vector forward, up;
    private final double depth;
    private volatile double sx = PANEL_X, sy = 0.1;
    private float lastYaw, lastPitch;
    private boolean primed, recentring;

    // panel
    private final List<Entity> ui = new ArrayList<>();
    private final List<Zone> zones = new ArrayList<>();
    private final Map<String, TextDisplay> spellRows = new HashMap<>();
    private TextDisplay captionTitle, captionText;
    /** The class whose page is open, null on the list. */
    private String detail;
    private String hovered;
    private long hoveredSince;
    private String activeSlot;

    // scene
    private String shown;
    private ClassDef cls;
    private RpgPlayer hero;
    private Mannequin actor;
    private final List<LivingEntity> monsters = new ArrayList<>();
    private List<String> script = List.of();
    private int step, repeated;
    private long waitUntil;

    ShowcaseSession(Showcase showcase, Player player, Showcase.Settings settings, int stage, Location origin) {
        this.showcase = showcase;
        this.player = player;
        this.settings = settings;
        this.stage = stage;
        this.origin = origin;
        this.world = origin.getWorld();
        double pitch = Math.toRadians(settings.camPitch());
        // the camera looks south (yaw 0), tilted down: its right is west
        forward = new Vector(0, -Math.sin(pitch), Math.cos(pitch));
        up = new Vector(0, Math.cos(pitch), Math.sin(pitch));
        depth = Math.max(0.5, number("distance", 1.0));
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

    boolean closing() {
        return closing;
    }

    /** True while the showcase itself teleports him (in, back out). */
    boolean moving() {
        return moving;
    }

    // ================================================================== opening and closing

    void open() {
        back = player.getLocation();
        eye = origin.clone().add(settings.camX(), settings.camY(), settings.camZ());
        eye.setYaw(0);
        eye.setPitch(settings.camPitch());
        Location feet = eye.clone().subtract(0, EYE, 0);
        anchor = eye.clone().add(forward.clone().multiply(depth));
        anchor.setYaw(0);
        anchor.setPitch(0);
        moving = true;
        try {
            player.teleport(feet, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            moving = false;
        }
        camera = world.spawn(feet, Mannequin.class, CreatureSpawnEvent.SpawnReason.CUSTOM, m -> {
            m.addScoreboardTag(Showcase.TAG);
            m.setPersistent(false);
            m.setVisibleByDefault(false);
            m.setInvisible(true);
            m.setInvulnerable(true);
            m.setSilent(true);
            m.setGravity(false);
            m.setCollidable(false);
            m.setImmovable(true);
            m.setDescription(null);
            m.setRotation(0, settings.camPitch());
            // F5 must not move the view: no third person distance on the camera
            Attribute distance = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("camera_distance"));
            AttributeInstance a = distance == null ? null : m.getAttribute(distance);
            if (a != null) a.addTransientModifier(new AttributeModifier(MODIFIER, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
        });
        Location seatAt = feet.clone();
        seatAt.setPitch(0);
        seat = world.spawn(seatAt, ArmorStand.class, a -> {
            a.addScoreboardTag(Showcase.TAG);
            a.setPersistent(false);
            a.setVisibleByDefault(false);
            a.setMarker(true);
            a.setInvisible(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setBodyYaw(0);
        });
        pointer = world.spawn(pointerAt(), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.text(text("showcase.pointer"));
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(0));
            d.setShadowed(true);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTeleportDuration(1);
            float s = (float) (number("pointer-scale", 0.16) * depth);
            // a text stands on its place: lowered by half a glyph, the pointer's middle is the pointed spot
            d.setTransformation(new Transformation(new Vector3f(0, -4.5f * PX * s, 0), new Quaternionf(), new Vector3f(s), new Quaternionf()));
        });
        for (Entity e : List.of(camera, seat, pointer)) player.showEntity(showcase.plugin(), e);
        seat.addPassenger(player);
        player.setPlayerTime(settings.time(), false);
        Camera.view(player, camera);
        Camera.turns(player, (p, yaw, pitch) -> turned(yaw, pitch));
        RpgPlayer own = showcase.players().get(player);
        List<String> ids = RpgPlayers.visible(showcase.set());
        buildList();
        play(own != null && ids.contains(own.heroClass().id()) ? own.heroClass().id() : ids.get(0));
    }

    void close(boolean bringBack) {
        closing = true;
        Camera.turns(player, null);
        endScene();
        clearPanel();
        if (player.getVehicle() == seat) player.leaveVehicle();
        for (Entity e : new Entity[]{pointer, camera, seat}) if (e != null && e.isValid()) e.remove();
        if (!player.isOnline()) return;
        Camera.view(player, null);
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
        if (player.getVehicle() != seat) seat.addPassenger(player);
        // the camera only reaches his client once the arena's chunks and entities did (he was just taken far away, how
        // long that takes depends on his connection): said again all along, a camera already set changes nothing
        if (ticks == 3 || ticks % 10 == 0) Camera.view(player, camera);
        if (pointer.isValid()) pointer.teleport(pointerAt());
        hover();
        scene();
    }

    // ================================================================== pointer

    private Location pointerAt() {
        // a little nearer than the panel (drawn over it), brought back in so that it covers the same spot
        double d = depth - 0.03, k = d / depth;
        return eye.clone().add(forward.clone().multiply(d)).add(-sx * depth * k, 0, 0).add(up.clone().multiply(sy * depth * k));
    }

    /**
     * His mouse turned his (unseen) head: the pointer follows, at once (network thread). The head is brought back to
     * the middle before it reaches the end of its travel (a seated player turns at most 85 degrees from his seat).
     */
    private synchronized void turned(float yaw, float pitch) {
        if (closing) return;
        if (recentring) {
            if (Math.abs(pitch) > 20 || Math.abs(wrap(yaw)) > 20) return;
            recentring = false;
            primed = false;
        }
        if (!primed) {
            lastYaw = yaw;
            lastPitch = pitch;
            primed = true;
            return;
        }
        float dyaw = wrap(yaw - lastYaw), dpitch = pitch - lastPitch;
        lastYaw = yaw;
        lastPitch = pitch;
        double speed = settings.pointerSpeed();
        sx = clamp(sx + dyaw * speed, 1.22);
        sy = clamp(sy - dpitch * speed, 0.68);
        if (Math.abs(pitch) > 50 || Math.abs(wrap(yaw)) > 55) {
            recentring = true;
            Camera.turn(player, 0f, false, 0f, false);
        }
        Location at = pointerAt();
        Camera.place(player, pointer, at.getX(), at.getY(), at.getZ());
    }

    private void hover() {
        String now = null;
        double x = sx, y = sy;
        for (Zone z : zones) {
            if (z.has(x, y)) {
                now = z.id();
                break;
            }
        }
        if (!java.util.Objects.equals(now, hovered)) {
            for (Zone z : zones) {
                if (z.rect() == null || !z.rect().isValid()) continue;
                if (z.id().equals(hovered)) z.rect().setBackgroundColor(Color.fromARGB(z.idle()));
                if (z.id().equals(now)) z.rect().setBackgroundColor(Color.fromARGB(z.hover()));
            }
            hovered = now;
            hoveredSince = ticks;
            if (now != null) Sounds.cue(player, "page");
        }
        // the pointer resting on a class: its show starts
        if (hovered != null && hovered.startsWith("class:") && ticks - hoveredSince >= settings.hoverTicks()) {
            String id = hovered.substring(6);
            if (!id.equals(shown)) play(id);
        }
    }

    void click() {
        if (closing || ticks - lastClick < 4 || hovered == null) return;
        lastClick = ticks;
        if (hovered.startsWith("class:")) {
            String id = hovered.substring(6);
            Sounds.cue(player, "click");
            if (!id.equals(shown)) play(id);
            buildDetail(id);
        } else if (hovered.equals("back")) {
            Sounds.cue(player, "click");
            buildList();
        } else if (hovered.equals("choose") && detail != null) {
            RpgPlayer own = showcase.players().get(player);
            if (own != null && own.heroClass().id().equals(detail)) {
                Sounds.cue(player, "error");
                return;
            }
            String id = detail;
            showcase.close(player, true);
            showcase.players().pick(player, id);
        }
    }

    // ================================================================== panel

    private void clearPanel() {
        for (Entity e : ui) if (e.isValid()) e.remove();
        ui.clear();
        zones.clear();
        spellRows.clear();
        captionTitle = null;
        captionText = null;
        hovered = null;
    }

    /** What both views share: the band on the left and, under the scene, the spell being cast. */
    private void buildCommon() {
        clearPanel();
        rect(PANEL_X, 0, PANEL_W, 1.7, color("background", 0xD9120826), 0);
        rect(PANEL_X + PANEL_W / 2, 0, 0.008, 1.7, color("edge", 0xFFF0A800), 0.0005);
        int shade = color("caption", 0x99000000);
        captionTitle = label(Component.empty(), 0.42, -0.52, 0.002, 0.17f, 400, shade);
        captionText = label(Component.empty(), 0.42, -0.60, 0.002, 0.095f, 280, shade);
        caption(activeSlot);
    }

    /** The list: one card per class; pointing at one plays it, a click opens it. */
    private void buildList() {
        detail = null;
        buildCommon();
        RpgPlayer own = showcase.players().get(player);
        List<String> ids = RpgPlayers.visible(showcase.set());
        label(text(own == null ? "showcase.title" : "showcase.title-change"), PANEL_X, 0.50, 0.002, 0.2f, 400, 0);
        label(text("showcase.hint"), PANEL_X, 0.425, 0.002, 0.1f, 210, 0);
        double stepY = Math.min(0.29, 0.86 / ids.size()), h = stepY - 0.04;
        int card = color("card", 0xB00D0D0D);
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            double cy = 0.25 - i * stepY;
            String accent = accent(id);
            TextDisplay r = rect(PANEL_X, cy, ROW_W, h, card, 0.001);
            zones.add(new Zone("class:" + id, PANEL_X - ROW_W / 2, cy - h / 2, PANEL_X + ROW_W / 2, cy + h / 2, r, card, color("card-hover", 0xD05B2D9E)));
            icon(weapon(id), PANEL_X - 0.265, cy, 0.003, 0.15f);
            label(text("showcase.card-name", "%color%", "&" + accent, "%name%", raw("classes." + id + ".name")), PANEL_X + 0.07, cy + 0.005, 0.002, 0.23f, 400, 0);
            label(text("showcase.card-role", "%role%", raw("class-pages.classes." + id + ".role")), PANEL_X + 0.07, cy - 0.075, 0.002, 0.095f, 170, 0);
            if (own != null && own.heroClass().id().equals(id)) label(text("showcase.yours"), PANEL_X + 0.07, cy - 0.11, 0.002, 0.08f, 300, 0);
        }
        label(text(own == null ? "showcase.footer-first" : "showcase.footer"), PANEL_X, -0.645, 0.002, 0.085f, 320, 0);
    }

    /** One class: what it is, its four spells (the one being cast is lit), the button that chooses it. */
    private void buildDetail(String id) {
        detail = id;
        buildCommon();
        ClassDef c = showcase.set().classes().get(id);
        RpgPlayer own = showcase.players().get(player);
        String accent = accent(id);
        int card = color("card", 0xB00D0D0D);
        TextDisplay backRect = rect(PANEL_X, 0.585, ROW_W, 0.085, card, 0.001);
        zones.add(new Zone("back", PANEL_X - ROW_W / 2, 0.5425, PANEL_X + ROW_W / 2, 0.6275, backRect, card, color("card-hover", 0xD05B2D9E)));
        label(text("showcase.back"), PANEL_X, 0.563, 0.002, 0.095f, 300, 0);
        icon(weapon(id), PANEL_X, 0.445, 0.003, 0.14f);
        label(text("showcase.card-name", "%color%", "&" + accent, "%name%", raw("classes." + id + ".name")), PANEL_X, 0.29, 0.002, 0.25f, 400, 0);
        label(text("showcase.card-role", "%role%", raw("class-pages.classes." + id + ".role")), PANEL_X, 0.235, 0.002, 0.09f, 230, 0);
        label(text("showcase.description", "%text%", raw("classes." + id + ".description")), PANEL_X, 0.065, 0.002, 0.09f, 190, 0);
        List<String> slots = new ArrayList<>(List.of("basic"));
        for (String slot : ClassDef.SLOTS) if (c.skills().get(slot) != null) slots.add(slot);
        for (int j = 0; j < slots.size(); j++) {
            String slot = slots.get(j);
            double cy = -0.03 - j * 0.105;
            spellRows.put(slot, rect(PANEL_X, cy, ROW_W, 0.09, card, 0.001));
            label(text("showcase.spell", "%key%", keyName(c, slot), "%name%", raw("class-pages.classes." + id + "." + slot + ".name")),
                    PANEL_X, cy - 0.02, 0.002, 0.105f, 400, 0);
        }
        boolean mine = own != null && own.heroClass().id().equals(id);
        int idle = mine ? card : color("choose", 0xD02A9D5F), hover = mine ? card : color("choose-hover", 0xFF2ECF7B);
        TextDisplay choose = rect(PANEL_X, -0.49, ROW_W, 0.11, idle, 0.001);
        zones.add(new Zone("choose", PANEL_X - ROW_W / 2, -0.545, PANEL_X + ROW_W / 2, -0.435, choose, idle, hover));
        label(text(mine ? "showcase.owned" : "showcase.choose", "%name%", raw("classes." + id + ".name")), PANEL_X, -0.517, 0.002, 0.11f, 400, 0);
        if (!mine) {
            label(text(own == null ? "showcase.note-first" : "showcase.note-change", "%hours%", String.valueOf(showcase.players().cooldownHours())),
                    PANEL_X, -0.65, 0.002, 0.075f, 230, 0);
        }
        lightRow();
    }

    /** The spell being cast, named under the scene; its row lit on the class's page. */
    private void caption(String slot) {
        activeSlot = slot;
        if (captionTitle == null || !captionTitle.isValid() || !captionText.isValid()) return;
        int shade = color("caption", 0x99000000);
        if (slot == null || cls == null) {
            captionTitle.text(Component.empty());
            captionText.text(Component.empty());
            captionTitle.setBackgroundColor(Color.fromARGB(0));
            captionText.setBackgroundColor(Color.fromARGB(0));
        } else {
            String id = cls.id();
            captionTitle.text(text("showcase.caption", "%key%", keyName(cls, slot), "%name%", raw("class-pages.classes." + id + "." + slot + ".name")));
            captionTitle.setBackgroundColor(Color.fromARGB(shade));
            String line = raw("showcase.lines." + id + "." + slot);
            captionText.text(line.isEmpty() ? Component.empty() : text("showcase.caption-text", "%text%", line));
            captionText.setBackgroundColor(Color.fromARGB(line.isEmpty() ? 0 : shade));
        }
        lightRow();
    }

    private void lightRow() {
        int card = color("card", 0xB00D0D0D);
        for (Map.Entry<String, TextDisplay> e : spellRows.entrySet()) {
            if (!e.getValue().isValid()) continue;
            boolean on = e.getKey().equals(activeSlot) && detail != null && detail.equals(shown);
            e.getValue().setBackgroundColor(Color.fromARGB(on ? tint(accent(detail), 0x90) : card));
        }
    }

    // ------------------------------------------------------------------ pieces of the panel

    /** A text standing on (x, y), centred. {@code background}: 0 for none. */
    private TextDisplay label(Component text, double x, double y, double z, float scale, int lineWidth, int background) {
        float s = (float) (scale * depth);
        return display(TextDisplay.class, d -> {
            d.text(text);
            d.setLineWidth(lineWidth);
            d.setShadowed(true);
            d.setBackgroundColor(Color.fromARGB(background));
            d.setTransformation(new Transformation(new Vector3f((float) (x * depth), (float) (y * depth), (float) z), new Quaternionf(), new Vector3f(s), new Quaternionf()));
        });
    }

    /**
     * A flat rectangle centred on (cx, cy): the background of a text display holding one space (5 pixels wide, 11 high,
     * from 2 pixels left of the display to 3 right of it, standing on it), stretched to the size asked.
     */
    private TextDisplay rect(double cx, double cy, double w, double h, int argb, double z) {
        float fx = (float) (w * depth / (5 * PX)), fy = (float) (h * depth / (11 * PX));
        return display(TextDisplay.class, d -> {
            d.text(Component.text(" "));
            d.setShadowed(false);
            d.setBackgroundColor(Color.fromARGB(argb));
            d.setTransformation(new Transformation(new Vector3f((float) (cx * depth) - 0.5f * PX * fx, (float) ((cy - h / 2) * depth), (float) z),
                    new Quaternionf(), new Vector3f(fx, fy, 1), new Quaternionf()));
        });
    }

    private void icon(ItemStack item, double x, double y, double z, float size) {
        if (item == null) return;
        float s = (float) (size * depth);
        display(ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
            d.setTransformation(new Transformation(new Vector3f((float) (x * depth), (float) (y * depth), (float) z), new Quaternionf(), new Vector3f(s, s, 0.01f), new Quaternionf()));
        });
    }

    private <T extends Display> T display(Class<T> type, java.util.function.Consumer<T> setup) {
        T d = world.spawn(anchor, type, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setBillboard(Display.Billboard.CENTER);
            e.setBrightness(new Display.Brightness(15, 15));
            setup.accept(e);
        });
        player.showEntity(showcase.plugin(), d);
        ui.add(d);
        return d;
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
        waitUntil = ticks + 8;
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
            waitUntil = ticks + 12;
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

    private static int tint(String rgb, int alpha) {
        return alpha << 24 | Integer.parseInt(rgb.substring(1), 16);
    }

    /** A colour of the panel ({@code rpg.showcase.panel.<name>}, eight hex digits: opacity then colour). */
    private int color(String name, int fallback) {
        ConfigurationSection p = settings.panel();
        String v = p == null ? null : p.getString(name);
        try {
            return v == null ? fallback : (int) Long.parseLong(v.replace("#", ""), 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double number(String name, double fallback) {
        ConfigurationSection p = settings.panel();
        return p == null ? fallback : p.getDouble(name, fallback);
    }

    private static int integer(String[] parts, int index, int fallback) {
        try {
            return parts.length > index ? Math.max(1, Integer.parseInt(parts[index])) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float wrap(float a) {
        a %= 360;
        if (a >= 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }

    private static double clamp(double v, double max) {
        return Math.max(-max, Math.min(max, v));
    }
}

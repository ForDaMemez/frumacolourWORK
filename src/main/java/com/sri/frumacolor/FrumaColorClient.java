package com.sri.frumacolor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.Entity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FrumaColorClient implements ClientModInitializer {

    /** Saved to config/frumacolor.json */
    public static class Config {
        public Map<String, Integer> colors = new HashMap<>();   // particle id -> 0xRRGGBB
        public Map<String, Integer> textures = new HashMap<>(); // sprite-name keyword -> 0xRRGGBB
        public int allColor = -1;                                // -1 = off, otherwise tint every particle (test mode)
        public boolean shift = true;                             // true = keep the multicolor look, pushed toward the target hue
        public int spread = 25;                                  // shift mode: how many degrees of hue variety to keep
    }

    /** Menu presets. Edit these hex values to change the colors. */
    public static final String[] PRESET_NAMES = { "Deep Crimson", "Blue", "Cyan", "Yellow", "Orange", "Green" };
    public static final int[] PRESET_COLORS = { 0x8A0C20, 0x1E5BFF, 0x00E5FF, 0xFFE600, 0xFF7A00, 0x22DD44 };

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("frumacolor.json");
    public static Config config = new Config();

    private static boolean logging = false;
    private static boolean openMenu = false;
    private static final Set<String> seen = new HashSet<>();
    private static final Set<String> SPRITES = ConcurrentHashMap.newKeySet();

    // images already recolored (so each one is only tinted once)
    private static final Set<NativeImage> DONE =
        Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    // diagnostics, reset each time the mod triggers a reload
    private static final AtomicInteger SEEN_SINCE_RELOAD = new AtomicInteger();
    private static final AtomicInteger TINTED_SINCE_RELOAD = new AtomicInteger();
    private static final ConcurrentLinkedQueue<String> TINTED_SAMPLE = new ConcurrentLinkedQueue<>();

    // /fcolor watch state
    private static boolean watching = false;
    private static long watchEnd = 0;
    private static FabricClientCommandSource watchSrc = null;
    private static final Set<String> baseline = new HashSet<>();
    private static final Map<String, String> watchFound = new LinkedHashMap<>(); // texture -> first model number
    private static List<String> lastFound = new ArrayList<>();
    private static final Map<String, List<String>> NUM_CACHE = new HashMap<>();

    private interface Hit { void on(String texture, String number); }

    @Override
    public void onInitializeClient() {
        load();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // open the menu once the chat screen has closed
            if (openMenu && client.screen == null) {
                openMenu = false;
                client.setScreen(new ColorMenuScreen());
            }
            if (!watching) return;
            try {
                sampleItemDisplays(watchSrc, (t, n) -> { if (!baseline.contains(t)) watchFound.putIfAbsent(t, n); });
            } catch (Exception ignored) { }
            if (System.currentTimeMillis() >= watchEnd) finishWatch();
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("fcolor")
                // /fcolor menu : opens the preset menu
                .then(ClientCommandManager.literal("menu")
                    .executes(ctx -> {
                        openMenu = true;
                        return 1;
                    }))
                // /fcolor preset <name> : applies a preset color to every texture rule (crimson, blue, cyan, yellow, orange, green)
                .then(ClientCommandManager.literal("preset")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word()).executes(ctx -> {
                        String arg = StringArgumentType.getString(ctx, "name").toLowerCase();
                        for (int i = 0; i < PRESET_NAMES.length; i++) {
                            if (PRESET_NAMES[i].toLowerCase().replace(" ", "").contains(arg)) {
                                applyPreset(PRESET_COLORS[i], PRESET_NAMES[i]);
                                return 1;
                            }
                        }
                        msg(ctx.getSource(), "Unknown preset. Use: crimson, blue, cyan, yellow, orange, green");
                        return 0;
                    })))
                // /fcolor add end_rod FF00AA
                .then(ClientCommandManager.literal("add")
                    .then(ClientCommandManager.argument("particle", StringArgumentType.word())
                        .then(ClientCommandManager.argument("hex", StringArgumentType.word())
                            .executes(ctx -> {
                                String id = normalize(StringArgumentType.getString(ctx, "particle"));
                                Integer rgb = parseHex(StringArgumentType.getString(ctx, "hex"));
                                if (rgb == null) { msg(ctx.getSource(), "Bad hex color. Use e.g. FF00AA"); return 0; }
                                config.colors.put(id, rgb);
                                save();
                                msg(ctx.getSource(), "Set " + id + " to #" + String.format("%06X", rgb));
                                return 1;
                            }))))
                // /fcolor remove end_rod
                .then(ClientCommandManager.literal("remove")
                    .then(ClientCommandManager.argument("particle", StringArgumentType.word())
                        .executes(ctx -> {
                            String id = normalize(StringArgumentType.getString(ctx, "particle"));
                            config.colors.remove(id);
                            save();
                            msg(ctx.getSource(), "Removed " + id);
                            return 1;
                        })))
                // /fcolor clear : wipes every saved color and reloads textures
                .then(ClientCommandManager.literal("clear")
                    .executes(ctx -> {
                        config.colors.clear();
                        config.textures.clear();
                        config.allColor = -1;
                        save();
                        reload();
                        msg(ctx.getSource(), "Cleared everything. Reloading textures...");
                        return 1;
                    }))
                // /fcolor watch [seconds] : records every item display texture that appears near you, then lists the new ones
                .then(ClientCommandManager.literal("watch")
                    .executes(ctx -> startWatch(ctx.getSource(), 20))
                    .then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(1, 120))
                        .executes(ctx -> startWatch(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "seconds")))))
                // /fcolor watchapply <hex> : recolors every texture the last watch found
                .then(ClientCommandManager.literal("watchapply")
                    .then(ClientCommandManager.argument("hex", StringArgumentType.word()).executes(ctx -> {
                        Integer rgb = parseHex(StringArgumentType.getString(ctx, "hex"));
                        if (rgb == null) { msg(ctx.getSource(), "Bad hex color."); return 0; }
                        if (lastFound.isEmpty()) { msg(ctx.getSource(), "Nothing found yet. Run /fcolor watch first."); return 0; }
                        for (String k : lastFound) config.textures.put(k, rgb);
                        save(); reload();
                        msg(ctx.getSource(), "Recolored " + lastFound.size() + " texture(s) to #" + String.format("%06X", rgb) + ". Reloading...");
                        return 1;
                    })))
                // /fcolor mode shift|solid : shift keeps the multicolor look, solid paints everything one color
                .then(ClientCommandManager.literal("mode")
                    .then(ClientCommandManager.literal("shift").executes(ctx -> {
                        setShift(true);
                        msg(ctx.getSource(), "Mode: shift (keeps the multicolor look). Reloading...");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("solid").executes(ctx -> {
                        setShift(false);
                        msg(ctx.getSource(), "Mode: solid (one flat color). Reloading...");
                        return 1;
                    })))
                // /fcolor spread <0-180> : in shift mode, how much color variety is kept (bigger = more variety)
                .then(ClientCommandManager.literal("spread")
                    .then(ClientCommandManager.argument("degrees", IntegerArgumentType.integer(0, 180)).executes(ctx -> {
                        config.spread = IntegerArgumentType.getInteger(ctx, "degrees");
                        save(); reload();
                        msg(ctx.getSource(), "Spread set to " + config.spread + ". Reloading...");
                        return 1;
                    })))
                // /fcolor debug : what happened during the last reload
                .then(ClientCommandManager.literal("debug")
                    .executes(ctx -> {
                        msg(ctx.getSource(), "Rules saved: " + config.textures.size()
                            + " | mode: " + (config.shift ? "shift" : "solid") + " spread " + config.spread
                            + " | textures created since last mod reload: " + SEEN_SINCE_RELOAD.get()
                            + " | images recolored at upload: " + TINTED_SINCE_RELOAD.get()
                            + " | total names ever seen: " + SPRITES.size());
                        for (String s : TINTED_SAMPLE) msg(ctx.getSource(), "recolored: " + s);
                        return 1;
                    }))
                // /fcolor list
                .then(ClientCommandManager.literal("list")
                    .executes(ctx -> {
                        if (config.colors.isEmpty() && config.textures.isEmpty()) msg(ctx.getSource(), "Nothing set.");
                        config.colors.forEach((k, v) -> msg(ctx.getSource(), k + " -> #" + String.format("%06X", v)));
                        config.textures.forEach((k, v) -> msg(ctx.getSource(), "texture '" + k + "' -> #" + String.format("%06X", v)));
                        if (config.allColor >= 0) msg(ctx.getSource(), "ALL particles -> #" + String.format("%06X", config.allColor));
                        return 1;
                    }))
                // /fcolor all FF00FF   or   /fcolor all off   (test mode: tints every particle)
                .then(ClientCommandManager.literal("all")
                    .then(ClientCommandManager.literal("off").executes(ctx -> {
                        config.allColor = -1; save();
                        msg(ctx.getSource(), "Test mode off");
                        return 1;
                    }))
                    .then(ClientCommandManager.argument("hex", StringArgumentType.word()).executes(ctx -> {
                        Integer rgb = parseHex(StringArgumentType.getString(ctx, "hex"));
                        if (rgb == null) { msg(ctx.getSource(), "Bad hex color."); return 0; }
                        config.allColor = rgb; save();
                        msg(ctx.getSource(), "Every particle is now #" + String.format("%06X", rgb));
                        return 1;
                    })))
                // /fcolor log on|off : prints each new particle type spawned near you once
                .then(ClientCommandManager.literal("log")
                    .then(ClientCommandManager.literal("on").executes(ctx -> {
                        logging = true; seen.clear();
                        msg(ctx.getSource(), "Logging on. Use Fruma's M1 and watch chat.");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("off").executes(ctx -> {
                        logging = false;
                        msg(ctx.getSource(), "Logging off");
                        return 1;
                    })))
                // /fcolor tex <keyword> <hex|off> : recolor every texture whose name contains <keyword>, then reload
                .then(ClientCommandManager.literal("tex")
                    .then(ClientCommandManager.argument("keyword", StringArgumentType.word())
                        .then(ClientCommandManager.literal("off").executes(ctx -> {
                            config.textures.remove(StringArgumentType.getString(ctx, "keyword").toLowerCase());
                            save(); reload();
                            msg(ctx.getSource(), "Removed. Reloading textures...");
                            return 1;
                        }))
                        .then(ClientCommandManager.argument("hex", StringArgumentType.word()).executes(ctx -> {
                            Integer rgb = parseHex(StringArgumentType.getString(ctx, "hex"));
                            if (rgb == null) { msg(ctx.getSource(), "Bad hex color."); return 0; }
                            String kw = StringArgumentType.getString(ctx, "keyword").toLowerCase();
                            config.textures.put(kw, rgb);
                            save(); reload();
                            msg(ctx.getSource(), "Textures matching '" + kw + "' -> #" + String.format("%06X", rgb) + ". Reloading...");
                            return 1;
                        }))))
                // /fcolor model <number> [hex] : look up ONE oak_boat custom model number, show its textures, optionally recolor
                .then(ClientCommandManager.literal("model")
                    .then(ClientCommandManager.argument("number", StringArgumentType.word())
                        .executes(ctx -> {
                            List<String> kws = resolve(ctx.getSource(), "oak_boat", StringArgumentType.getString(ctx, "number"), false);
                            for (String k : kws) msg(ctx.getSource(), "texture: " + k);
                            if (!kws.isEmpty()) msg(ctx.getSource(), "Add a hex color to the command to recolor these.");
                            return 1;
                        })
                        .then(ClientCommandManager.argument("hex", StringArgumentType.word()).executes(ctx -> {
                            Integer rgb = parseHex(StringArgumentType.getString(ctx, "hex"));
                            if (rgb == null) { msg(ctx.getSource(), "Bad hex color."); return 0; }
                            List<String> kws = resolve(ctx.getSource(), "oak_boat", StringArgumentType.getString(ctx, "number"), false);
                            if (kws.isEmpty()) return 0;
                            for (String k : kws) {
                                config.textures.put(k, rgb);
                                msg(ctx.getSource(), "recoloring '" + k + "' -> #" + String.format("%06X", rgb)
                                    + "  (undo: /fcolor tex " + k + " off)");
                            }
                            save(); reload();
                            return 1;
                        }))))
                // /fcolor models <from> <to> [hex] : the same for a whole range of numbers, e.g. 40424 to 40434
                .then(ClientCommandManager.literal("models")
                    .then(ClientCommandManager.argument("from", IntegerArgumentType.integer(0))
                        .then(ClientCommandManager.argument("to", IntegerArgumentType.integer(0))
                            .executes(ctx -> rangeCmd(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "from"),
                                IntegerArgumentType.getInteger(ctx, "to"), null))
                            .then(ClientCommandManager.argument("hex", StringArgumentType.word())
                                .executes(ctx -> {
                                    Integer rgb = parseHex(StringArgumentType.getString(ctx, "hex"));
                                    if (rgb == null) { msg(ctx.getSource(), "Bad hex color."); return 0; }
                                    return rangeCmd(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "from"),
                                        IntegerArgumentType.getInteger(ctx, "to"), rgb);
                                })))))
                // /fcolor find <keyword> : list loaded texture names containing the keyword
                .then(ClientCommandManager.literal("find")
                    .then(ClientCommandManager.argument("keyword", StringArgumentType.word()).executes(ctx -> {
                        String kw = StringArgumentType.getString(ctx, "keyword").toLowerCase();
                        int n = 0;
                        for (String name : SPRITES) {
                            if (name.toLowerCase().contains(kw)) {
                                msg(ctx.getSource(), name);
                                if (++n >= 40) { msg(ctx.getSource(), "...more not shown, use a longer keyword"); break; }
                            }
                        }
                        if (n == 0) msg(ctx.getSource(), "No loaded textures match '" + kw + "'.");
                        return 1;
                    })))
                // /fcolor entities : lists entities within 10 blocks; for item displays also prints the textures each one uses
                .then(ClientCommandManager.literal("entities")
                    .executes(ctx -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.level == null || mc.player == null) return 0;
                        int n = 0;
                        for (Entity e : mc.level.entitiesForRendering()) {
                            if (e == mc.player || e.distanceTo(mc.player) > 10) continue;
                            String type = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
                            if (type.equals("minecraft:item_display")) {
                                var stack = e.getSlot(0).get();
                                String full = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
                                String itemPath = full.substring(full.indexOf(':') + 1);
                                String patch = String.valueOf(stack.getComponentsPatch());
                                Matcher fm = Pattern.compile("floats=\\[([0-9]+)").matcher(patch);
                                if (itemPath.equals("air")) {
                                    msg(ctx.getSource(), "item_display: empty");
                                } else if (fm.find()) {
                                    String num = fm.group(1);
                                    List<String> t = resolve(ctx.getSource(), itemPath, num, true);
                                    msg(ctx.getSource(), "item_display: " + itemPath + " #" + num + " -> textures " + t);
                                } else {
                                    msg(ctx.getSource(), "item_display: " + itemPath + " " + patch);
                                }
                            } else {
                                msg(ctx.getSource(), type + " | name=\"" + e.getName().getString() + "\"");
                            }
                            if (++n >= 80) break;
                        }
                        return 1;
                    })));
        });
    }

    /** Used by the menu and /fcolor preset: gives every texture rule the same color, then reloads. */
    public static void applyPreset(int rgb, String name) {
        if (config.textures.isEmpty()) {
            chat("No effect rules yet. Set the effects up first (/fcolor watch, then /fcolor watchapply B0102A).");
            return;
        }
        int n = config.textures.size();
        for (Map.Entry<String, Integer> e : config.textures.entrySet()) e.setValue(rgb);
        save();
        reload();
        chat(name + " applied to " + n + " texture rule(s). Reloading...");
    }

    public static void setShift(boolean shift) {
        config.shift = shift;
        save();
        reload();
    }

    /** Starts /fcolor watch: remembers what is around now, then records anything new for the given time. */
    private static int startWatch(FabricClientCommandSource src, int seconds) {
        baseline.clear();
        watchFound.clear();
        watchSrc = src;
        try {
            sampleItemDisplays(src, (t, n) -> baseline.add(t));
        } catch (Exception ignored) { }
        watchEnd = System.currentTimeMillis() + seconds * 1000L;
        watching = true;
        msg(src, "Watching for " + seconds + " seconds. Place the totem and trigger the effects NOW.");
        return 1;
    }

    private static void finishWatch() {
        watching = false;
        lastFound = new ArrayList<>(watchFound.keySet());
        chat("Watch finished. " + lastFound.size() + " new texture(s) appeared:");
        int shown = 0;
        for (Map.Entry<String, String> e : watchFound.entrySet()) {
            chat("  " + e.getKey() + "   (model #" + e.getValue() + ")");
            if (++shown >= 40) { chat("  ...and more"); break; }
        }
        if (!lastFound.isEmpty()) chat("Recolor them all with: /fcolor watchapply B0102A");
    }

    /** Looks at every item display near the player and reports each texture it uses. */
    private static void sampleItemDisplays(FabricClientCommandSource src, Hit hit) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || e.distanceTo(mc.player) > 12) continue;
            String type = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
            if (!type.equals("minecraft:item_display")) continue;
            var stack = e.getSlot(0).get();
            String full = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            String itemPath = full.substring(full.indexOf(':') + 1);
            if (itemPath.equals("air")) continue;
            Matcher fm = Pattern.compile("floats=\\[([0-9]+)").matcher(String.valueOf(stack.getComponentsPatch()));
            if (!fm.find()) continue;
            String num = fm.group(1);
            String key = itemPath + "#" + num;
            List<String> t = NUM_CACHE.get(key);
            if (t == null) {
                t = resolve(src, itemPath, num, true);
                NUM_CACHE.put(key, t);
            }
            for (String tex : t) hit.on(tex, num);
        }
    }

    private static void chat(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[fcolor] " + text), false);
    }

    /** Called from the particle mixin every time a particle is created. */
    public static void onParticle(ParticleOptions options, Particle particle, double x, double y, double z) {
        String id = String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType()));

        if (logging) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.distanceToSqr(x, y, z) < 100 && seen.add(id)) {
                mc.player.displayClientMessage(Component.literal("[fcolor] particle: " + id), false);
            }
        }

        Integer rgb = config.allColor >= 0 ? Integer.valueOf(config.allColor) : config.colors.get(id);
        if (rgb == null) return;

        if (particle instanceof SingleQuadParticle quad) {
            quad.setColor(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f);
        }
    }

    private static void reload() {
        SEEN_SINCE_RELOAD.set(0);
        TINTED_SINCE_RELOAD.set(0);
        TINTED_SAMPLE.clear();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(mc::reloadResourcePacks);
    }

    /** Called when a texture is created: only records its name. */
    public static void onSprite(String name, NativeImage img) {
        SEEN_SINCE_RELOAD.incrementAndGet();
        if (SPRITES.size() < 200000) SPRITES.add(name);
    }

    /** Called right before a texture is uploaded to the GPU: applies the recolor to every image it will draw from. */
    public static void onUpload(String name, NativeImage[] images) {
        if (config.textures.isEmpty() || images == null) return;
        String lower = name.toLowerCase();
        Integer rgb = null;
        for (Map.Entry<String, Integer> e : config.textures.entrySet()) {
            if (lower.contains(e.getKey())) { rgb = e.getValue(); break; }
        }
        if (rgb == null) return;
        for (NativeImage img : images) {
            if (img == null || !DONE.add(img)) continue; // each image is only tinted once
            tint(img, rgb);
            TINTED_SINCE_RELOAD.incrementAndGet();
            if (TINTED_SAMPLE.size() < 8 && !TINTED_SAMPLE.contains(name)) TINTED_SAMPLE.add(name);
        }
    }

    /** Recolors an image. Pixels are read and written as ARGB (this fixes the old red/blue swap). */
    private static void tint(NativeImage img, int rgb) {
        int tr = (rgb >> 16) & 0xFF, tg = (rgb >> 8) & 0xFF, tb = rgb & 0xFF;
        float targetHue = rgbToHsv(tr, tg, tb)[0];
        float spread = config.spread;
        boolean shift = config.shift;

        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getPixel(x, y);
                int a = (p >>> 24) & 0xFF;
                if (a == 0) continue;
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                int out;
                if (shift) {
                    float[] hsv = rgbToHsv(r, g, b);
                    if (hsv[1] < 0.12f) {
                        out = p & 0xFFFFFF; // grays and whites stay as they are
                    } else {
                        // squeeze the original hue range into a band around the target hue, keeping the order of colors
                        float h = targetHue + ((hsv[0] - 180f) / 180f) * spread;
                        if (h < 0) h += 360f;
                        if (h >= 360f) h -= 360f;
                        float s = Math.min(1f, 0.55f + 0.45f * hsv[1]);
                        out = hsvToRgb(h, s, hsv[2]);
                    }
                } else {
                    float v = Math.max(r, Math.max(g, b)) / 255f; // flat color, keeps brightness
                    out = (((int) (tr * v)) << 16) | (((int) (tg * v)) << 8) | ((int) (tb * v));
                }
                img.setPixel(x, y, (a << 24) | (out & 0xFFFFFF));
            }
        }
    }

    private static float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float d = max - min;
        float h;
        if (d == 0) h = 0;
        else if (max == rf) h = 60f * (((gf - bf) / d) % 6f);
        else if (max == gf) h = 60f * (((bf - rf) / d) + 2f);
        else h = 60f * (((rf - gf) / d) + 4f);
        if (h < 0) h += 360f;
        float s = max == 0 ? 0 : d / max;
        return new float[] { h, s, max };
    }

    private static int hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float x = c * (1f - Math.abs(((h / 60f) % 2f) - 1f));
        float m = v - c;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        int ri = Math.round((r + m) * 255f);
        int gi = Math.round((g + m) * 255f);
        int bi = Math.round((b + m) * 255f);
        return (ri << 16) | (gi << 8) | bi;
    }

    /** Handles /fcolor models: resolves every number in the range, then optionally recolors the unique textures. */
    private static int rangeCmd(FabricClientCommandSource src, int from, int to, Integer rgb) {
        if (to < from || to - from > 200) {
            msg(src, "Use a low number then a high number, at most 200 apart.");
            return 0;
        }
        Set<String> kws = new LinkedHashSet<>();
        int missing = 0;
        for (int n = from; n <= to; n++) {
            List<String> k = resolve(src, "oak_boat", String.valueOf(n), true);
            if (k.isEmpty()) missing++; else kws.addAll(k);
        }
        msg(src, "Numbers " + from + "-" + to + ": " + kws.size() + " unique texture(s), "
            + missing + " number(s) with none found.");
        int shown = 0;
        for (String k : kws) {
            msg(src, "texture: " + k);
            if (++shown >= 30) { msg(src, "...and more"); break; }
        }
        if (rgb == null) {
            if (!kws.isEmpty()) msg(src, "Add a hex color to recolor these.");
            return 1;
        }
        if (kws.isEmpty()) return 0;
        for (String k : kws) config.textures.put(k, rgb);
        save();
        reload();
        msg(src, "Recolored to #" + String.format("%06X", rgb) + ". Reloading textures... (undo all: /fcolor clear)");
        return 1;
    }

    private static String readText(Resource r) {
        try (java.io.InputStream in = r.open()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void say(FabricClientCommandSource src, boolean quiet, String text) {
        if (!quiet) msg(src, text);
    }

    /** Turns a custom model number on the given item (e.g. oak_boat) into the texture names its model uses. */
    private static List<String> resolve(FabricClientCommandSource src, String itemName, String numberRaw, boolean quiet) {
        List<String> out = new ArrayList<>();
        String number = numberRaw.replaceAll("\\.0+$", "");
        var rm = Minecraft.getInstance().getResourceManager();
        String model = null;

        // 1) modern item definition: assets/<ns>/items/<item>.json (range_dispatch with thresholds)
        for (var en : rm.listResources("items", p -> p.getPath().endsWith("/" + itemName + ".json")).entrySet()) {
            String text = readText(en.getValue());
            Matcher m = Pattern.compile("\"threshold\"\\s*:\\s*" + number
                + "(?![0-9])(?:\\.0+)?[\\s\\S]*?\"model\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
            if (m.find()) {
                model = m.group(1);
                say(src, quiet, "found " + number + " in " + en.getKey() + " -> model " + model);
                break;
            }
        }

        // 2) older style: overrides inside models/item/<item>.json
        if (model == null) {
            for (var en : rm.listResources("models", p -> p.getPath().endsWith("item/" + itemName + ".json")).entrySet()) {
                String text = readText(en.getValue());
                Matcher m = Pattern.compile("\"custom_model_data\"\\s*:\\s*" + number
                    + "(?![0-9])[\\s\\S]*?\"model\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
                if (m.find()) {
                    model = m.group(1);
                    say(src, quiet, "found " + number + " in " + en.getKey() + " -> model " + model);
                    break;
                }
            }
        }

        if (model == null) {
            say(src, quiet, "Could not find " + number + " in any " + itemName + " file. Send me this message.");
            return out;
        }

        // 3) read the model file and pull out its textures
        String ns = "minecraft", path = model;
        int c = model.indexOf(':');
        if (c >= 0) { ns = model.substring(0, c); path = model.substring(c + 1); }
        String target = ns + ":models/" + path + ".json";

        String modelText = null;
        for (var en : rm.listResources("models", p -> p.getPath().endsWith(".json")).entrySet()) {
            if (String.valueOf(en.getKey()).equals(target)) {
                modelText = readText(en.getValue());
                break;
            }
        }
        if (modelText == null) {
            say(src, quiet, "Model file not found: " + target + ". Send me this message.");
            return out;
        }

        Matcher tb = Pattern.compile("\"textures\"\\s*:\\s*\\{([^}]*)\\}").matcher(modelText);
        if (!tb.find()) {
            say(src, quiet, "Model " + target + " has no textures block (it probably uses a parent model). Send me this message.");
            return out;
        }
        Matcher tv = Pattern.compile("\"[^\"]+\"\\s*:\\s*\"([^\"]+)\"").matcher(tb.group(1));
        while (tv.find()) {
            String t = tv.group(1);
            if (t.startsWith("#")) continue;
            int cc = t.indexOf(':');
            if (cc >= 0) t = t.substring(cc + 1);
            t = t.substring(t.lastIndexOf('/') + 1).toLowerCase(); // keep only the file name
            if (t.equals("empty") || t.isEmpty()) continue;         // skip the blank placeholder
            if (!out.contains(t)) out.add(t);
        }
        if (out.isEmpty()) say(src, quiet, "Model had no usable textures. Send me this message.");
        return out;
    }

    private static String normalize(String s) {
        s = s.toLowerCase();
        return s.contains(":") ? s : "minecraft:" + s;
    }

    private static Integer parseHex(String s) {
        try {
            s = s.startsWith("#") ? s.substring(1) : s;
            if (s.length() != 6) return null;
            return Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void msg(FabricClientCommandSource src, String text) {
        src.sendFeedback(Component.literal("[fcolor] " + text));
    }

    private static void load() {
        try {
            if (Files.exists(FILE)) config = GSON.fromJson(Files.readString(FILE), Config.class);
        } catch (Exception ignored) { }
        if (config == null) config = new Config();
        if (config.textures == null) config.textures = new HashMap<>();
    }

    private static void save() {
        try { Files.writeString(FILE, GSON.toJson(config)); } catch (Exception ignored) { }
    }
}

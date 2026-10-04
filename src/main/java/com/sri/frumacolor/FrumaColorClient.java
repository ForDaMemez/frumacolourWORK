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

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("frumacolor.json");
    public static Config config = new Config();

    private static boolean logging = false;
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
            if (!watching) return;
            try {
                sampleItemDisplays(watchSrc, (t, n) -> { if (!baseline.contains(t)) watchFound.putIfAbsent(t, n); });
            } catch (Exception ignored) { }
            if (System.currentTimeMillis() >= watchEnd) finishWatch();
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("fcolor")
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
                        config.shift = true; save(); reload();
                        msg(ctx.getSource(), "Mode: shift (keeps the multicolor look). Reloading...");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("solid").executes(ctx -> {
                        config.shift = false; save(); reload();
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

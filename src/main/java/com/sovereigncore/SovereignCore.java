package com.sovereigncore;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Mod(SovereignCore.MOD_ID)
public final class SovereignCore {
    public static final String MOD_ID = "sovereigncore";
    public static final String VERSION = "1.3.0-forge-1.20.1";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern SAFE_KEY = Pattern.compile("[a-zA-Z0-9_-]{1,32}");
    private static final Pattern SAFE_PLAYER = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern SAFE_ITEM = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final int MAX_AUDIT_ENTRIES = 250;

    private static MinecraftServer server;
    private static Path dataFile;
    private static final Map<UUID, LinkedHashSet<String>> PLAYER_RANKS = new HashMap<>();
    private static final Map<UUID, String> KNOWN_NAMES = new HashMap<>();
    private static final Map<UUID, Integer> HOME_LIMIT_OVERRIDES = new HashMap<>();
    private static final Map<UUID, Integer> WARP_LIMIT_OVERRIDES = new HashMap<>();
    private static final Map<UUID, Integer> TPA_LIMIT_OVERRIDES = new HashMap<>();
    private static final Map<UUID, Integer> TPA_COOLDOWN_OVERRIDES = new HashMap<>();
    private static final Map<UUID, Map<String, SavedPos>> HOMES = new HashMap<>();
    private static final Map<String, SavedPos> PUBLIC_WARPS = new LinkedHashMap<>();
    private static final Map<UUID, Map<String, SavedPos>> PERSONAL_WARPS = new HashMap<>();
    private static final Map<String, RankConfig> ROLE_CONFIGS = new LinkedHashMap<>();
    private static final Map<UUID, SavedPos> FROZEN_PLAYERS = new HashMap<>();
    private static final Map<UUID, TeleportRequest> TPA_REQUESTS = new HashMap<>();
    private static final Map<UUID, Long> LAST_TPA_TIME = new HashMap<>();
    private static final Map<UUID, List<Long>> TPA_HISTORY = new HashMap<>();
    private static final Map<String, String> MOD_BAN_REASONS = new LinkedHashMap<>();
    private static final List<String> AUDIT_LOG = new ArrayList<>();
    private static SavedPos spawn;

    private static final List<String> PERMISSIONS = List.of(
            "players.view", "players.teleport", "players.bring", "players.rank", "players.limits",
            "players.inventory.view", "players.inventory.edit", "players.enderchest.view", "players.enderchest.edit",
            "players.heal", "players.feed", "players.gamemode", "players.fly", "players.god", "players.freeze",
            "players.kick", "players.ban", "players.unban", "players.moderate",
            "ranks.manage", "ranks.delete", "ranks.edit.owner",
            "homes.use", "homes.set", "homes.manage", "warps.use", "warps.manage", "warps.personal",
            "teleport.use", "teleport.coords", "tpa.use", "tpa.manage", "spawn.use", "spawn.manage",
            "items.give", "logs.view", "settings.manage"
    );

    public SovereignCore() {
        NetworkHandler.register();
        MinecraftForge.EVENT_BUS.addListener(this::registerCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLogin);
        MinecraftForge.EVENT_BUS.addListener(SovereignCore::onPlayerTick);
        LOGGER.info("SovereignCore {} initialized", VERSION);
    }

    // Lifecycle and durable storage -------------------------------------------------------------

    private void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        dataFile = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("sovereigncore.properties");
        loadData();
        LOGGER.info("SovereignCore loaded with {} ranks, {} public warps and {} stored player profiles",
                ROLE_CONFIGS.size(), PUBLIC_WARPS.size(), KNOWN_NAMES.size());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        saveData();
        FROZEN_PLAYERS.clear();
        TPA_REQUESTS.clear();
        TPA_HISTORY.clear();
        LAST_TPA_TIME.clear();
        server = null;
    }

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        KNOWN_NAMES.put(player.getUUID(), player.getGameProfile().getName());
        PLAYER_RANKS.computeIfAbsent(player.getUUID(), id -> new LinkedHashSet<>(List.of("member")));
        saveData();
    }

    private static void loadData() {
        PLAYER_RANKS.clear(); KNOWN_NAMES.clear(); HOME_LIMIT_OVERRIDES.clear(); WARP_LIMIT_OVERRIDES.clear(); TPA_LIMIT_OVERRIDES.clear(); TPA_COOLDOWN_OVERRIDES.clear(); HOMES.clear();
        PUBLIC_WARPS.clear(); PERSONAL_WARPS.clear(); ROLE_CONFIGS.clear(); FROZEN_PLAYERS.clear();
        TPA_REQUESTS.clear(); LAST_TPA_TIME.clear(); TPA_HISTORY.clear(); MOD_BAN_REASONS.clear(); AUDIT_LOG.clear(); spawn = null;
        seedDefaultRanks();
        Properties p = new Properties();
        try {
            if (dataFile != null && Files.exists(dataFile)) {
                try (InputStream in = Files.newInputStream(dataFile)) { p.load(in); }
            }
        } catch (IOException ex) {
            LOGGER.error("Could not load SovereignCore data", ex);
        }

        Set<String> roleIds = new LinkedHashSet<>(ROLE_CONFIGS.keySet());
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("rankcfg.")) {
                String[] s = key.split("\\.", 3); if (s.length == 3) roleIds.add(s[1]);
            } else if (key.startsWith("rankperm.")) {
                String[] s = key.split("\\.", 3); if (s.length == 3) roleIds.add(s[1]);
            }
        }
        for (String id : roleIds) {
            RankConfig cfg = ROLE_CONFIGS.get(id);
            if (cfg == null) cfg = new RankConfig(id, titleCase(id), "#8795A8", 100, "member", 3, 0, 5, 30, false);
            cfg.label = p.getProperty("rankcfg." + id + ".label", cfg.label);
            cfg.color = validColor(p.getProperty("rankcfg." + id + ".color", cfg.color), cfg.color);
            cfg.description = p.getProperty("rankcfg." + id + ".description", cfg.description);
            cfg.icon = safeRankIcon(p.getProperty("rankcfg." + id + ".icon", cfg.icon));
            cfg.priority = parseInt(p.getProperty("rankcfg." + id + ".priority"), cfg.priority, 0, 10000);
            cfg.parent = p.getProperty("rankcfg." + id + ".parent", cfg.parent);
            cfg.homesLimit = parseLimit(p.getProperty("rankcfg." + id + ".homes"), cfg.homesLimit);
            cfg.personalWarpsLimit = parseLimit(p.getProperty("rankcfg." + id + ".warps"), cfg.personalWarpsLimit);
            cfg.tpaLimitPerHour = parseLimit(p.getProperty("rankcfg." + id + ".tpa"), cfg.tpaLimitPerHour);
            cfg.tpaCooldownSeconds = parseInt(p.getProperty("rankcfg." + id + ".cooldown"), cfg.tpaCooldownSeconds, 0, 86400);
            cfg.allPermissions = Boolean.parseBoolean(p.getProperty("rankcfg." + id + ".all", Boolean.toString(cfg.allPermissions)));
            ROLE_CONFIGS.put(id, cfg);
        }
        for (String key : p.stringPropertyNames()) {
            String value = p.getProperty(key, "");
            try {
                if (key.startsWith("rankperm.")) {
                    String[] bits = key.split("\\.", 3);
                    if (bits.length == 3 && ROLE_CONFIGS.containsKey(bits[1])) ROLE_CONFIGS.get(bits[1]).permissionOverrides.put(bits[2], Boolean.parseBoolean(value));
                } else if (key.startsWith("ranks.")) {
                    UUID id = UUID.fromString(key.substring(6));
                    LinkedHashSet<String> assigned = Arrays.stream(value.split(",")).map(SovereignCore::cleanKey)
                            .filter(ROLE_CONFIGS::containsKey).collect(Collectors.toCollection(LinkedHashSet::new));
                    if (!assigned.isEmpty()) PLAYER_RANKS.put(id, assigned);
                } else if (key.startsWith("name.")) {
                    KNOWN_NAMES.put(UUID.fromString(key.substring(5)), value);
                } else if (key.startsWith("home.")) {
                    String[] bits = key.split("\\.", 3);
                    if (bits.length == 3) {
                        SavedPos pos = SavedPos.parse(value);
                        if (pos != null && SAFE_KEY.matcher(bits[2]).matches()) HOMES.computeIfAbsent(UUID.fromString(bits[1]), k -> new LinkedHashMap<>()).put(cleanKey(bits[2]), pos);
                    }
                } else if (key.startsWith("warp.public.")) {
                    SavedPos pos = SavedPos.parse(value); String n = key.substring("warp.public.".length());
                    if (pos != null && SAFE_KEY.matcher(n).matches()) PUBLIC_WARPS.put(cleanKey(n), pos);
                } else if (key.startsWith("warp.personal.")) {
                    String[] bits = key.split("\\.", 4);
                    if (bits.length == 4) {
                        SavedPos pos = SavedPos.parse(value);
                        if (pos != null && SAFE_KEY.matcher(bits[3]).matches()) PERSONAL_WARPS.computeIfAbsent(UUID.fromString(bits[2]), k -> new LinkedHashMap<>()).put(cleanKey(bits[3]), pos);
                    }
                } else if (key.startsWith("homeLimit.")) {
                    HOME_LIMIT_OVERRIDES.put(UUID.fromString(key.substring("homeLimit.".length())), checkedLimit(value));
                } else if (key.startsWith("warpLimit.")) {
                    WARP_LIMIT_OVERRIDES.put(UUID.fromString(key.substring("warpLimit.".length())), checkedLimit(value));
                } else if (key.startsWith("tpaLimit.")) {
                    TPA_LIMIT_OVERRIDES.put(UUID.fromString(key.substring("tpaLimit.".length())), checkedLimit(value));
                } else if (key.startsWith("tpaCooldown.")) {
                    TPA_COOLDOWN_OVERRIDES.put(UUID.fromString(key.substring("tpaCooldown.".length())), checkedInt(value, 0, 86400));
                } else if (key.startsWith("modban.")) {
                    MOD_BAN_REASONS.put(key.substring("modban.".length()).toLowerCase(Locale.ROOT), value);
                } else if (key.startsWith("audit.")) {
                    AUDIT_LOG.add(value);
                } else if (key.equals("spawn")) {
                    spawn = SavedPos.parse(value);
                }
            } catch (RuntimeException ex) {
                LOGGER.warn("Ignoring invalid SovereignCore data entry '{}': {}", key, ex.getMessage());
            }
        }
        AUDIT_LOG.sort(Comparator.comparing(SovereignCore::auditTimestamp));
        if (AUDIT_LOG.size() > MAX_AUDIT_ENTRIES) AUDIT_LOG.subList(0, AUDIT_LOG.size() - MAX_AUDIT_ENTRIES).clear();
        // Repairs invalid/missing parent references without silently allowing inheritance cycles.
        for (RankConfig cfg : ROLE_CONFIGS.values()) {
            if (cfg.id.equals("owner") || cfg.id.equals("admin") || cfg.id.equals("member")) cfg.parent = "";
            else if (!cfg.parent.isBlank() && (!ROLE_CONFIGS.containsKey(cfg.parent) || cfg.parent.equals(cfg.id) || createsParentCycle(cfg.id, cfg.parent))) cfg.parent = "member";
        }
        saveData();
    }

    private static void seedDefaultRanks() {
        ROLE_CONFIGS.put("owner", new RankConfig("owner", "Owner", "#F5B942", 1000, "", -1, -1, -1, 0, true));
        ROLE_CONFIGS.put("admin", new RankConfig("admin", "Admin", "#EF6262", 900, "", -1, -1, -1, 0, true));
        ROLE_CONFIGS.put("moderator", new RankConfig("moderator", "Moderator", "#E8A23B", 500, "member", 10, 1, 30, 10, false));
        ROLE_CONFIGS.put("vip", new RankConfig("vip", "VIP", "#B58AF7", 200, "member", 10, 3, 10, 15, false));
        ROLE_CONFIGS.put("builder", new RankConfig("builder", "Builder", "#57C7A5", 150, "member", 5, 2, 5, 30, false));
        ROLE_CONFIGS.put("member", new RankConfig("member", "Member", "#8795A8", 100, "", 3, 0, 5, 30, false));
        grantDefaults("owner", "*");
        grantDefaults("admin", "*");
        grantDefaults("moderator", "players.view", "players.teleport", "players.bring", "players.freeze", "players.kick", "players.moderate", "homes.manage", "warps.use", "teleport.use", "tpa.use", "spawn.use", "logs.view");
        grantDefaults("vip", "homes.use", "homes.set", "warps.use", "warps.personal", "tpa.use", "spawn.use");
        grantDefaults("builder", "homes.use", "homes.set", "warps.use", "warps.personal", "teleport.use", "spawn.use");
        grantDefaults("member", "homes.use", "homes.set", "warps.use", "tpa.use", "spawn.use");
    }

    private static void grantDefaults(String role, String... permissions) {
        RankConfig cfg = ROLE_CONFIGS.get(role);
        if (cfg == null) return;
        for (String permission : permissions) if (!permission.equals("*")) cfg.permissionOverrides.put(permission, true);
    }

    private static void saveData() {
        if (dataFile == null) return;
        Properties p = new Properties();
        for (RankConfig cfg : ROLE_CONFIGS.values()) {
            String root = "rankcfg." + cfg.id + ".";
            p.setProperty(root + "label", cfg.label);
            p.setProperty(root + "color", cfg.color);
            p.setProperty(root + "description", cfg.description == null ? "" : cfg.description);
            p.setProperty(root + "icon", safeRankIcon(cfg.icon));
            p.setProperty(root + "priority", Integer.toString(cfg.priority));
            p.setProperty(root + "parent", cfg.parent);
            p.setProperty(root + "homes", Integer.toString(cfg.homesLimit));
            p.setProperty(root + "warps", Integer.toString(cfg.personalWarpsLimit));
            p.setProperty(root + "tpa", Integer.toString(cfg.tpaLimitPerHour));
            p.setProperty(root + "cooldown", Integer.toString(cfg.tpaCooldownSeconds));
            p.setProperty(root + "all", Boolean.toString(cfg.allPermissions));
            cfg.permissionOverrides.forEach((node, value) -> p.setProperty("rankperm." + cfg.id + "." + node, Boolean.toString(value)));
        }
        PLAYER_RANKS.forEach((uuid, ranks) -> p.setProperty("ranks." + uuid, String.join(",", ranks)));
        KNOWN_NAMES.forEach((uuid, name) -> p.setProperty("name." + uuid, name));
        HOME_LIMIT_OVERRIDES.forEach((uuid, limit) -> p.setProperty("homeLimit." + uuid, Integer.toString(limit)));
        WARP_LIMIT_OVERRIDES.forEach((uuid, limit) -> p.setProperty("warpLimit." + uuid, Integer.toString(limit)));
        TPA_LIMIT_OVERRIDES.forEach((uuid, limit) -> p.setProperty("tpaLimit." + uuid, Integer.toString(limit)));
        TPA_COOLDOWN_OVERRIDES.forEach((uuid, seconds) -> p.setProperty("tpaCooldown." + uuid, Integer.toString(seconds)));
        HOMES.forEach((uuid, homes) -> homes.forEach((name, pos) -> p.setProperty("home." + uuid + "." + name, pos.encode())));
        PUBLIC_WARPS.forEach((name, pos) -> p.setProperty("warp.public." + name, pos.encode()));
        PERSONAL_WARPS.forEach((uuid, warps) -> warps.forEach((name, pos) -> p.setProperty("warp.personal." + uuid + "." + name, pos.encode())));
        MOD_BAN_REASONS.forEach((name, reason) -> p.setProperty("modban." + name, reason));
        for (int i = 0; i < AUDIT_LOG.size(); i++) p.setProperty("audit." + String.format(Locale.ROOT, "%04d", i), AUDIT_LOG.get(i));
        if (spawn != null) p.setProperty("spawn", spawn.encode());
        Path tmp = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
        Path automaticBackup = dataFile.resolveSibling(dataFile.getFileName() + ".bak");
        try {
            Files.createDirectories(dataFile.getParent());
            if (Files.exists(dataFile)) {
                try { Files.copy(dataFile, automaticBackup, StandardCopyOption.REPLACE_EXISTING); }
                catch (IOException backupError) { LOGGER.warn("Could not update automatic backup before save: {}", backupError.getMessage()); }
            }
            try (OutputStream out = Files.newOutputStream(tmp)) { p.store(out, "SovereignCore 1.3 - back up this file before manual editing"); }
            try { Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException ignored) { Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ex) {
            LOGGER.error("Could not save SovereignCore data", ex);
        }
    }

    private static int parseInt(String raw, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(raw))); }
        catch (RuntimeException ignored) { return fallback; }
    }
    private static int parseLimit(String raw, int fallback) { return parseInt(raw, fallback, -1, 10000); }
    private static String validColor(String raw, String fallback) { return raw != null && COLOR.matcher(raw).matches() ? raw.toUpperCase(Locale.ROOT) : fallback; }
    private static String titleCase(String value) { return value.isEmpty() ? value : value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1); }
    private static String safeRankIcon(String raw) {
        if (raw == null) return "◆";
        String value = raw.trim();
        if (value.isEmpty()) return "•";
        if (value.length() > 12 || value.contains("|") || value.contains("\n") || value.contains("\r") || value.contains("\t") || value.indexOf('§') >= 0) return "◆";
        return value;
    }
    private static String cleanKey(String raw) { return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT); }
    private static boolean safeKey(String raw) { return raw != null && SAFE_KEY.matcher(raw.trim()).matches(); }

    // Permissions and role hierarchy ------------------------------------------------------------

    private static LinkedHashSet<String> assignedRanks(UUID id) {
        LinkedHashSet<String> ranks = PLAYER_RANKS.get(id);
        if (ranks == null || ranks.isEmpty()) return new LinkedHashSet<>(List.of("member"));
        return new LinkedHashSet<>(ranks);
    }

    private static String primaryRank(UUID id) {
        return assignedRanks(id).stream().map(ROLE_CONFIGS::get).filter(cfg -> cfg != null)
                .max(Comparator.comparingInt(cfg -> cfg.priority)).map(cfg -> cfg.id).orElse("member");
    }

    private static String primaryRank(ServerPlayer player) { return primaryRank(player.getUUID()); }

    private static String rankLabels(UUID id) {
        return assignedRanks(id).stream().map(ROLE_CONFIGS::get).filter(cfg -> cfg != null)
                .sorted(Comparator.comparingInt((RankConfig cfg) -> cfg.priority).reversed())
                .map(cfg -> safeRankIcon(cfg.icon) + " " + cfg.label).collect(Collectors.joining(", "));
    }

    private static boolean can(ServerPlayer player, String permission) {
        if (player.hasPermissions(2)) return true;
        String node = cleanKey(permission);
        for (String role : assignedRanks(player.getUUID())) {
            if (roleAllows(role, node, new HashSet<>())) return true;
        }
        return false;
    }

    private static boolean roleAllows(String roleId, String node, Set<String> visited) {
        RankConfig cfg = ROLE_CONFIGS.get(roleId);
        if (cfg == null || !visited.add(roleId)) return false;
        if (cfg.permissionOverrides.containsKey(node)) return cfg.permissionOverrides.get(node);
        if (cfg.allPermissions) return true;
        return !cfg.parent.isBlank() && roleAllows(cfg.parent, node, visited);
    }

    private static boolean canManageRank(ServerPlayer actor, String roleId) {
        if (!can(actor, "ranks.manage")) return false;
        if (!ROLE_CONFIGS.containsKey(roleId)) return false;
        if (actor.hasPermissions(4)) return true;
        if (roleId.equals("owner")) return false;
        return maxPriority(actor.getUUID()) > ROLE_CONFIGS.get(roleId).priority;
    }

    private static int maxPriority(UUID playerId) {
        return assignedRanks(playerId).stream().map(ROLE_CONFIGS::get).filter(c -> c != null)
                .mapToInt(c -> c.priority).max().orElse(0);
    }

    private static boolean createsParentCycle(String role, String parent) {
        Set<String> seen = new HashSet<>();
        String current = parent;
        while (!current.isBlank()) {
            if (current.equals(role) || !seen.add(current)) return true;
            RankConfig cfg = ROLE_CONFIGS.get(current);
            if (cfg == null) return false;
            current = cfg.parent;
        }
        return false;
    }

    private static int effectiveHomesLimit(UUID uuid) {
        Integer override = HOME_LIMIT_OVERRIDES.get(uuid);
        if (override != null) return override;
        int maximum = 0;
        for (String role : assignedRanks(uuid)) {
            RankConfig cfg = ROLE_CONFIGS.get(role);
            if (cfg == null) continue;
            if (cfg.homesLimit < 0) return -1;
            maximum = Math.max(maximum, cfg.homesLimit);
        }
        return maximum;
    }

    private static int effectivePersonalWarpLimit(UUID uuid) {
        Integer override = WARP_LIMIT_OVERRIDES.get(uuid);
        if (override != null) return override;
        int maximum = 0;
        for (String role : assignedRanks(uuid)) {
            RankConfig cfg = ROLE_CONFIGS.get(role);
            if (cfg == null) continue;
            if (cfg.personalWarpsLimit < 0) return -1;
            maximum = Math.max(maximum, cfg.personalWarpsLimit);
        }
        return maximum;
    }

    private static int effectiveTpaLimit(UUID uuid) {
        Integer override = TPA_LIMIT_OVERRIDES.get(uuid);
        if (override != null) return override;
        int maximum = 0;
        for (String role : assignedRanks(uuid)) {
            RankConfig cfg = ROLE_CONFIGS.get(role);
            if (cfg == null) continue;
            if (cfg.tpaLimitPerHour < 0) return -1;
            maximum = Math.max(maximum, cfg.tpaLimitPerHour);
        }
        return maximum;
    }

    private static int effectiveTpaCooldown(UUID uuid) {
        Integer override = TPA_COOLDOWN_OVERRIDES.get(uuid);
        if (override != null) return override;
        int minimum = Integer.MAX_VALUE;
        for (String role : assignedRanks(uuid)) {
            RankConfig cfg = ROLE_CONFIGS.get(role);
            if (cfg != null) minimum = Math.min(minimum, cfg.tpaCooldownSeconds);
        }
        return minimum == Integer.MAX_VALUE ? 30 : minimum;
    }

    private static boolean require(ServerPlayer player, String permission) {
        if (can(player, permission)) return true;
        deny(player, permission, "main", "");
        return false;
    }

    // Player resolution, messages, audit log ----------------------------------------------------

    private static void tell(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal("§8[§5Sovereign§dCore§8] §f" + message));
    }

    private static void sendUi(ServerPlayer player, String page, String data) {
        NetworkHandler.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new NetworkHandler.UiPacket(page, data == null ? "" : data));
    }

    private static void deny(ServerPlayer player, String permission, String returnPage, String context) {
        tell(player, "§cNo permission: " + permission);
        sendUi(player, "notice", "Access denied: " + permission + "\n" + returnPage + "\n" + context);
    }

    private static String playerName(UUID id) {
        if (server != null) {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) return online.getGameProfile().getName();
        }
        return KNOWN_NAMES.getOrDefault(id, id.toString().substring(0, 8));
    }

    private static ServerPlayer findOnline(String name) {
        if (server == null || name == null || name.isBlank()) return null;
        return server.getPlayerList().getPlayerByName(name.trim());
    }

    private static UUID resolveUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try { return UUID.fromString(value.trim()); } catch (IllegalArgumentException ignored) { }
        ServerPlayer online = findOnline(value);
        if (online != null) return online.getUUID();
        for (Map.Entry<UUID, String> entry : KNOWN_NAMES.entrySet()) if (entry.getValue().equalsIgnoreCase(value.trim())) return entry.getKey();
        if (server != null && server.getProfileCache() != null && SAFE_PLAYER.matcher(value.trim()).matches()) {
            try {
                return server.getProfileCache().get(value.trim()).map(profile -> profile.getId()).orElse(null);
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    private static SavedPos currentPosition(ServerPlayer player) {
        return new SavedPos(player.serverLevel().dimension().location().toString(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    private static ServerLevel getLevel(String dimensionId) {
        if (server == null || dimensionId == null) return null;
        ResourceLocation location = ResourceLocation.tryParse(dimensionId);
        if (location == null) return null;
        ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, location);
        return server.getLevel(key);
    }

    private static boolean teleport(ServerPlayer player, SavedPos pos) {
        if (pos == null) { tell(player, "§cSaved position is invalid."); return false; }
        ServerLevel level = getLevel(pos.dimension);
        if (level == null) { tell(player, "§cDimension is missing or not loaded: " + pos.dimension); return false; }
        player.teleportTo(level, pos.x, pos.y, pos.z, pos.yaw, pos.pitch);
        return true;
    }

    private static Instant auditTimestamp(String entry) {
        if (entry == null) return Instant.EPOCH;
        int separator = entry.indexOf(" | ");
        String timestamp = separator < 0 ? entry : entry.substring(0, separator);
        try { return Instant.parse(timestamp); }
        catch (RuntimeException ignored) { return Instant.EPOCH; }
    }

    private static void audit(ServerPlayer actor, String action, String detail) {
        String sanitized = detail == null ? "" : detail.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        AUDIT_LOG.add(Instant.now() + " | " + actor.getGameProfile().getName() + " | " + action + " | " + sanitized);
        if (AUDIT_LOG.size() > MAX_AUDIT_ENTRIES) AUDIT_LOG.remove(0);
    }

    private static void complete(ServerPlayer actor, String message, String page, String context, String action, String detail) {
        audit(actor, action, detail);
        saveData();
        tell(actor, "§a" + message);
        openPage(actor, page, context);
    }

    private static void error(ServerPlayer actor, String message, String page, String context) {
        tell(actor, "§c" + message);
        sendUi(actor, "notice", message + "\n" + page + "\n" + context);
    }

    // GUI data requests -------------------------------------------------------------------------

    private static void sendMain(ServerPlayer player) {
        String role = primaryRank(player);
        RankConfig cfg = ROLE_CONFIGS.getOrDefault(role, ROLE_CONFIGS.get("member"));
        List<String> panels = new ArrayList<>(List.of("homes", "warps", "teleport", "personal"));
        if (can(player, "players.view")) panels.add("players");
        if (can(player, "ranks.manage")) panels.add("ranks");
        if (can(player, "settings.manage") || can(player, "spawn.manage")) panels.add("settings");
        if (can(player, "players.ban") || can(player, "players.unban") || can(player, "players.moderate")) panels.add("moderation");
        if (can(player, "logs.view")) panels.add("logs");
        String data = "role=" + role + "\nlabel=" + safeRankIcon(cfg.icon) + " " + cfg.label + "\nonline=" + (server == null ? 0 : server.getPlayerList().getPlayerCount()) + "\npanels=" + String.join(",", panels)
                + "\nhomes=" + effectiveHomesLimit(player.getUUID()) + "\nwarps=" + effectivePersonalWarpLimit(player.getUUID());
        sendUi(player, "main", data);
    }

    private static void sendPlayers(ServerPlayer actor, String search) {
        if (!can(actor, "players.view")) { deny(actor, "players.view", "main", ""); return; }
        String needle = cleanKey(search);
        List<String> rows = server.getPlayerList().getPlayers().stream()
                .filter(p -> needle.isBlank() || p.getGameProfile().getName().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(p -> p.getGameProfile().getName().toLowerCase(Locale.ROOT)))
                .map(p -> p.getUUID() + "\t" + p.getGameProfile().getName() + "\t" + primaryRank(p) + "\t" + p.serverLevel().dimension().location() + "\t" + p.gameMode.getGameModeForPlayer().getName() + "\t" + Math.round(p.getHealth()))
                .collect(Collectors.toList());
        sendUi(actor, "players", String.join("\n", rows));
    }

    private static void sendPlayerDetails(ServerPlayer actor, String value) {
        if (!can(actor, "players.view")) { deny(actor, "players.view", "main", ""); return; }
        UUID id = resolveUuid(value);
        if (id == null) { error(actor, "Player is not known to this server. They need to have joined before offline management can resolve their UUID.", "players", ""); return; }
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        String data = "name=" + playerName(id) + "\nuuid=" + id + "\nonline=" + (online != null)
                + "\nrank=" + primaryRank(id) + "\nranks=" + String.join(",", assignedRanks(id))
                + "\nrankLabels=" + rankLabels(id)
                + "\ndimension=" + (online == null ? "offline" : online.serverLevel().dimension().location())
                + "\ngamemode=" + (online == null ? "offline" : online.gameMode.getGameModeForPlayer().getName())
                + "\nhealth=" + (online == null ? "-" : Math.round(online.getHealth()))
                + "\nhomeLimit=" + effectiveHomesLimit(id)
                + "\nhomeOverride=" + (HOME_LIMIT_OVERRIDES.containsKey(id) ? HOME_LIMIT_OVERRIDES.get(id) : "inherit");
        sendUi(actor, "player", data);
    }

    private static void sendPlayerLimits(ServerPlayer actor, String targetName) {
        if (!can(actor, "players.limits")) { deny(actor, "players.limits", "player", targetName); return; }
        UUID id = resolveUuid(targetName);
        if (id == null) { error(actor, "Player is not known to this server.", "players", ""); return; }
        if (!canModifyPlayer(actor, id)) { deny(actor, "players.limits for that player", "player", playerName(id)); return; }
        String data = "name=" + playerName(id)
                + "\nhomeOverride=" + valueOrInherit(HOME_LIMIT_OVERRIDES.get(id))
                + "\nwarpOverride=" + valueOrInherit(WARP_LIMIT_OVERRIDES.get(id))
                + "\ntpaOverride=" + valueOrInherit(TPA_LIMIT_OVERRIDES.get(id))
                + "\ncooldownOverride=" + valueOrInherit(TPA_COOLDOWN_OVERRIDES.get(id))
                + "\neffectiveHomes=" + effectiveHomesLimit(id)
                + "\neffectiveWarps=" + effectivePersonalWarpLimit(id)
                + "\neffectiveTpa=" + effectiveTpaLimit(id)
                + "\neffectiveCooldown=" + effectiveTpaCooldown(id);
        sendUi(actor, "playerlimits", data);
    }

    private static String valueOrInherit(Integer value) { return value == null ? "inherit" : Integer.toString(value); }

    private static void sendHomes(ServerPlayer actor, String ownerName) {
        UUID owner = ownerName == null || ownerName.isBlank() ? actor.getUUID() : resolveUuid(ownerName);
        if (owner == null) { error(actor, "Player not found.", "players", ""); return; }
        if (owner.equals(actor.getUUID())) {
            if (!can(actor, "homes.use")) { deny(actor, "homes.use", "main", ""); return; }
        } else if (!can(actor, "homes.manage") || !canModifyPlayer(actor, owner)) { deny(actor, "homes.manage", "player", playerName(owner)); return; }
        List<String> rows = new ArrayList<>();
        HOMES.getOrDefault(owner, Collections.emptyMap()).entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> rows.add(owner + "\t" + playerName(owner) + "\t" + e.getKey() + "\t" + e.getValue().shortDisplay()));
        String payload = "owner=" + owner + "\nownerName=" + playerName(owner) + (rows.isEmpty() ? "" : "\n" + String.join("\n", rows));
        sendUi(actor, "homes", payload);
    }

    private static void sendWarps(ServerPlayer actor) {
        if (!can(actor, "warps.use")) { deny(actor, "warps.use", "main", ""); return; }
        List<String> rows = new ArrayList<>();
        PUBLIC_WARPS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> rows.add(e.getKey() + "\t" + e.getValue().shortDisplay() + "\tpublic\t-\tServer"));
        for (Map.Entry<UUID, Map<String, SavedPos>> user : PERSONAL_WARPS.entrySet()) {
            if (!user.getKey().equals(actor.getUUID()) && !can(actor, "warps.manage")) continue;
            user.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> rows.add(e.getKey() + "\t" + e.getValue().shortDisplay() + "\tpersonal\t" + user.getKey() + "\t" + playerName(user.getKey())));
        }
        sendUi(actor, "warps", String.join("\n", rows));
    }

    private static void sendRanks(ServerPlayer actor) {
        if (!can(actor, "ranks.manage")) { deny(actor, "ranks.manage", "main", ""); return; }
        List<String> rows = ROLE_CONFIGS.values().stream().sorted(Comparator.comparingInt((RankConfig c) -> c.priority).reversed())
                .map(c -> c.id + "\t" + c.label + "\t" + c.priority + "\t" + rankMemberCount(c.id) + "\t" + c.homesLimit + "\t" + c.personalWarpsLimit + "\t" + c.tpaLimitPerHour + "\t" + c.color + "\t" + c.description + "\t" + safeRankIcon(c.icon))
                .collect(Collectors.toList());
        sendUi(actor, "ranks", String.join("\n", rows));
    }

    private static int rankMemberCount(String role) {
        int total = 0;
        for (UUID id : PLAYER_RANKS.keySet()) if (assignedRanks(id).contains(role)) total++;
        return total;
    }

    private static void sendRankDetails(ServerPlayer actor, String roleId) {
        String id = cleanKey(roleId);
        RankConfig cfg = ROLE_CONFIGS.get(id);
        if (cfg == null) { error(actor, "Rank not found.", "ranks", ""); return; }
        if (!canManageRank(actor, id)) { deny(actor, "permission to edit rank " + id, "ranks", ""); return; }
        String data = "id=" + cfg.id + "\nlabel=" + cfg.label + "\ncolor=" + cfg.color + "\npriority=" + cfg.priority
                + "\nparent=" + cfg.parent + "\nhomes=" + cfg.homesLimit + "\nwarps=" + cfg.personalWarpsLimit
                + "\ntpa=" + cfg.tpaLimitPerHour + "\ncooldown=" + cfg.tpaCooldownSeconds + "\nall=" + cfg.allPermissions + "\ndescription=" + (cfg.description == null ? "" : cfg.description) + "\nicon=" + safeRankIcon(cfg.icon);
        sendUi(actor, "rankdetail", data);
    }

    private static void sendRolePermissions(ServerPlayer actor, String roleId) {
        String id = cleanKey(roleId);
        RankConfig cfg = ROLE_CONFIGS.get(id);
        if (cfg == null) { error(actor, "Rank not found.", "ranks", ""); return; }
        if (!canManageRank(actor, id)) { deny(actor, "permission to edit rank " + id, "ranks", ""); return; }
        List<String> rows = PERMISSIONS.stream().map(node -> node + "\t" + roleAllows(id, node, new HashSet<>())).collect(Collectors.toList());
        sendUi(actor, "roleperms", id + "\n" + String.join("\n", rows));
    }

    private static void sendAssignRanks(ServerPlayer actor, String targetName) {
        if (!can(actor, "players.rank")) { deny(actor, "players.rank", "player", targetName); return; }
        UUID target = resolveUuid(targetName);
        if (target == null) { error(actor, "Player is not known to this server.", "player", targetName); return; }
        if (!canModifyPlayer(actor, target)) { deny(actor, "rank assignment for " + playerName(target), "player", playerName(target)); return; }
        List<String> rows = ROLE_CONFIGS.values().stream().filter(cfg -> cfg.id.equals("member") || canAssignRole(actor, target, cfg.id))
                .sorted(Comparator.comparingInt((RankConfig cfg) -> cfg.priority).reversed())
                .map(cfg -> cfg.id + "\t" + cfg.label + "\t" + cfg.priority + "\t" + assignedRanks(target).contains(cfg.id))
                .collect(Collectors.toList());
        sendUi(actor, "assignrank", "target=" + target + "\nname=" + playerName(target) + (rows.isEmpty() ? "" : "\n" + String.join("\n", rows)));
    }

    private static void sendBans(ServerPlayer actor, String search) {
        if (!can(actor, "players.unban") && !can(actor, "players.moderate")) { deny(actor, "players.unban", "main", ""); return; }
        String needle = cleanKey(search);
        List<String> names = new ArrayList<>();
        try {
            // UserBanList is keyed by GameProfile, not by username String.
            names.addAll(server.getPlayerList().getBans().getUserList().stream()
                    .map(profile -> profile.getName())
                    .filter(name -> name != null && !name.isBlank())
                    .collect(Collectors.toList()));
        } catch (RuntimeException ex) {
            LOGGER.warn("Could not read vanilla ban list: {}", ex.getMessage());
        }
        for (String name : MOD_BAN_REASONS.keySet()) {
            if (names.stream().noneMatch(existing -> existing.equalsIgnoreCase(name))) names.add(name);
        }
        List<String> rows = names.stream().filter(n -> needle.isBlank() || n.toLowerCase(Locale.ROOT).contains(needle))
                .sorted(String.CASE_INSENSITIVE_ORDER).map(n -> n + "\t" + MOD_BAN_REASONS.getOrDefault(n.toLowerCase(Locale.ROOT), "Vanilla server ban")).collect(Collectors.toList());
        sendUi(actor, "bans", String.join("\n", rows));
    }

    private static void sendLogs(ServerPlayer actor, String search) {
        if (!can(actor, "logs.view")) { deny(actor, "logs.view", "main", ""); return; }
        String needle = cleanKey(search);
        List<String> rows = new ArrayList<>(AUDIT_LOG);
        Collections.reverse(rows);
        if (!needle.isBlank()) rows = rows.stream().filter(line -> line.toLowerCase(Locale.ROOT).contains(needle)).collect(Collectors.toList());
        sendUi(actor, "logs", String.join("\n", rows));
    }

    private static void sendInspection(ServerPlayer actor, String targetName, String kind) {
        if (!can(actor, kind.equals("ender") ? "players.enderchest.view" : "players.inventory.view")) { deny(actor, "inventory view", "player", targetName); return; }
        ServerPlayer target = findOnline(targetName);
        if (target == null) { error(actor, "Inventory inspection is only available while the player is online.", "player", targetName); return; }
        List<String> rows = new ArrayList<>();
        rows.add("target=" + target.getGameProfile().getName());
        rows.add("kind=" + (kind.equals("ender") ? "ender" : "inventory"));
        if (kind.equals("ender")) {
            for (int i = 0; i < target.getEnderChestInventory().getContainerSize(); i++) {
                ItemStack stack = target.getEnderChestInventory().getItem(i);
                if (!stack.isEmpty()) rows.add(i + "\t" + stack.getCount() + "\t" + stack.getHoverName().getString() + "\t" + BuiltInRegistries.ITEM.getKey(stack.getItem()));
            }
        } else {
            for (int i = 0; i < target.getInventory().getContainerSize(); i++) {
                ItemStack stack = target.getInventory().getItem(i);
                if (!stack.isEmpty()) rows.add(i + "\t" + stack.getCount() + "\t" + stack.getHoverName().getString() + "\t" + BuiltInRegistries.ITEM.getKey(stack.getItem()));
            }
        }
        sendUi(actor, "inspect", String.join("\n", rows));
    }

    private static void openPage(ServerPlayer actor, String page, String context) {
        switch (page == null ? "main" : page) {
            case "main" -> sendMain(actor);
            case "players" -> sendPlayers(actor, "");
            case "player" -> sendPlayerDetails(actor, context);
            case "playerlimits" -> sendPlayerLimits(actor, context);
            case "homes" -> sendHomes(actor, context);
            case "warps" -> sendWarps(actor);
            case "ranks" -> sendRanks(actor);
            case "rankdetail" -> sendRankDetails(actor, context);
            case "roleperms" -> sendRolePermissions(actor, context);
            case "assignrank" -> sendAssignRanks(actor, context);
            case "bans" -> sendBans(actor, "");
            case "logs" -> sendLogs(actor, "");
            case "teleport" -> sendUi(actor, "teleport", "");
            case "personal" -> sendUi(actor, "personal", "");
            case "settings" -> {
                if (!can(actor, "settings.manage") && !can(actor, "spawn.manage")) { deny(actor, "settings.manage", "main", ""); return; }
                sendUi(actor, "settings", "");
            }
            case "inspect" -> {
                String[] parts = context.split("\\|", 2);
                sendInspection(actor, parts[0], parts.length > 1 ? parts[1] : "inventory");
            }
            default -> sendMain(actor);
        }
    }

    // All C2S actions are validated on the server; client UI is never an authority. ----------------

    public static void handleAction(ServerPlayer actor, String action, String arg1, String arg2, String page, String context) {
        if (actor == null || server == null) return;
        String a = action == null ? "" : action.toUpperCase(Locale.ROOT);
        String x = arg1 == null ? "" : arg1.trim();
        String y = arg2 == null ? "" : arg2.trim();
        String returnPage = page == null || page.isBlank() ? "main" : page;
        String returnContext = context == null ? "" : context;
        try {
            switch (a) {
                case "REQ_MAIN" -> sendMain(actor);
                case "REQ_PAGE" -> openPage(actor, x, y);
                case "REQ_PLAYERS" -> sendPlayers(actor, x);
                case "REQ_PLAYER" -> sendPlayerDetails(actor, x);
                case "REQ_PLAYER_LIMITS" -> sendPlayerLimits(actor, x);
                case "REQ_HOME_LIST" -> sendHomes(actor, x);
                case "REQ_WARP_LIST" -> sendWarps(actor);
                case "REQ_RANKS" -> sendRanks(actor);
                case "REQ_RANK_DETAIL" -> sendRankDetails(actor, x);
                case "REQ_ROLE_PERMS" -> sendRolePermissions(actor, x);
                case "REQ_ASSIGN_RANK" -> sendAssignRanks(actor, x);
                case "REQ_BANS" -> sendBans(actor, x);
                case "REQ_LOGS" -> sendLogs(actor, x);
                case "REQ_INSPECT" -> sendInspection(actor, x, y);
                case "SET_HOME" -> setHome(actor, x, returnPage, returnContext);
                case "DEL_HOME" -> deleteHome(actor, actor.getUUID(), x, returnPage, returnContext);
                case "HOME" -> {
                    if (!can(actor, "homes.use") && !can(actor, "homes.manage")) { deny(actor, "homes.use", returnPage, returnContext); return; }
                    String[] bits = x.split("\\|", 2);
                    UUID owner = bits.length == 2 ? resolveUuid(bits[0]) : actor.getUUID();
                    String home = bits.length == 2 ? bits[1] : x;
                    if (owner == null || (!owner.equals(actor.getUUID()) && (!can(actor, "homes.manage") || !canModifyPlayer(actor, owner)))) { deny(actor, "homes.manage", returnPage, returnContext); return; }
                    SavedPos pos = HOMES.getOrDefault(owner, Collections.emptyMap()).get(cleanKey(home));
                    if (pos == null) { error(actor, "Home not found: " + home, returnPage, returnContext); return; }
                    if (teleport(actor, pos)) complete(actor, "Teleported to " + playerName(owner) + "'s home " + home + ".", returnPage, returnContext, "HOME", playerName(owner) + ":" + home);
                }
                case "DEL_HOME_OTHER" -> {
                    String[] bits = x.split("\\|", 2);
                    if (bits.length != 2) { error(actor, "Invalid home selector.", returnPage, returnContext); return; }
                    UUID owner = resolveUuid(bits[0]);
                    if (owner == null) { error(actor, "Player not found.", returnPage, returnContext); return; }
                    deleteHome(actor, owner, bits[1], returnPage, returnContext);
                }
                case "SET_WARP" -> setWarp(actor, x, y, returnPage, returnContext);
                case "WARP" -> useWarp(actor, x, returnPage, returnContext);
                case "DEL_WARP" -> deleteWarp(actor, x, returnPage, returnContext);
                case "SET_SPAWN" -> {
                    if (!can(actor, "spawn.manage")) { deny(actor, "spawn.manage", returnPage, returnContext); return; }
                    spawn = currentPosition(actor);
                    complete(actor, "Server spawn saved.", returnPage, returnContext, "SET_SPAWN", spawn.shortDisplay());
                }
                case "SPAWN" -> {
                    if (!can(actor, "spawn.use")) { deny(actor, "spawn.use", returnPage, returnContext); return; }
                    if (spawn != null) teleport(actor, spawn);
                    else {
                        ServerLevel overworld = server.overworld();
                        actor.teleportTo(overworld, overworld.getSharedSpawnPos().getX() + .5, overworld.getSharedSpawnPos().getY(), overworld.getSharedSpawnPos().getZ() + .5, actor.getYRot(), actor.getXRot());
                    }
                    complete(actor, "Teleported to spawn.", returnPage, returnContext, "SPAWN", "");
                }
                case "TP_TO" -> {
                    if (!can(actor, "players.teleport") && !can(actor, "teleport.use")) { deny(actor, "players.teleport", returnPage, returnContext); return; }
                    ServerPlayer target = findOnline(x);
                    if (target == null) { error(actor, "Player is not online: " + x, returnPage, returnContext); return; }
                    actor.teleportTo(target.serverLevel(), target.getX(), target.getY(), target.getZ(), target.getYRot(), target.getXRot());
                    complete(actor, "Teleported to " + x + ".", returnPage, returnContext, "TP_TO", x);
                }
                case "TP_HERE" -> {
                    if (!can(actor, "players.bring")) { deny(actor, "players.bring", returnPage, returnContext); return; }
                    ServerPlayer target = findOnline(x);
                    if (target == null) { error(actor, "Player is not online: " + x, returnPage, returnContext); return; }
                    if (!canModifyPlayer(actor, target.getUUID())) { deny(actor, "bring a higher rank", returnPage, returnContext); return; }
                    target.teleportTo(actor.serverLevel(), actor.getX(), actor.getY(), actor.getZ(), actor.getYRot(), actor.getXRot());
                    tell(target, "§eA staff member teleported you.");
                    complete(actor, "Brought " + x + " to you.", returnPage, returnContext, "TP_HERE", x);
                }
                case "TP_COORDS" -> {
                    if (!can(actor, "teleport.coords")) { deny(actor, "teleport.coords", returnPage, returnContext); return; }
                    String[] fields = x.split("\\|", 2);
                    String[] xyz = fields[0].split(",");
                    if (xyz.length != 3) { error(actor, "Use x,y,z or dimension|x,y,z.", returnPage, returnContext); return; }
                    try {
                        double px = Double.parseDouble(xyz[0].trim()), py = Double.parseDouble(xyz[1].trim()), pz = Double.parseDouble(xyz[2].trim());
                        if (!Double.isFinite(px) || !Double.isFinite(py) || !Double.isFinite(pz) || Math.abs(px) > 30_000_000 || Math.abs(pz) > 30_000_000 || py < -4096 || py > 4096) throw new NumberFormatException();
                        String dim = fields.length > 1 && !fields[1].isBlank() ? fields[1].trim() : actor.serverLevel().dimension().location().toString();
                        if (teleport(actor, new SavedPos(dim, px, py, pz, actor.getYRot(), actor.getXRot()))) complete(actor, "Teleported to coordinates.", returnPage, returnContext, "TP_COORDS", dim + " " + px + "," + py + "," + pz);
                    } catch (NumberFormatException ex) { error(actor, "Coordinates must be valid numbers in a safe range.", returnPage, returnContext); }
                }
                case "TPA" -> requestTeleport(actor, x, returnPage, returnContext);
                case "TP_ACCEPT" -> acceptTeleport(actor, returnPage, returnContext);
                case "TP_DENY" -> denyTeleport(actor, returnPage, returnContext);
                case "SET_RANK" -> replacePlayerRanks(actor, x, y, returnPage, returnContext);
                case "TOGGLE_PLAYER_RANK" -> togglePlayerRank(actor, x, y, returnPage, returnContext);
                case "CREATE_ROLE" -> createRole(actor, x, returnPage, returnContext);
                case "DELETE_ROLE" -> deleteRole(actor, x, returnPage, returnContext);
                case "SAVE_ROLE_META" -> saveRoleMeta(actor, x, y, returnPage, returnContext);
                case "TOGGLE_ROLE_PERMISSION" -> toggleRolePermission(actor, x, y, returnPage, returnContext);
                case "TOGGLE_ROLE_ALL" -> toggleRoleAll(actor, x, returnPage, returnContext);
                case "SET_PLAYER_HOME_LIMIT" -> setPlayerHomeLimit(actor, x, y, returnPage, returnContext);
                case "SAVE_PLAYER_LIMITS" -> savePlayerLimits(actor, x, y, returnPage, returnContext);
                case "BACKUP_DATA" -> backupData(actor, returnPage, returnContext);
                case "RESTORE_DATA" -> restoreDataBackup(actor, returnPage, returnContext);
                case "TOGGLE_FREEZE" -> toggleFreeze(actor, x, returnPage, returnContext);
                case "HEAL" -> targetAction(actor, x, "players.heal", returnPage, returnContext, "HEAL", target -> { target.setHealth(target.getMaxHealth()); target.clearFire(); });
                case "FEED" -> targetAction(actor, x, "players.feed", returnPage, returnContext, "FEED", target -> { target.getFoodData().setFoodLevel(20); target.getFoodData().setSaturation(20F); });
                case "GAMEMODE" -> {
                    if (!can(actor, "players.gamemode")) { deny(actor, "players.gamemode", returnPage, returnContext); return; }
                    ServerPlayer target = findOnline(x);
                    if (target == null) { error(actor, "Player is not online: " + x, returnPage, returnContext); return; }
                    if (!canModifyPlayer(actor, target.getUUID())) { deny(actor, "change gamemode of a higher rank", returnPage, returnContext); return; }
                    GameType mode = switch (cleanKey(y)) { case "creative", "1", "c" -> GameType.CREATIVE; case "adventure", "2", "a" -> GameType.ADVENTURE; case "spectator", "3", "sp" -> GameType.SPECTATOR; default -> GameType.SURVIVAL; };
                    target.setGameMode(mode);
                    complete(actor, "Gamemode changed for " + x + ".", returnPage, returnContext, "GAMEMODE", x + " -> " + mode.getName());
                }
                case "TOGGLE_GOD" -> targetAction(actor, x, "players.god", returnPage, returnContext, "TOGGLE_GOD", target -> { target.getAbilities().invulnerable = !target.getAbilities().invulnerable; target.onUpdateAbilities(); });
                case "TOGGLE_FLY" -> targetAction(actor, x, "players.fly", returnPage, returnContext, "TOGGLE_FLY", target -> { target.getAbilities().mayfly = !target.getAbilities().mayfly; if (!target.getAbilities().mayfly) target.getAbilities().flying = false; target.onUpdateAbilities(); });
                case "CLEAR_EFFECTS" -> targetAction(actor, x, "players.moderate", returnPage, returnContext, "CLEAR_EFFECTS", ServerPlayer::removeAllEffects);
                case "CLEAR_XP" -> targetAction(actor, x, "players.moderate", returnPage, returnContext, "CLEAR_XP", target -> { target.setExperienceLevels(0); target.setExperiencePoints(0); target.totalExperience = 0; });
                case "INV_CLEAR" -> targetAction(actor, x, "players.inventory.edit", returnPage, returnContext, "INV_CLEAR", target -> { target.getInventory().clearContent(); target.containerMenu.broadcastChanges(); });
                case "ENDER_CLEAR" -> targetAction(actor, x, "players.enderchest.edit", returnPage, returnContext, "ENDER_CLEAR", target -> { target.getEnderChestInventory().clearContent(); target.containerMenu.broadcastChanges(); });
                case "REMOVE_INV_SLOT" -> removeInventorySlot(actor, x, y, false, returnPage, returnContext);
                case "REMOVE_ENDER_SLOT" -> removeInventorySlot(actor, x, y, true, returnPage, returnContext);
                case "GIVE_ITEM" -> giveItem(actor, x, y, returnPage, returnContext);
                case "KICK" -> moderateTarget(actor, x, y, "kick", returnPage, returnContext);
                case "BAN" -> banTarget(actor, x, y, returnPage, returnContext);
                case "PARDON" -> pardonTarget(actor, x, returnPage, returnContext);
                case "PARDON_IP" -> pardonIp(actor, x, returnPage, returnContext);
                case "OP" -> opTarget(actor, x, true, returnPage, returnContext);
                case "DEOP" -> opTarget(actor, x, false, returnPage, returnContext);
                default -> error(actor, "Unknown action: " + a, returnPage, returnContext);
            }
        } catch (Exception ex) {
            LOGGER.error("SovereignCore action '{}' failed for {}", a, actor.getGameProfile().getName(), ex);
            error(actor, "Action failed. Check the server log for details.", returnPage, returnContext);
        }
    }

    private interface PlayerOperation { void run(ServerPlayer target); }
    private static void targetAction(ServerPlayer actor, String targetName, String permission, String page, String context, String action, PlayerOperation operation) {
        if (!can(actor, permission)) { deny(actor, permission, page, context); return; }
        ServerPlayer target = findOnline(targetName);
        if (target == null) { error(actor, "Player is not online: " + targetName, page, context); return; }
        if (!canModifyPlayer(actor, target.getUUID())) { deny(actor, "manage a player with equal or higher rank", page, context); return; }
        operation.run(target);
        complete(actor, action.replace('_', ' ') + " completed for " + targetName + ".", page, context, action, targetName);
    }

    private static boolean canModifyPlayer(ServerPlayer actor, UUID targetId) {
        if (actor.hasPermissions(4)) return true;
        if (targetId.equals(actor.getUUID())) return true;
        if (assignedRanks(targetId).contains("owner")) return false;
        return maxPriority(actor.getUUID()) > maxPriority(targetId);
    }

    private static boolean canAssignRole(ServerPlayer actor, UUID targetId, String roleId) {
        RankConfig role = ROLE_CONFIGS.get(roleId);
        if (role == null || !can(actor, "players.rank")) return false;
        if (roleId.equals("owner")) return actor.hasPermissions(4);
        if (assignedRanks(targetId).contains("owner") && !actor.hasPermissions(4)) return false;
        return actor.hasPermissions(4) || maxPriority(actor.getUUID()) > role.priority && canModifyPlayer(actor, targetId);
    }

    // Homes and warps ---------------------------------------------------------------------------

    private static void setHome(ServerPlayer actor, String name, String page, String context) {
        if (!can(actor, "homes.set")) { deny(actor, "homes.set", page, context); return; }
        if (!safeKey(name)) { error(actor, "Home name must use 1-32 letters, digits, _ or -.", page, context); return; }
        Map<String, SavedPos> homes = HOMES.computeIfAbsent(actor.getUUID(), id -> new LinkedHashMap<>());
        String key = cleanKey(name);
        int limit = effectiveHomesLimit(actor.getUUID());
        if (!homes.containsKey(key) && limit >= 0 && homes.size() >= limit) { error(actor, "Home limit reached (" + limit + ").", page, context); return; }
        homes.put(key, currentPosition(actor));
        KNOWN_NAMES.put(actor.getUUID(), actor.getGameProfile().getName());
        complete(actor, "Saved home '" + key + "'.", page, context, "SET_HOME", key);
    }

    private static void deleteHome(ServerPlayer actor, UUID owner, String name, String page, String context) {
        if (owner == null) { error(actor, "Player not found.", page, context); return; }
        if (owner.equals(actor.getUUID())) {
            if (!can(actor, "homes.set")) { deny(actor, "homes.set", page, context); return; }
        } else if (!can(actor, "homes.manage") || !canModifyPlayer(actor, owner)) { deny(actor, "homes.manage", page, context); return; }
        Map<String, SavedPos> homes = HOMES.get(owner);
        if (homes == null || homes.remove(cleanKey(name)) == null) { error(actor, "Home not found: " + name, page, context); return; }
        if (homes.isEmpty()) HOMES.remove(owner);
        complete(actor, "Deleted home '" + name + "' for " + playerName(owner) + ".", page, context, "DEL_HOME", playerName(owner) + ":" + name);
    }

    private static void setWarp(ServerPlayer actor, String name, String scope, String page, String context) {
        if (!safeKey(name)) { error(actor, "Warp name must use 1-32 letters, digits, _ or -.", page, context); return; }
        String key = cleanKey(name);
        if (scope.equalsIgnoreCase("personal")) {
            if (!can(actor, "warps.personal")) { deny(actor, "warps.personal", page, context); return; }
            Map<String, SavedPos> warps = PERSONAL_WARPS.computeIfAbsent(actor.getUUID(), id -> new LinkedHashMap<>());
            int limit = effectivePersonalWarpLimit(actor.getUUID());
            if (!warps.containsKey(key) && limit >= 0 && warps.size() >= limit) { error(actor, "Personal warp limit reached (" + limit + ").", page, context); return; }
            warps.put(key, currentPosition(actor));
            complete(actor, "Saved personal warp '" + key + "'.", page, context, "SET_PERSONAL_WARP", key);
        } else {
            if (!can(actor, "warps.manage")) { deny(actor, "warps.manage", page, context); return; }
            PUBLIC_WARPS.put(key, currentPosition(actor));
            complete(actor, "Saved public warp '" + key + "'.", page, context, "SET_PUBLIC_WARP", key);
        }
    }

    private static void useWarp(ServerPlayer actor, String selector, String page, String context) {
        if (!can(actor, "warps.use")) { deny(actor, "warps.use", page, context); return; }
        String[] bits = selector.split("\\|", 3);
        SavedPos pos;
        String label;
        if (bits.length == 3 && bits[0].equalsIgnoreCase("personal")) {
            UUID owner = resolveUuid(bits[1]);
            if (owner == null || (!owner.equals(actor.getUUID()) && !can(actor, "warps.manage"))) {
                deny(actor, "warps.manage", page, context); return;
            }
            pos = PERSONAL_WARPS.getOrDefault(owner, Collections.emptyMap()).get(cleanKey(bits[2]));
            label = bits[2];
        } else if (bits.length == 2 && bits[0].equalsIgnoreCase("personal")) {
            pos = PERSONAL_WARPS.getOrDefault(actor.getUUID(), Collections.emptyMap()).get(cleanKey(bits[1]));
            label = bits[1];
        } else {
            pos = PUBLIC_WARPS.get(cleanKey(selector));
            if (pos == null) pos = PERSONAL_WARPS.getOrDefault(actor.getUUID(), Collections.emptyMap()).get(cleanKey(selector));
            label = selector;
        }
        if (pos == null) { error(actor, "Warp not found: " + label, page, context); return; }
        if (teleport(actor, pos)) complete(actor, "Teleported to warp '" + label + "'.", page, context, "WARP", label);
    }

    private static void deleteWarp(ServerPlayer actor, String selector, String page, String context) {
        String[] bits = selector.split("\\|", 3);
        if (bits.length >= 2 && bits[0].equalsIgnoreCase("personal")) {
            UUID owner = bits.length == 3 ? resolveUuid(bits[1]) : actor.getUUID();
            String name = bits.length == 3 ? bits[2] : bits[1];
            if (owner == null) { error(actor, "Player not found.", page, context); return; }
            if (!owner.equals(actor.getUUID()) && (!can(actor, "warps.manage") || !canModifyPlayer(actor, owner))) { deny(actor, "warps.manage", page, context); return; }
            Map<String, SavedPos> map = PERSONAL_WARPS.get(owner);
            if (map == null || map.remove(cleanKey(name)) == null) { error(actor, "Personal warp not found.", page, context); return; }
            if (map.isEmpty()) PERSONAL_WARPS.remove(owner);
            complete(actor, "Deleted personal warp '" + name + "'.", page, context, "DEL_PERSONAL_WARP", playerName(owner) + ":" + name);
        } else {
            if (!can(actor, "warps.manage")) { deny(actor, "warps.manage", page, context); return; }
            if (PUBLIC_WARPS.remove(cleanKey(selector)) == null) { error(actor, "Public warp not found.", page, context); return; }
            complete(actor, "Deleted public warp '" + selector + "'.", page, context, "DEL_PUBLIC_WARP", selector);
        }
    }

    private static void requestTeleport(ServerPlayer requester, String targetName, String page, String context) {
        if (!can(requester, "tpa.use")) { deny(requester, "tpa.use", page, context); return; }
        ServerPlayer target = findOnline(targetName);
        if (target == null) { error(requester, "Player is not online: " + targetName, page, context); return; }
        if (target.getUUID().equals(requester.getUUID())) { error(requester, "You cannot request teleport to yourself.", page, context); return; }
        long now = System.currentTimeMillis();
        long next = LAST_TPA_TIME.getOrDefault(requester.getUUID(), 0L) + effectiveTpaCooldown(requester.getUUID()) * 1000L;
        if (now < next) { error(requester, "Teleport cooldown: " + Math.max(1, (next - now + 999) / 1000) + " seconds.", page, context); return; }
        List<Long> history = TPA_HISTORY.computeIfAbsent(requester.getUUID(), k -> new ArrayList<>());
        history.removeIf(t -> now - t > 3_600_000L);
        int limit = effectiveTpaLimit(requester.getUUID());
        if (limit >= 0 && history.size() >= limit) { error(requester, "Hourly teleport request limit reached (" + limit + ").", page, context); return; }
        TPA_REQUESTS.put(target.getUUID(), new TeleportRequest(requester.getUUID(), now));
        LAST_TPA_TIME.put(requester.getUUID(), now); history.add(now);
        tell(target, "§e" + requester.getGameProfile().getName() + " wants to teleport to you. Use /tpaccept or the manager UI.");
        complete(requester, "Teleport request sent to " + targetName + ".", page, context, "TPA", targetName);
    }

    private static void acceptTeleport(ServerPlayer target, String page, String context) {
        if (!can(target, "tpa.use")) { deny(target, "tpa.use", page, context); return; }
        TeleportRequest request = TPA_REQUESTS.remove(target.getUUID());
        if (request == null || System.currentTimeMillis() - request.createdAt > 120_000L) { error(target, "No pending request, or the request expired.", page, context); return; }
        ServerPlayer requester = server.getPlayerList().getPlayer(request.requester);
        if (requester == null) { error(target, "Requester is offline.", page, context); return; }
        requester.teleportTo(target.serverLevel(), target.getX(), target.getY(), target.getZ(), target.getYRot(), target.getXRot());
        tell(requester, "§aYour teleport request was accepted.");
        complete(target, "Accepted request from " + requester.getGameProfile().getName() + ".", page, context, "TP_ACCEPT", requester.getGameProfile().getName());
    }

    private static void denyTeleport(ServerPlayer target, String page, String context) {
        TeleportRequest request = TPA_REQUESTS.remove(target.getUUID());
        if (request == null) { error(target, "No pending teleport request.", page, context); return; }
        ServerPlayer requester = server.getPlayerList().getPlayer(request.requester);
        if (requester != null) tell(requester, "§c" + target.getGameProfile().getName() + " denied your teleport request.");
        complete(target, "Teleport request denied.", page, context, "TP_DENY", requester == null ? "offline requester" : requester.getGameProfile().getName());
    }

    // Rank management ----------------------------------------------------------------------------

    private static void createRole(ServerPlayer actor, String raw, String page, String context) {
        if (!can(actor, "ranks.manage")) { deny(actor, "ranks.manage", page, context); return; }
        String id = cleanKey(raw);
        if (!safeKey(id) || ROLE_CONFIGS.containsKey(id) || id.equals("owner") || id.equals("admin")) { error(actor, "Use a new rank ID (letters, digits, _ or -).", page, context); return; }
        String parent = "member";
        int priority = Math.max(1, maxPriority(actor.getUUID()) - 1);
        RankConfig cfg = new RankConfig(id, titleCase(id), "#8795A8", priority, parent, 3, 0, 5, 30, false);
        ROLE_CONFIGS.put(id, cfg);
        complete(actor, "Created rank '" + id + "'. Configure its permissions and limits next.", "rankdetail", id, "CREATE_ROLE", id);
    }

    private static void deleteRole(ServerPlayer actor, String roleId, String page, String context) {
        String id = cleanKey(roleId);
        if (!canManageRank(actor, id)) { deny(actor, "permission to delete rank " + id, page, context); return; }
        if (Set.of("owner", "admin", "member").contains(id)) { error(actor, "This built-in rank cannot be deleted.", page, context); return; }
        ROLE_CONFIGS.remove(id);
        for (RankConfig cfg : ROLE_CONFIGS.values()) {
            if (cfg.parent.equals(id)) cfg.parent = "member";
            cfg.permissionOverrides.remove(id);
        }
        for (Map.Entry<UUID, LinkedHashSet<String>> entry : PLAYER_RANKS.entrySet()) {
            entry.getValue().remove(id);
            if (entry.getValue().isEmpty()) entry.getValue().add("member");
        }
        complete(actor, "Deleted rank '" + id + "' and reassigned affected players to Member where needed.", "ranks", "", "DELETE_ROLE", id);
    }

    private static void saveRoleMeta(ServerPlayer actor, String roleId, String raw, String page, String context) {
        String id = cleanKey(roleId);
        RankConfig cfg = ROLE_CONFIGS.get(id);
        if (cfg == null || !canManageRank(actor, id)) { deny(actor, "permission to edit rank " + id, page, context); return; }
        String[] values = raw.split("\\|", -1);
        if (values.length != 10) { error(actor, "Invalid rank settings form. Refresh and try again.", page, context); return; }
        String label = values[0].trim();
        String color = validColor(values[1].trim(), "");
        String parent = cleanKey(values[3]);
        String description = values[8].trim();
        String icon = safeRankIcon(values[9]);
        if (label.isEmpty() || label.length() > 32 || label.contains("|") || label.contains("\n") || label.contains("\r") || label.contains("\t")
                || description.length() > 120 || description.contains("|") || description.contains("\n") || description.contains("\r") || description.contains("\t") || color.isEmpty()) {
            error(actor, "Rank label, description or color is invalid.", page, context); return;
        }
        if (id.equals("owner") && !actor.hasPermissions(4)) { deny(actor, "ranks.edit.owner", page, context); return; }
        if (parent.equals(id) || (!parent.isBlank() && !ROLE_CONFIGS.containsKey(parent))) { error(actor, "Parent rank is invalid.", page, context); return; }
        int priority, homes, warps, tpa, cooldown;
        try {
            priority = checkedInt(values[2], 0, 10000);
            homes = checkedLimit(values[4]); warps = checkedLimit(values[5]); tpa = checkedLimit(values[6]);
            cooldown = checkedInt(values[7], 0, 86400);
        } catch (IllegalArgumentException ex) { error(actor, "Limits must be -1 (unlimited) or non-negative numbers; cooldown must be 0-86400.", page, context); return; }
        if (createsParentCycle(id, parent)) { error(actor, "This parent would create a rank inheritance cycle.", page, context); return; }
        cfg.label = label; cfg.color = color; cfg.priority = priority; cfg.parent = parent; cfg.description = description; cfg.icon = icon;
        cfg.homesLimit = homes; cfg.personalWarpsLimit = warps; cfg.tpaLimitPerHour = tpa; cfg.tpaCooldownSeconds = cooldown;
        complete(actor, "Saved settings for rank '" + id + "'.", "rankdetail", id, "SAVE_ROLE_META", id);
    }

    private static int checkedInt(String raw, int min, int max) {
        int value = Integer.parseInt(raw.trim());
        if (value < min || value > max) throw new IllegalArgumentException();
        return value;
    }
    private static int checkedLimit(String raw) { return checkedInt(raw, -1, 10000); }

    private static void toggleRolePermission(ServerPlayer actor, String roleId, String permission, String page, String context) {
        String id = cleanKey(roleId), node = cleanKey(permission);
        RankConfig cfg = ROLE_CONFIGS.get(id);
        if (cfg == null || !PERMISSIONS.contains(node) || !canManageRank(actor, id)) { deny(actor, "permission to edit rank", page, context); return; }
        if (id.equals("owner") && !actor.hasPermissions(4)) { deny(actor, "ranks.edit.owner", page, context); return; }
        boolean current = roleAllows(id, node, new HashSet<>());
        cfg.permissionOverrides.put(node, !current);
        complete(actor, "Permission '" + node + "' is now " + (!current ? "enabled" : "disabled") + " for " + id + ".", "roleperms", id, "TOGGLE_ROLE_PERMISSION", id + ":" + node);
    }

    private static void toggleRoleAll(ServerPlayer actor, String roleId, String page, String context) {
        String id = cleanKey(roleId);
        RankConfig cfg = ROLE_CONFIGS.get(id);
        if (cfg == null || !canManageRank(actor, id)) { deny(actor, "permission to edit rank", page, context); return; }
        if (id.equals("owner") && !actor.hasPermissions(4)) { deny(actor, "ranks.edit.owner", page, context); return; }
        cfg.allPermissions = !cfg.allPermissions;
        complete(actor, "All-permissions mode is now " + cfg.allPermissions + " for " + id + ".", "rankdetail", id, "TOGGLE_ROLE_ALL", id);
    }

    private static void replacePlayerRanks(ServerPlayer actor, String targetName, String roleId, String page, String context) {
        UUID targetId = resolveUuid(targetName);
        if (targetId == null) { error(actor, "Player is not known to this server.", page, context); return; }
        String role = cleanKey(roleId);
        if (!ROLE_CONFIGS.containsKey(role) || !canAssignRole(actor, targetId, role)) { deny(actor, "permission to assign rank", page, context); return; }
        PLAYER_RANKS.put(targetId, new LinkedHashSet<>(List.of(role)));
        complete(actor, "Set " + playerName(targetId) + " to rank '" + role + "'.", "player", playerName(targetId), "SET_RANK", playerName(targetId) + " -> " + role);
    }

    private static void togglePlayerRank(ServerPlayer actor, String targetName, String roleId, String page, String context) {
        UUID targetId = resolveUuid(targetName);
        String role = cleanKey(roleId);
        if (targetId == null || !ROLE_CONFIGS.containsKey(role) || !canAssignRole(actor, targetId, role)) { deny(actor, "permission to assign rank", page, context); return; }
        LinkedHashSet<String> assigned = PLAYER_RANKS.computeIfAbsent(targetId, id -> new LinkedHashSet<>(List.of("member")));
        if (assigned.contains(role)) {
            if (role.equals("owner") && !actor.hasPermissions(4)) { deny(actor, "ranks.edit.owner", page, context); return; }
            if (assigned.size() == 1) { error(actor, "A player must have at least one rank. Assign another rank first.", page, context); return; }
            assigned.remove(role);
        } else assigned.add(role);
        complete(actor, "Updated ranks for " + playerName(targetId) + ". Active ranks: " + String.join(", ", assigned), "assignrank", playerName(targetId), "TOGGLE_PLAYER_RANK", playerName(targetId) + ":" + role);
    }

    private static void savePlayerLimits(ServerPlayer actor, String targetName, String rawValues, String page, String context) {
        if (!can(actor, "players.limits")) { deny(actor, "players.limits", page, context); return; }
        UUID target = resolveUuid(targetName);
        if (target == null || !canModifyPlayer(actor, target)) { deny(actor, "players.limits for that player", page, context); return; }
        String[] values = rawValues.split("\\|", -1);
        if (values.length != 4) { error(actor, "Invalid limits form. Reopen the limits editor and try again.", page, context); return; }
        Integer homes, warps, tpa, cooldown;
        try {
            homes = parseOverrideLimit(values[0]);
            warps = parseOverrideLimit(values[1]);
            tpa = parseOverrideLimit(values[2]);
            cooldown = parseOverrideCooldown(values[3]);
        } catch (IllegalArgumentException ex) {
            error(actor, "Use 'inherit' or a number. Limits accept -1 for unlimited; cooldown accepts 0-86400 seconds.", page, context); return;
        }
        putOverride(HOME_LIMIT_OVERRIDES, target, homes);
        putOverride(WARP_LIMIT_OVERRIDES, target, warps);
        putOverride(TPA_LIMIT_OVERRIDES, target, tpa);
        putOverride(TPA_COOLDOWN_OVERRIDES, target, cooldown);
        complete(actor, "Saved personal limits for " + playerName(target) + ".", "playerlimits", playerName(target), "SAVE_PLAYER_LIMITS", playerName(target));
    }

    private static Integer parseOverrideLimit(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("inherit") || value.equalsIgnoreCase("default")) return null;
        return checkedLimit(value);
    }

    private static Integer parseOverrideCooldown(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("inherit") || value.equalsIgnoreCase("default")) return null;
        return checkedInt(value, 0, 86400);
    }

    private static void putOverride(Map<UUID, Integer> map, UUID player, Integer value) {
        if (value == null) map.remove(player); else map.put(player, value);
    }

    private static void backupData(ServerPlayer actor, String page, String context) {
        if (!can(actor, "settings.manage")) { deny(actor, "settings.manage", page, context); return; }
        if (dataFile == null || !Files.exists(dataFile)) { error(actor, "No saved data file exists yet.", page, context); return; }
        Path manual = dataFile.resolveSibling("sovereigncore-manual-backup.properties");
        try {
            Files.copy(dataFile, manual, StandardCopyOption.REPLACE_EXISTING);
            complete(actor, "Created a manual backup at " + manual.getFileName() + ".", page, context, "BACKUP_DATA", manual.getFileName().toString());
        } catch (IOException ex) {
            LOGGER.error("Could not create SovereignCore backup", ex);
            error(actor, "Backup failed. Check the server log and data folder permissions.", page, context);
        }
    }

    private static void restoreDataBackup(ServerPlayer actor, String page, String context) {
        if (!can(actor, "settings.manage") || (!actor.hasPermissions(4) && !assignedRanks(actor.getUUID()).contains("owner"))) { deny(actor, "Owner / vanilla level 4 to restore backup", page, context); return; }
        if (dataFile == null) { error(actor, "Data file path is not initialized.", page, context); return; }
        Path manual = dataFile.resolveSibling("sovereigncore-manual-backup.properties");
        if (!Files.exists(manual)) { error(actor, "No manual backup exists yet. Create one first.", page, context); return; }
        try {
            Files.copy(manual, dataFile, StandardCopyOption.REPLACE_EXISTING);
            loadData();
            complete(actor, "Restored the manual backup. Re-check ranks and limits before continuing.", "settings", "", "RESTORE_DATA", manual.getFileName().toString());
        } catch (IOException ex) {
            LOGGER.error("Could not restore SovereignCore backup", ex);
            error(actor, "Restore failed. Current server data was not intentionally cleared.", page, context);
        }
    }

    private static void setPlayerHomeLimit(ServerPlayer actor, String targetName, String rawValue, String page, String context) {
        if (!can(actor, "players.limits")) { deny(actor, "players.limits", page, context); return; }
        UUID target = resolveUuid(targetName);
        if (target == null || !canModifyPlayer(actor, target)) { deny(actor, "players.limits for that player", page, context); return; }
        if (rawValue.equalsIgnoreCase("inherit") || rawValue.equalsIgnoreCase("default")) HOME_LIMIT_OVERRIDES.remove(target);
        else {
            int limit;
            try { limit = checkedLimit(rawValue); }
            catch (IllegalArgumentException ex) { error(actor, "Home limit must be -1 or a number from 0 to 10000.", page, context); return; }
            HOME_LIMIT_OVERRIDES.put(target, limit);
        }
        complete(actor, "Saved Home limit for " + playerName(target) + ".", "player", playerName(target), "SET_PLAYER_HOME_LIMIT", playerName(target) + ":" + rawValue);
    }

    // Player moderation and inventory -----------------------------------------------------------

    private static void toggleFreeze(ServerPlayer actor, String targetName, String page, String context) {
        if (!can(actor, "players.freeze")) { deny(actor, "players.freeze", page, context); return; }
        ServerPlayer target = findOnline(targetName);
        if (target == null) { error(actor, "Player is not online: " + targetName, page, context); return; }
        if (!canModifyPlayer(actor, target.getUUID())) { deny(actor, "freeze a higher rank", page, context); return; }
        if (FROZEN_PLAYERS.remove(target.getUUID()) != null) {
            tell(target, "§aYou have been unfrozen.");
            complete(actor, "Unfroze " + targetName + ".", page, context, "UNFREEZE", targetName);
        } else {
            FROZEN_PLAYERS.put(target.getUUID(), currentPosition(target));
            tell(target, "§cYou have been frozen by staff.");
            complete(actor, "Froze " + targetName + ".", page, context, "FREEZE", targetName);
        }
    }

    private static void removeInventorySlot(ServerPlayer actor, String targetName, String arg, boolean ender, String page, String context) {
        String permission = ender ? "players.enderchest.edit" : "players.inventory.edit";
        if (!can(actor, permission)) { deny(actor, permission, page, context); return; }
        ServerPlayer target = findOnline(targetName);
        if (target == null || !canModifyPlayer(actor, target.getUUID())) { error(actor, "Target is offline or not manageable.", page, context); return; }
        int slot;
        try { slot = Integer.parseInt(arg); } catch (NumberFormatException ex) { error(actor, "Invalid inventory slot.", page, context); return; }
        if (ender) {
            if (slot < 0 || slot >= target.getEnderChestInventory().getContainerSize()) { error(actor, "Invalid ender chest slot.", page, context); return; }
            target.getEnderChestInventory().setItem(slot, ItemStack.EMPTY);
        } else {
            if (slot < 0 || slot >= target.getInventory().getContainerSize()) { error(actor, "Invalid inventory slot.", page, context); return; }
            target.getInventory().setItem(slot, ItemStack.EMPTY);
        }
        target.containerMenu.broadcastChanges();
        complete(actor, "Removed item from " + targetName + " slot " + slot + ".", "inspect", targetName + "|" + (ender ? "ender" : "inventory"), ender ? "REMOVE_ENDER_SLOT" : "REMOVE_INV_SLOT", targetName + " slot " + slot);
    }

    private static void giveItem(ServerPlayer actor, String targetName, String itemAndCount, String page, String context) {
        if (!can(actor, "items.give")) { deny(actor, "items.give", page, context); return; }
        ServerPlayer target = findOnline(targetName);
        if (target == null || !canModifyPlayer(actor, target.getUUID())) { error(actor, "Target is offline or not manageable.", page, context); return; }
        String[] bits = itemAndCount.trim().split("\\s+");
        String item = bits.length > 0 ? bits[0] : "";
        int count = 1;
        try { if (bits.length > 1) count = Integer.parseInt(bits[1]); } catch (NumberFormatException ex) { count = -1; }
        if (!SAFE_ITEM.matcher(item).matches() || count < 1 || count > 64) { error(actor, "Use item ID and optional count, e.g. minecraft:diamond 4 (1-64).", page, context); return; }
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "give " + target.getGameProfile().getName() + " " + item + " " + count);
        complete(actor, "Gave " + count + " x " + item + " to " + targetName + ".", page, context, "GIVE_ITEM", targetName + " " + count + "x" + item);
    }

    private static void moderateTarget(ServerPlayer actor, String targetName, String reason, String action, String page, String context) {
        String permission = action.equals("kick") ? "players.kick" : "players.ban";
        if (!can(actor, permission)) { deny(actor, permission, page, context); return; }
        ServerPlayer target = findOnline(targetName);
        if (target == null) { error(actor, "Player is not online: " + targetName, page, context); return; }
        if (!canModifyPlayer(actor, target.getUUID())) { deny(actor, action + " a higher rank", page, context); return; }
        String safeReason = sanitizeReason(reason, "Removed by staff");
        if (action.equals("kick")) target.connection.disconnect(Component.literal(safeReason));
        complete(actor, "Kicked " + targetName + ".", page, context, "KICK", targetName + ":" + safeReason);
    }

    private static String sanitizeReason(String reason, String fallback) {
        String safe = reason == null ? "" : reason.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        if (safe.isBlank()) safe = fallback;
        return safe.length() > 140 ? safe.substring(0, 140) : safe;
    }

    private static void banTarget(ServerPlayer actor, String targetName, String reason, String page, String context) {
        if (!can(actor, "players.ban")) { deny(actor, "players.ban", page, context); return; }
        if (!SAFE_PLAYER.matcher(targetName).matches()) { error(actor, "Invalid Minecraft username.", page, context); return; }
        UUID id = resolveUuid(targetName);
        if (id != null && !canModifyPlayer(actor, id)) { deny(actor, "ban a higher rank", page, context); return; }
        String safeReason = sanitizeReason(reason, "Banned by staff");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "ban " + targetName + " " + safeReason);
        MOD_BAN_REASONS.put(targetName.toLowerCase(Locale.ROOT), safeReason);
        complete(actor, "Ban command executed for " + targetName + ".", page, context, "BAN", targetName + ":" + safeReason);
    }

    private static void pardonTarget(ServerPlayer actor, String targetName, String page, String context) {
        if (!can(actor, "players.unban")) { deny(actor, "players.unban", page, context); return; }
        if (!SAFE_PLAYER.matcher(targetName).matches()) { error(actor, "Invalid Minecraft username.", page, context); return; }
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "pardon " + targetName);
        MOD_BAN_REASONS.remove(targetName.toLowerCase(Locale.ROOT));
        complete(actor, "Pardon command executed for " + targetName + ".", page, context, "PARDON", targetName);
    }

    private static void pardonIp(ServerPlayer actor, String ip, String page, String context) {
        if (!can(actor, "players.unban")) { deny(actor, "players.unban", page, context); return; }
        if (!ip.matches("[0-9a-fA-F:.]{3,45}")) { error(actor, "Invalid IP address format.", page, context); return; }
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "pardon-ip " + ip);
        complete(actor, "Pardon-IP command executed.", page, context, "PARDON_IP", ip);
    }

    private static void opTarget(ServerPlayer actor, String targetName, boolean op, String page, String context) {
        if (!actor.hasPermissions(4)) { deny(actor, "vanilla permission level 4", page, context); return; }
        if (!SAFE_PLAYER.matcher(targetName).matches()) { error(actor, "Invalid Minecraft username.", page, context); return; }
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), (op ? "op " : "deop ") + targetName);
        complete(actor, (op ? "OP command" : "DEOP command") + " executed for " + targetName + ".", page, context, op ? "OP" : "DEOP", targetName);
    }

    private static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player) || player.level().isClientSide) return;
        SavedPos anchor = FROZEN_PLAYERS.get(player.getUUID());
        if (anchor == null) return;
        ServerLevel level = getLevel(anchor.dimension);
        if (level == null) return;
        if (player.serverLevel() != level || player.distanceToSqr(anchor.x, anchor.y, anchor.z) > 0.20) {
            player.teleportTo(level, anchor.x, anchor.y, anchor.z, anchor.yaw, anchor.pitch);
        }
        player.setDeltaMovement(0, 0, 0);
        player.setSprinting(false);
    }

    // Commands ----------------------------------------------------------------------------------

    private void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("sc")
                .executes(c -> commandPlayer(c.getSource(), SovereignCore::sendMain))
                .then(Commands.literal("reload").requires(s -> s.hasPermission(4)).executes(c -> {
                    loadData(); c.getSource().sendSuccess(() -> Component.literal("SovereignCore data reloaded."), false); return 1;
                }))
                .then(Commands.literal("rank").then(Commands.argument("player", StringArgumentType.word()).then(Commands.argument("rank", StringArgumentType.word()).executes(c -> commandPlayer(c.getSource(), p -> replacePlayerRanks(p, StringArgumentType.getString(c, "player"), StringArgumentType.getString(c, "rank"), "main", "")))))));
        d.register(Commands.literal("manager").executes(c -> commandPlayer(c.getSource(), SovereignCore::sendMain)));
        d.register(Commands.literal("sethome").executes(c -> commandPlayer(c.getSource(), p -> setHome(p, "home", "main", "")))
                .then(Commands.argument("name", StringArgumentType.word()).executes(c -> commandPlayer(c.getSource(), p -> setHome(p, StringArgumentType.getString(c, "name"), "main", "")))));
        d.register(Commands.literal("home").executes(c -> commandPlayer(c.getSource(), p -> sendHomes(p, "")))
                .then(Commands.argument("name", StringArgumentType.word()).executes(c -> commandPlayer(c.getSource(), p -> handleDirectHome(p, StringArgumentType.getString(c, "name"))))));
        d.register(Commands.literal("homes").executes(c -> commandPlayer(c.getSource(), p -> sendHomes(p, ""))));
        d.register(Commands.literal("delhome").then(Commands.argument("name", StringArgumentType.word()).executes(c -> commandPlayer(c.getSource(), p -> deleteHome(p, p.getUUID(), StringArgumentType.getString(c, "name"), "main", "")))));
        d.register(Commands.literal("setwarp").then(Commands.argument("name", StringArgumentType.word()).executes(c -> commandPlayer(c.getSource(), p -> setWarp(p, StringArgumentType.getString(c, "name"), "public", "main", "")))));
        d.register(Commands.literal("warp").executes(c -> commandPlayer(c.getSource(), SovereignCore::sendWarps))
                .then(Commands.argument("name", StringArgumentType.word()).executes(c -> commandPlayer(c.getSource(), p -> useWarp(p, StringArgumentType.getString(c, "name"), "main", "")))));
        d.register(Commands.literal("warps").executes(c -> commandPlayer(c.getSource(), SovereignCore::sendWarps)));
        d.register(Commands.literal("delwarp").then(Commands.argument("name", StringArgumentType.word()).executes(c -> commandPlayer(c.getSource(), p -> deleteWarp(p, StringArgumentType.getString(c, "name"), "main", "")))));
        d.register(Commands.literal("setspawn").executes(c -> commandPlayer(c.getSource(), p -> handleAction(p, "SET_SPAWN", "", "", "settings", ""))));
        d.register(Commands.literal("spawn").executes(c -> commandPlayer(c.getSource(), p -> handleAction(p, "SPAWN", "", "", "main", ""))));
        d.register(Commands.literal("tpa").then(Commands.argument("player", StringArgumentType.word()).executes(c -> commandPlayer(c.getSource(), p -> requestTeleport(p, StringArgumentType.getString(c, "player"), "main", "")))));
        d.register(Commands.literal("tpaccept").executes(c -> commandPlayer(c.getSource(), p -> acceptTeleport(p, "main", ""))));
        d.register(Commands.literal("tpdeny").executes(c -> commandPlayer(c.getSource(), p -> denyTeleport(p, "main", ""))));
        d.register(Commands.literal("sctp").then(Commands.argument("player", StringArgumentType.word()).executes(c -> commandPlayer(c.getSource(), p -> handleAction(p, "TP_TO", StringArgumentType.getString(c, "player"), "", "players", "")))));
    }

    private static int commandPlayer(CommandSourceStack source, java.util.function.Consumer<ServerPlayer> action) {
        if (source.getEntity() instanceof ServerPlayer player) action.accept(player);
        else source.sendFailure(Component.literal("This command must be used in game by a player."));
        return 1;
    }

    private static void handleDirectHome(ServerPlayer actor, String name) {
        if (!can(actor, "homes.use")) { deny(actor, "homes.use", "main", ""); return; }
        SavedPos pos = HOMES.getOrDefault(actor.getUUID(), Collections.emptyMap()).get(cleanKey(name));
        if (pos == null) { tell(actor, "§cHome not found: " + name); return; }
        if (teleport(actor, pos)) { audit(actor, "HOME", name); saveData(); }
    }

    private static final class TeleportRequest {
        final UUID requester;
        final long createdAt;
        TeleportRequest(UUID requester, long createdAt) { this.requester = requester; this.createdAt = createdAt; }
    }
}

package com.sovereigncore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;

/**
 * Dashboard-oriented Forge screen. Long lists are drawn in a clipped, scrollable region;
 * fixed navigation/action buttons remain visible at the bottom/top of the panel.
 */
public final class ManagerScreen extends Screen {
    private final String page;
    private final String data;
    private final String mainData;
    private final String forcedReturnPage;
    private final String forcedReturnContext;

    private int panelX, panelY, panelW, panelH, sidebarW, contentX, contentW;
    private int listTop, listBottom, rowHeight = 29, scrollOffset;
    private final List<NavItem> sidebarItems = new ArrayList<>();
    private final List<RunnableCard> dashboardCards = new ArrayList<>();
    private int sidebarScrollOffset;
    private int dashboardScrollOffset;
    private EditBox searchBox;
    private EditBox fieldA, fieldB, fieldC, fieldD, fieldE, fieldF, fieldG, fieldH, fieldI, fieldJ;
    private String targetName = "";
    private String rankId = "";
    private String inventoryKind = "inventory";
    private String searchText = "";
    private String confirmAction = "";
    private String confirmArg1 = "";
    private String confirmArg2 = "";
    private String confirmPage = "main";
    private String confirmContext = "";
    private String confirmPrompt = "Are you sure?";

    public ManagerScreen(String page, String data, String mainData) {
        super(Component.literal("SovereignCore"));
        this.page = page == null ? "main" : page;
        this.data = data == null ? "" : data;
        this.mainData = mainData == null ? "" : mainData;
        this.forcedReturnPage = "";
        this.forcedReturnContext = "";
        parsePageContext();
    }

    private ManagerScreen(String mainData, String action, String arg1, String arg2, String returnPage, String returnContext, String prompt) {
        super(Component.literal("Confirm action"));
        this.page = "confirm";
        this.data = "";
        this.mainData = mainData == null ? "" : mainData;
        this.forcedReturnPage = returnPage == null ? "main" : returnPage;
        this.forcedReturnContext = returnContext == null ? "" : returnContext;
        this.confirmAction = action;
        this.confirmArg1 = arg1;
        this.confirmArg2 = arg2;
        this.confirmPage = this.forcedReturnPage;
        this.confirmContext = this.forcedReturnContext;
        this.confirmPrompt = prompt == null ? "Are you sure?" : prompt;
    }

    private void parsePageContext() {
        Map<String, String> values = keyValues(data);
        if (page.equals("player")) targetName = values.getOrDefault("name", "");
        if (page.equals("playerlimits")) targetName = values.getOrDefault("name", "");
        if (page.equals("rankdetail")) rankId = values.getOrDefault("id", "");
        if (page.equals("roleperms")) rankId = firstLine(data);
        if (page.equals("assignrank")) {
            List<String> lines = lines(data);
            if (lines.size() > 1 && lines.get(1).startsWith("name=")) targetName = lines.get(1).substring(5);
        }
        if (page.equals("inspect")) {
            targetName = values.getOrDefault("target", "");
            inventoryKind = values.getOrDefault("kind", "inventory");
        }
        if (page.equals("homes")) targetName = values.getOrDefault("ownerName", "");
    }

    @Override
    protected void init() {
        super.init();
        labelLines.clear();
        panelW = Math.min(900, Math.max(280, width - 12));
        panelH = Math.min(570, Math.max(210, height - 12));
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        sidebarW = Math.min(158, Math.max(76, panelW / 5));
        contentX = panelX + sidebarW + 10;
        contentW = Math.max(150, panelW - sidebarW - 22);
        listTop = panelY + 101;
        listBottom = panelY + panelH - 62;

        buildSidebar();
        switch (page) {
            case "main" -> buildMain();
            case "players" -> buildPlayers();
            case "player" -> buildPlayer();
            case "playerlimits" -> buildPlayerLimits();
            case "teleport" -> buildTeleport();
            case "homes" -> buildHomes();
            case "warps" -> buildWarps();
            case "ranks" -> buildRanks();
            case "rankdetail" -> buildRankDetail();
            case "roleperms" -> buildRolePermissions();
            case "assignrank" -> buildAssignRanks();
            case "bans" -> buildBans();
            case "logs" -> buildLogs();
            case "personal" -> buildPersonal();
            case "settings" -> buildSettings();
            case "inspect" -> buildInspect();
            case "notice" -> buildNotice();
            case "confirm" -> buildConfirm();
            default -> addButton("Back to dashboard", contentX, panelY + panelH - 38, contentW, 22, () -> request("REQ_MAIN", "", "", "main", ""));
        }
    }

    private void buildSidebar() {
        sidebarItems.clear();
        int x = panelX + 8, w = sidebarW - 16;
        Set<String> panels = csv(keyValues(mainData).getOrDefault("panels", ""));
        sidebarItems.add(new NavItem("Overview", () -> request("REQ_MAIN", "", "", "main", "")));
        if (panels.contains("players")) sidebarItems.add(new NavItem("Players", () -> request("REQ_PLAYERS", "", "", "players", "")));
        sidebarItems.add(new NavItem("My homes", () -> request("REQ_HOME_LIST", "", "", "homes", "")));
        sidebarItems.add(new NavItem("Warps", () -> request("REQ_WARP_LIST", "", "", "warps", "")));
        sidebarItems.add(new NavItem("Teleport", () -> setLocalPage("teleport", "")));
        if (panels.contains("ranks")) sidebarItems.add(new NavItem("Ranks", () -> request("REQ_RANKS", "", "", "ranks", "")));
        if (panels.contains("moderation")) sidebarItems.add(new NavItem("Moderation", () -> request("REQ_BANS", "", "", "bans", "")));
        if (panels.contains("logs")) sidebarItems.add(new NavItem("Audit logs", () -> request("REQ_LOGS", "", "", "logs", "")));
        if (panels.contains("settings")) sidebarItems.add(new NavItem("Settings", () -> setLocalPage("settings", "")));
        addButton("Personal", x, panelY + panelH - 61, w, 23, () -> setLocalPage("personal", ""));
        addButton("Close", x, panelY + panelH - 33, w, 22, this::onClose);
    }

    private void buildMain() {
        dashboardCards.clear();
        Set<String> panels = csv(keyValues(mainData).getOrDefault("panels", ""));
        if (panels.contains("players")) dashboardCards.add(new RunnableCard("Player management", () -> request("REQ_PLAYERS", "", "", "players", "")));
        dashboardCards.add(new RunnableCard("My homes", () -> request("REQ_HOME_LIST", "", "", "homes", "")));
        dashboardCards.add(new RunnableCard("Teleport center", () -> setLocalPage("teleport", "")));
        dashboardCards.add(new RunnableCard("Warps", () -> request("REQ_WARP_LIST", "", "", "warps", "")));
        if (panels.contains("ranks")) dashboardCards.add(new RunnableCard("Ranks & permissions", () -> request("REQ_RANKS", "", "", "ranks", "")));
        if (panels.contains("moderation")) dashboardCards.add(new RunnableCard("Moderation / unban", () -> request("REQ_BANS", "", "", "bans", "")));
        if (panels.contains("settings")) dashboardCards.add(new RunnableCard("Server settings", () -> request("REQ_PAGE", "settings", "", "settings", "")));
        if (panels.contains("logs")) dashboardCards.add(new RunnableCard("Audit history", () -> request("REQ_LOGS", "", "", "logs", "")));
        String role = keyValues(mainData).getOrDefault("role", "member");
        switch (role) {
            case "owner" -> dashboardCards.add(new RunnableCard("Owner control center", () -> request("REQ_PLAYERS", "", "", "players", "")));
            case "admin" -> dashboardCards.add(new RunnableCard("Admin quick controls", () -> request("REQ_PLAYERS", "", "", "players", "")));
            case "moderator" -> dashboardCards.add(new RunnableCard("Moderation queue", () -> request("REQ_BANS", "", "", "bans", "")));
            case "vip" -> dashboardCards.add(new RunnableCard("VIP benefits", () -> request("REQ_HOME_LIST", "", "", "homes", "")));
            case "builder" -> dashboardCards.add(new RunnableCard("Builder tools", () -> setLocalPage("teleport", "")));
            default -> dashboardCards.add(new RunnableCard("Member quick access", () -> setLocalPage("personal", "")));
        }
        dashboardScrollOffset = Math.max(0, Math.min(dashboardScrollOffset, maxDashboardScrollRows()));
    }

    private void buildPlayers() {
        buildSearchBox("Search online players…");
        fieldA = textField(contentX + 8, panelY + panelH - 54, contentW - 112, 20, "Open offline player by username");
        addButton("Open player", contentX + contentW - 96, panelY + panelH - 54, 88, 20,
                () -> request("REQ_PLAYER", value(fieldA), "", "players", ""));
    }

    private void buildPlayer() {
        Map<String, String> info = keyValues(data);
        String actualName = info.getOrDefault("name", targetName);
        int x = contentX + 8, y = panelY + 56, gap = 7;
        int w = (contentW - 24) / 2;
        String online = info.getOrDefault("online", "false").equals("true") ? "Online" : "Offline";
        boolean isOnline = info.getOrDefault("online", "false").equals("true");
        if (isOnline) {
            fieldA = textField(x, y + 40, contentW - 16, 20, "Reason for moderation / item ID and count");
            int by = y + 67;
            addButton("Teleport to player", x, by, w, 21, () -> confirm("TP_TO", actualName, "", "player", actualName, "Teleport to " + actualName + "?"));
            addButton("Bring player here", x + w + gap, by, w, 21, () -> confirm("TP_HERE", actualName, "", "player", actualName, "Bring " + actualName + " to your location?")); by += 27;
            addButton("View / manage homes", x, by, w, 21, () -> request("REQ_HOME_LIST", actualName, "", "homes", actualName));
            addButton("Assign ranks", x + w + gap, by, w, 21, () -> request("REQ_ASSIGN_RANK", actualName, "", "assignrank", actualName)); by += 27;
            addButton("Inspect inventory", x, by, w, 21, () -> request("REQ_INSPECT", actualName, "inventory", "inspect", actualName + "|inventory"));
            addButton("Inspect ender chest", x + w + gap, by, w, 21, () -> request("REQ_INSPECT", actualName, "ender", "inspect", actualName + "|ender")); by += 27;
            addButton("Heal", x, by, w, 21, () -> act("HEAL", actualName, "", "player", actualName));
            addButton("Feed", x + w + gap, by, w, 21, () -> act("FEED", actualName, "", "player", actualName)); by += 27;
            addButton("Gamemode: Survival", x, by, w, 21, () -> act("GAMEMODE", actualName, "survival", "player", actualName));
            addButton("Gamemode: Creative", x + w + gap, by, w, 21, () -> act("GAMEMODE", actualName, "creative", "player", actualName)); by += 27;
            addButton("Toggle flight", x, by, w, 21, () -> act("TOGGLE_FLY", actualName, "", "player", actualName));
            addButton("Toggle invulnerability", x + w + gap, by, w, 21, () -> act("TOGGLE_GOD", actualName, "", "player", actualName)); by += 27;
            addButton("Freeze / unfreeze", x, by, w, 21, () -> act("TOGGLE_FREEZE", actualName, "", "player", actualName));
            addButton("Clear effects", x + w + gap, by, w, 21, () -> confirm("CLEAR_EFFECTS", actualName, "", "player", actualName, "Remove all potion effects from " + actualName + "?")); by += 27;
            addButton("Edit limits…", x, by, w, 21, () -> request("REQ_PLAYER_LIMITS", actualName, "", "playerlimits", actualName));
            addButton("Give item from input", x + w + gap, by, w, 21, () -> act("GIVE_ITEM", actualName, value(fieldA), "player", actualName)); by += 27;
            addButton("Clear inventory", x, by, w, 21, () -> confirm("INV_CLEAR", actualName, "", "player", actualName, "Clear every inventory slot for " + actualName + "?"));
            addButton("Clear ender chest", x + w + gap, by, w, 21, () -> confirm("ENDER_CLEAR", actualName, "", "player", actualName, "Clear the ender chest of " + actualName + "?")); by += 27;
            addButton("Clear XP", x, by, w, 21, () -> confirm("CLEAR_XP", actualName, "", "player", actualName, "Clear all XP for " + actualName + "?"));
            addButton("Kick…", x + w + gap, by, w, 21, () -> confirm("KICK", actualName, value(fieldA), "player", actualName, "Kick " + actualName + " from this server?")); by += 27;
            addButton("Ban…", x, by, w, 21, () -> confirm("BAN", actualName, value(fieldA), "player", actualName, "Ban " + actualName + "?"));
            addButton("Pardon…", x + w + gap, by, w, 21, () -> confirm("PARDON", actualName, "", "player", actualName, "Remove the server ban for " + actualName + "?"));
        } else {
            fieldA = textField(x, y + 40, w, 20, "Offline player home limit (use limits page)");
            addButton("Manage saved homes", x, y + 67, w, 22, () -> request("REQ_HOME_LIST", actualName, "", "homes", actualName));
            addButton("Assign ranks", x + w + gap, y + 67, w, 22, () -> request("REQ_ASSIGN_RANK", actualName, "", "assignrank", actualName));
            addButton("Edit limits…", x, y + 96, w, 22, () -> request("REQ_PLAYER_LIMITS", actualName, "", "playerlimits", actualName));
            addButton("Refresh profile", x + w + gap, y + 96, w, 22, () -> request("REQ_PLAYER", actualName, "", "player", actualName));
            labelLines.add("This player is offline. Teleport and live inventory actions appear when they join.@" + x + "," + (y + 130));
        }
        labelLines.add("Target: " + actualName + "   •   " + online + "   •   Rank(s): " + info.getOrDefault("rankLabels", info.getOrDefault("rank", "member")) + "@" + (contentX + 8) + "," + (panelY + 47));
        labelLines.add("Dimension: " + info.getOrDefault("dimension", "unknown") + "   •   Health: " + info.getOrDefault("health", "-") + "@" + (contentX + 8) + "," + (panelY + 61));
        labelLines.add("Effective home limit: " + info.getOrDefault("homeLimit", "3") + "@" + (contentX + 8) + "," + (panelY + 75));
    }

    private void buildPlayerLimits() {
        Map<String, String> values = keyValues(data);
        String name = values.getOrDefault("name", targetName);
        labelLines.add("Player-specific overrides: enter 'inherit' to use the rank default, -1 for unlimited limits.@" + (contentX + 8) + "," + (panelY + 52));
        labelLines.add("Effective values — homes: " + values.getOrDefault("effectiveHomes", "-") + ", warps: " + values.getOrDefault("effectiveWarps", "-")
                + ", TPA/hour: " + values.getOrDefault("effectiveTpa", "-") + ", cooldown: " + values.getOrDefault("effectiveCooldown", "-") + "s@" + (contentX + 8) + "," + (panelY + 67));
        int x = contentX + 8, y = panelY + 97, gap = 8, colW = (contentW - 24) / 2;
        fieldA = labelledField("Home limit", values.getOrDefault("homeOverride", "inherit"), x, y, colW);
        fieldB = labelledField("Personal warp limit", values.getOrDefault("warpOverride", "inherit"), x + colW + gap, y, colW);
        fieldC = labelledField("TPA requests per hour", values.getOrDefault("tpaOverride", "inherit"), x, y + 55, colW);
        fieldD = labelledField("TPA cooldown seconds", values.getOrDefault("cooldownOverride", "inherit"), x + colW + gap, y + 55, colW);
        int by = panelY + panelH - 58;
        addButton("Save limits", x, by, colW, 22, () -> act("SAVE_PLAYER_LIMITS", name,
                value(fieldA) + "|" + value(fieldB) + "|" + value(fieldC) + "|" + value(fieldD), "playerlimits", name));
        addButton("Reset all to rank defaults", x + colW + gap, by, colW, 22, () -> act("SAVE_PLAYER_LIMITS", name, "inherit|inherit|inherit|inherit", "playerlimits", name));
        addButton("Back to player", x, by + 27, contentW - 16, 21, () -> request("REQ_PLAYER", name, "", "player", name));
    }

    private void buildTeleport() {
        int x = contentX + 8, y = panelY + 68, w = contentW - 16;
        fieldA = textField(x, y, w, 20, "Player name");
        fieldB = textField(x, y + 34, w, 20, "x,y,z  OR  minecraft:the_nether|x,y,z");
        addButton("Teleport to player", x, y + 62, w / 2 - 4, 22, () -> confirm("TP_TO", value(fieldA), "", "teleport", "", "Teleport to " + value(fieldA) + "?"));
        addButton("Bring player here", x + w / 2 + 4, y + 62, w / 2 - 4, 22, () -> confirm("TP_HERE", value(fieldA), "", "teleport", "", "Bring " + value(fieldA) + " to you?"));
        addButton("Teleport to coordinates / dimension", x, y + 91, w, 22, () -> act("TP_COORDS", value(fieldB), "", "teleport", ""));
        addButton("Request TPA", x, y + 120, w / 2 - 4, 22, () -> act("TPA", value(fieldA), "", "teleport", ""));
        addButton("Accept request", x + w / 2 + 4, y + 120, w / 2 - 4, 22, () -> act("TP_ACCEPT", "", "", "teleport", ""));
        addButton("Deny request", x, y + 149, w, 22, () -> act("TP_DENY", "", "", "teleport", ""));
        labelLines.add("Coordinates example: 120,70,-45  •  Nether: minecraft:the_nether|10,64,10");
    }

    private void buildHomes() {
        buildSearchBox("Filter homes by owner, name or dimension…");
        fieldA = textField(contentX + 8, panelY + panelH - 54, contentW - 212, 20, "New home name");
        addButton("Set my home", contentX + contentW - 197, panelY + panelH - 54, 94, 20,
                () -> act("SET_HOME", value(fieldA), "", "homes", targetName));
        addButton("My homes", contentX + contentW - 96, panelY + panelH - 54, 88, 20,
                () -> request("REQ_HOME_LIST", "", "", "homes", ""));
    }

    private void buildWarps() {
        buildSearchBox("Filter public and personal warps…");
        fieldA = textField(contentX + 8, panelY + panelH - 54, contentW - 282, 20, "New warp name");
        addButton("Personal", contentX + contentW - 267, panelY + panelH - 54, 81, 20,
                () -> act("SET_WARP", value(fieldA), "personal", "warps", ""));
        addButton("Public", contentX + contentW - 180, panelY + panelH - 54, 77, 20,
                () -> act("SET_WARP", value(fieldA), "public", "warps", ""));
        addButton("Refresh", contentX + contentW - 96, panelY + panelH - 54, 88, 20,
                () -> request("REQ_WARP_LIST", "", "", "warps", ""));
    }

    private void buildRanks() {
        buildSearchBox("Search ranks by ID or display name…");
        fieldA = textField(contentX + 8, panelY + panelH - 54, contentW - 116, 20, "New rank ID");
        addButton("Create rank", contentX + contentW - 105, panelY + panelH - 54, 97, 20,
                () -> act("CREATE_ROLE", value(fieldA), "", "ranks", ""));
    }

    private void buildRankDetail() {
        Map<String, String> v = keyValues(data);
        int x = contentX + 8, y = panelY + 68, gap = 8;
        int colW = (contentW - 24) / 2;
        labelLines.add("Configure rank identity, inheritance and limits. Use -1 for unlimited.");
        fieldA = labelledField("Display name", v.getOrDefault("label", ""), x, y, colW); // label
        fieldB = labelledField("Color (#RRGGBB)", v.getOrDefault("color", "#8795A8"), x + colW + gap, y, colW);
        fieldC = labelledField("Priority (higher wins)", v.getOrDefault("priority", "100"), x, y + 47, colW);
        fieldD = labelledField("Parent rank (blank = none)", v.getOrDefault("parent", ""), x + colW + gap, y + 47, colW);
        fieldE = labelledField("Home limit", v.getOrDefault("homes", "3"), x, y + 94, colW);
        fieldF = labelledField("Personal warp limit", v.getOrDefault("warps", "0"), x + colW + gap, y + 94, colW);
        fieldG = labelledField("TPA requests/hour", v.getOrDefault("tpa", "5"), x, y + 141, colW);
        fieldH = labelledField("TPA cooldown seconds", v.getOrDefault("cooldown", "30"), x + colW + gap, y + 141, colW);
        fieldI = labelledField("Rank description (optional)", v.getOrDefault("description", ""), x, y + 188, colW);
        fieldJ = labelledField("Rank icon / symbol", v.getOrDefault("icon", "◆"), x + colW + gap, y + 188, colW);
        labelLines.add("All-permissions mode: " + v.getOrDefault("all", "false") + " (toggle with the button below)@" + x + "," + (y + 233));
        int by = panelY + panelH - 65;
        addButton("Save settings", x, by, colW, 22, () -> act("SAVE_ROLE_META", rankId,
                value(fieldA) + "|" + value(fieldB) + "|" + value(fieldC) + "|" + value(fieldD) + "|" + value(fieldE) + "|" + value(fieldF) + "|" + value(fieldG) + "|" + value(fieldH) + "|" + value(fieldI) + "|" + value(fieldJ), "rankdetail", rankId));
        addButton("Edit permissions", x + colW + gap, by, colW, 22, () -> request("REQ_ROLE_PERMS", rankId, "", "roleperms", rankId));
        addButton("Toggle all-permissions", x, by + 27, colW, 22, () -> act("TOGGLE_ROLE_ALL", rankId, "", "rankdetail", rankId));
        addButton("Delete rank…", x + colW + gap, by + 27, colW, 22, () -> confirm("DELETE_ROLE", rankId, "", "ranks", "", "Delete rank '" + rankId + "' and reassign affected users?"));
    }

    private void buildRolePermissions() {
        buildSearchBox("Filter permission names…");
        addButton("Back to rank", contentX + 8, panelY + panelH - 54, contentW - 16, 20,
                () -> request("REQ_RANK_DETAIL", rankId, "", "rankdetail", rankId));
    }

    private void buildAssignRanks() {
        List<String> all = lines(data);
        String id = all.isEmpty() ? "" : all.get(0).replace("target=", "");
        String name = all.size() > 1 && all.get(1).startsWith("name=") ? all.get(1).substring(5) : targetName;
        targetName = name;
        labelLines.add("Click a rank to toggle it for " + name + ". Highest priority determines the primary rank.");
        buildSearchBox("Filter available ranks…");
        addButton("Back to player", contentX + 8, panelY + panelH - 54, contentW - 16, 20,
                () -> request("REQ_PLAYER", name, "", "player", name));
    }

    private void buildBans() {
        buildSearchBox("Search banned usernames…");
        fieldA = textField(contentX + 8, panelY + panelH - 54, contentW - 116, 20, "IP address to unban");
        addButton("Unban IP…", contentX + contentW - 105, panelY + panelH - 54, 97, 20,
                () -> confirm("PARDON_IP", value(fieldA), "", "bans", "", "Remove the IP ban for " + value(fieldA) + "?"));
    }
    private void buildLogs() { buildSearchBox("Search audit entries…"); }

    private void buildPersonal() {
        int x = contentX + 8, y = panelY + 70, w = contentW - 16;
        fieldA = textField(x, y, w, 20, "Home or player name");
        addButton("Set home", x, y + 32, w, 22, () -> act("SET_HOME", value(fieldA), "", "personal", ""));
        addButton("Teleport to home", x, y + 61, w, 22, () -> act("HOME", value(fieldA), "", "personal", ""));
        addButton("Browse my homes", x, y + 90, w, 22, () -> request("REQ_HOME_LIST", "", "", "homes", ""));
        addButton("Request teleport to player", x, y + 119, w, 22, () -> act("TPA", value(fieldA), "", "personal", ""));
        addButton("Accept pending TPA", x, y + 148, w, 22, () -> act("TP_ACCEPT", "", "", "personal", ""));
        addButton("Deny pending TPA", x, y + 177, w, 22, () -> act("TP_DENY", "", "", "personal", ""));
        addButton("Teleport to spawn", x, y + 206, w, 22, () -> act("SPAWN", "", "", "personal", ""));
        addButton("Browse warps", x, y + 235, w, 22, () -> request("REQ_WARP_LIST", "", "", "warps", ""));
    }

    private void buildSettings() {
        int x = contentX + 8, y = panelY + 70, w = contentW - 16;
        addButton("Set server spawn here", x, y, w, 22, () -> confirm("SET_SPAWN", "", "", "settings", "", "Set the server spawn at your current location?"));
        addButton("Teleport to spawn", x, y + 29, w, 22, () -> act("SPAWN", "", "", "settings", ""));
        addButton("Manage public / personal warps", x, y + 58, w, 22, () -> request("REQ_WARP_LIST", "", "", "warps", ""));
        addButton("Manage ranks", x, y + 87, w, 22, () -> request("REQ_RANKS", "", "", "ranks", ""));
        addButton("View audit logs", x, y + 116, w, 22, () -> request("REQ_LOGS", "", "", "logs", ""));
        addButton("Create manual data backup", x, y + 145, w, 22, () -> confirm("BACKUP_DATA", "", "", "settings", "", "Create a backup of the current SovereignCore settings and player data?"));
        addButton("Restore manual backup…", x, y + 174, w, 22, () -> confirm("RESTORE_DATA", "", "", "settings", "", "Restore the last manual backup? Current data will be replaced. This requires Owner / level 4 access."));
        labelLines.add("Data file: world/data/sovereigncore.properties. Automatic .bak and manual backup are kept separately.");
    }

    private void buildInspect() {
        buildSearchBox("Filter inventory items…");
        addButton(inventoryKind.equals("ender") ? "Clear ender chest…" : "Clear inventory…", contentX + 8, panelY + panelH - 54, (contentW - 24) / 2, 20,
                () -> confirm(inventoryKind.equals("ender") ? "ENDER_CLEAR" : "INV_CLEAR", targetName, "", "inspect", targetName + "|" + inventoryKind,
                        "Clear all " + inventoryKind + " slots for " + targetName + "?"));
        addButton("Back to player", contentX + 16 + (contentW - 24) / 2, panelY + panelH - 54, (contentW - 24) / 2, 20,
                () -> request("REQ_PLAYER", targetName, "", "player", targetName));
    }

    private void buildNotice() {
        List<String> l = lines(data);
        addButton("Back / retry previous page", contentX + 8, panelY + panelH - 48, contentW - 16, 22, () -> {
            String returnPage = l.size() > 1 ? l.get(1) : "main";
            String returnContext = l.size() > 2 ? l.get(2) : "";
            request("REQ_PAGE", returnPage, returnContext, returnPage, returnContext);
        });
    }

    private void buildConfirm() {
        addButton("Confirm action", contentX + 8, panelY + panelH - 52, (contentW - 24) / 2, 23,
                () -> NetworkHandler.sendAction(confirmAction, confirmArg1, confirmArg2, confirmPage, confirmContext));
        addButton("Cancel", contentX + 16 + (contentW - 24) / 2, panelY + panelH - 52, (contentW - 24) / 2, 23,
                () -> NetworkHandler.sendAction("REQ_PAGE", confirmPage, confirmContext, confirmPage, confirmContext));
    }

    private void buildSearchBox(String hint) {
        searchBox = new EditBox(font, contentX + 8, panelY + 65, contentW - 16, 20, Component.literal("Search"));
        searchBox.setMaxLength(100);
        searchBox.setHint(Component.literal(hint));
        searchText = ClientScreens.searchFor(page);
        searchBox.setValue(searchText);
        addRenderableWidget(searchBox);
    }

    private EditBox labelledField(String label, String value, int x, int rowY, int w) {
        labelLines.add(label + "@" + x + "," + rowY);
        EditBox box = textField(x, rowY + 17, w, 20, label);
        box.setValue(value == null ? "" : value);
        return box;
    }

    private EditBox textField(int x, int y, int w, int h, String hint) {
        EditBox box = new EditBox(font, x, y, Math.max(30, w), h, Component.literal(hint));
        box.setMaxLength(180);
        box.setHint(Component.literal(hint));
        addRenderableWidget(box);
        return box;
    }

    private void addButton(String title, int x, int y, int w, int h, Runnable action) {
        int safeX = Math.max(panelX + 3, Math.min(x, panelX + panelW - Math.max(30, w) - 3));
        int safeY = Math.max(panelY + 3, Math.min(y, panelY + panelH - Math.max(18, h) - 3));
        int safeW = Math.max(30, Math.min(w, panelX + panelW - safeX - 3));
        addRenderableWidget(Button.builder(Component.literal(title), b -> action.run()).bounds(safeX, safeY, safeW, h).build());
    }

    private void request(String action, String arg1, String arg2, String desiredPage, String context) {
        searchText = searchBox == null ? "" : searchBox.getValue();
        ClientScreens.rememberSearch(page, searchText);
        NetworkHandler.sendAction(action, arg1, arg2, desiredPage, context);
    }

    private void act(String action, String arg1, String arg2, String returnPage, String context) {
        NetworkHandler.sendAction(action, arg1, arg2, returnPage, context);
    }

    private void confirm(String action, String arg1, String arg2, String returnPage, String context, String prompt) {
        String preparedArg2 = arg2 == null ? "" : arg2;
        minecraft.setScreen(new ManagerScreen(mainData, action, arg1 == null ? "" : arg1, preparedArg2, returnPage, context, prompt));
    }

    private void setLocalPage(String nextPage, String pageData) {
        minecraft.setScreen(new ManagerScreen(nextPage, pageData, mainData));
    }

    private String value(EditBox box) { return box == null ? "" : box.getValue().trim(); }

    // Scrollable data list -----------------------------------------------------------------------

    private final List<String> labelLines = new ArrayList<>();

    private List<String> dataRows() {
        List<String> all = lines(data);
        if (page.equals("assignrank")) return all.size() > 2 ? new ArrayList<>(all.subList(2, all.size())) : new ArrayList<>();
        if (page.equals("roleperms")) return all.size() > 1 ? new ArrayList<>(all.subList(1, all.size())) : new ArrayList<>();
        if (page.equals("homes")) return all.stream().filter(s -> !s.startsWith("owner=") && !s.startsWith("ownerName=")).collect(Collectors.toList());
        if (page.equals("inspect")) return all.stream().filter(s -> !s.startsWith("target=") && !s.startsWith("kind=")).collect(Collectors.toList());
        return all;
    }

    private List<String> filteredRows() {
        List<String> rows = dataRows();
        String needle = searchBox == null ? searchText : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        if (searchBox != null) ClientScreens.rememberSearch(page, searchBox.getValue());
        if (!needle.isBlank()) rows = rows.stream().filter(s -> s.toLowerCase(Locale.ROOT).contains(needle)).collect(Collectors.toList());
        return rows;
    }

    private boolean listPage() {
        return Set.of("players", "homes", "warps", "ranks", "roleperms", "assignrank", "bans", "logs", "inspect").contains(page);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int bg = 0xF0141822;
        int sidebar = 0xFF1D2633;
        int content = 0xFF222C3A;
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, bg);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 3, 0xFF8B5CF6);
        graphics.fill(panelX, panelY + 3, panelX + sidebarW, panelY + panelH, sidebar);
        graphics.fill(contentX, panelY + 8, panelX + panelW - 8, panelY + panelH - 8, content);
        graphics.drawString(font, Component.literal("SOVEREIGNCORE"), panelX + 10, panelY + 17, 0xFFE8D9FF, false);
        Map<String, String> main = keyValues(mainData);
        graphics.drawString(font, Component.literal("SERVER CONTROL  /  " + main.getOrDefault("label", "Player")), contentX + 10, panelY + 17, 0xFFF1F5F9, false);
        graphics.drawString(font, Component.literal(pageTitle()), contentX + 10, panelY + 38, 0xFFC9D5E5, false);

        // Labels are kept declarative so the current viewport is recalculated every frame.
        for (String label : labelLines) {
            if (label.contains("@")) {
                String[] bits = label.split("@", 2); String[] xy = bits[1].split(",", 2);
                try { graphics.drawString(font, Component.literal(bits[0]), Integer.parseInt(xy[0]), Integer.parseInt(xy[1]) + 1, 0xFFB9C6D5, false); }
                catch (NumberFormatException ignored) { }
            } else if (!label.isBlank()) graphics.drawString(font, Component.literal(label), contentX + 9, panelY + 57 + (labelLines.indexOf(label) * 15), 0xFFB9C6D5, false);
        }

        if (page.equals("main")) {
            graphics.drawString(font, Component.literal("Online: " + main.getOrDefault("online", "0")), contentX + 10, panelY + panelH - 30, 0xFF9CAFC4, false);
            graphics.drawString(font, Component.literal("Role: " + main.getOrDefault("label", "Member")), contentX + 120, panelY + panelH - 30, 0xFFC4B5FD, false);
        } else if (page.equals("notice")) {
            List<String> l = lines(data);
            if (!l.isEmpty()) drawWrapped(graphics, l.get(0), contentX + 10, panelY + 98, contentW - 30, 0xFFFFC2A8);
        } else if (page.equals("confirm")) {
            drawWrapped(graphics, confirmPrompt, contentX + 12, panelY + 100, contentW - 32, 0xFFFDE68A);
            drawWrapped(graphics, "Confirm only if the selected action and target are correct.", contentX + 12, panelY + 142, contentW - 32, 0xFFCCD6E2);
        }
        renderSidebarItems(graphics, mouseX, mouseY);
        if (page.equals("main")) renderDashboardCards(graphics, mouseX, mouseY);
        if (listPage()) renderDataRows(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private int sidebarTop() { return panelY + 54; }
    private int sidebarBottom() { return Math.max(sidebarTop() + 1, panelY + panelH - 68); }
    private int sidebarRowHeight() { return 23; }
    private int sidebarGap() { return 5; }
    private int visibleSidebarRows() {
        return Math.max(1, (sidebarBottom() - sidebarTop() + sidebarGap()) / (sidebarRowHeight() + sidebarGap()));
    }
    private int maxSidebarScrollRows() { return Math.max(0, sidebarItems.size() - visibleSidebarRows()); }

    private int dashboardTop() { return panelY + 63; }
    private int dashboardBottom() { return Math.max(dashboardTop() + 1, panelY + panelH - 45); }
    private int dashboardGap() { return 7; }
    private int dashboardCardHeight() {
        int available = dashboardBottom() - dashboardTop();
        return Math.max(25, Math.min(46, (available - 12) / 3 - dashboardGap()));
    }
    private int dashboardRowStride() { return dashboardCardHeight() + dashboardGap(); }
    private int visibleDashboardRows() {
        return Math.max(1, (dashboardBottom() - dashboardTop() + dashboardGap()) / dashboardRowStride());
    }
    private int maxDashboardScrollRows() {
        int allRows = (dashboardCards.size() + 1) / 2;
        return Math.max(0, allRows - visibleDashboardRows());
    }

    private void renderSidebarItems(GuiGraphics graphics, int mouseX, int mouseY) {
        if (sidebarItems.isEmpty()) return;
        sidebarScrollOffset = Math.max(0, Math.min(sidebarScrollOffset, maxSidebarScrollRows()));
        int left = panelX + 8;
        int width = sidebarW - 16;
        int rowH = sidebarRowHeight();
        graphics.enableScissor(left, sidebarTop(), left + width, sidebarBottom());
        for (int row = 0; row < visibleSidebarRows(); row++) {
            int index = sidebarScrollOffset + row;
            if (index >= sidebarItems.size()) break;
            int y = sidebarTop() + row * (rowH + sidebarGap());
            boolean hover = mouseX >= left && mouseX < left + width && mouseY >= y && mouseY < y + rowH;
            drawDashboardButton(graphics, sidebarItems.get(index).title, left, y, width, rowH, hover, false);
        }
        graphics.disableScissor();
        if (sidebarItems.size() > visibleSidebarRows()) {
            int trackX = panelX + sidebarW - 5;
            int trackH = sidebarBottom() - sidebarTop();
            graphics.fill(trackX, sidebarTop(), trackX + 2, sidebarBottom(), 0xFF475569);
            int thumbH = Math.max(12, trackH * visibleSidebarRows() / sidebarItems.size());
            int range = Math.max(1, sidebarItems.size() - visibleSidebarRows());
            int thumbY = sidebarTop() + (trackH - thumbH) * sidebarScrollOffset / range;
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xFFC4B5FD);
        }
    }

    private void renderDashboardCards(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = contentX + 10;
        int availableW = Math.max(70, contentW - 28);
        int gap = Math.min(10, Math.max(5, availableW / 32));
        int cardW = Math.max(30, (availableW - gap) / 2);
        int cardH = dashboardCardHeight();
        int stride = cardH + dashboardGap();
        int top = dashboardTop();
        int bottom = dashboardBottom();
        dashboardScrollOffset = Math.max(0, Math.min(dashboardScrollOffset, maxDashboardScrollRows()));
        graphics.enableScissor(contentX + 5, top, panelX + panelW - 8, bottom);
        for (int visibleRow = 0; visibleRow < visibleDashboardRows(); visibleRow++) {
            int row = dashboardScrollOffset + visibleRow;
            for (int col = 0; col < 2; col++) {
                int index = row * 2 + col;
                if (index >= dashboardCards.size()) break;
                int x = left + col * (cardW + gap);
                int y = top + visibleRow * stride;
                boolean hover = mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + cardH;
                drawDashboardButton(graphics, dashboardCards.get(index).title, x, y, cardW, cardH, hover, true);
            }
        }
        graphics.disableScissor();
        int allRows = (dashboardCards.size() + 1) / 2;
        if (allRows > visibleDashboardRows()) {
            int trackX = panelX + panelW - 13;
            int trackH = bottom - top;
            graphics.fill(trackX, top, trackX + 2, bottom, 0xFF475569);
            int thumbH = Math.max(13, trackH * visibleDashboardRows() / allRows);
            int range = Math.max(1, allRows - visibleDashboardRows());
            int thumbY = top + (trackH - thumbH) * dashboardScrollOffset / range;
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xFFC4B5FD);
            graphics.drawString(font, Component.literal("Scroll to see more"), contentX + 10, bottom + 5, 0xFF91A4B9, false);
        }
    }

    private void drawDashboardButton(GuiGraphics graphics, String title, int x, int y, int w, int h, boolean hover, boolean card) {
        int outer = hover ? 0xFF9D7BFF : 0xFF080B10;
        int inner = hover ? 0xFF46536A : (card ? 0xFF343D4A : 0xFF303946);
        graphics.fill(x, y, x + w, y + h, outer);
        if (w > 4 && h > 4) graphics.fill(x + 2, y + 2, x + w - 2, y + h - 2, inner);
        graphics.fill(x + 3, y + 3, x + w - 3, y + 4, hover ? 0xFFBDA5FF : 0xFF657185);
        String shown = clip(title, Math.max(8, w - 12));
        int textX = x + (w - font.width(shown)) / 2;
        int textY = y + (h - 8) / 2;
        graphics.drawString(font, Component.literal(shown), textX, textY, 0xFFF1F5F9, false);
    }

    private void renderDataRows(GuiGraphics graphics, int mouseX, int mouseY) {
        List<String> rows = filteredRows();
        int headerY = listTop - 10;
        graphics.drawString(font, Component.literal(rows.size() + " entries  •  Mouse wheel to scroll"), contentX + 9, headerY, 0xFF91A4B9, false);
        int visible = visibleRowCount();
        if (rows.isEmpty()) {
            graphics.fill(contentX + 8, listTop, contentX + contentW - 8, listTop + 34, 0xFF293545);
            graphics.drawString(font, Component.literal("No entries found. Clear the search or check permissions."), contentX + 16, listTop + 12, 0xFFCFD8E3, false);
            return;
        }
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, rows.size() - visible)));
        for (int i = 0; i < visible && i + scrollOffset < rows.size(); i++) {
            int index = i + scrollOffset;
            int y = listTop + i * rowHeight;
            boolean hovered = mouseX >= contentX + 8 && mouseX <= contentX + contentW - 8 && mouseY >= y && mouseY < y + rowHeight - 2;
            graphics.fill(contentX + 8, y, contentX + contentW - 8, y + rowHeight - 2, hovered ? 0xFF34465A : (i % 2 == 0 ? 0xFF293545 : 0xFF263240));
            drawRowText(graphics, page, rows.get(index), contentX + 15, y + 8, contentW - 40);
            if (rowHasSecondaryAction(page)) graphics.drawString(font, Component.literal(secondaryLabel(page, rows.get(index))), contentX + contentW - 61, y + 8, 0xFFFCA5A5, false);
            if (rows.get(index).length() > 120) graphics.fill(contentX + 8, y + rowHeight - 3, contentX + contentW - 8, y + rowHeight - 2, 0xFF475569);
        }
        if (rows.size() > visible) {
            int trackX = contentX + contentW - 5;
            int trackH = visible * rowHeight - 4;
            graphics.fill(trackX, listTop, trackX + 2, listTop + trackH, 0xFF475569);
            int thumbH = Math.max(12, trackH * visible / rows.size());
            int range = Math.max(1, rows.size() - visible);
            int thumbY = listTop + (trackH - thumbH) * scrollOffset / range;
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xFFC4B5FD);
        }
    }

    private void drawRowText(GuiGraphics g, String page, String row, int x, int y, int maxWidth) {
        String text = row;
        String[] f = row.split("\\t", -1);
        switch (page) {
            case "players" -> { if (f.length >= 4) text = f[1] + "  •  " + f[2] + "  •  " + f[3] + (f.length > 5 ? "  •  HP " + f[5] : ""); }
            case "homes" -> { if (f.length >= 4) text = f[1] + "  /  " + f[2] + "  •  " + f[3]; }
            case "warps" -> { if (f.length >= 5) text = f[0] + "  •  " + f[2] + "  •  " + f[1]; }
            case "ranks" -> {
                if (f.length >= 8) {
                    int rankColor = parseRankColor(f[7]);
                    String icon = f.length > 9 ? f[9] + " " : "◆ ";
                    String display = clip(icon + f[1], Math.max(30, maxWidth / 3));
                    g.drawString(font, Component.literal(display), x, y, rankColor, false);
                    String details = "  [" + f[0] + "]  •  priority " + f[2] + "  •  members " + f[3] + "  •  homes " + f[4] + "  •  warps " + f[5];
                    g.drawString(font, Component.literal(clip(details, Math.max(30, maxWidth - font.width(display) - 8))), x + font.width(display) + 8, y, 0xFFE2E8F0, false);
                    return;
                }
            }
            case "roleperms" -> { if (f.length >= 2) text = (Boolean.parseBoolean(f[1]) ? "✓  " : "○  ") + f[0]; }
            case "assignrank" -> { if (f.length >= 4) text = (Boolean.parseBoolean(f[3]) ? "✓  " : "○  ") + f[1] + "  [" + f[0] + "]  •  priority " + f[2]; }
            case "bans" -> { if (f.length >= 2) text = f[0] + "  •  " + f[1]; }
            case "inspect" -> { if (f.length >= 4) text = "Slot " + f[0] + "  •  " + f[1] + " x " + f[2] + "  [" + f[3] + "]"; }
            default -> { }
        }
        g.drawString(font, Component.literal(clip(text, maxWidth)), x, y, 0xFFE2E8F0, false);
    }

    private int parseRankColor(String hex) {
        try { return hex != null && hex.matches("#[0-9A-Fa-f]{6}") ? (0xFF000000 | Integer.parseInt(hex.substring(1), 16)) : 0xFFE2E8F0; }
        catch (NumberFormatException ignored) { return 0xFFE2E8F0; }
    }

    private String clip(String text, int widthPx) {
        if (font.width(text) <= widthPx) return text;
        String s = text;
        while (!s.isEmpty() && font.width(s + "…") > widthPx) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private int visibleRowCount() { return Math.max(1, (listBottom - listTop) / rowHeight); }

    private boolean rowHasSecondaryAction(String rowPage) {
        return Set.of("homes", "warps", "bans", "inspect", "ranks").contains(rowPage);
    }

    private String secondaryLabel(String rowPage, String row) {
        return switch (rowPage) {
            case "homes", "warps" -> "Delete";
            case "bans" -> "Unban";
            case "inspect" -> "Remove";
            case "ranks" -> "Edit";
            default -> "";
        };
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int step = delta > 0 ? -2 : 2;
        if (mouseX >= panelX + 4 && mouseX < panelX + sidebarW && mouseY >= sidebarTop() && mouseY <= sidebarBottom()) {
            sidebarScrollOffset = Math.max(0, Math.min(maxSidebarScrollRows(), sidebarScrollOffset + step));
            return true;
        }
        if (page.equals("main") && mouseX >= contentX + 5 && mouseX <= panelX + panelW - 8 && mouseY >= dashboardTop() && mouseY <= dashboardBottom()) {
            dashboardScrollOffset = Math.max(0, Math.min(maxDashboardScrollRows(), dashboardScrollOffset + step));
            return true;
        }
        if (listPage() && mouseX >= contentX + 6 && mouseX <= contentX + contentW - 6 && mouseY >= listTop && mouseY <= listBottom) {
            List<String> rows = filteredRows();
            int maxOffset = Math.max(0, rows.size() - visibleRowCount());
            scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset - (delta > 0 ? 3 : -3)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= panelX + 8 && mouseX < panelX + sidebarW - 8 && mouseY >= sidebarTop() && mouseY < sidebarBottom()) {
            int relativeY = (int) mouseY - sidebarTop();
            int stride = sidebarRowHeight() + sidebarGap();
            int row = relativeY / stride;
            int index = sidebarScrollOffset + row;
            if (relativeY >= 0 && relativeY % stride < sidebarRowHeight()
                    && row >= 0 && row < visibleSidebarRows() && index >= 0 && index < sidebarItems.size()) {
                sidebarItems.get(index).action.run();
                return true;
            }
        }
        if (button == 0 && page.equals("main") && mouseX >= contentX + 5 && mouseX <= panelX + panelW - 8 && mouseY >= dashboardTop() && mouseY < dashboardBottom()) {
            int left = contentX + 10;
            int availableW = Math.max(70, contentW - 28);
            int gap = Math.min(10, Math.max(5, availableW / 32));
            int cardW = Math.max(30, (availableW - gap) / 2);
            int col = mouseX >= left && mouseX < left + cardW ? 0
                    : (mouseX >= left + cardW + gap && mouseX < left + cardW * 2 + gap ? 1 : -1);
            int relativeY = (int) mouseY - dashboardTop();
            int visibleRow = relativeY / dashboardRowStride();
            int index = (dashboardScrollOffset + visibleRow) * 2 + col;
            if (col >= 0 && relativeY >= 0 && relativeY % dashboardRowStride() < dashboardCardHeight()
                    && visibleRow >= 0 && visibleRow < visibleDashboardRows() && index >= 0 && index < dashboardCards.size()) {
                dashboardCards.get(index).action.run();
                return true;
            }
        }
        if (listPage() && mouseX >= contentX + 8 && mouseX <= contentX + contentW - 8 && mouseY >= listTop && mouseY < listBottom) {
            int row = (int) ((mouseY - listTop) / rowHeight);
            List<String> rows = filteredRows();
            int index = scrollOffset + row;
            if (row >= 0 && row < visibleRowCount() && index >= 0 && index < rows.size()) {
                String selected = rows.get(index);
                boolean secondary = mouseX >= contentX + contentW - 78;
                handleRowClick(selected, secondary);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void handleRowClick(String row, boolean secondary) {
        String[] f = row.split("\\t", -1);
        switch (page) {
            case "players" -> { if (f.length > 1) request("REQ_PLAYER", f[1], "", "player", f[1]); }
            case "homes" -> {
                if (f.length < 3) return;
                String selector = f[0] + "|" + f[2];
                if (secondary) confirm("DEL_HOME_OTHER", selector, "", "homes", targetName, "Delete home '" + f[2] + "' owned by " + f[1] + "?");
                else act("HOME", selector, "", "homes", targetName);
            }
            case "warps" -> {
                if (f.length < 4) return;
                String selector = f[2].equals("personal") ? "personal|" + f[3] + "|" + f[0] : f[0];
                if (secondary) confirm("DEL_WARP", selector, "", "warps", "", "Delete warp '" + f[0] + "'?");
                else act("WARP", selector, "", "warps", "");
            }
            case "ranks" -> {
                if (f.length == 0) return;
                if (secondary) confirm("DELETE_ROLE", f[0], "", "ranks", "", "Delete rank '" + f[0] + "' and reassign affected users?");
                else request("REQ_RANK_DETAIL", f[0], "", "rankdetail", f[0]);
            }
            case "roleperms" -> { if (f.length > 0) act("TOGGLE_ROLE_PERMISSION", rankId, f[0], "roleperms", rankId); }
            case "assignrank" -> { if (f.length > 0) act("TOGGLE_PLAYER_RANK", targetName, f[0], "assignrank", targetName); }
            case "bans" -> { if (f.length > 0 && secondary) confirm("PARDON", f[0], "", "bans", "", "Unban '" + f[0] + "'?"); else if (f.length > 0) confirm("PARDON", f[0], "", "bans", "", "Unban '" + f[0] + "'?"); }
            case "inspect" -> {
                if (f.length > 0) {
                    String action = inventoryKind.equals("ender") ? "REMOVE_ENDER_SLOT" : "REMOVE_INV_SLOT";
                    confirm(action, targetName, f[0], "inspect", targetName + "|" + inventoryKind, "Remove the entire stack in slot " + f[0] + "?");
                }
            }
            case "logs" -> { }
            default -> { }
        }
    }

    private static final class RunnableCard {
        final String title; final Runnable action;
        RunnableCard(String title, Runnable action) { this.title = title; this.action = action; }
    }

    private static final class NavItem {
        final String title; final Runnable action;
        NavItem(String title, Runnable action) { this.title = title; this.action = action; }
    }

    private String pageTitle() {
        return switch (page) {
            case "main" -> "DASHBOARD";
            case "players" -> "PLAYER DIRECTORY";
            case "player" -> "PLAYER DETAILS & CONTROLS";
            case "playerlimits" -> "PLAYER-SPECIFIC LIMITS";
            case "teleport" -> "TELEPORT CENTER";
            case "homes" -> "HOME MANAGER";
            case "warps" -> "WARP DIRECTORY";
            case "ranks" -> "RANKS & GROUPS";
            case "rankdetail" -> "RANK CONFIGURATION  /  " + rankId;
            case "roleperms" -> "PERMISSION EDITOR  /  " + rankId;
            case "assignrank" -> "PLAYER RANK ASSIGNMENT";
            case "bans" -> "BAN LIST  /  UNBAN";
            case "logs" -> "AUDIT LOGS";
            case "personal" -> "PERSONAL TOOLS";
            case "settings" -> "SERVER SETTINGS";
            case "inspect" -> "INVENTORY INSPECTOR  /  " + targetName;
            case "notice" -> "NOTICE";
            case "confirm" -> "CONFIRM SENSITIVE ACTION";
            default -> "MANAGEMENT";
        };
    }

    private static String firstLine(String s) { int i = s.indexOf('\n'); return i < 0 ? s : s.substring(0, i); }
    private static List<String> lines(String s) {
        if (s == null || s.isBlank()) return new ArrayList<>();
        return Arrays.stream(s.split("\n", -1)).filter(line -> !line.isBlank()).collect(Collectors.toCollection(ArrayList::new));
    }
    private static Map<String, String> keyValues(String s) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String line : lines(s)) { int p = line.indexOf('='); if (p > 0) out.put(line.substring(0, p), line.substring(p + 1)); }
        return out;
    }
    private static Set<String> csv(String s) {
        if (s == null || s.isBlank()) return Collections.emptySet();
        return Arrays.stream(s.split(",")).map(String::trim).filter(v -> !v.isEmpty()).collect(Collectors.toCollection(HashSet::new));
    }

    private void drawWrapped(GuiGraphics graphics, String message, int x, int y, int maxWidth, int color) {
        StringBuilder line = new StringBuilder();
        int drawY = y;
        for (String word : message.split("\\s+")) {
            String test = line.isEmpty() ? word : line + " " + word;
            if (font.width(test) > maxWidth && !line.isEmpty()) {
                graphics.drawString(font, Component.literal(line.toString()), x, drawY, color, false);
                drawY += 13; line.setLength(0); line.append(word);
            } else {
                if (!line.isEmpty()) line.append(' ');
                line.append(word);
            }
        }
        if (!line.isEmpty()) graphics.drawString(font, Component.literal(line.toString()), x, drawY, color, false);
    }

    @Override
    public void onClose() { super.onClose(); }
}

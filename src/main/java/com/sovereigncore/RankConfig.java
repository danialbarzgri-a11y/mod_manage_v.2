package com.sovereigncore;

import java.util.LinkedHashMap;
import java.util.Map;

/** Server-side mutable configuration for a SovereignCore rank. */
final class RankConfig {
    final String id;
    String label;
    String color;
    String description = "";
    String icon = "◆";
    int priority;
    String parent;
    int homesLimit;
    int personalWarpsLimit;
    int tpaLimitPerHour;
    int tpaCooldownSeconds;
    boolean allPermissions;
    final Map<String, Boolean> permissionOverrides = new LinkedHashMap<>();

    RankConfig(String id, String label, String color, int priority, String parent,
               int homesLimit, int personalWarpsLimit, int tpaLimitPerHour,
               int tpaCooldownSeconds, boolean allPermissions) {
        this.id = id;
        this.label = label;
        this.color = color;
        this.icon = defaultIcon(id);
        this.priority = priority;
        this.parent = parent == null ? "" : parent;
        this.homesLimit = homesLimit;
        this.personalWarpsLimit = personalWarpsLimit;
        this.tpaLimitPerHour = tpaLimitPerHour;
        this.tpaCooldownSeconds = tpaCooldownSeconds;
        this.allPermissions = allPermissions;
    }

    private static String defaultIcon(String id) {
        return switch (id) {
            case "owner" -> "♛";
            case "admin" -> "◆";
            case "moderator" -> "⚑";
            case "vip" -> "✦";
            case "builder" -> "⚒";
            default -> "•";
        };
    }
}

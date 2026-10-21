package com.sovereigncore;

import net.minecraft.client.Minecraft;

/** Client-only UI entry point. The remembered capability payload is advisory; server checks remain authoritative. */
public final class ClientScreens {
    private static String lastMainData = "";
    private static final java.util.Map<String, String> searches = new java.util.HashMap<>();

    public static String searchFor(String page) { return searches.getOrDefault(page, ""); }
    public static void rememberSearch(String page, String value) { if (page != null) searches.put(page, value == null ? "" : value); }

    private ClientScreens() { }

    public static void open(String page, String data) {
        if ("main".equals(page)) lastMainData = data == null ? "" : data;
        Minecraft.getInstance().setScreen(new ManagerScreen(page, data == null ? "" : data, lastMainData));
    }
}

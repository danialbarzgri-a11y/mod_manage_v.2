# SovereignCore requirement traceability

| User requirement | Source implementation | Validation status |
|---|---|---|
| Minecraft 1.20.1 Forge mod, not Paper plugin | Forge `@Mod`, `mods.toml`, ForgeGradle build | Source config checked; real build still needed |
| GUI dashboard and better UX | `ManagerScreen`, `ClientScreens` | Static Java compile passed against API-signature stubs; no real client run |
| Search and scroll large lists | `ManagerScreen.buildSearchBox`, `mouseScrolled`, `renderDataRows` | Source checked |
| Player details and rank assignment | Core player query actions and `assignrank` screen | Source checked |
| Home management | `HOMES`, set/delete/list/tp actions | Source checked |
| Public/personal warps and spawn | `PUBLIC_WARPS`, `PERSONAL_WARPS`, spawn action | Source checked |
| TPA request/accept/deny and limits | TPA map, hour history, rank and player cooldown/limit logic | Source checked; gameplay test needed |
| Owner/Admin/Moderator/VIP/Builder/Member/custom roles | `RankConfig`, `ROLE_CONFIGS` | Source checked |
| Rank priority, parent inheritance and permission overrides | `can`, `roleAllows`, `canManageRank` | Source checked; adversarial permission tests needed |
| Per-rank Home/Warp/TPA limits | `RankConfig` and effective limit helpers | Source checked |
| Per-player Home/Warp/TPA/cooldown overrides | Override maps and `playerlimits` screen | Source checked |
| Ban/unban, Kick, Freeze, inventory tools | Core moderation/inventory actions and player GUI | Source checked; gameplay test needed |
| Persistent data and backups | properties file, automatic `.bak`, manual backup/restore | Source checked; restore integration test needed |
| Server-authoritative permissions | `handleAction` and each action handler re-check permission | Source checked; packet/security runtime test needed |
| Final JAR | GitHub Actions build/reobf/upload artifact workflow | Not yet built or verified in this offline environment |

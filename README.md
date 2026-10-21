# SovereignCore — Forge 1.20.1

**A GUI-first server-management mod project** for Minecraft Java 1.20.1, Forge 47.2.x, Java 17.

This project is a Forge mod, **not** a Paper/Spigot plugin. Since it includes a custom GUI and client/server packets, install the same production JAR on the server and on each client connecting to the server.

## Build status and trust note

The Java source has passed a local static compilation check against API-signature stubs. This environment could not download ForgeGradle/Minecraft dependencies or launch an actual Forge server, so the JAR has **not** been runtime-tested here. The included GitHub Actions workflow performs the real ForgeGradle build, remaps the production JAR, and uploads it as an artifact. A successful build still needs a test on a staging server before public use.

## Features in the current source

- Custom in-game dashboard with role-aware panels, search fields, scrollable lists, fixed bottom actions, confirmations and notices.
- UUID-based player records, known player names and online/offline profile lookup where the server profile cache knows the player.
- Multiple named homes per player; staff home browsing and moderation rules.
- Public and personal warps, server spawn, teleport to player/bring player, coordinate and dimension teleport.
- TPA request, accept and deny, hourly request limits and cooldowns.
- Configurable rank system: Owner, Admin, Moderator, VIP, Builder, Member and custom ranks.
- Rank editor for label, color, symbol, description, priority, parent rank, home limit, personal warp limit, TPA limit, cooldown and permission overrides.
- Multiple ranks per player; high-priority rank determines the primary display rank; parent-rank permission inheritance and explicit allow/deny overrides.
- Per-player overrides for Home limit, personal Warp limit, TPA requests/hour and TPA cooldown. Use `inherit` to return to rank defaults and `-1` for unlimited limits.
- Player controls: heal, feed, gamemode, flight, invulnerability, freeze/unfreeze, potion-effect/XP clearing, inventory and ender-chest inspection/removal, item giving.
- Moderation: kick, vanilla ban/pardon, IP pardon, OP/DEOP actions gated by vanilla level 4, and ban list support.
- Audit entries and automatic `.bak` plus manual backup/restore for SovereignCore data.
- Server-side authorization on all management actions; the client UI is not trusted as an authority.

## Build on GitHub (no local Gradle setup)

1. Download this ZIP and extract it on your computer.
2. Create a repository on GitHub.
3. Upload the **contents** of this folder to the repository root (do not upload only the ZIP and do not place the entire project inside one extra nested folder). The root should contain `build.gradle`, `settings.gradle`, `src/`, and `.github/workflows/build.yml`.
4. Make sure the hidden `.github` folder and its `workflows` subfolder are included. In Windows File Explorer, use **View → Show → Hidden items** if needed.
5. Commit the uploaded files.
6. Open the repository's **Actions** tab. The workflow `Build SovereignCore Forge 1.20.1` starts on a push to `main`/`master`. You can also open the workflow and click **Run workflow**.
7. If the run succeeds, open that run and download `SovereignCore-Forge-1.20.1-JAR` under **Artifacts**. GitHub downloads the artifact as a ZIP; extract it to find the JAR in your Downloads folder.
8. Install that exact JAR in the `mods` folder of the Forge 1.20.1 server and every client, then restart them.

If the workflow does not appear, create a file named `.github/workflows/build.yml` in the repository using **Add file → Create new file**, and copy the contents of `BUILD_WORKFLOW_TEMPLATE.yml` into it. Commit the new workflow, then reopen Actions.

## Commands

- `/sc` and `/manager` — open SovereignCore dashboard
- `/sethome [name]`, `/home [name]`, `/homes`, `/delhome <name>`
- `/setwarp <name>`, `/warp [name]`, `/warps`, `/delwarp <name>`
- `/setspawn`, `/spawn`
- `/tpa <player>`, `/tpaccept`, `/tpdeny`
- `/sctp <player>` — permission-gated staff teleport
- `/sc rank <player> <rank>` — permission-gated rank assignment
- `/sc reload` — reloads SovereignCore data; requires vanilla permission level 4

## Data and backups

Data is stored in `<world>/data/sovereigncore.properties`. An automatic `sovereigncore.properties.bak` is updated before saves, and a separate `sovereigncore-manual-backup.properties` is created from the Settings UI. Make a world/server backup before testing restore or using this early version publicly.

## Default permission model

- Owner/Admin start with all SovereignCore permissions.
- Moderator has a limited moderation set.
- VIP, Builder and Member get smaller default sets.
- Operators at vanilla permission level 2 or higher bypass the custom permission checks. Owner rank mutation and backup restore have extra protections.
- `-1` means unlimited for configurable limits. `inherit` resets a per-player override.

## Known scope/validation limits

- The actual ForgeGradle production build and a real client/dedicated-server launch must be validated by GitHub Actions and then by a staging server; this environment did not have Forge dependencies available.
- Some actions intentionally target online players only (live inventory operations, movement, heal/feed, gamemode, freeze). Offline rank and limit management requires the player's name/UUID to be known to the server profile cache or stored history.
- The current rank model allows multiple roles with permission inheritance, but complex production permission ecosystems and interactions with unrelated Forge mods are outside this mod's permission system.
- Use backups and test in a disposable world before using on a public server.

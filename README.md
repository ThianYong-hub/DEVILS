# DEVILS-ADDON

DEVILS-ADDON is a client-side Fabric addon for Meteor Client. It contains the main `devils-addon` module set, an optional `devils-game` companion jar, and an optional SyncHub backend for cross-client synchronization.

It is installed into `.minecraft/mods` like a normal Fabric client mod and runs on the client.

## Artifacts

| Artifact | Purpose |
| --- | --- |
| `devils-addon` | Main Meteor Client addon with combat, movement, utility, world automation, SyncHub, ChestTracker, and Xaero-related integrations. |
| `devils-game` | Optional Meteor Client companion addon with game overlays and game-sync configuration. |
| `SyncHub` | Optional standalone Python backend used by sync-aware modules. It is not required for local-only addon usage. |

Current versions are read from `gradle.properties`:

| Property | Current Value |
| --- | --- |
| `addon_version` | `0.0.58` |
| `game_version` | `0.0.4` |
| `minecraft_version` | `1.21.11` |
| `loader_version` | `0.18.4` |
| `fabric_api_version` | `0.141.3+1.21.11` |

## Requirements

- Minecraft `1.21.11`
- Fabric Loader `0.18.4+`
- Fabric API for `1.21.11`
- Java `21`
- Meteor Client build for `1.21.11`

The main addon integrates source-native patches for the Xaero map ecosystem, ChestTracker, WhereIsIt, Searchables, YACL, and supporting libraries. Normal builds use Gradle-resolved inputs plus tracked patch/vendor files, so no external local source dump is required for a ZIP or git-clone build.

## Installation

1. Install Minecraft with Fabric Loader for the supported Minecraft version.
2. Install Fabric API and Meteor Client for the same Minecraft version.
3. Download `devils-addon-*.jar` from GitHub Releases, or build it from source.
4. Optional: download `devils-game-*.jar` if you want the companion game overlays.
5. Put the selected jars into `.minecraft/mods`.
6. Launch Minecraft and open the Meteor GUI.
7. Main modules appear under the `Devils` category. Companion game modules appear under `Devils-Game`.

## Building From Source

Linux/macOS:

```bash
./gradlew build
```

Windows:

```bat
gradlew.bat build
```

Build only one client jar:

```bash
./gradlew :devils-addon:build
./gradlew :devils-game:build
```

Root release artifacts are collected into:

```text
build/libs/
```

Project-level artifacts are produced under:

```text
devils-addon/build/libs/
devils-game/build/libs/
```

Note: the `devils-addon` build validates tracked source-native patch files, the vendored XaeroLib helper jar, and remapped dependency inputs before processing resources.

## Main Modules

### Combat

| Module | Description |
| --- | --- |
| `auto-cev` | Places obsidian and crystals for a Cev cycle. |
| `auto-pearl` | Tracks a target and chases with ender pearls. |
| `tnt-bomber` | Builds an obsidian trap and executes a TNT bombing sequence. |
| `lava-bucket` | Places and collects lava around nearby players. |
| `mace-spoof` | Spoofs fall-distance conditions for mace damage logic. |
| `spear-spoof` | Spear combat FSM with targeting, movement, attack contour, and debug pipeline. |

### Movement And World

| Module | Description |
| --- | --- |
| `auto-wasp` | Elytra follow/chase module with obstacle-aware routing. |
| `anti-wasp` | Elytra evasion patterns with obstacle checks. |
| `h-clip` | Horizontal corner clip helper. |
| `v-clip` | Vertical clip by configured distance. |
| `highway-builder-plus` | Nether highway, tunnel, and flat-path builder. |
| `nuker-plus` | Area block breaking with filters, sorting, render, and speed-mine related settings. |

### Utility And Team

| Module | Description |
| --- | --- |
| `auto-login` | Sends saved `/login` or `/reg` commands matched by username and multiplayer address. |
| `auto-craft` | Slot-driven chain crafting without recipe-book placement. |
| `auto-anvil-rename` | Automates anvil rename operations for matching items. |
| `ping` | Sync-aware ping markers with render and sound. |
| `tracker-player` / `join-watcher` | Per-player join, leave, death, sound, and optional chat-send rules. |
| `stash-mover` | Two-account stash transfer loop using container automation and pearl stasis coordination. |
| `discord-rpc` | Discord Rich Presence branding for the addon. |

### Integrations

| Module Or Integration | Description |
| --- | --- |
| `sync-hub` | Shared sync settings for AutoLogin, Ping, ChestTracker, and Xaero live map marker sync. |
| `chest-tracker` | Integrated ChestTracker runtime with Devils theme, local storage controls, and SyncHub sync. |
| `mod-auto-updater` | Fabric mod migration helper using Modrinth and GitHub release lookups. |
| Xaero integration | Source-native runtime integration for Xaero Minimap, Xaero World Map, XaeroPlus, and live marker sync. |

## Devils Game

The optional `devils-game` jar registers the `Devils-Game` Meteor category.

| Module | Description |
| --- | --- |
| `game-sync-hub` | Dedicated sync settings for companion game sessions and presence. |
| `checkers` | Checkers overlay with script and game-sync modes. |
| `chess` | Chess overlay with script and game-sync modes. |
| `slot-machine` | Slot machine overlay. |
| `blackjack` | Blackjack overlay. |
| `russian-roulette` | Russian roulette overlay. |
| `doom` | Doom launcher using bundled runtime assets. |

## Commands

| Command | Purpose |
| --- | --- |
| `.autoraname setname <text>` | Set the target rename text for `auto-anvil-rename`. |
| `.autoraname clearitems` | Clear the `auto-anvil-rename` item filter list. |
| `.session <nick> [password]` | Switch to a cracked Meteor account, optionally save an AutoLogin password for the current address, and reconnect when possible. |
| `.stashmover pearlchest` | Save the return-pearl supply chest from the crosshair target. |
| `.stashmover lootchest` | Save the destination chest where moved resources are deposited. |
| `.stashmover water` | Save the water block used by the pearl chamber from the player's current position. |
| `.stashmover chamber` | Save the chamber/trapdoor interaction point from the crosshair target. |
| `.stashmover pearltarget` | Save the precise pearl aim point from the crosshair target. |
| `.stashmover status` | Print saved StashMover positions and runtime state. |
| `.stashmover clear <target>` | Clear one saved StashMover target: `pearlchest`, `lootchest`, `water`, `chamber`, or `pearltarget`. |

## StashMover

`stash-mover` is designed for a two-account workflow:

- `MOVER` loots source containers, deposits resources into the configured loot chest, stages the next return pearl, and uses the configured return command.
- `LOADER` stays near the pearl chamber and clicks/loads the chamber when the MOVER message is received.

Required saved positions:

- `pearlchest`: chest containing ender pearls used for return staging.
- `lootchest`: destination chest where moved resources are deposited.
- `water`: water block for the pearl chamber.
- `chamber`: trapdoor or chamber interaction point.
- `pearltarget`: optional precise pearl entry/settle point.

Recommended setup commands on the MOVER account:

```text
.stashmover pearlchest
.stashmover lootchest
.stashmover water
.stashmover chamber
.stashmover pearltarget
.stashmover status
```

Important behavior:

- `lootchest` is deposit-only. It is not a source chest.
- Source chests are selected dynamically by scan distance and filters.
- If `only-shulkers` is enabled, non-shulker contents are ignored for source-looting decisions.
- If another module refills ender pearls after taking one pearl, StashMover returns leftover pearls to `pearlchest` before sending the return command.
- Public multiplayer environments remain authoritative for death, pearl, chunk-loading, anti-cheat, and movement behavior. Test the setup in the target environment before leaving it unattended.

## SyncHub Backend

SyncHub is an optional Python backend used by sync-aware modules. It supports bearer auth, request signing, E2E payload enforcement, long-poll/stream sync routes, admin diagnostics, and Docker Compose deployment.

Create a private environment file from the safe template:

```bash
cd SyncHub
cp .env.example .env
```

Generate your own secrets:

```bash
python -c "import secrets; print('SYNC_AUTH_TOKEN=' + secrets.token_urlsafe(32)); print('SYNC_REQUEST_SIGNING_KEY=' + secrets.token_urlsafe(48)); print('SYNC_E2E_SECRET=' + secrets.token_urlsafe(48))"
```

Backend `.env` should contain backend values such as:

```env
SYNC_AUTH_TOKEN=replace_me
SYNC_REQUEST_SIGNING_KEY=replace_me
SYNC_REQUIRE_REQUEST_SIGNING=true
SYNC_REQUIRE_E2E=true
SYNC_HOST=0.0.0.0
SYNC_PORT=7878
```

`SYNC_E2E_SECRET` is client-side only. Put it into the Meteor `sync-hub` / `game-sync-hub` setting named `e2e-secret`; do not add it to backend `.env`.

Resolve order and migration behavior:

- preferred names win over legacy aliases
- legacy aliases are still accepted during the compatibility window
- `auth-token` is authentication material only; do not reuse it as an encryption key
- `e2e-secret` encrypts payloads before they leave the client; the backend does not need the raw value
- If `SYNC_REQUIRE_REQUEST_SIGNING=true`, the request also needs the normal `X-Devils-*` signing headers built from `SYNC_REQUEST_SIGNING_KEY`

Run the backend:

```bash
docker compose up -d --build
```

Health endpoint:

```text
GET /health
```

Admin diagnostics endpoint:

```text
GET /v1/admin/config
```

Admin routes require the configured auth token and, when request signing is enabled, the `X-Devils-*` signing headers.

## SyncHub Stress Tester

Script:

```text
SyncHub/tests/sync_stress_tester.py
```

Common flags:

```text
--base-url
--module
--server-key
--mode random|elytra48
--duration-sec
--clients
--push-interval-ms
--pull-interval-ms
--auth-token
--request-signing-key
--e2e-secret
--output-json
```

Example:

```bash
python SyncHub/tests/sync_stress_tester.py \
  --base-url http://127.0.0.1:7878 \
  --module xaero-world-map \
  --server-key example.test:25565 \
  --duration-sec 120 \
  --mode random \
  --clients 2 \
  --auth-token "$SYNC_AUTH_TOKEN" \
  --request-signing-key "$SYNC_REQUEST_SIGNING_KEY" \
  --e2e-secret "$SYNC_E2E_SECRET"
```

## Security

- Do not commit `.env`, local Meteor config, Minecraft logs, crash reports, runtime saves, cookies, sessions, or database/state files.
- Never commit or share SyncHub auth tokens, admin tokens, request signing keys, or E2E secrets.
- Do not reuse `auth-token` as an encryption secret.
- Use `.env.example` only as a template.
- Keep production SyncHub configuration private.
- Rotate any secret that was copied into a public issue, log, screenshot, or commit.
- AutoLogin and `.session` can store account credentials locally; keep local addon config folders private.

## Repository Structure

| Path | Purpose |
| --- | --- |
| `devils-addon/` | Main Fabric/Meteor addon source, resources, tests, and source-native integration code. |
| `devils-game/` | Optional companion Fabric/Meteor addon for game overlays. |
| `devils-shared/` | Shared Java code used by both addon jars. |
| `SyncHub/` | Optional Python sync backend, Docker files, and backend tests. |
| `chesttracker-port/` | Source basis for ChestTracker-related integration work. |
| `tools/` | Local build helper artifacts that are explicitly allowed by `.gitignore`. |
| `.github/workflows/` | CI, release, and README-version automation. |

## Credits And Notices

This project builds on Meteor Client, Fabric, Fabric API, Xaero Minimap, Xaero World Map, XaeroPlus, ChestTracker, WhereIsIt, Searchables, YACL, JackFredLib, and other libraries listed in Gradle metadata and generated third-party notices.

Bundled game/audio assets include their own notice files under the relevant resource directories, including Freedoom/Doom runtime notices and sound library notices. Keep those notices with redistributed builds.

## License

The root project license is GPL-3.0. See [LICENSE](LICENSE).

Bundled and source-native third-party components remain subject to their own upstream license terms and notices.

<div align="center">
  <img src="icon.png" alt="Devils Addon Icon" width="200"/>
  
  # Devils Addon
  
  **Client-side Fabric addon for Meteor Client**
  
  [![Stars](https://img.shields.io/github/stars/ThianYong-hub/DEVILS?style=for-the-badge&color=blue)](https://github.com/ThianYong-hub/DEVILS/stargazers)
  [![Issues](https://img.shields.io/github/issues/ThianYong-hub/DEVILS?style=for-the-badge&color=red)](https://github.com/ThianYong-hub/DEVILS/issues)
  [![License](https://img.shields.io/github/license/ThianYong-hub/DEVILS?style=for-the-badge&color=green)](https://github.com/ThianYong-hub/DEVILS/blob/main/LICENSE)
  [![Discord](https://img.shields.io/badge/Discord-Join-7289da?style=for-the-badge&logo=discord)](https://discord.gg/meteorclient)
</div>

<br>

Mostly made for anarchy / private server messing around, with some sync stuff bolted on because running two clients by hand gets annoying.

Current addon build (`0.0.60`)
Current game build (`0.0.4`)

## What Is In Here

- `devils-addon` - main Meteor addon. Combat, movement, stash tools, pings, ChestTracker/Xaero glue, SyncHub settings.
- `devils-game` - optional extra jar with dumb little game overlays.
- `SyncHub` - tiny Python server for sharing module data between clients.
- `devils-shared` - shared Java bits used by both jars.
- `chesttracker-port` and `tools` - local build glue. Ugly, but it works.

This repo vendors/assimilates a few mod resources. If GitHub spits out a tiny addon jar without the `META-INF/devils-addon/mixins/*.json` files, that build is broken. The Gradle build and Actions now check that so it should fail before release instead of crashing Minecraft.

## Needed Stuff

- Minecraft `1.21.11`
- Fabric Loader `0.18.4+`
- Fabric API for `1.21.11`
- Java `21`
- Meteor Client for `1.21.11`

## Build

Windows:

```bat
gradlew.bat build
```

Linux/macOS:

```bash
./gradlew build
```

Jars land in:

- `build/libs/`
- `devils-addon/build/libs/`
- `devils-game/build/libs/`
- `libs/` after the export task

If you only need the main jar:

```bash
./gradlew :devils-addon:remapJar
```

## Install

- Install Fabric, Fabric API and Meteor for the same MC version.
- Drop `devils-addon-*.jar` into `.minecraft/mods`.
- Drop `devils-game-*.jar` too if you want the game overlays.
- Launch, open Meteor GUI, check the `Devils` category.

Works on my machine. If it explodes, check `latest.log` first.

## Main Modules

Combat:

- `auto-cev` - obsidian/crystal cev loop.
- `auto-pearl` - target chase with pearl throws.
- `tnt-bomber` - trap + TNT sequence.
- `lava-bucket` - lava around nearby players.
- `mace-spoof` - fall distance spoofing for mace stuff.
- `spear-spoof` - WIP spear combat FSM. Very timing-sensitive.

Movement/world:

- `auto-wasp` - elytra chase with obstacle checks.
- `anti-wasp` - elytra escape patterns.
- `h-clip` / `v-clip` - simple clip helpers.
- `highway-builder-plus` - nether highway/tunnel builder.
- `nuker-plus` - block nuker with filters and speed-mine-ish settings.

Utility/team:

- `auto-login` - sends saved `/login` or `/reg`.
- `auto-craft` - slot-based crafting without the recipe book.
- `auto-anvil-rename` - renames matching items in an anvil.
- `ping` - synced ping markers.
- `tracker-player` / `join-watcher` - join/leave/death sounds and chat rules.
- `stash-mover` - two-account stash moving loop with pearl stasis.
- `discord-rpc` - Discord presence.

Integrations:

- `sync-hub` - shared SyncHub settings.
- `chest-tracker` - bundled ChestTracker runtime and sync.
- `mod-auto-updater` - Modrinth/GitHub jar mover.
- Xaero stuff - minimap/worldmap/XaeroPlus glue and marker sync.

## StashMover Notes

Two account setup:

- `MOVER` loots source chests, dumps items into the loot chest, loads the next pearl and runs the return command.
- `LOADER` waits by the chamber and clicks when the mover whispers the load message.

Save these first:

- `.stashmover pearlchest`
- `.stashmover lootchest`
- `.stashmover water`
- `.stashmover chamber`
- `.stashmover pearltarget`
- `.stashmover status`

Small gotchas:

- `lootchest` is only for dumping loot. It is not scanned as a source.
- Source chests are picked by scan range and filters.
- `only-shulkers` ignores non-shulker junk when picking source chests.
- Real servers still win. Death messages, pearl timing, chunks and anticheat can all mess with this.

## SyncHub

SyncHub is just the little Python server for modules that need shared state. It has auth, request signing, simple encryption so admins cant sniff coords, long-poll/stream routes, and a couple admin endpoints.

Quick setup:

```bash
cd SyncHub
cp .env.example .env
python -c "import secrets; print('SYNC_AUTH_TOKEN=' + secrets.token_urlsafe(32)); print('SYNC_REQUEST_SIGNING_KEY=' + secrets.token_urlsafe(48)); print('SYNC_E2E_SECRET=' + secrets.token_urlsafe(48))"
docker compose up -d --build
```

Backend `.env` usually needs:

```env
SYNC_AUTH_TOKEN=replace_me
SYNC_REQUEST_SIGNING_KEY=replace_me
SYNC_REQUIRE_REQUEST_SIGNING=true
SYNC_REQUIRE_E2E=true
SYNC_HOST=0.0.0.0
SYNC_PORT=7878
```

Put `SYNC_E2E_SECRET` in the Meteor `sync-hub` / `game-sync-hub` setting called `e2e-secret`. Do not put it in backend `.env`; the server does not need the raw secret.

Resolve order and migration behavior:

- preferred names win over legacy aliases
- old aliases still work for now
- `auth-token` is only auth, not encryption
- `e2e-secret` encrypts client payloads before upload
- If `SYNC_REQUIRE_REQUEST_SIGNING=true`, the request also needs the normal `X-Devils-*` signing headers

Useful endpoints:

- `GET /health`
- `GET /v1/admin/config`
- `/v1/sync/pull`
- `/v1/sync/push`
- `/v1/sync/stream`

Stress test:

```bash
python SyncHub/tests/sync_stress_tester.py \
  --base-url http://127.0.0.1:7878 \
  --module xaero-world-map \
  --server-key example.test:25565 \
  --duration-sec 120 \
  --clients 2 \
  --auth-token "$SYNC_AUTH_TOKEN" \
  --request-signing-key "$SYNC_REQUEST_SIGNING_KEY" \
  --e2e-secret "$SYNC_E2E_SECRET"
```

## Commands I Actually Use

- `.session <nick> [password]`
- `.autoraname setname <text>`
- `.autoraname clearitems`
- `.stashmover status`
- `.stashmover clear <pearlchest|lootchest|water|chamber|pearltarget>`

## GitHub Builds

Actions run tests, build both jars, then check the addon jar for the relocated mixin configs. The important one from the crash was:

- `META-INF/devils-addon/mixins/chesttracker.mixins.json`

If that file is missing, the action fails. No more "release looked fine but Fabric dies on launch" nonsense.

## Do Not Commit

- `.env`
- Minecraft logs/crash reports
- local Meteor configs
- cookies/sessions/accounts
- SyncHub tokens/signing keys/e2e secrets
- state/db files from the backend

## Credits

Built on Fabric, Meteor Client, Fabric API, Xaero Minimap/World Map/XaeroPlus, ChestTracker, WhereIsIt, Searchables, YACL, JackFredLib and a bunch of Gradle-resolved libs.

Root license is GPL-3.0. Some bundled/source-native stuff has its own license text, keep those notices with public builds.

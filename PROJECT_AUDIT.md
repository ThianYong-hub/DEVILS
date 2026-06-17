# DEVILS Addon — Full Project Audit

> **Audit date:** 2026-06-17
> **Branch:** `main`
> **HEAD commit:** `11b87b6` — `[MiMo] Auto-snapshot: Before destructive command`
> **Auditor:** MiMo Agent (read-only pass, no files modified)

---

## 1. Project Overview

| Field | Value |
|---|---|
| **Name** | Devils Addon |
| **Type** | Client-side Fabric addon for Meteor Client |
| **Purpose** | Anarchy / private-server utility: combat, stash tools, sync, mini-game overlays |
| **License** | GPL-3.0-only (`LICENSE`) |
| **Authors** | `23XT`, `SOCKETLOST` |
| **Source** | https://github.com/ThianYong-hub/DEVILS |
| **Current addon version** | `0.0.60` |
| **Current game version** | `0.0.4` |

The project is a multi-module Gradle build targeting Minecraft 1.21.11 on Fabric Loader 0.18.4, compiled with Java 21. It produces two separate JAR artifacts (addon + game) plus a shared internal library.

---

## 2. Module Layout

```
Devils-Addon/                        (root Gradle project)
├── devils-addon/                    Main Meteor addon (combat, movement, stash, sync)
├── devils-game/                     Optional game overlays (chess, checkers, doom, slots, etc.)
├── devils-shared/                   Shared internal Java library (sync crypto, config infra)
├── SyncHub/                         Python sync server (FastAPI-like, self-contained)
├── tools/                           Build tooling (CFR decompiler, source-native materializer)
└── (root)                           Gradle config, CI/CD, README
```

### 2.1 `devils-addon` (main module)

- **Entry point:** `DevilsAddon.java` — extends `MeteorAddon`, registers 17+ modules + commands + HUD elements.
- **Fabric entry:** `fabric.mod.json` declares `preLaunch` → `CrashGuard`, `main` → `DevilsAddon`.
- **Mixin:** Single invoker mixin: `ClientPlayerInteractionManagerInvoker` (access widener for interaction manager).
- **Category:** `Devils` (Diamond icon).
- **Module count:** 17 top-level modules plus sub-packages (HighwayBuilder, StashMover sub-system, ModAutoUpdater).
- **Commands:** `SessionCommand` (registered in addon init).
- **HUD:** `ArmorPercentHud`, `DurabilityPercentHud`.
- **Smoke tests (runtime):** `AutoWaspRuntimeValidation`, `InputRuntimeValidation`, `NukerPlusSmoke`, `AssimilatedQualitySmoke` — self-checks injected at startup.
- **Build:** 981-line `build.gradle.kts` with source-native JAR embedding, complex version resolution, validation tasks, and IntelliJ run configurations.
- **Unit tests:** 15+ test classes in `src/test/`.

### 2.2 `devils-game` (game module)

- **Entry point:** `DevilsGameAddon.java` — extends `MeteorAddon`, registers 7 game overlay modules.
- **Category:** `Devils-Game` (Diamond icon).
- **Modules:** `GameSyncHub`, `CheckersOverlay`, `ChessOverlay`, `DoomOverlay`, `SlotMachineOverlay`, `RussianRouletteOverlay`, `BlackjackOverlay`.
- **Chess engine:** Full Stockfish integration — `StockfishBridge.java` (ProcessBuilder-based), `StockfishDownloader.java` (auto-download from GitHub releases), `NativeLoader.java`, `ChessEngine.java`.
- **Chess core:** `ChessCore.java` (board state), `ChessLogic.java` (move validation, FEN parsing).
- **Checkers:** `CheckersLogic.java` (board + move rules).
- **Game sync:** `MiniGamesSyncRuntime`, `MiniGamesSyncCodec`, `MiniGamesPresenceModel`, `MiniGamesRuntimeSupport` — network sync layer for multiplayer game sessions.
- **GUI screens:** `ChessGameScreen`, `CheckersGameScreen`.
- **Support:** `ChessOverlaySession`, `ChessOverlayRenderer`, `GamesCursorController`, `GameLaunchCoordinator`, `GameCrashGuard`.
- **Build:** 122-line `build.gradle.kts`, `evaluationDependsOn(":devils-shared")`, includes a `gamesRecoverySmoke` run config.

### 2.3 `devils-shared` (shared library)

- **Package:** `com.devils.addon.shared`
- **Archives name:** `devils-shared-internal` (not published to Maven; consumed as project dependency).
- **Key classes:**
  - `SyncCrypto` — E2E encryption (AES-256-GCM, PBKDF2 128k iterations, envelope format `devils-e2e:v2:`).
  - `AbstractSyncConfigModule` — abstract `Module` base with `base-url`, `secret-key`, `api-key` settings, 3s timeout, NBT serialization.
  - `SyncDomainRoutes` — canonical URL paths (`/v1/core/sync/pull|push|stream`, `/v1/games/sync/pull|push|stream` + legacy aliases).
  - `SyncJsonUtils`, `SyncConfigDiagnostics` — JSON helpers and diagnostic probing.
- **Build:** Standalone `fabric-loom` 1.14.10, compile-only Meteor Client deps from `maven.meteordev.org`.
- **Tests:** None (all sync tests live in `devils-addon/src/test/`).

### 2.4 `SyncHub` (Python server)

- **File:** `sync_backend.py` (74.7 KB, ~1900 lines) — single-file sync server.
- **Protocol:** HTTP/HTTPS JSON API with E2E encryption, request signing (HMAC-SHA256), nonce replay protection, bearer token auth.
- **Endpoints:** `/v1/core/sync/pull|push|stream`, `/v1/games/sync/pull|push|stream`, `/health`, `/admin/config` (read-only), `/admin/dump` (admin token).
- **Features:** long-poll streaming, namespace isolation, TLS support, Docker deployment, state persistence to JSON file.
- **Docker:** `Dockerfile` (Python 3.12-slim), `docker-compose.yml` with healthcheck and volume mount.
- **Config:** `.env.example` documents all environment variables; `.env` present (643B — credentials placeholder).
- **Tests:** `test_sync_backend.py` (27.2 KB), `sync_stress_tester.py` (34.5 KB), `admin_config_runtime_probe.py` (8.9 KB).

---

## 3. Technology Stack

| Layer | Technology | Version |
|---|---|---|
| Language (client) | Java | 21 |
| Language (server) | Python | 3.12 |
| Build system | Gradle (Kotlin DSL) | 9.2.1 |
| Build plugin | fabric-loom | 1.14.10 |
| Mod loader | Fabric Loader | 0.18.4 |
| Minecraft | Minecraft | 1.21.11 |
| Mappings | Yarn | 1.21.11+build.4 |
| Base framework | Meteor Client | 0.5.2-2297 |
| Config UI | YACL | 4.3.4+1.21.2 |
| Mapping reflection | FabricSRG | 0.1.3+1.21 |
| Mixin | (bundled via Meteor/Fabric) | — |
| Xaero World Map | fabric-1.21.11-1.40.11 | — |
| Xaero Minimap | fabric-1.21.11-25.3.10 | — |
| XaeroPlus | 2.30.9+fabric-1.21.11 | — |
| Discord RPC | discord-rpc (custom) | 1.1.0 |

---

## 4. Build System Details

### 4.1 Root (`build.gradle.kts`)
- Empty plugins/repos block (all configured per-module).
- `collectReleaseArtifacts` task: syncs addon + game JARs from `libs/` subfolders into root `libs/`.

### 4.2 `devils-addon/build.gradle.kts` (981 lines)
- **Version resolution chain:** `DEVILS_ADDON_VERSION` env → `addon_version_override` property → `addon_version` from `gradle.properties`.
- **Source-native system:** Custom mechanism to vendor select dependency JARs into the output JAR (client-side only, excludes from publication). Configured via `sourceNativeJar`, `sourceNativeNestedJar`, `sourceNativeVendor` dependency buckets.
- **Resource excludes:** Careful filtering of META-INF, LICENSE files, icon.png, yacl-128x.png, pack.mcmeta, architectury inject stubs.
- **Run configurations:** `Devils Addon Client` (default), `Devils Addon Client (IDEA IN-DEV)`, `addonIntegrationSmoke`.
- **Validation tasks:** `validateAddonMetadata`, `validateJarSanity`, `validateJarContents`.
- **`evaluationDependsOn(":devils-shared")`** — shared module must evaluate first.

### 4.3 `devils-game/build.gradle.kts` (122 lines)
- Simpler build, same fabric-loom plugin.
- `evaluationDependsOn(":devils-shared")`.
- Game version resolved from `DEVILS_GAME_VERSION` env → `game_version_override` → `game_version`.
- `gamesRecoverySmoke` run config for crash-guard validation.

### 4.4 `devils-shared/build.gradle.kts`
- `archivesName = "devils-shared-internal"`.
- Compile-only Meteor Client dependency (never published to Maven).

### 4.5 `settings.gradle.kts`
- `pluginManagement` repos: Fabric Maven, Maven Central, Gradle Plugin Portal.
- Includes: `devils-addon`, `devils-game`, `devils-shared`.

### 4.6 `gradle.properties`
- `org.gradle.jvmargs=-Xmx3G` — elevated heap for complex builds.

---

## 5. CI/CD Pipeline

Six GitHub Actions workflows in `.github/workflows/`:

| Workflow | Trigger | Purpose |
|---|---|---|
| `build-pr.yml` | PR to `main` | Builds on ubuntu-latest, Java 21, uploads JARs as artifacts |
| `dev-build.yml` | Push to non-`main` branches | Builds + publishes dev Maven artifact |
| `release-tag.yml` | `workflow_dispatch` (tag input) | Builds + creates GitHub Release with JARs |
| `auto-patch-tag.yml` | `workflow_dispatch` | Auto-increments semver PATCH from latest tag, creates new tag |
| `manual-release-tag.yml` | `workflow_dispatch` (optional tag) | Validates/creates explicit release tag |
| `sync-readme-version.yml` | `release: [published]` | Updates README version badges via `curl` + `jq` |

**Observations:**
- No automated test execution in CI (build-only).
- No linting/checkstyle/spotless configured.
- Tag-based release flow is well-structured with both auto-increment and manual options.
- README version sync is a nice touch for badge accuracy.

---

## 6. Module Architecture (devils-addon)

### 6.1 Combat Modules

| Module | Description |
|---|---|
| `AutoWasp` | Automated Wasp attack logic (sub-package: `autowasp/` with PathSearch/Pathfinder/RayTrace/Calculation) |
| `AntiWasp` | Anti-Wasp countermeasures |
| `AutoCev` | Auto Crystal-Elytra-void PvP automation |
| `AutoPearl` | Automated ender pearl management |
| `MaceSpoof` | Mace damage spoofing |
| `SpearSpoof` | Spear damage spoofing |
| `LavaBucket` | Lava bucket placement automation |

### 6.2 Utility Modules

| Module | Description |
|---|---|
| `AutoLogin` | Auto-authentication for server login prompts |
| `AutoCraft` | Automated crafting (sub-package: `autocraft/` with planner/policies) |
| `AutoAnvilRename` | Automated anvil rename operations |
| `ClipModules` | Noclip/clipping utilities |
| `NukerPlus` | Enhanced block-breaking/nuker |
| `TnTBomber` | TNT placement automation |
| `JoinWatcher` | Player join monitoring |
| `Ping` | Network ping tracking/overlay |
| `DiscordRPC` | Discord Rich Presence integration |
| `SyncHub` | E2E encrypted config sync between clients |

### 6.3 Complex Sub-systems

**StashMover** — large sub-package (`stashmover/`) with:
- `StashMoverSupport/` — slot policy, runtime, pearl approach, own pearl tracker, interaction, command
- Multi-faceted stash relocation automation

**HighwayBuilder** — highway construction automation (`highwaybuilder/` sub-package)

**ModAutoUpdater** — automatic mod update checking

### 6.4 Infrastructure / Utilities

| Class | Purpose |
|---|---|
| `CrashGuard` | PreLaunch entrypoint; log filtering, Xaero version compatibility checks |
| `TrackerPlayersSetting` | Custom setting type for player tracking lists |
| `AddonModulesConfig` | Module configuration management |
| Smoke tests (4 classes) | Runtime self-validation for AutoWasp, Input, NukerPlus, Assimilated quality |

---

## 7. Sync System Architecture

The sync system enables data sharing between multiple DEVILS clients through a central Python server.

### 7.1 Encryption Layer (`SyncCrypto`)

```
Client A (push) → AES-256-GCM encrypt → Base64 envelope "devils-e2e:v2:<nonce><ciphertext>"
  → HTTP POST to SyncHub →
Client B (pull) → AES-256-GCM decrypt → plaintext
```

- **Key derivation:** PBKDF2WithHmacSHA256, 128,000 iterations, random 16-byte salt
- **Nonce:** 12 random bytes per message
- **Auth tag:** 128-bit GCM tag appended to ciphertext
- **Envelope format:** `devils-e2e:v2:<base64(salt + nonce + ciphertext + tag)>`

### 7.2 Request Signing (Server-side)

- HMAC-SHA256 over `timestamp + body`
- 30-second signing window (configurable)
- Nonce replay protection (TTL 120s, cache max 200k entries)

### 7.3 Protocol Flow

```
1. Client encrypts payload with shared secret
2. Client signs request with signing key
3. POST /v1/core/sync/push { payload: "<encrypted>", timestamp, nonce, signature }
4. Server validates auth token, signature, decrypts envelope
5. Server stores state in JSON file
6. Other client polls /v1/core/sync/pull or opens /v1/core/sync/stream (long-poll)
7. Server delivers encrypted payload to polling client
```

### 7.4 Namespaces

- **Core namespace:** AutoLogin credentials, Ping settings
- **Games namespace:** Mini-game presence and state
- **Legacy endpoints:** `/pull`, `/push` redirect to core namespace

### 7.5 Module Design (`AbstractSyncConfigModule`)

- Base class for Meteor `Module` with sync capabilities.
- Settings: `base-url`, `secret-key`, `api-key` — user-configurable in Meteor GUI.
- 3-second HTTP timeout, 25ms stream polling interval, 45-second diagnostic cooldown.
- NBT serialization for Meteor's config system.

---

## 8. Games Subsystem (devils-game)

### 8.1 Game Overlays

| Game | Classes | Notes |
|---|---|---|
| **Chess** | `ChessOverlay`, `ChessCore`, `ChessLogic`, `ChessOverlaySession`, `ChessOverlayRenderer`, `ChessGameScreen` | Full board logic, FEN support, move validation, GUI screen |
| **Checkers** | `CheckersOverlay`, `CheckersLogic`, `CheckersGameScreen` | Board + move rules, GUI screen |
| **Doom** | `DoomOverlay`, `DoomSession`, `DoomWindow` | Overlay-based Doom game |
| **Slot Machine** | `SlotMachineOverlay`, `SlotMachineSession`, `SlotMachineWindow` | Casino-style overlay |
| **Russian Roulette** | `RussianRouletteOverlay`, `RussianRouletteSession`, `RussianRouletteWindow` | Game of chance overlay |
| **Blackjack** | `BlackjackOverlay` | Card game overlay |

### 8.2 Chess Engine Integration

- **Stockfish** used as sole engine via `ProcessBuilder` (previously had SCRIPT mode, now removed).
- `StockfishDownloader` — auto-downloads Stockfish binary from GitHub releases.
- `NativeLoader` — platform-specific native library loading.
- `ChessEngine` — engine abstraction with `setStockfishConfig`, `setScriptLevel` methods.
- NNUE network support added for Stockfish evaluation.

### 8.3 Game Sync

- `MiniGamesSyncRuntime` — runtime sync for multiplayer game sessions.
- `MiniGamesSyncCodec` — encoding/decoding game state for network transport.
- `MiniGamesPresenceModel` — tracks player presence in game sessions.
- `GameSyncHub` — Meteor module that manages game-level sync.
- `GameCrashGuard` — crash protection for game subsystem.

---

## 9. Test Coverage

### 9.1 Java Unit Tests (devils-addon)

| Test Class | Scope |
|---|---|
| `ProjectConfigSuiteTest` (22.9 KB) | Comprehensive build config validation |
| `ProjectStructureSuiteTest` (6.8 KB) | Source tree structure verification |
| `AutoLoginTest` | AutoLogin module logic |
| `AutoLoginSourceTest` | AutoLogin source/implementation |
| `AutoCraftSourceTest` | AutoCraft module logic |
| `AutoCraftPoliciesTest` | AutoCraft policy engine |
| `AutoCraftPlannerTest` | AutoCraft planning logic |
| `NukerPlusTest` | NukerPlus module logic |
| `StashMoverSourceTest` | StashMover module logic |
| `StashMoverSlotPolicyTest` | StashMover slot allocation |
| `StashMoverOwnPearlTrackerTest` | Pearl tracking logic |
| `StashMoverPearlApproachAnchorTest` | Pearl approach anchoring |
| `StashMoverMessageMatcherTest` | Chat message matching |
| `DiscordRpcSourceTest` | Discord RPC integration |
| `SessionCommandSourceTest` | Session command logic |
| `TrackerPlayersSettingTest` (6.1 KB) | Player tracker setting |
| `JoinSoundPlayerTest` (6.1 KB) | Join sound playback |
| `AssimilatedInteractionRegressionTest` | Interaction regression guard |
| `SyncConfigMigrationRuntimeTest` | Sync config migration |
| `SyncConfigDiagnosticsTest` | Sync diagnostics |

### 9.2 Python Tests (SyncHub)

| Test File | Size | Scope |
|---|---|---|
| `test_sync_backend.py` | 27.2 KB | Core sync protocol, encryption, auth, streaming |
| `sync_stress_tester.py` | 34.5 KB | Load/stress testing for sync server |
| `admin_config_runtime_probe.py` | 8.9 KB | Admin endpoint validation |

### 9.3 Runtime Smoke Tests (Java)

Embedded self-checks that run during client startup:
- `AutoWaspRuntimeValidation`
- `InputRuntimeValidation`
- `NukerPlusSmoke`
- `AssimilatedQualitySmoke`
- `DevilsGameRecoverySmoke` (game module)

### 9.4 Coverage Assessment

- **Good:** Core modules have dedicated test classes. Build config has a comprehensive test suite. Sync system has both unit and stress tests.
- **Missing:** No tests for `AutoCev`, `AutoPearl`, `AutoWasp` pathfinding logic, `HighwayBuilder`, `TnTBomber`, `ClipModules`, `LavaBucket`, game overlays (chess/checkers logic). The `devils-shared` module has no direct test directory.
- **No CI test execution:** Tests exist but are not run in GitHub Actions workflows.

---

## 10. Dependencies

### 10.1 Client-side (devils-addon)

| Dependency | Type | Source |
|---|---|---|
| Meteor Client | Compile (provided) | `maven.meteordev.org` |
| YACL 4.3.4+1.21.2 | Compile | Maven |
| Xaero World Map | Source-native (vendored) | CurseForge Maven |
| Xaero Minimap | Source-native (vendored) | CurseForge Maven |
| XaeroPlus | Source-native (vendored) | GitHub Packages |
| FabricSRG 0.1.3+1.21 | Source-native (vendored) | Modrinth Maven |
| Discord RPC 1.1.0 | Source-native (vendored) | GitHub Packages |
| Architectury | Runtime (excluded from JAR) | CurseForge Maven |
| Caffeine | Runtime (excluded from JAR) | Maven Central |
| LambdaEvents | Runtime (excluded from JAR) | — |

### 10.2 Source-native Vendoring System

The build uses a custom "source-native" mechanism to embed third-party classes directly into the addon JAR:
- **`sourceNativeJar`** configuration: full JAR dependency inclusion.
- **`sourceNativeNestedJar`** configuration: nested JAR inclusion.
- **`sourceNativeVendor`** configuration: selective class inclusion.
- Extraction task `extractSourceNativeRuntimeClasses` merges class files into the build output.
- Resource excludes prevent license/icon duplication.
- Java class excludes for Caffeine/LambdaEvents prevent conflict with Meteor Client's bundled copies.

This is an advanced pattern that avoids runtime dependency conflicts in Minecraft mod loading, but increases build complexity significantly.

---

## 11. Security Considerations

### 11.1 Positive

- **E2E encryption:** AES-256-GCM with PBKDF2 (128k iterations) is strong.
- **Request signing:** HMAC-SHA256 with replay protection.
- **TLS support:** Server supports TLS 1.3 minimum.
- **Bearer token auth:** Separate admin and user tokens.
- **Secret management:** `.env.example` with placeholder values; `.env` present but should be gitignored (not in `.gitignore` — **flagged as risk**).
- **No hardcoded secrets found** in source code.

### 11.2 Concerns

| Risk | Detail | Severity |
|---|---|---|
| `.env` file in repo | `SyncHub/.env` (643B) exists and may contain real credentials | **High** — verify gitignore covers this |
| No `.gitignore` for SyncHub | SyncHub directory has no dedicated `.gitignore` | Medium |
| `__pycache__` in repo | `SyncHub/tests/__pycache__/` is tracked | Low |
| Single-file server | 74.7 KB Python file is hard to audit and test in isolation | Low |
| PBKDF2 iteration count | 128k is adequate for 2026, but Argon2 would be stronger | Informational |
| Healthcheck uses `_create_unverified_context()` | Docker healthcheck disables TLS verification | Low (local only) |

---

## 12. Code Quality Observations

### 12.1 Strengths

1. **Comprehensive build validation:** Custom tasks (`validateAddonMetadata`, `validateJarSanity`, `validateJarContents`) catch packaging errors early.
2. **Runtime self-checks:** Smoke tests at startup catch configuration/runtime issues before user interaction.
3. **Clean separation:** Shared crypto/config logic in `devils-shared`, game logic isolated in `devils-game`.
4. **Well-documented README:** Clear module descriptions, build instructions, known limitations.
5. **CI/CD with tag automation:** Three-tag strategy (auto-patch, manual, readme sync) is well thought out.
6. **Extensive test suite for a mod project:** 20 Java tests + 3 Python test files.

### 12.2 Concerns

| Issue | Detail |
|---|---|
| **981-line build script** | `devils-addon/build.gradle.kts` is very large; could benefit from extraction into buildSrc or convention plugins |
| **No linting/static analysis** | No Checkstyle, Spotless, PMD, or ErrorProne configured |
| **Tests not in CI** | GitHub Actions only builds, never runs tests |
| **Mixed concerns in modules** | Some modules (e.g., `StashMover`) have very deep sub-packages that could be separate modules |
| **No Javadoc** | Public API classes lack documentation (common in mod projects but limits maintainability) |
| **No dependency lock file** | No `gradle.lockfile` or version catalog — dependency updates could silently change behavior |
| **Git history noise** | Many `[MiMo] Auto-snapshot` commits from automated tooling; squash/clean before releases recommended |

### 12.3 Build Complexity Notes

The source-native vendoring system is the most complex part of the build. It exists because:
1. Meteor Client bundles its own copies of Caffeine, LambdaEvents, and other libraries.
2. Xaero mods and XaeroPlus are not on standard Maven repos (require CurseForge/GitHub Packages).
3. The addon needs to include these classes without causing classpath conflicts at runtime.

This is a valid approach for the Minecraft mod ecosystem but makes the build harder to maintain and debug.

---

## 13. File Statistics

| Component | Files (approx) | Lines (approx) |
|---|---|---|
| `devils-addon` Java sources | ~60 | ~12,000 |
| `devils-game` Java sources | ~40 | ~8,000 |
| `devils-shared` Java sources | ~8 | ~1,500 |
| `SyncHub` Python | 1 main + 3 test | ~3,000 |
| Build scripts | 4 Gradle files | ~1,200 |
| CI/CD workflows | 6 | ~400 |
| Total | ~120 | ~26,100 |

---

## 14. Dependency Graph

```
devils-addon ──depends──→ devils-shared (project dependency)
devils-game  ──depends──→ devils-shared (project dependency)

devils-shared ──compileOnly──→ Meteor Client (maven.meteordev.org)

devils-addon ──sourceNativeJar──→ Xaero World Map, Xaero Minimap, XaeroPlus, Discord RPC, Architectury, Caffeine, LambdaEvents
devils-addon ──implementation──→ YACL, FabricSRG

devils-game ──implementation──→ (standard Fabric/Minecraft deps via loom)
```

---

## 15. Recommendations (Read-Only Assessment)

| Priority | Recommendation |
|---|---|
| High | Add `SyncHub/.env` to `.gitignore` and verify no real credentials are tracked |
| High | Add test execution to CI (`./gradlew test`) |
| High | Remove `__pycache__` from version control; add to `.gitignore` |
| Medium | Add Checkstyle or Spotless for consistent code formatting |
| Medium | Consider extracting build logic from the 981-line `devils-addon/build.gradle.kts` into `buildSrc/` |
| Medium | Add Gradle dependency lock or version catalog for reproducible builds |
| Medium | Add Javadoc to `AbstractSyncConfigModule`, `SyncCrypto`, and `SyncDomainRoutes` (public API) |
| Low | Clean up git history before releases (squash auto-snapshot commits) |
| Low | Split `sync_backend.py` into smaller modules for maintainability |
| Low | Add game logic unit tests (ChessLogic, CheckersLogic) |

---

*End of audit. No files were modified during this review.*

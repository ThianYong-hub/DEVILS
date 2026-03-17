package com.example.addon.audio;

import com.example.addon.settings.TrackerPlayerRule.SoundSourceMode;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JoinSoundPlayer {
    public static final String SOUND_FOLDER = "devils-addon/sounds";
    public static final Identifier FALLBACK_SOUND_ID = Identifier.of("minecraft", "entity.experience_orb.pickup");
    public static final int DEFAULT_LOCAL_OGG_VOLUME_PERCENT = 100;
    static final Logger LOG = LoggerFactory.getLogger("Devils/JoinSoundPlayer");
    static final ExecutorService AUDIO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Devils-JoinSoundPlayer");
        thread.setDaemon(true);
        return thread;
    });

    private JoinSoundPlayer() {}

    public enum ResolvedType {
        FILE,
        SOUND_ID
    }

    public record ResolvedSound(ResolvedType type, Path filePath, Identifier soundId) {
    }

    public enum PlaybackStatus {
        OK,
        FILE_NOT_FOUND,
        UNSUPPORTED_FORMAT,
        LINE_UNAVAILABLE,
        DECODE_ERROR,
        FALLBACK_USED
    }

    public record PlaybackDiagnosticResult(
        PlaybackStatus status,
        ResolvedSound resolved,
        boolean fallbackUsed,
        String message
    ) {
        public boolean isOk() {
            return status == PlaybackStatus.OK;
        }
    }

    public static void play(SoundSourceMode sourceMode, String soundValue, String defaultSoundSpec) {
        play(sourceMode, soundValue, defaultSoundSpec, DEFAULT_LOCAL_OGG_VOLUME_PERCENT);
    }

    public static void play(SoundSourceMode sourceMode, String soundValue, String defaultSoundSpec, int localOggVolumePercent) {
        playInternal(sourceMode, soundValue, defaultSoundSpec, localOggVolumePercent, false, false);
    }

    public static PlaybackDiagnosticResult playWithDiagnostics(SoundSourceMode sourceMode, String soundValue, String defaultSoundSpec) {
        return playWithDiagnostics(sourceMode, soundValue, defaultSoundSpec, DEFAULT_LOCAL_OGG_VOLUME_PERCENT);
    }

    public static PlaybackDiagnosticResult playWithDiagnostics(
        SoundSourceMode sourceMode,
        String soundValue,
        String defaultSoundSpec,
        int localOggVolumePercent
    ) {
        return toPublic(playInternal(sourceMode, soundValue, defaultSoundSpec, localOggVolumePercent, true, true));
    }

    public static PlaybackDiagnosticResult testPlay(SoundSourceMode sourceMode, String soundValue, String defaultSoundSpec) {
        return testPlay(sourceMode, soundValue, defaultSoundSpec, DEFAULT_LOCAL_OGG_VOLUME_PERCENT);
    }

    public static PlaybackDiagnosticResult testPlay(
        SoundSourceMode sourceMode,
        String soundValue,
        String defaultSoundSpec,
        int localOggVolumePercent
    ) {
        return toPublic(playInternal(sourceMode, soundValue, defaultSoundSpec, localOggVolumePercent, true, true));
    }

    public static void play(String playerSoundSpec, String defaultSoundSpec) {
        play(SoundSourceMode.ManualId, playerSoundSpec, defaultSoundSpec);
    }

    public static ResolvedSound resolveForPlayback(
        SoundSourceMode sourceMode,
        String soundValue,
        String defaultSoundSpec,
        Path gameDir
    ) {
        return toPublic(resolveForPlaybackDetailed(sourceMode, soundValue, defaultSoundSpec, gameDir, false).sound());
    }

    public static ResolvedSound resolveForPlaybackWithDiagnostics(
        SoundSourceMode sourceMode,
        String soundValue,
        String defaultSoundSpec,
        Path gameDir
    ) {
        return toPublic(resolveForPlaybackDetailed(sourceMode, soundValue, defaultSoundSpec, gameDir, true).sound());
    }

    private static JoinSoundResolvedPlayback resolveForPlaybackDetailed(
        SoundSourceMode sourceMode,
        String soundValue,
        String defaultSoundSpec,
        Path gameDir,
        boolean diagnostics
    ) {
        Path soundsRoot = ensureSoundsDirectory(gameDir);

        if (diagnostics) {
            LOG.info(
                "Join sound request: sourceMode={}, soundValue='{}', default='{}', soundsRoot='{}'.",
                sourceMode,
                soundValue,
                defaultSoundSpec,
                soundsRoot
            );
        }

        JoinSoundRuleResolution playerResolved = resolveRuleSound(sourceMode, soundValue, soundsRoot, diagnostics);
        if (playerResolved.sound() != null) {
            return new JoinSoundResolvedPlayback(
                playerResolved.sound(),
                false,
                JoinSoundPlaybackStatus.OK,
                "Rule sound played."
            );
        }

        if (diagnostics) LOG.warn("Rule sound invalid, trying module default sound.");

        JoinSoundResolvedSound defaultResolved = resolveLegacySoundSpec(defaultSoundSpec, soundsRoot, diagnostics);
        if (defaultResolved != null) {
            return new JoinSoundResolvedPlayback(
                defaultResolved,
                true,
                statusOrFallback(playerResolved.status()),
                nonBlank(playerResolved.message(), "Rule sound was invalid, fallback to module default.")
            );
        }

        if (diagnostics) LOG.warn("Module default sound invalid, falling back to '{}'.", FALLBACK_SOUND_ID);
        return new JoinSoundResolvedPlayback(
            JoinSoundResolvedSound.fromSoundId(FALLBACK_SOUND_ID),
            true,
            statusOrFallback(playerResolved.status()),
            nonBlank(playerResolved.message(), "Rule sound was invalid, fallback to builtin.")
        );
    }

    public static ResolvedSound resolveForPlayback(String playerSoundSpec, String defaultSoundSpec, Path gameDir) {
        return resolveForPlayback(SoundSourceMode.ManualId, playerSoundSpec, defaultSoundSpec, gameDir);
    }

    private static JoinSoundPlaybackDiagnosticResult playInternal(
        SoundSourceMode sourceMode,
        String soundValue,
        String defaultSoundSpec,
        int localOggVolumePercent,
        boolean diagnostics,
        boolean waitForFileResult
    ) {
        Path gameDir = safeGameDir();
        JoinSoundResolvedPlayback resolved = resolveForPlaybackDetailed(sourceMode, soundValue, defaultSoundSpec, gameDir, diagnostics);
        int sanitizedVolumePercent = sanitizeLocalOggVolumePercent(localOggVolumePercent);

        if (diagnostics) {
            String file = resolved.sound().filePath() == null ? "-" : resolved.sound().filePath().toString();
            String id = resolved.sound().soundId() == null ? "-" : resolved.sound().soundId().toString();
            LOG.info(
                "Resolved join sound: type={}, file='{}', id='{}', fallbackUsed={}, primaryStatus={}, oggVolume={}%, classloader={}",
                resolved.sound().type(),
                file,
                id,
                resolved.fallbackUsed(),
                resolved.primaryStatus(),
                sanitizedVolumePercent,
                JoinSoundPlayer.class.getClassLoader()
            );
        }

        JoinSoundPlaybackStatus playbackStatus = playResolved(resolved.sound(), sanitizedVolumePercent, diagnostics, waitForFileResult);
        JoinSoundPlaybackStatus finalStatus = toFinalStatus(resolved, playbackStatus);
        String message = messageForStatus(finalStatus, resolved.primaryMessage());

        if (diagnostics) {
            LOG.info(
                "Join sound diagnostic result: finalStatus={}, playbackStatus={}, fallbackUsed={}, message='{}'.",
                finalStatus,
                playbackStatus,
                resolved.fallbackUsed() || playbackStatus != JoinSoundPlaybackStatus.OK,
                message
            );
        }

        return new JoinSoundPlaybackDiagnosticResult(
            finalStatus,
            resolved.sound(),
            resolved.fallbackUsed() || playbackStatus != JoinSoundPlaybackStatus.OK,
            message
        );
    }

    private static Path safeGameDir() {
        try {
            return FabricLoader.getInstance().getGameDir();
        } catch (Throwable ignored) {
            return Path.of(".");
        }
    }

    public static Path ensureSoundsDirectory() {
        return ensureSoundsDirectory(FabricLoader.getInstance().getGameDir());
    }

    public static Path ensureSoundsDirectory(Path gameDir) {
        Path root = resolveSoundsRoot(gameDir);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            LOG.warn("Unable to create sounds folder '{}': {}", root, e.toString());
        }
        return root;
    }

    public static List<String> listLocalSoundFiles() {
        return listLocalSoundFiles(ensureSoundsDirectory());
    }

    public static List<String> listLocalSoundFiles(Path soundsRoot) {
        List<String> files = new ArrayList<>();

        if (soundsRoot == null || !Files.isDirectory(soundsRoot)) return files;

        try (Stream<Path> stream = Files.walk(soundsRoot)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".ogg"))
                .forEach(path -> files.add(
                    soundsRoot.relativize(path).toString().replace('\\', '/')
                ));
        } catch (IOException e) {
            LOG.warn("Failed to list local sounds in '{}': {}", soundsRoot, e.toString());
        }

        files.sort(Comparator.comparing(String::toLowerCase));
        return files;
    }

    public static Path resolveSoundsRoot(Path gameDir) {
        Path root = (gameDir == null ? Path.of(".") : gameDir).resolve(SOUND_FOLDER);
        return root.toAbsolutePath().normalize();
    }

    private static PlaybackDiagnosticResult toPublic(JoinSoundPlaybackDiagnosticResult result) {
        if (result == null) return new PlaybackDiagnosticResult(PlaybackStatus.FALLBACK_USED, null, true, "No diagnostic result.");
        return new PlaybackDiagnosticResult(
            toPublic(result.status()),
            toPublic(result.resolved()),
            result.fallbackUsed(),
            result.message()
        );
    }

    private static ResolvedSound toPublic(JoinSoundResolvedSound sound) {
        if (sound == null) return null;
        return new ResolvedSound(toPublic(sound.type()), sound.filePath(), sound.soundId());
    }

    private static PlaybackStatus toPublic(JoinSoundPlaybackStatus status) {
        if (status == null) return PlaybackStatus.FALLBACK_USED;
        return switch (status) {
            case OK -> PlaybackStatus.OK;
            case FILE_NOT_FOUND -> PlaybackStatus.FILE_NOT_FOUND;
            case UNSUPPORTED_FORMAT -> PlaybackStatus.UNSUPPORTED_FORMAT;
            case LINE_UNAVAILABLE -> PlaybackStatus.LINE_UNAVAILABLE;
            case DECODE_ERROR -> PlaybackStatus.DECODE_ERROR;
            case FALLBACK_USED -> PlaybackStatus.FALLBACK_USED;
        };
    }

    private static ResolvedType toPublic(JoinSoundResolvedType type) {
        if (type == null) return ResolvedType.SOUND_ID;
        return switch (type) {
            case FILE -> ResolvedType.FILE;
            case SOUND_ID -> ResolvedType.SOUND_ID;
        };
    }

    private static JoinSoundRuleResolution resolveRuleSound(SoundSourceMode sourceMode, String soundValue, Path soundsRoot, boolean diagnostics) {
        if (sourceMode == null) sourceMode = SoundSourceMode.ManualId;

        return switch (sourceMode) {
            case LocalFolder -> {
                JoinSoundLocalResolution local = resolveLocalOggPathDetailed(soundValue, soundsRoot, diagnostics);
                if (local.path() != null) yield new JoinSoundRuleResolution(JoinSoundResolvedSound.fromFile(local.path()), JoinSoundPlaybackStatus.OK, "Local sound resolved.");
                if (diagnostics) LOG.warn("Local sound could not be resolved from '{}'.", soundValue);
                yield new JoinSoundRuleResolution(null, local.status(), local.message());
            }
            case GameRegistry, ManualId -> {
                Identifier id = parseIdentifier(soundValue);
                if (id == null && diagnostics) {
                    LOG.warn("Sound id '{}' is invalid for source '{}'.", soundValue, sourceMode);
                }
                if (id != null) yield new JoinSoundRuleResolution(JoinSoundResolvedSound.fromSoundId(id), JoinSoundPlaybackStatus.OK, "Sound id resolved.");
                yield new JoinSoundRuleResolution(null, JoinSoundPlaybackStatus.FALLBACK_USED, "Sound id is invalid.");
            }
        };
    }

    private static JoinSoundResolvedSound resolveLegacySoundSpec(String soundSpec, Path soundsRoot, boolean diagnostics) {
        if (soundSpec == null) return null;
        String value = soundSpec.trim();
        if (value.isEmpty()) return null;

        if (value.toLowerCase().endsWith(".ogg")) {
            Path file = resolveLocalOggPath(value, soundsRoot, diagnostics);
            if (file == null && diagnostics) {
                LOG.warn("Default local sound file '{}' could not be resolved.", value);
            }
            return file != null ? JoinSoundResolvedSound.fromFile(file) : null;
        }

        Identifier id = parseIdentifier(value);
        if (id == null && diagnostics) {
            LOG.warn("Default sound id '{}' is invalid.", value);
        }
        return id != null ? JoinSoundResolvedSound.fromSoundId(id) : null;
    }

    public static Path resolveLocalOggPath(String value, Path soundsRoot) {
        return resolveLocalOggPath(value, soundsRoot, false);
    }

    private static Path resolveLocalOggPath(String value, Path soundsRoot, boolean diagnostics) {
        return resolveLocalOggPathDetailed(value, soundsRoot, diagnostics).path();
    }

    private static JoinSoundLocalResolution resolveLocalOggPathDetailed(String value, Path soundsRoot, boolean diagnostics) {
        if (soundsRoot == null || value == null) {
            return new JoinSoundLocalResolution(null, JoinSoundPlaybackStatus.FILE_NOT_FOUND, "Local sound path is empty.");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return new JoinSoundLocalResolution(null, JoinSoundPlaybackStatus.FILE_NOT_FOUND, "Local sound path is empty.");

        try {
            Path relative = Path.of(trimmed);
            if (relative.isAbsolute()) {
                if (diagnostics) LOG.warn("Rejected absolute local sound path '{}'.", trimmed);
                return new JoinSoundLocalResolution(null, JoinSoundPlaybackStatus.FILE_NOT_FOUND, "Absolute local path is not allowed.");
            }

            Path candidate = soundsRoot.resolve(relative).normalize();
            if (!candidate.startsWith(soundsRoot)) {
                if (diagnostics) LOG.warn("Rejected path traversal for local sound '{}'.", trimmed);
                return new JoinSoundLocalResolution(null, JoinSoundPlaybackStatus.FILE_NOT_FOUND, "Path traversal is not allowed.");
            }
            if (!candidate.getFileName().toString().toLowerCase().endsWith(".ogg")) {
                if (diagnostics) LOG.warn("Rejected non-ogg local sound path '{}'.", trimmed);
                return new JoinSoundLocalResolution(null, JoinSoundPlaybackStatus.FILE_NOT_FOUND, "Only .ogg local sounds are supported.");
            }
            if (!Files.isRegularFile(candidate)) {
                if (diagnostics) LOG.warn("Local sound file does not exist '{}'.", candidate);
                return new JoinSoundLocalResolution(null, JoinSoundPlaybackStatus.FILE_NOT_FOUND, "Local .ogg file was not found.");
            }

            return new JoinSoundLocalResolution(candidate, JoinSoundPlaybackStatus.OK, "Local sound resolved.");
        } catch (InvalidPathException ignored) {
            if (diagnostics) LOG.warn("Invalid local sound path '{}'.", trimmed);
            return new JoinSoundLocalResolution(null, JoinSoundPlaybackStatus.FILE_NOT_FOUND, "Local path is invalid.");
        }
    }

    private static Identifier parseIdentifier(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return value.contains(":") ? Identifier.of(value) : Identifier.of("minecraft", value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JoinSoundPlaybackStatus playResolved(
        JoinSoundResolvedSound resolved,
        int localOggVolumePercent,
        boolean diagnostics,
        boolean waitForFileResult
    ) {
        if (resolved.type() == JoinSoundResolvedType.FILE) {
            if (waitForFileResult) {
                JoinSoundPlaybackStatus status = JoinSoundOggPlayback.playBlocking(resolved.filePath(), localOggVolumePercent, diagnostics);
                if (status != JoinSoundPlaybackStatus.OK) playSoundId(FALLBACK_SOUND_ID);
                return status;
            }

            JoinSoundOggPlayback.playAsync(resolved.filePath(), localOggVolumePercent, diagnostics);
            return JoinSoundPlaybackStatus.OK;
        }

        return playSoundId(resolved.soundId()) ? JoinSoundPlaybackStatus.OK : JoinSoundPlaybackStatus.FALLBACK_USED;
    }

    static boolean playSoundId(Identifier id) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return true;

        boolean exactMatch = false;
        SoundEvent sound = SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP;
        try {
            exactMatch = Registries.SOUND_EVENT.containsId(id);
            if (exactMatch) sound = Registries.SOUND_EVENT.get(id);
        } catch (Throwable t) {
            LOG.warn("Failed to resolve sound id '{}' from registry, using fallback '{}'.", id, FALLBACK_SOUND_ID, t);
        }

        SoundEvent finalSound = sound;
        client.execute(() -> client.getSoundManager().play(PositionedSoundInstance.ui(finalSound, 1.0f, 1.0f)));
        return exactMatch;
    }

    private static JoinSoundPlaybackStatus toFinalStatus(JoinSoundResolvedPlayback resolved, JoinSoundPlaybackStatus playbackStatus) {
        if (playbackStatus != JoinSoundPlaybackStatus.OK) return playbackStatus;
        if (resolved.primaryStatus() != JoinSoundPlaybackStatus.OK) return resolved.primaryStatus();
        if (resolved.fallbackUsed()) return JoinSoundPlaybackStatus.FALLBACK_USED;
        return JoinSoundPlaybackStatus.OK;
    }

    private static JoinSoundPlaybackStatus statusOrFallback(JoinSoundPlaybackStatus status) {
        return status == JoinSoundPlaybackStatus.OK ? JoinSoundPlaybackStatus.FALLBACK_USED : status;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String messageForStatus(JoinSoundPlaybackStatus status, String primaryMessage) {
        return switch (status) {
            case OK -> "Sound played.";
            case FILE_NOT_FOUND -> nonBlank(primaryMessage, "Local .ogg file not found. Fallback played.");
            case UNSUPPORTED_FORMAT -> "Unsupported .ogg format. Fallback played.";
            case LINE_UNAVAILABLE -> "Audio device line unavailable. Fallback played.";
            case DECODE_ERROR -> "Could not decode/open local .ogg. Fallback played.";
            case FALLBACK_USED -> nonBlank(primaryMessage, "Fallback sound was used.");
        };
    }

    public static int sanitizeLocalOggVolumePercent(int value) {
        if (value < 0) return 0;
        if (value > 200) return 200;
        return value;
    }
}




package com.example.addon.audio;

import com.example.addon.settings.SoundSourceMode;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JoinSoundPlayer {
    public static final String SOUND_FOLDER = "devils-addon/sounds";
    public static final Identifier FALLBACK_SOUND_ID = Identifier.of("minecraft", "entity.experience_orb.pickup");
    public static final int DEFAULT_LOCAL_OGG_VOLUME_PERCENT = 100;
    private static final Logger LOG = LoggerFactory.getLogger("Devils/JoinSoundPlayer");
    private static final ExecutorService AUDIO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Devils-JoinSoundPlayer");
        thread.setDaemon(true);
        return thread;
    });

    private JoinSoundPlayer() {}

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
        return playInternal(sourceMode, soundValue, defaultSoundSpec, localOggVolumePercent, true, true);
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
        return playInternal(sourceMode, soundValue, defaultSoundSpec, localOggVolumePercent, true, true);
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
        return resolveForPlaybackDetailed(sourceMode, soundValue, defaultSoundSpec, gameDir, false).sound();
    }

    public static ResolvedSound resolveForPlaybackWithDiagnostics(
        SoundSourceMode sourceMode,
        String soundValue,
        String defaultSoundSpec,
        Path gameDir
    ) {
        return resolveForPlaybackDetailed(sourceMode, soundValue, defaultSoundSpec, gameDir, true).sound();
    }

    private static ResolvedPlayback resolveForPlaybackDetailed(
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

        RuleResolution playerResolved = resolveRuleSound(sourceMode, soundValue, soundsRoot, diagnostics);
        if (playerResolved.sound() != null) {
            return new ResolvedPlayback(
                playerResolved.sound(),
                false,
                PlaybackStatus.OK,
                "Rule sound played."
            );
        }

        if (diagnostics) LOG.warn("Rule sound invalid, trying module default sound.");

        ResolvedSound defaultResolved = resolveLegacySoundSpec(defaultSoundSpec, soundsRoot, diagnostics);
        if (defaultResolved != null) {
            return new ResolvedPlayback(
                defaultResolved,
                true,
                statusOrFallback(playerResolved.status()),
                nonBlank(playerResolved.message(), "Rule sound was invalid, fallback to module default.")
            );
        }

        if (diagnostics) LOG.warn("Module default sound invalid, falling back to '{}'.", FALLBACK_SOUND_ID);
        return new ResolvedPlayback(
            ResolvedSound.fromSoundId(FALLBACK_SOUND_ID),
            true,
            statusOrFallback(playerResolved.status()),
            nonBlank(playerResolved.message(), "Rule sound was invalid, fallback to builtin.")
        );
    }

    public static ResolvedSound resolveForPlayback(String playerSoundSpec, String defaultSoundSpec, Path gameDir) {
        return resolveForPlayback(SoundSourceMode.ManualId, playerSoundSpec, defaultSoundSpec, gameDir);
    }

    private static PlaybackDiagnosticResult playInternal(
        SoundSourceMode sourceMode,
        String soundValue,
        String defaultSoundSpec,
        int localOggVolumePercent,
        boolean diagnostics,
        boolean waitForFileResult
    ) {
        Path gameDir = safeGameDir();
        ResolvedPlayback resolved = resolveForPlaybackDetailed(sourceMode, soundValue, defaultSoundSpec, gameDir, diagnostics);
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

        PlaybackStatus playbackStatus = playResolved(resolved.sound(), sanitizedVolumePercent, diagnostics, waitForFileResult);
        PlaybackStatus finalStatus = toFinalStatus(resolved, playbackStatus);
        String message = messageForStatus(finalStatus, resolved.primaryMessage());

        if (diagnostics) {
            LOG.info(
                "Join sound diagnostic result: finalStatus={}, playbackStatus={}, fallbackUsed={}, message='{}'.",
                finalStatus,
                playbackStatus,
                resolved.fallbackUsed() || playbackStatus != PlaybackStatus.OK,
                message
            );
        }

        return new PlaybackDiagnosticResult(
            finalStatus,
            resolved.sound(),
            resolved.fallbackUsed() || playbackStatus != PlaybackStatus.OK,
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

    private static RuleResolution resolveRuleSound(SoundSourceMode sourceMode, String soundValue, Path soundsRoot, boolean diagnostics) {
        if (sourceMode == null) sourceMode = SoundSourceMode.ManualId;

        return switch (sourceMode) {
            case LocalFolder -> {
                LocalResolution local = resolveLocalOggPathDetailed(soundValue, soundsRoot, diagnostics);
                if (local.path() != null) yield new RuleResolution(ResolvedSound.fromFile(local.path()), PlaybackStatus.OK, "Local sound resolved.");
                if (diagnostics) LOG.warn("Local sound could not be resolved from '{}'.", soundValue);
                yield new RuleResolution(null, local.status(), local.message());
            }
            case GameRegistry, ManualId -> {
                Identifier id = parseIdentifier(soundValue);
                if (id == null && diagnostics) {
                    LOG.warn("Sound id '{}' is invalid for source '{}'.", soundValue, sourceMode);
                }
                if (id != null) yield new RuleResolution(ResolvedSound.fromSoundId(id), PlaybackStatus.OK, "Sound id resolved.");
                yield new RuleResolution(null, PlaybackStatus.FALLBACK_USED, "Sound id is invalid.");
            }
        };
    }

    private static ResolvedSound resolveLegacySoundSpec(String soundSpec, Path soundsRoot, boolean diagnostics) {
        if (soundSpec == null) return null;
        String value = soundSpec.trim();
        if (value.isEmpty()) return null;

        if (value.toLowerCase().endsWith(".ogg")) {
            Path file = resolveLocalOggPath(value, soundsRoot, diagnostics);
            if (file == null && diagnostics) {
                LOG.warn("Default local sound file '{}' could not be resolved.", value);
            }
            return file != null ? ResolvedSound.fromFile(file) : null;
        }

        Identifier id = parseIdentifier(value);
        if (id == null && diagnostics) {
            LOG.warn("Default sound id '{}' is invalid.", value);
        }
        return id != null ? ResolvedSound.fromSoundId(id) : null;
    }

    public static Path resolveLocalOggPath(String value, Path soundsRoot) {
        return resolveLocalOggPath(value, soundsRoot, false);
    }

    private static Path resolveLocalOggPath(String value, Path soundsRoot, boolean diagnostics) {
        return resolveLocalOggPathDetailed(value, soundsRoot, diagnostics).path();
    }

    private static LocalResolution resolveLocalOggPathDetailed(String value, Path soundsRoot, boolean diagnostics) {
        if (soundsRoot == null || value == null) {
            return new LocalResolution(null, PlaybackStatus.FILE_NOT_FOUND, "Local sound path is empty.");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return new LocalResolution(null, PlaybackStatus.FILE_NOT_FOUND, "Local sound path is empty.");

        try {
            Path relative = Path.of(trimmed);
            if (relative.isAbsolute()) {
                if (diagnostics) LOG.warn("Rejected absolute local sound path '{}'.", trimmed);
                return new LocalResolution(null, PlaybackStatus.FILE_NOT_FOUND, "Absolute local path is not allowed.");
            }

            Path candidate = soundsRoot.resolve(relative).normalize();
            if (!candidate.startsWith(soundsRoot)) {
                if (diagnostics) LOG.warn("Rejected path traversal for local sound '{}'.", trimmed);
                return new LocalResolution(null, PlaybackStatus.FILE_NOT_FOUND, "Path traversal is not allowed.");
            }
            if (!candidate.getFileName().toString().toLowerCase().endsWith(".ogg")) {
                if (diagnostics) LOG.warn("Rejected non-ogg local sound path '{}'.", trimmed);
                return new LocalResolution(null, PlaybackStatus.FILE_NOT_FOUND, "Only .ogg local sounds are supported.");
            }
            if (!Files.isRegularFile(candidate)) {
                if (diagnostics) LOG.warn("Local sound file does not exist '{}'.", candidate);
                return new LocalResolution(null, PlaybackStatus.FILE_NOT_FOUND, "Local .ogg file was not found.");
            }

            return new LocalResolution(candidate, PlaybackStatus.OK, "Local sound resolved.");
        } catch (InvalidPathException ignored) {
            if (diagnostics) LOG.warn("Invalid local sound path '{}'.", trimmed);
            return new LocalResolution(null, PlaybackStatus.FILE_NOT_FOUND, "Local path is invalid.");
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

    private static PlaybackStatus playResolved(
        ResolvedSound resolved,
        int localOggVolumePercent,
        boolean diagnostics,
        boolean waitForFileResult
    ) {
        if (resolved.type() == ResolvedType.FILE) {
            if (waitForFileResult) {
                PlaybackStatus status = playOggBlocking(resolved.filePath(), localOggVolumePercent, diagnostics);
                if (status != PlaybackStatus.OK) playSoundId(FALLBACK_SOUND_ID);
                return status;
            }

            playOggAsync(resolved.filePath(), localOggVolumePercent, diagnostics);
            return PlaybackStatus.OK;
        }

        return playSoundId(resolved.soundId()) ? PlaybackStatus.OK : PlaybackStatus.FALLBACK_USED;
    }

    private static boolean playSoundId(Identifier id) {
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
        client.execute(() -> client.getSoundManager().play(PositionedSoundInstance.master(finalSound, 1.0f, 1.0f)));
        return exactMatch;
    }

    private static void playOggAsync(Path filePath, int localOggVolumePercent, boolean diagnostics) {
        AUDIO_EXECUTOR.execute(() -> {
            PlaybackStatus status = playOggOnAudioThread(filePath, localOggVolumePercent, diagnostics);
            if (status != PlaybackStatus.OK) {
                LOG.warn("Failed to play local join sound '{}', status={}. Falling back to '{}'.", filePath, status, FALLBACK_SOUND_ID);
                playSoundId(FALLBACK_SOUND_ID);
            }

            if (diagnostics) LOG.info("Diagnostic playback finished for local sound '{}'.", filePath);
        });
    }

    private static PlaybackStatus playOggBlocking(Path filePath, int localOggVolumePercent, boolean diagnostics) {
        Future<PlaybackStatus> future = AUDIO_EXECUTOR.submit(() -> playOggOnAudioThread(filePath, localOggVolumePercent, diagnostics));
        try {
            PlaybackStatus status = future.get();
            if (diagnostics) LOG.info("Blocking diagnostic playback finished for local sound '{}', status={}.", filePath, status);
            return status;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Audio playback interrupted for '{}'.", filePath, e);
            return PlaybackStatus.DECODE_ERROR;
        } catch (ExecutionException e) {
            LOG.warn("Audio playback execution failed for '{}'.", filePath, e.getCause());
            return PlaybackStatus.DECODE_ERROR;
        }
    }

    private static PlaybackStatus playOggOnAudioThread(Path filePath, int localOggVolumePercent, boolean diagnostics) {
        Thread thread = Thread.currentThread();
        ClassLoader previousClassLoader = thread.getContextClassLoader();
        ClassLoader targetClassLoader = JoinSoundPlayer.class.getClassLoader();
        int sanitizedVolumePercent = sanitizeLocalOggVolumePercent(localOggVolumePercent);

        thread.setContextClassLoader(targetClassLoader);

        if (diagnostics) {
            LOG.info(
                "Audio thread='{}', contextClassLoader='{}', playerClassLoader='{}'.",
                thread.getName(),
                thread.getContextClassLoader(),
                targetClassLoader
            );
        }

        try (AudioInputStream inputStream = openPcmAudioStream(filePath)) {
            Clip clip = openClip(inputStream.getFormat(), diagnostics);
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP || event.getType() == LineEvent.Type.CLOSE) {
                    clip.close();
                }
            });
            clip.open(inputStream);
            applyLocalOggVolume(clip, sanitizedVolumePercent, diagnostics);
            clip.start();
            return PlaybackStatus.OK;
        } catch (UnsupportedAudioFileException e) {
            LOG.warn("Unsupported local sound format '{}'.", filePath, e);
            return PlaybackStatus.UNSUPPORTED_FORMAT;
        } catch (LineUnavailableException e) {
            LOG.warn("Audio line unavailable for local sound '{}'.", filePath, e);
            return PlaybackStatus.LINE_UNAVAILABLE;
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("Decode/open failure for local sound '{}'.", filePath, e);
            return PlaybackStatus.DECODE_ERROR;
        } catch (Exception e) {
            LOG.warn("Unexpected playback error for local sound '{}'.", filePath, e);
            return PlaybackStatus.DECODE_ERROR;
        } finally {
            thread.setContextClassLoader(previousClassLoader);
        }
    }

    private static void applyLocalOggVolume(Clip clip, int volumePercent, boolean diagnostics) {
        if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            if (diagnostics) LOG.info("MASTER_GAIN control is not supported by clip. Keeping default gain.");
            return;
        }

        try {
            FloatControl control = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            float min = control.getMinimum();
            float max = control.getMaximum();

            float gain;
            if (volumePercent <= 0) {
                gain = min;
            } else {
                gain = (float) (20.0 * Math.log10(volumePercent / 100.0));
            }

            if (gain < min) gain = min;
            if (gain > max) gain = max;

            control.setValue(gain);

            if (diagnostics) {
                LOG.info("Applied local .ogg volume: {}% -> {} dB (range {}..{}).", volumePercent, gain, min, max);
            }
        } catch (Exception e) {
            LOG.warn("Failed to apply local .ogg volume {}%. Continuing with default gain.", volumePercent, e);
        }
    }

    private static Clip openClip(AudioFormat format, boolean diagnostics) throws LineUnavailableException {
        DataLine.Info lineInfo = new DataLine.Info(Clip.class, format);

        try {
            return (Clip) AudioSystem.getLine(lineInfo);
        } catch (LineUnavailableException | IllegalArgumentException primary) {
            if (diagnostics) {
                LOG.warn("Clip open via DataLine.Info failed, trying AudioSystem.getClip(). format={}", format, primary);
            }

            try {
                return AudioSystem.getClip();
            } catch (LineUnavailableException fallbackLine) {
                fallbackLine.addSuppressed(primary);
                throw fallbackLine;
            } catch (IllegalArgumentException fallbackInvalid) {
                LineUnavailableException wrapped = new LineUnavailableException("Unable to obtain clip line.");
                wrapped.addSuppressed(primary);
                wrapped.addSuppressed(fallbackInvalid);
                throw wrapped;
            }
        }
    }

    private static AudioInputStream openPcmAudioStream(Path filePath) throws UnsupportedAudioFileException, IOException {
        AudioInputStream encoded = AudioSystem.getAudioInputStream(filePath.toFile());
        AudioFormat source = encoded.getFormat();

        AudioFormat pcm = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            source.getSampleRate(),
            16,
            source.getChannels(),
            source.getChannels() * 2,
            source.getSampleRate(),
            false
        );

        if (!AudioSystem.isConversionSupported(pcm, source)) {
            encoded.close();
            throw new UnsupportedAudioFileException("PCM conversion is not supported for source format: " + source);
        }

        return AudioSystem.getAudioInputStream(pcm, encoded);
    }

    private static PlaybackStatus toFinalStatus(ResolvedPlayback resolved, PlaybackStatus playbackStatus) {
        if (playbackStatus != PlaybackStatus.OK) return playbackStatus;
        if (resolved.primaryStatus() != PlaybackStatus.OK) return resolved.primaryStatus();
        if (resolved.fallbackUsed()) return PlaybackStatus.FALLBACK_USED;
        return PlaybackStatus.OK;
    }

    private static PlaybackStatus statusOrFallback(PlaybackStatus status) {
        return status == PlaybackStatus.OK ? PlaybackStatus.FALLBACK_USED : status;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String messageForStatus(PlaybackStatus status, String primaryMessage) {
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

    public enum ResolvedType {
        FILE,
        SOUND_ID
    }

    public record ResolvedSound(ResolvedType type, Path filePath, Identifier soundId) {
        public static ResolvedSound fromFile(Path path) {
            return new ResolvedSound(ResolvedType.FILE, path, null);
        }

        public static ResolvedSound fromSoundId(Identifier id) {
            return new ResolvedSound(ResolvedType.SOUND_ID, null, id);
        }
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

    private record RuleResolution(ResolvedSound sound, PlaybackStatus status, String message) {}

    private record LocalResolution(Path path, PlaybackStatus status, String message) {}

    private record ResolvedPlayback(
        ResolvedSound sound,
        boolean fallbackUsed,
        PlaybackStatus primaryStatus,
        String primaryMessage
    ) {}
}

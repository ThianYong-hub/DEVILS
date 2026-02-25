package com.example.addon;

import com.example.addon.audio.JoinSoundPlayer;
import com.example.addon.settings.SoundSourceMode;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinSoundPlayerTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesPlayerFileBeforeDefaultSound() throws IOException {
        Path soundsRoot = JoinSoundPlayer.resolveSoundsRoot(tempDir);
        Files.createDirectories(soundsRoot);
        Path playerFile = soundsRoot.resolve("bandit.ogg");
        Files.writeString(playerFile, "not-a-real-ogg");

        JoinSoundPlayer.ResolvedSound resolved = JoinSoundPlayer.resolveForPlayback(
            SoundSourceMode.LocalFolder,
            "bandit.ogg",
            "minecraft:block.note_block.bell",
            tempDir
        );

        assertEquals(JoinSoundPlayer.ResolvedType.FILE, resolved.type());
        assertNotNull(resolved.filePath());
        assertEquals(playerFile.toAbsolutePath().normalize(), resolved.filePath());
    }

    @Test
    void fallsBackToDefaultSoundIdWhenPlayerSpecInvalid() {
        JoinSoundPlayer.ResolvedSound resolved = JoinSoundPlayer.resolveForPlayback(
            SoundSourceMode.LocalFolder,
            "missing-player-file.ogg",
            "minecraft:block.note_block.bell",
            tempDir
        );

        assertEquals(JoinSoundPlayer.ResolvedType.SOUND_ID, resolved.type());
        assertEquals(Identifier.of("minecraft", "block.note_block.bell"), resolved.soundId());
    }

    @Test
    void fallsBackToDefaultLocalFileWhenPlayerLocalFileMissing() throws IOException {
        Path soundsRoot = JoinSoundPlayer.resolveSoundsRoot(tempDir);
        Files.createDirectories(soundsRoot.resolve("defaults"));

        Path defaultLocal = soundsRoot.resolve("defaults").resolve("ok.ogg");
        Files.writeString(defaultLocal, "not-a-real-ogg");

        JoinSoundPlayer.ResolvedSound resolved = JoinSoundPlayer.resolveForPlayback(
            SoundSourceMode.LocalFolder,
            "missing-player-file.ogg",
            "defaults/ok.ogg",
            tempDir
        );

        assertEquals(JoinSoundPlayer.ResolvedType.FILE, resolved.type());
        assertNotNull(resolved.filePath());
        assertEquals(defaultLocal.toAbsolutePath().normalize(), resolved.filePath());
    }

    @Test
    void blocksPathTraversalAndUsesBuiltInFallback() {
        JoinSoundPlayer.ResolvedSound resolved = JoinSoundPlayer.resolveForPlayback(
            SoundSourceMode.LocalFolder,
            "../escape.ogg",
            "",
            tempDir
        );

        assertEquals(JoinSoundPlayer.ResolvedType.SOUND_ID, resolved.type());
        assertEquals(JoinSoundPlayer.FALLBACK_SOUND_ID, resolved.soundId());
    }

    @Test
    void listsLocalSoundsRecursivelySorted() throws IOException {
        Path soundsRoot = JoinSoundPlayer.resolveSoundsRoot(tempDir);
        Files.createDirectories(soundsRoot.resolve("nested"));
        Files.createDirectories(soundsRoot.resolve("z"));

        Files.writeString(soundsRoot.resolve("nested").resolve("b.ogg"), "a");
        Files.writeString(soundsRoot.resolve("a.ogg"), "a");
        Files.writeString(soundsRoot.resolve("z").resolve("c.ogg"), "a");
        Files.writeString(soundsRoot.resolve("nested").resolve("ignore.txt"), "a");

        var listed = JoinSoundPlayer.listLocalSoundFiles(soundsRoot);
        assertEquals(
            java.util.List.of("a.ogg", "nested/b.ogg", "z/c.ogg"),
            listed
        );
    }

    @Test
    void resolvesGameRegistryAndManualIds() {
        JoinSoundPlayer.ResolvedSound gameSound = JoinSoundPlayer.resolveForPlayback(
            SoundSourceMode.GameRegistry,
            "minecraft:block.note_block.harp",
            "",
            tempDir
        );

        JoinSoundPlayer.ResolvedSound manualSound = JoinSoundPlayer.resolveForPlayback(
            SoundSourceMode.ManualId,
            "myaddon:custom.sound",
            "",
            tempDir
        );

        assertEquals(JoinSoundPlayer.ResolvedType.SOUND_ID, gameSound.type());
        assertEquals(Identifier.of("minecraft", "block.note_block.harp"), gameSound.soundId());
        assertEquals(JoinSoundPlayer.ResolvedType.SOUND_ID, manualSound.type());
        assertEquals(Identifier.of("myaddon", "custom.sound"), manualSound.soundId());
    }

    @Test
    void resolveWithDiagnosticsFallsBackToBuiltinForInvalidInputs() {
        JoinSoundPlayer.ResolvedSound resolved = JoinSoundPlayer.resolveForPlaybackWithDiagnostics(
            SoundSourceMode.LocalFolder,
            "missing.ogg",
            "bad id with space",
            tempDir
        );

        assertEquals(JoinSoundPlayer.ResolvedType.SOUND_ID, resolved.type());
        assertEquals(JoinSoundPlayer.FALLBACK_SOUND_ID, resolved.soundId());
    }

    @Test
    void testPlayReportsFileNotFoundForMissingLocalSound() {
        JoinSoundPlayer.PlaybackDiagnosticResult result = JoinSoundPlayer.testPlay(
            SoundSourceMode.LocalFolder,
            "missing.ogg",
            ""
        );

        assertEquals(JoinSoundPlayer.PlaybackStatus.FILE_NOT_FOUND, result.status());
        assertTrue(result.fallbackUsed());
    }

    @Test
    void testPlayReportsFallbackUsedForInvalidManualId() {
        JoinSoundPlayer.PlaybackDiagnosticResult result = JoinSoundPlayer.testPlay(
            SoundSourceMode.ManualId,
            "bad id with space",
            ""
        );

        assertEquals(JoinSoundPlayer.PlaybackStatus.FALLBACK_USED, result.status());
        assertTrue(result.fallbackUsed());
    }

    @Test
    void sanitizeLocalOggVolumePercentClampsRange() {
        assertEquals(0, JoinSoundPlayer.sanitizeLocalOggVolumePercent(-50));
        assertEquals(35, JoinSoundPlayer.sanitizeLocalOggVolumePercent(35));
        assertEquals(200, JoinSoundPlayer.sanitizeLocalOggVolumePercent(999));
    }
}

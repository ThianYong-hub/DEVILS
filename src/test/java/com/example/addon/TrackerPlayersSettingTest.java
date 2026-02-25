package com.example.addon;

import com.example.addon.settings.SoundSourceMode;
import com.example.addon.settings.TrackEventMode;
import com.example.addon.settings.TrackerPlayerRule;
import com.example.addon.settings.TrackerPlayersSetting;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackerPlayersSettingTest {
    @Test
    void serializesAndDeserializesRules() {
        List<TrackerPlayerRule> rules = List.of(
            new TrackerPlayerRule(
                "Bandit",
                TrackEventMode.Join,
                true,
                false,
                "",
                SoundSourceMode.LocalFolder,
                "bandit.ogg",
                80,
                0
            ),
            new TrackerPlayerRule(
                "Enemy123",
                TrackEventMode.Death,
                false,
                true,
                "/msg hello",
                SoundSourceMode.ManualId,
                "minecraft:block.note_block.bell",
                100,
                350
            )
        );

        NbtList serialized = TrackerPlayersSetting.rulesToNbt(rules);
        List<TrackerPlayerRule> deserialized = TrackerPlayersSetting.rulesFromNbt(serialized);

        assertEquals(rules, deserialized);
    }

    @Test
    void normalizeLocalSoundValuePicksFirstWhenMissingOrBlank() {
        List<String> localSounds = new ArrayList<>(List.of("a.ogg", "nested/b.ogg"));

        assertEquals("a.ogg", TrackerPlayersSetting.normalizeLocalSoundValue("", localSounds));
        assertEquals("a.ogg", TrackerPlayersSetting.normalizeLocalSoundValue("missing.ogg", localSounds));
        assertEquals("nested/b.ogg", TrackerPlayersSetting.normalizeLocalSoundValue("nested/b.ogg", localSounds));
    }

    @Test
    void normalizeLocalSoundValueReturnsEmptyWhenNoLocalSounds() {
        assertEquals("", TrackerPlayersSetting.normalizeLocalSoundValue("anything.ogg", List.of()));
    }

    @Test
    void loadDefaultsWhenOptionalFieldsMissingInNbt() {
        NbtList list = new NbtList();

        var tag = new net.minecraft.nbt.NbtCompound();
        tag.putString("player", "Bandit");
        tag.putString("event-mode", "Join");
        tag.putBoolean("sound-enabled", true);
        tag.putBoolean("send-enabled", false);
        tag.putString("command-text", "");
        tag.putString("sound-source", "LocalFolder");
        tag.putString("sound-value", "bandit.ogg");
        list.add(tag);

        List<TrackerPlayerRule> loaded = TrackerPlayersSetting.rulesFromNbt(list);
        assertEquals(1, loaded.size());
        assertEquals(TrackerPlayerRule.DEFAULT_OGG_VOLUME_PERCENT, loaded.getFirst().oggVolumePercent());
        assertEquals(TrackerPlayerRule.DEFAULT_CHAT_DELAY_MS, loaded.getFirst().chatDelayMs());
    }

    @Test
    void sourceContainsOnlineSelectorAndDiagnosticSoundButton() throws IOException {
        String source = Files.readString(Path.of(
            "src", "main", "java", "com", "example", "addon", "settings", "TrackerPlayersSetting.java"
        ));

        assertTrue(source.contains("OnlinePlayerSelectScreen"));
        assertTrue(source.contains("testPlay("));
        assertTrue(source.contains("addColumnCell("));
        assertTrue(source.contains("addLocalVolumeEditor("));
        assertFalse(source.contains("theme.checkbox(rule.soundEnabled())).minWidth("));
        assertFalse(source.contains("theme.checkbox(rule.sendEnabled())).minWidth("));
        assertFalse(source.contains("theme.button(\"Online\")).minWidth("));
    }
}

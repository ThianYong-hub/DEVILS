package com.example.addon;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinWatcherAutoDisableTest {
    @Test
    void autoDisableIsAppliedOnlyToTriggeredRule() throws IOException {
        String source = Files.readString(Path.of(
            "src", "main", "java", "com", "example", "addon", "modules", "JoinWatcher.java"
        ));

        assertTrue(
            source.contains("updatedRules.set(i, rule.withSendEnabled(false));"),
            "Expected per-rule send disable assignment by index."
        );
        assertTrue(
            source.contains("if (autoDisableSendAfterChat.get())"),
            "Expected auto-disable guard in send flow."
        );
        assertTrue(
            source.contains("if (changed) trackerPlayers.set(updatedRules);"),
            "Expected applying updated rules list only after targeted change."
        );
    }

    @Test
    void sourceContainsDeathEventHandling() throws IOException {
        String source = Files.readString(Path.of(
            "src", "main", "java", "com", "example", "addon", "modules", "JoinWatcher.java"
        ));

        assertTrue(
            source.contains("DeathMessageS2CPacket"),
            "Expected DeathMessageS2CPacket import/usage."
        );
        assertTrue(
            source.contains("handleDeathPacket"),
            "Expected dedicated death packet handler."
        );
        assertTrue(
            source.contains("case Death -> trigger == RuleTrigger.Death;"),
            "Expected Death event branch in matchesEvent."
        );
        assertTrue(
            source.contains("case Both -> trigger == RuleTrigger.Join || trigger == RuleTrigger.Leave;"),
            "Expected Both to remain Join+Leave only."
        );
        assertTrue(
            source.contains("rule.soundValueFor(toRuleTrigger(trigger))"),
            "Expected event-specific sound selection for join/leave/death."
        );
    }

    @Test
    void sourceContainsPerRuleDelayedChatSend() throws IOException {
        String source = Files.readString(Path.of(
            "src", "main", "java", "com", "example", "addon", "modules", "JoinWatcher.java"
        ));

        assertTrue(
            source.contains("int delayMs = rule.chatDelayMs();"),
            "Expected per-rule chat delay usage."
        );
        assertTrue(
            source.contains("queueDelayedChatSend(i, rule, command, delayMs);"),
            "Expected delayed send queueing path."
        );
        assertTrue(
            source.contains("CHAT_SEND_EXECUTOR.schedule("),
            "Expected scheduled executor usage for delayed chat send."
        );
    }
}

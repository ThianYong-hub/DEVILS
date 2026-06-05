package com.devils.addon.modules;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoLoginTest {
    @Test
    void parsesLoginCommand() {
        AutoLogin.ParsedCommand parsed = AutoLogin.parseCredentialCommand("/login secret");

        assertNotNull(parsed);
        assertEquals(AutoLogin.LoginMode.LOGIN, parsed.mode());
        assertEquals("secret", parsed.password());
    }

    @Test
    void parsesRegisterCommandWithMatchingPasswords() {
        AutoLogin.ParsedCommand parsed = AutoLogin.parseCredentialCommand("reg pass123 pass123");

        assertNotNull(parsed);
        assertEquals(AutoLogin.LoginMode.REGISTER, parsed.mode());
        assertEquals("pass123", parsed.password());
    }

    @Test
    void rejectsRegisterCommandWithDifferentPasswords() {
        assertNull(AutoLogin.parseCredentialCommand("/reg pass123 different"));
    }

    @Test
    void matchesUsernameAndServerIgnoringCaseAndWhitespace() {
        assertTrue(AutoLogin.matchesKey("  PlayerOne ", " Example.org:25565 ", "playerone", "example.org:25565"));
        assertTrue(AutoLogin.matchesKey("PlayerOne", "example.org:25565", "playerone", "example.org"));
        assertFalse(AutoLogin.matchesKey("PlayerOne", "example.org", "playerone", "other.org"));
    }

    @Test
    void detectsLoginPromptOnlyForLoginMode() {
        assertTrue(AutoLogin.isAuthRequest("Please login with /login password", AutoLogin.LoginMode.LOGIN));
        assertTrue(AutoLogin.isAuthRequest("example >> Authorize with /login <password>", AutoLogin.LoginMode.LOGIN));
        assertFalse(AutoLogin.isAuthRequest("player typed login in chat", AutoLogin.LoginMode.LOGIN));
        assertFalse(AutoLogin.isAuthRequest("player typed /login123 in chat", AutoLogin.LoginMode.LOGIN));
        assertFalse(AutoLogin.isAuthRequest("Please login now", AutoLogin.LoginMode.LOGIN));
        assertFalse(AutoLogin.isAuthRequest("Please login with /login password", AutoLogin.LoginMode.REGISTER));
    }

    @Test
    void detectsRegisterPromptOnlyForRegisterMode() {
        assertTrue(AutoLogin.isAuthRequest("Please register with /register password password", AutoLogin.LoginMode.REGISTER));
        assertTrue(AutoLogin.isAuthRequest("Use /reg <password> <password>", AutoLogin.LoginMode.REGISTER));
        assertFalse(AutoLogin.isAuthRequest("Use /region claim", AutoLogin.LoginMode.REGISTER));
        assertFalse(AutoLogin.isAuthRequest("Please register now", AutoLogin.LoginMode.REGISTER));
        assertFalse(AutoLogin.isAuthRequest("Please register with /register password password", AutoLogin.LoginMode.LOGIN));
    }

    @Test
    void matchesDebugMessagesOnlyWhenTextActuallyMatches() {
        assertTrue(AutoLogin.debugMessagesMatch(
            "<22:18> example >> Authorize with /login <password>",
            "example >> Authorize with /login <password>"
        ));
        assertFalse(AutoLogin.debugMessagesMatch(
            "Player A: /login secret",
            "Player B: /login secret"
        ));
        assertFalse(AutoLogin.debugMessagesMatch(
            "prefix example >> /login",
            "example >> /login"
        ));
    }

    @Test
    void detectsAuthLikeMessagesWithCommandBoundariesOnly() {
        assertTrue(AutoLogin.looksLikeAuthPrompt("example >> Authorize with /login <password>"));
        assertTrue(AutoLogin.looksLikeAuthPrompt("Server: use /register pass pass"));
        assertTrue(AutoLogin.looksLikeAuthPrompt("&#C8C8C8<21:53>&r &fServer >> &6/login <password>&f"));
        assertFalse(AutoLogin.looksLikeAuthPrompt("player says login without slash"));
        assertFalse(AutoLogin.looksLikeAuthPrompt("player says /login123"));
    }

    @Test
    void trustsAuthPacketOnlyWhenMessageDoesNotPointToOnlinePlayer() {
        assertTrue(AutoLogin.isTrustedAuthPacketMessage(
            "example >> Authorize with /login <password>, you have 3 attempts.",
            Set.of("someplayer", "anotherplayer")
        ));
        assertFalse(AutoLogin.isTrustedAuthPacketMessage(
            "<00:27> <Player> example >> Authorize with /login <password>, you have 3 attempts.",
            Set.of("player", "anotherplayer")
        ));
        assertFalse(AutoLogin.isTrustedAuthPacketMessage(
            "admin >> Authorize with /login <password>, you have 3 attempts.",
            Set.of("admin", "anotherplayer")
        ));
    }

    @Test
    void trustsReceiveFallbackOnlyWhenMessageCannotBeLinkedToOnlinePlayer() {
        String prompt = "devils >> Authorize with /login <password>, you have 3 attempts.";

        assertTrue(AutoLogin.isReceiveFallbackMessageTrusted(prompt, Set.of("player1", "player2")));
        assertTrue(AutoLogin.isReceiveFallbackMessageTrusted("<01:01> " + prompt, Set.of("player1", "player2")));
        assertFalse(AutoLogin.isReceiveFallbackMessageTrusted(prompt, Set.of("devils", "player2")));
        assertFalse(AutoLogin.isReceiveFallbackMessageTrusted("attacker >> Authorize with /login <password>, you have 3 attempts.", Set.of("attacker", "player2")));
        assertFalse(AutoLogin.isReceiveFallbackMessageTrusted("<attacker> " + prompt, Set.of("attacker", "player2")));
        assertFalse(AutoLogin.isReceiveFallbackMessageTrusted("?? <<attacker>> " + prompt, Set.of("attacker", "player2")));
        assertFalse(AutoLogin.isReceiveFallbackMessageTrusted(prompt, Set.of()));
    }

    @Test
    void composesCommandsForSupportedModes() {
        assertEquals("/login secret", AutoLogin.composeCommand(AutoLogin.LoginMode.LOGIN, "secret"));
        assertEquals("/reg secret secret", AutoLogin.composeCommand(AutoLogin.LoginMode.REGISTER, "secret"));
        assertNull(AutoLogin.composeCommand(AutoLogin.LoginMode.LOGIN, "   "));
    }
}

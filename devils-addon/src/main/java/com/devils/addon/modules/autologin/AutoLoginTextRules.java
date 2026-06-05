package com.devils.addon.modules.autologin;

import com.devils.addon.modules.AutoLogin;
import meteordevelopment.meteorclient.systems.accounts.Account;
import meteordevelopment.meteorclient.systems.accounts.Accounts;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class AutoLoginTextRules {
    private static final Pattern PLAYER_NAME_TOKEN = Pattern.compile("[a-z0-9_]{3,16}");
    private static final Pattern LOGIN_COMMAND_PATTERN = Pattern.compile("(?<![a-z0-9_])/login(?![a-z0-9_])");
    private static final Pattern REGISTER_COMMAND_PATTERN = Pattern.compile("(?<![a-z0-9_])/register(?![a-z0-9_])");
    private static final Pattern REG_COMMAND_PATTERN = Pattern.compile("(?<![a-z0-9_])/reg(?![a-z0-9_])");
    private static final Pattern LEGACY_COLOR_CODE_PATTERN = Pattern.compile("(?i)[\u00A7&][0-9A-FK-ORX]");
    private static final Pattern HEX_COLOR_CODE_PATTERN = Pattern.compile("(?i)&#[0-9a-f]{6}");

    private AutoLoginTextRules() {
    }

    public static AutoLogin.ParsedCommand parseCredentialCommand(String rawCommand) {
        if (rawCommand == null) return null;
        String trimmed = rawCommand.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("/")) trimmed = trimmed.substring(1).trim();
        if (trimmed.isEmpty()) return null;
        String[] args = trimmed.split("\\s+");
        if (args.length < 2) return null;
        String command = args[0].toLowerCase(Locale.ROOT);
        return switch (command) {
            case "login" -> args.length == 2 ? new AutoLogin.ParsedCommand(AutoLogin.LoginMode.LOGIN, args[1]) : null;
            case "reg", "register" -> args.length == 3 && args[1].equals(args[2]) ? new AutoLogin.ParsedCommand(AutoLogin.LoginMode.REGISTER, args[1]) : null;
            default -> null;
        };
    }

    public static boolean matchesKey(String storedUsername, String storedServer, String currentUsername, String currentServer) {
        return normalizeKey(storedUsername).equals(normalizeKey(currentUsername)) && normalizeServerKey(storedServer).equals(normalizeServerKey(currentServer));
    }

    public static boolean isAuthRequest(String message, AutoLogin.LoginMode mode) {
        if (message == null) return false;
        String normalized = normalizeMessage(message);
        if (normalized.isEmpty()) return false;
        return switch (mode) {
            case LOGIN -> containsLoginPrompt(normalized);
            case REGISTER -> containsRegisterPrompt(normalized);
        };
    }

    public static boolean looksLikeAuthPrompt(String message) {
        String normalized = normalizeMessage(message);
        return containsLoginPrompt(normalized) || containsRegisterPrompt(normalized);
    }

    public static boolean debugMessagesMatch(String chatMessage, String packetMessage) {
        String normalizedChat = normalizeMessage(chatMessage);
        String normalizedPacket = normalizeMessage(packetMessage);
        if (normalizedChat.isEmpty() || normalizedPacket.isEmpty()) return false;
        if (normalizedChat.equals(normalizedPacket)) return true;
        return stripLeadingAngleTags(normalizedChat).equals(stripLeadingAngleTags(normalizedPacket));
    }

    public static boolean isTrustedAuthPacketMessage(String message, Set<String> onlinePlayerNames) {
        if (!looksLikeAuthPrompt(message)) return false;
        String normalized = normalizeMessage(message);
        String stripped = stripLeadingAngleTags(normalized);
        if (stripped.isEmpty() || !containsAuthContextNormalized(stripped)) return false;
        Set<String> onlineNames = onlinePlayerNames == null ? Set.of() : onlinePlayerNames;
        if (containsOnlinePlayerTokenBeforeAuthCommand(normalized, onlineNames)) return false;
        for (String tag : extractLeadingAngleTags(normalized)) {
            if (isLikelyPlayerNameToken(tag) && onlineNames.contains(normalizeKey(tag))) return false;
        }
        String relayPrefix = extractRelayPrefix(stripped);
        if (relayPrefix != null && onlineNames.contains(normalizeKey(relayPrefix))) return false;
        return true;
    }

    public static boolean isTrustedSystemAuthPacketMessage(String message) {
        if (!looksLikeAuthPrompt(message)) return false;
        String normalized = normalizeMessage(message);
        String stripped = stripLeadingAngleTags(normalized);
        return !stripped.isEmpty() && containsAuthContextNormalized(stripped);
    }

    public static String[] getAccountOptions() {
        List<String> values = new ArrayList<>();
        for (Account<?> account : Accounts.get()) {
            String username = account.getUsername();
            if (username == null || username.isBlank()) continue;
            if (values.stream().noneMatch(existing -> existing.equalsIgnoreCase(username))) values.add(username);
        }
        return values.toArray(String[]::new);
    }

    public static String[] getServerOptions(String currentServer, String selectedServer) {
        Set<String> values = new LinkedHashSet<>();
        if (currentServer != null && !currentServer.isBlank()) values.add(currentServer.trim());
        try {
            ServerList serverList = new ServerList(meteordevelopment.meteorclient.MeteorClient.mc);
            serverList.loadFile();
            for (int i = 0; i < serverList.size(); i++) {
                ServerInfo info = serverList.get(i);
                if (info == null || info.address == null || info.address.isBlank()) continue;
                values.add(info.address.trim());
            }
        } catch (Throwable ignored) {
        }
        if (selectedServer != null && !selectedServer.isBlank()) values.add(selectedServer.trim());
        if (values.isEmpty()) values.add("");
        return values.toArray(String[]::new);
    }

    public static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizeServerKey(String value) {
        String normalized = normalizeKey(value);
        return normalized.endsWith(":25565") ? normalized.substring(0, normalized.length() - 6) : normalized;
    }

    public static String normalizeMessage(String value) {
        if (value == null) return "";
        String flattened = value.replace('\n', ' ').replace('\r', ' ');
        String noHex = HEX_COLOR_CODE_PATTERN.matcher(flattened).replaceAll("");
        String noLegacy = LEGACY_COLOR_CODE_PATTERN.matcher(noHex).replaceAll("");
        return noLegacy.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean containsLoginPrompt(String message) {
        return containsCommandToken(message, LOGIN_COMMAND_PATTERN);
    }

    public static boolean containsRegisterPrompt(String message) {
        return containsCommandToken(message, REGISTER_COMMAND_PATTERN) || containsCommandToken(message, REG_COMMAND_PATTERN);
    }

    public static boolean containsCommandToken(String message, Pattern pattern) {
        return message != null && !message.isEmpty() && pattern.matcher(message).find();
    }

    public static List<String> extractLeadingAngleTags(String message) {
        ArrayList<String> tags = new ArrayList<>();
        if (message == null || message.isBlank()) return tags;
        String remaining = message.trim();
        while (remaining.startsWith("<")) {
            int end = remaining.indexOf('>');
            if (end <= 1) break;
            tags.add(remaining.substring(1, end).trim());
            remaining = remaining.substring(end + 1).trim();
        }
        return tags;
    }

    public static String stripLeadingAngleTags(String message) {
        if (message == null || message.isBlank()) return "";
        String remaining = message.trim();
        while (remaining.startsWith("<")) {
            int end = remaining.indexOf('>');
            if (end <= 1) break;
            remaining = remaining.substring(end + 1).trim();
        }
        return remaining;
    }

    public static boolean isLikelyPlayerNameToken(String token) {
        return token != null && PLAYER_NAME_TOKEN.matcher(token.trim().toLowerCase(Locale.ROOT)).matches();
    }

    public static String extractRelayPrefix(String message) {
        if (message == null || message.isBlank()) return null;
        int arrowIndex = message.indexOf(">>");
        if (arrowIndex <= 0) return null;
        String prefix = message.substring(0, arrowIndex).trim();
        return isLikelyPlayerNameToken(prefix) ? prefix : null;
    }

    public static boolean isReceiveFallbackMessageTrusted(String message, Set<String> onlinePlayerNames) {
        if (message == null || message.isBlank()) return false;
        if (onlinePlayerNames == null || onlinePlayerNames.isEmpty()) return false;

        String normalized = normalizeMessage(message);
        if (containsOnlinePlayerTokenBeforeAuthCommand(normalized, onlinePlayerNames)) return false;

        String stripped = stripLeadingAngleTags(normalized);
        if (stripped.isEmpty() || !looksLikeAuthPrompt(stripped) || !containsAuthContextNormalized(stripped)) return false;

        for (String tag : extractLeadingAngleTags(normalized)) {
            if (isLikelyPlayerNameToken(tag) && onlinePlayerNames.contains(normalizeKey(tag))) return false;
        }

        String relayPrefix = extractRelayPrefix(stripped);
        if (onlinePlayerNames.contains(normalizeKey(relayPrefix))) return false;
        return true;
    }

    public static boolean containsOnlinePlayerTokenBeforeAuthCommand(String normalizedMessage, Set<String> onlinePlayerNames) {
        if (normalizedMessage == null || normalizedMessage.isBlank() || onlinePlayerNames == null || onlinePlayerNames.isEmpty()) return false;

        int commandIndex = firstAuthCommandIndex(normalizedMessage);
        String prefix = commandIndex > 0 ? normalizedMessage.substring(0, commandIndex) : normalizedMessage;
        if (prefix.isBlank()) return false;

        String[] tokens = prefix.split("[^a-z0-9_]+");
        for (String token : tokens) {
            if (token.length() < 3) continue;
            if (onlinePlayerNames.contains(token)) return true;
        }

        return false;
    }

    public static int firstAuthCommandIndex(String message) {
        int index = -1;
        String[] commands = new String[] { "/login", "/register", "/reg" };
        for (String command : commands) {
            int candidate = message.indexOf(command);
            if (candidate == -1) continue;
            if (index == -1 || candidate < index) index = candidate;
        }
        return index;
    }

    public static boolean containsAuthContextNormalized(String message) {
        if (message == null || message.isBlank()) return false;
        return message.contains("login")
            || message.contains("register")
            || message.contains("log in")
            || message.contains("sign in")
            || message.contains("authoriz")
            || message.contains("auth")
            || message.contains("attempt")
            || message.contains("password")
            || message.contains("\u0430\u0432\u0442\u043e\u0440\u0438\u0437")
            || message.contains("\u0437\u0430\u0440\u0435\u0433\u0438\u0441\u0442\u0440")
            || message.contains("\u0432\u043e\u0439\u0434")
            || message.contains("\u043f\u0430\u0440\u043e\u043b")
            || message.contains("\u043f\u043e\u043f\u044b\u0442");
    }

    public static String composeCommand(AutoLogin.LoginMode mode, String password) {
        if (password == null) return null;
        String trimmedPassword = password.trim();
        if (trimmedPassword.isEmpty()) return null;
        return switch (mode) {
            case LOGIN -> "/login " + trimmedPassword;
            case REGISTER -> "/reg " + trimmedPassword + " " + trimmedPassword;
        };
    }
}



package com.example.addon.modules.chesttracker;

import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class ChestTrackerNbtUtils {
    private static final String ENDER_CHEST_SUFFIX = ":ender_chest";
    private static final String SKYBLOCK_ENDER_CHEST_SUFFIX = ":skyblock_ender_chest";
    private static final String SHARE_ENDER_CHEST_NAMESPACE = "shareenderchest:";

    private ChestTrackerNbtUtils() {
    }

    public static String sanitizeSnapshotNbt(String base64GzipNbt) {
        if (base64GzipNbt == null || base64GzipNbt.isBlank()) return "";
        try {
            byte[] decoded = unzipBase64(base64GzipNbt);
            byte[] sanitized = stripPrivateEnderChestMemories(decoded);
            return zipBase64(sanitized);
        } catch (Throwable ignored) {
            return base64GzipNbt;
        }
    }

    public static byte[] stableNbtFingerprintBytes(String base64GzipNbt) {
        if (base64GzipNbt == null || base64GzipNbt.isBlank()) return new byte[0];
        try {
            byte[] raw = unzipBase64(base64GzipNbt);
            NbtCompound root = readMemoryBankNbt(raw);
            if (root == null) return raw;

            // Keep fingerprint stable by removing private ender data and serializing without gzip headers.
            removePrivateEnderChestKeys(root);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DataOutputStream dataOut = new DataOutputStream(out)) {
                NbtIo.writeCompound(root, dataOut);
            }
            return out.toByteArray();
        } catch (Throwable ignored) {
            try {
                return unzipBase64(base64GzipNbt);
            } catch (Throwable ignoredAgain) {
                return base64GzipNbt.getBytes(StandardCharsets.UTF_8);
            }
        }
    }

    public static byte[] stripPrivateEnderChestMemories(byte[] rawNbtBytes) {
        if (rawNbtBytes == null || rawNbtBytes.length == 0) return new byte[0];

        try {
            NbtCompound root = readMemoryBankNbt(rawNbtBytes);
            if (root == null || root.isEmpty()) return rawNbtBytes;

            boolean changed = removePrivateEnderChestKeys(root);
            if (!changed) return rawNbtBytes;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(root, out);
            return out.toByteArray();
        } catch (Throwable ignored) {
            return rawNbtBytes;
        }
    }

    public static byte[] normalizeCompressedMemoryBankNbt(byte[] rawNbtBytes) {
        if (rawNbtBytes == null || rawNbtBytes.length == 0) return new byte[0];

        try {
            NbtCompound root = readMemoryBankNbt(rawNbtBytes);
            if (root == null) return new byte[0];
            if (looksLikeLegacySingleKeyRoot(root)) {
                root = wrapLegacySingleKeyRoot(root);
            }
            if (!isLikelyMemoryBankRoot(root)) return new byte[0];

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(root, out);
            return out.toByteArray();
        } catch (Throwable ignored) {
            return new byte[0];
        }
    }

    public static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    public static int rgb(SettingColor color) {
        return ((color.r & 0xFF) << 16) | ((color.g & 0xFF) << 8) | (color.b & 0xFF);
    }

    public static String zipBase64(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    public static byte[] unzipBase64(String value) throws Exception {
        if (value == null || value.isBlank()) return new byte[0];
        byte[] compressed = Base64.getDecoder().decode(value);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return gzip.readAllBytes();
        }
    }

    public static NbtCompound readMemoryBankNbt(byte[] rawNbtBytes) throws Exception {
        try (ByteArrayInputStream in = new ByteArrayInputStream(rawNbtBytes)) {
            return NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
        } catch (Throwable compressedReadFailed) {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(rawNbtBytes))) {
                return NbtIo.readCompound(in, NbtSizeTracker.ofUnlimitedBytes());
            }
        }
    }

    private static boolean looksLikeLegacySingleKeyRoot(NbtCompound root) {
        if (root == null || root.isEmpty()) return false;
        boolean hasMemories = root.contains("memories");
        boolean hasOverrides = root.contains("overrides");
        if (!hasMemories && !hasOverrides) return false;

        for (String key : root.getKeys()) {
            if (isResourceLikeKey(key)) return false;
        }
        return true;
    }

    private static boolean isLikelyMemoryBankRoot(NbtCompound root) {
        if (root == null) return false;
        if (root.isEmpty()) return true;
        boolean hasResourceLikeTopKey = false;

        for (String key : root.getKeys()) {
            if ("memories".equals(key) || "overrides".equals(key)) return false;
            if (isResourceLikeKey(key)) hasResourceLikeTopKey = true;
        }
        return hasResourceLikeTopKey;
    }

    private static NbtCompound wrapLegacySingleKeyRoot(NbtCompound legacyRoot) {
        NbtCompound wrapped = new NbtCompound();
        wrapped.put(inferLegacySingleMemoryKeyId(legacyRoot), legacyRoot.copy());
        return wrapped;
    }

    private static String inferLegacySingleMemoryKeyId(NbtCompound legacyRoot) {
        final String fallback = "minecraft:chest";
        if (legacyRoot == null || !legacyRoot.contains("memories")) return fallback;

        Optional<NbtCompound> memoriesOpt = legacyRoot.getCompound("memories");
        if (memoriesOpt.isEmpty()) return fallback;
        NbtCompound memories = memoriesOpt.get();
        if (memories.isEmpty()) return fallback;

        java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
        for (String posKey : memories.getKeys()) {
            NbtElement element = memories.get(posKey);
            if (!(element instanceof NbtCompound memory)) continue;
            Optional<String> containerOpt = memory.getString("container");
            if (containerOpt.isEmpty()) continue;
            String container = containerOpt.get();
            if (!isResourceLikeKey(container)) continue;
            counts.put(container, counts.getOrDefault(container, 0) + 1);
        }

        String best = fallback;
        int bestCount = -1;
        for (java.util.Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    private static boolean isResourceLikeKey(String value) {
        if (value == null || value.isBlank()) return false;
        int colon = value.indexOf(':');
        if (colon <= 0 || colon >= value.length() - 1) return false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) return false;
        }
        return true;
    }

    public static boolean removePrivateEnderChestKeys(NbtCompound compound) {
        boolean changed = false;

        ArrayList<String> keysToRemove = new ArrayList<>();
        for (String key : compound.getKeys()) {
            if (isPrivateEnderChestMemoryKey(key)) keysToRemove.add(key);
        }

        for (String key : keysToRemove) {
            compound.remove(key);
            changed = true;
        }

        for (String key : new ArrayList<>(compound.getKeys())) {
            NbtElement child = compound.get(key);
            if (child instanceof NbtCompound childCompound) {
                if (removePrivateEnderChestKeys(childCompound)) changed = true;
            } else if (child instanceof NbtList childList) {
                if (removePrivateEnderChestKeys(childList)) changed = true;
            }
        }

        return changed;
    }

    private static boolean removePrivateEnderChestKeys(NbtList list) {
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            NbtElement child = list.get(i);
            if (child instanceof NbtCompound childCompound) {
                if (removePrivateEnderChestKeys(childCompound)) changed = true;
            } else if (child instanceof NbtList childList) {
                if (removePrivateEnderChestKeys(childList)) changed = true;
            }
        }
        return changed;
    }

    private static boolean isPrivateEnderChestMemoryKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return false;
        String key = rawKey.trim().toLowerCase(Locale.ROOT);
        return key.equals("ender_chest")
            || key.endsWith(ENDER_CHEST_SUFFIX)
            || key.endsWith(SKYBLOCK_ENDER_CHEST_SUFFIX)
            || key.startsWith(SHARE_ENDER_CHEST_NAMESPACE);
    }
}



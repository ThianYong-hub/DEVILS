package com.devils.addon.modules.chesttracker;

import com.devils.addon.modules.chesttracker.ChestTrackerSupport.Row;
import com.devils.addon.modules.chesttracker.ChestTrackerSupport.Snapshot;
import com.devils.addon.shared.sync.SyncJsonUtils;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKeys;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ChestTrackerSnapshotStore {
    private final String memoryAccessClass;
    private final String stringsClass;
    private final String snapshotSchema;
    private final long remoteApplySkewMs;

    public ChestTrackerSnapshotStore(String memoryAccessClass, String stringsClass, String snapshotSchema, long remoteApplySkewMs) {
        this.memoryAccessClass = memoryAccessClass;
        this.stringsClass = stringsClass;
        this.snapshotSchema = snapshotSchema;
        this.remoteApplySkewMs = remoteApplySkewMs;
    }

    public Path storageDir() {
        return FabricLoader.getInstance().getGameDir().resolve("devils-addon").resolve("chesttracker");
    }

    public Snapshot readLocalSnapshot(String bankId, String serverKey) {
        try {
            Path nbt = storageDir().resolve(bankId + ".nbt");
            if (!Files.isRegularFile(nbt)) return null;

            byte[] nbtBytes = Files.readAllBytes(nbt);
            byte[] syncSafeNbtBytes = ChestTrackerNbtUtils.stripPrivateEnderChestMemories(nbtBytes);
            long updated = ChestTrackerSupport.lastModified(nbt);
            return new Snapshot(bankId, serverKey, updated, ChestTrackerNbtUtils.zipBase64(syncSafeNbtBytes), "");
        } catch (Throwable ignored) {
            return null;
        }
    }

    public boolean writeSnapshot(Snapshot snapshot) {
        if (snapshot == null) return false;
        try {
            byte[] nbtBytes = ChestTrackerNbtUtils.unzipBase64(snapshot.nbt());
            if (nbtBytes.length == 0) return false;
            byte[] normalized = ChestTrackerNbtUtils.normalizeCompressedMemoryBankNbt(nbtBytes);
            if (normalized.length == 0) return false;
            ChestTrackerSupport.writeAtomically(storageDir().resolve(snapshot.bankId() + ".nbt"), normalized);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public Snapshot selectRemoteSnapshot(List<Row> rows, String bankId, String serverKey) {
        if (rows == null || rows.isEmpty()) return null;
        Snapshot newest = null;

        for (Row row : rows) {
            if (!row.enabled() || !normalizeServer(row.server()).equals(normalizeServer(serverKey))) continue;
            JsonObject payload = SyncJsonUtils.parseJsonObject(row.payload());
            if (payload == null) continue;
            if (!snapshotSchema.equals(SyncJsonUtils.readString(payload, "schema", ""))) continue;

            Snapshot snapshot = new Snapshot(
                bankId,
                serverKey,
                Math.max(0, SyncJsonUtils.readLong(payload, "updatedAt", 0)),
                ChestTrackerNbtUtils.sanitizeSnapshotNbt(SyncJsonUtils.readString(payload, "nbt", "")),
                SyncJsonUtils.readString(payload, "meta", "")
            );
            if (snapshot.nbt().isBlank()) continue;
            if (newest == null || snapshot.updatedAt() >= newest.updatedAt()) newest = snapshot;
        }

        return newest;
    }

    public List<Row> toRows(Snapshot snapshot) {
        if (snapshot == null) return List.of();

        JsonObject payload = new JsonObject();
        payload.addProperty("schema", snapshotSchema);
        payload.addProperty("bankId", snapshot.bankId());
        payload.addProperty("server", snapshot.serverKey());
        payload.addProperty("updatedAt", snapshot.updatedAt());
        payload.addProperty("nbt", snapshot.nbt());
        if (!snapshot.meta().isBlank()) payload.addProperty("meta", snapshot.meta());

        return List.of(new Row(true, "bank:" + snapshot.bankId(), snapshot.serverKey(), payload.toString(), 0));
    }

    public String fingerprint(Snapshot snapshot) {
        if (snapshot == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(snapshot.bankId().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(snapshot.serverKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(ChestTrackerNbtUtils.stableNbtFingerprintBytes(snapshot.nbt()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Throwable ignored) {
            return Integer.toHexString(snapshot.hashCode());
        }
    }

    public boolean shouldApplyRemote(Snapshot local, Snapshot remote, boolean localHasData, boolean remoteHasData) {
        if (remote == null || !remoteHasData) return false;
        String localFingerprint = fingerprint(local);
        String remoteFingerprint = fingerprint(remote);
        if (localFingerprint.equals(remoteFingerprint)) return false;
        if (local == null || !localHasData) return true;
        return remote.updatedAt() <= 0
            || local.updatedAt() <= 0
            || remote.updatedAt() + remoteApplySkewMs >= local.updatedAt();
    }

    public boolean snapshotHasSyncData(Snapshot snapshot) {
        if (snapshot == null || snapshot.nbt() == null || snapshot.nbt().isBlank()) return false;
        try {
            byte[] raw = ChestTrackerNbtUtils.unzipBase64(snapshot.nbt());
            if (raw.length == 0) return false;
            NbtCompound root = ChestTrackerNbtUtils.readMemoryBankNbt(raw);
            if (root == null || root.isEmpty()) return false;
            ChestTrackerNbtUtils.removePrivateEnderChestKeys(root);
            return hasNestedNbtData(root);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public String normalizeServer(String value) {
        if (value == null) return "";
        String out = value.trim().toLowerCase(Locale.ROOT);
        while (out.endsWith(".")) out = out.substring(0, out.length() - 1);
        if (out.isBlank()) return "";

        if (out.startsWith("[")) {
            int end = out.indexOf(']');
            if (end > 0 && out.length() > end + 2 && out.charAt(end + 1) == ':') {
                String port = out.substring(end + 2).trim();
                if ("25565".equals(port)) out = out.substring(0, end + 1);
            }
            return out;
        }

        int firstColon = out.indexOf(':');
        int lastColon = out.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon && lastColon + 1 < out.length()) {
            String port = out.substring(lastColon + 1).trim();
            boolean numericPort = !port.isEmpty() && port.chars().allMatch(Character::isDigit);
            if (numericPort && "25565".equals(port)) out = out.substring(0, lastColon);
        }
        return out;
    }

    public String namespaceKey(String value) {
        String out = normalizeServer(value).replaceAll("[^a-z0-9._-]+", "_");
        return out.isBlank() ? "unknown" : out;
    }

    public String resolveSyncBankId(String normalizedServer) {
        String loaded = getLoadedBankId();
        if (!loaded.isBlank()) return loaded;

        String canonical = bankIdForServer(normalizedServer);
        if (bankFilesExist(canonical)) return canonical;

        String portVariant = portVariantBankIdForServer(normalizedServer);
        if (!portVariant.isBlank() && bankFilesExist(portVariant)) return portVariant;

        String legacy = legacyBankIdForServer(normalizedServer);
        if (bankFilesExist(legacy)) return legacy;
        return "";
    }

    public void flushLoadedBankToDisk() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.player == null) return;

        try {
            if (client.world.getRegistryManager().getOptional(RegistryKeys.ENCHANTMENT).isEmpty()) return;
        } catch (Throwable ignored) {
            return;
        }

        try {
            Class<?> accessClass = Class.forName(memoryAccessClass);
            Object instance = accessClass.getField("INSTANCE").get(null);
            accessClass.getMethod("save").invoke(instance);
        } catch (Throwable ignored) {
        }
    }

    public void reloadLoadedBankFromDisk(String bankId) {
        try {
            Class<?> accessClass = Class.forName(memoryAccessClass);
            Object instance = accessClass.getField("INSTANCE").get(null);
            Object optional = accessClass.getMethod("getLoadedInternal").invoke(instance);
            if (!(optional instanceof Optional<?> loadedOpt) || loadedOpt.isEmpty()) return;

            Object loaded = loadedOpt.get();
            Object loadedId = loaded.getClass().getMethod("getId").invoke(loaded);
            if (!(loadedId instanceof String id) || !bankId.equals(id)) return;

            Field loadedField = accessClass.getDeclaredField("loaded");
            loadedField.setAccessible(true);
            loadedField.set(null, null);
            accessClass.getMethod("loadOrCreate", String.class, String.class).invoke(instance, bankId, bankId);
        } catch (Throwable ignored) {
        }
    }

    private boolean hasNestedNbtData(NbtElement element) {
        if (element == null) return false;
        if (element instanceof NbtCompound compound) {
            for (String key : compound.getKeys()) {
                if (hasNestedNbtData(compound.get(key))) return true;
            }
            return false;
        }
        if (element instanceof NbtList list) {
            for (int i = 0; i < list.size(); i++) {
                if (hasNestedNbtData(list.get(i))) return true;
            }
            return false;
        }
        return true;
    }

    private String bankIdForServer(String server) {
        return "multiplayer/" + sanitizeForPath(namespaceKey(server));
    }

    private String legacyBankIdForServer(String server) {
        return "server-" + sanitizeForPath(namespaceKey(server));
    }

    private String portVariantBankIdForServer(String server) {
        if (server == null || server.isBlank()) return "";

        String base = server.trim().toLowerCase(Locale.ROOT);
        while (base.endsWith(".")) base = base.substring(0, base.length() - 1);
        if (base.isBlank()) return "";

        String withPort;
        if (base.startsWith("[")) {
            if (!base.endsWith("]")) return "";
            withPort = base + ":25565";
        } else if (base.indexOf(':') >= 0) {
            return "";
        } else {
            withPort = base + ":25565";
        }

        return "multiplayer/" + sanitizeForPath(withPort.replaceAll("[^a-z0-9._-]+", "_"));
    }

    private String getLoadedBankId() {
        try {
            Class<?> accessClass = Class.forName(memoryAccessClass);
            Object instance = accessClass.getField("INSTANCE").get(null);
            Object optional = accessClass.getMethod("getLoadedInternal").invoke(instance);
            if (!(optional instanceof Optional<?> loadedOpt) || loadedOpt.isEmpty()) return "";
            Object loaded = loadedOpt.get();
            Object idObj = loaded.getClass().getMethod("getId").invoke(loaded);
            return idObj instanceof String id ? id : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean bankFilesExist(String id) {
        if (id == null || id.isBlank()) return false;
        Path root = storageDir();
        return Files.isRegularFile(root.resolve(id + ".nbt"))
            || Files.isRegularFile(root.resolve(id + ".nbt.meta"))
            || Files.isRegularFile(root.resolve(id + ".json"))
            || Files.isRegularFile(root.resolve(id + ".json.meta"));
    }

    private String sanitizeForPath(String source) {
        try {
            Class<?> stringsClassObj = Class.forName(stringsClass);
            Method sanitize = stringsClassObj.getMethod("sanitizeForPath", String.class);
            Object sanitized = sanitize.invoke(null, source);
            if (sanitized instanceof String value && !value.isBlank()) return value;
        } catch (Throwable ignored) {
        }
        return source;
    }
}



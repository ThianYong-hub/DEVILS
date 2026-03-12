package com.example.addon.chesttracker.impl.memory;

import org.jetbrains.annotations.Nullable;
import com.example.addon.chesttracker.api.memory.MemoryBank;
import com.example.addon.chesttracker.api.memory.MemoryBankAccess;
import com.example.addon.chesttracker.impl.memory.metadata.Metadata;
import com.example.addon.chesttracker.impl.storage.ConnectionSettings;
import com.example.addon.chesttracker.impl.storage.Storage;
import com.example.addon.chesttracker.impl.util.Constants;
import com.example.addon.chesttracker.impl.util.Strings;
import red.jackf.jackfredlib.client.api.gps.Coordinate;

import java.util.HashMap;
import java.util.Locale;
import java.util.Optional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class MemoryBankAccessImpl implements MemoryBankAccess {
    public static final MemoryBankAccessImpl INSTANCE = new MemoryBankAccessImpl();
    @Nullable
    private static MemoryBankImpl loaded = null;

    private MemoryBankAccessImpl() {}

    // API

    @Override
    public boolean loadOrCreate(String memoryBankId, String creationName) {
        INSTANCE.unload();
        loaded = Storage.load(memoryBankId).orElseGet(() -> {
            var bank = new MemoryBankImpl(Metadata.blankWithName(creationName), new HashMap<>());
            bank.setId(memoryBankId);
            return bank;
        });
        INSTANCE.save();

        return true;
    }

    public boolean unload() {
        if (loaded == null) return false;
        save();
        loaded = null;
        return true;
    }

    @Override
    public Optional<MemoryBank> getLoaded() {
        return Optional.ofNullable(loaded);
    }

    public Optional<MemoryBankImpl> getLoadedInternal() {
        return Optional.ofNullable(loaded);
    }

    public void save() {
        if (loaded == null) return;
        Storage.save(loaded);
    }

    // Internal

    // Load from a coordinate's ID, checking the override file if necessary.
    public boolean loadWithDefaults(Coordinate coordinate) {
        var settings = ConnectionSettings.getOrCreate(coordinate.id());
        var defaultId = getDefaultMemoryBankId(coordinate);
        var id = settings.memoryBankIdOverride().orElse(defaultId);

        // Backward compatibility with previous Devils builds that used "server-*" root files.
        if (settings.memoryBankIdOverride().isEmpty()) {
            var legacyId = getLegacyDefaultMemoryBankId(coordinate);
            if (!Storage.exists(defaultId) && Storage.exists(legacyId)) {
                migrateLegacyBankFiles(legacyId, defaultId);
                id = Storage.exists(defaultId) ? defaultId : legacyId;
            }
        }

        return loadOrCreate(id, coordinate.userFriendlyName());
    }

    public static String getDefaultMemoryBankId(Coordinate coordinate) {
        if (coordinate instanceof Coordinate.Multiplayer multi) {
            String address = multi.address() == null ? "" : multi.address().trim().toLowerCase(Locale.ROOT);
            while (address.endsWith(".")) address = address.substring(0, address.length() - 1);
            if (address.isBlank()) address = "unknown";
            return "multiplayer/" + Strings.sanitizeForPath(address);
        }

        String world = coordinate == null ? "" : coordinate.id();
        world = world == null ? "" : world.trim().toLowerCase(Locale.ROOT);
        while (world.endsWith(".")) world = world.substring(0, world.length() - 1);
        if (world.isBlank()) world = "unknown";
        return "singleplayer/" + Strings.sanitizeForPath(world);
    }

    private static String getLegacyDefaultMemoryBankId(Coordinate coordinate) {
        String base = coordinate == null ? "" : coordinate.id();
        if (coordinate instanceof Coordinate.Multiplayer multi && multi.address() != null) {
            String address = multi.address().trim();
            if (!address.isEmpty()) base = address;
        }

        base = base == null ? "" : base.trim().toLowerCase(Locale.ROOT);
        while (base.endsWith(".")) base = base.substring(0, base.length() - 1);
        if (base.isEmpty()) base = "unknown";
        return "server-" + Strings.sanitizeForPath(base);
    }

    private static void migrateLegacyBankFiles(String fromId, String toId) {
        if (fromId == null || toId == null || fromId.isBlank() || toId.isBlank()) return;
        if (fromId.equals(toId)) return;

        String[] suffixes = {".nbt", ".nbt.meta", ".nbt.old", ".json", ".json.meta"};
        for (String suffix : suffixes) {
            Path from = Constants.STORAGE_DIR.resolve(fromId + suffix);
            Path to = Constants.STORAGE_DIR.resolve(toId + suffix);

            try {
                if (!Files.isRegularFile(from) || Files.isRegularFile(to)) continue;
                if (to.getParent() != null) Files.createDirectories(to.getParent());
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
            }
        }
    }
}

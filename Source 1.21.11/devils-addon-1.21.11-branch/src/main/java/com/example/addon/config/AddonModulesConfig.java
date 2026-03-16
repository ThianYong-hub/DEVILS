package com.example.addon.config;

import com.example.addon.AddonTemplate;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.PostInit;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringNbtReader;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.EVENT_BUS;

public final class AddonModulesConfig {
    private static final AddonModulesConfig INSTANCE = new AddonModulesConfig();
    private static final Object IO_LOCK = new Object();

    private static final String ROOT_MODULES_KEY = "modules";
    private static final String MODULE_NAME_KEY = "name";
    private static final String MODULE_NBT_KEY = "nbt";
    private static final String JSON_FORMAT = "devils-modules-json-v1";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final String ADDON_PACKAGE = AddonTemplate.class.getPackageName();
    private static final Path CONFIG_DIR = FabricLoader.getInstance()
        .getGameDir()
        .resolve("devils-addon");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("modules.json");
    private static final List<Path> LEGACY_JSON_FILES = List.of(
        FabricLoader.getInstance().getConfigDir().resolve("devils-addon").resolve("modules.json")
    );
    private static final List<Path> LEGACY_NBT_FILES = List.of(
        FabricLoader.getInstance().getConfigDir().resolve("devils-addon").resolve("modules.nbt"),
        FabricLoader.getInstance().getGameDir().resolve("devils-addon").resolve("modules.nbt")
    );

    private static boolean initialized;

    private AddonModulesConfig() {
    }

    public static void init() {
        if (initialized) return;
        initialized = true;

        ensureConfigDirectory();
        EVENT_BUS.subscribe(INSTANCE);
        Runtime.getRuntime().addShutdownHook(new Thread(AddonModulesConfig::saveNow, "devils-addon-config-save"));
    }

    public static void saveNow() {
        if (!initialized) return;

        NbtCompound tag = INSTANCE.collectModulesTag();
        synchronized (IO_LOCK) {
            writeTag(CONFIG_FILE, tag);
        }
    }

    @PostInit
    public static void postInitLoad() {
        if (!initialized) return;
        INSTANCE.loadAndApplyNow();
        if (!Files.exists(CONFIG_FILE)) saveNow();
    }

    @EventHandler
    private void onGameLeft(meteordevelopment.meteorclient.events.game.GameLeftEvent event) {
        saveNow();
    }

    private void loadAndApplyNow() {
        NbtCompound tag;
        synchronized (IO_LOCK) {
            tag = readTagWithMigration();
        }
        if (tag == null) return;

        Map<String, NbtCompound> tagsByName = new HashMap<>();
        NbtList modulesTag = tag.getListOrEmpty(ROOT_MODULES_KEY);
        for (NbtElement moduleTagI : modulesTag) {
            if (!(moduleTagI instanceof NbtCompound moduleTag)) continue;
            String moduleName = moduleTag.getString(MODULE_NAME_KEY, "");
            if (moduleName.isBlank()) continue;
            tagsByName.put(moduleName, moduleTag);
        }

        int applied = 0;
        for (Module module : Modules.get().getAll()) {
            if (!isAddonModule(module)) continue;
            NbtCompound moduleTag = tagsByName.get(module.name);
            if (moduleTag == null) continue;
            module.fromTag(moduleTag);
            applied++;
        }

        if (applied > 0) {
            AddonTemplate.LOG.info("[Devils] Loaded {} module configs from {}.", applied, CONFIG_FILE);
        }
    }

    private NbtCompound collectModulesTag() {
        NbtCompound root = new NbtCompound();
        NbtList modulesTag = new NbtList();

        int saved = 0;
        for (Module module : Modules.get().getAll()) {
            if (!isAddonModule(module)) continue;
            NbtCompound moduleTag = module.toTag();
            if (moduleTag == null) continue;
            modulesTag.add(moduleTag);
            saved++;
        }

        root.put(ROOT_MODULES_KEY, modulesTag);
        root.putInt("savedCount", saved);
        root.putLong("savedAt", System.currentTimeMillis());
        return root;
    }

    private static boolean isAddonModule(Module module) {
        if (module == null || module.addon == null) return false;
        String modulePackage = module.addon.getPackage();
        return modulePackage != null
            && (modulePackage.equals(ADDON_PACKAGE) || modulePackage.startsWith(ADDON_PACKAGE + "."));
    }

    private static void ensureConfigDirectory() {
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException e) {
            AddonTemplate.LOG.error("[Devils] Failed to create config directory {}.", CONFIG_DIR, e);
        }
    }

    private static NbtCompound readTagWithMigration() {
        NbtCompound jsonTag = readJsonTag(CONFIG_FILE);
        if (jsonTag != null) return jsonTag;

        for (Path legacyJson : LEGACY_JSON_FILES) {
            NbtCompound migrated = readJsonTag(legacyJson);
            if (migrated == null) continue;
            AddonTemplate.LOG.info("[Devils] Migrating legacy addon config {} -> {}.", legacyJson, CONFIG_FILE);
            writeJsonTag(CONFIG_FILE, migrated);
            return migrated;
        }

        for (Path legacyNbt : LEGACY_NBT_FILES) {
            NbtCompound migrated = readLegacyTag(legacyNbt);
            if (migrated == null) continue;
            AddonTemplate.LOG.info("[Devils] Migrating legacy addon config {} -> {}.", legacyNbt, CONFIG_FILE);
            writeJsonTag(CONFIG_FILE, migrated);
            return migrated;
        }

        return null;
    }

    private static NbtCompound readLegacyTag(Path path) {
        if (!Files.exists(path)) return null;

        try {
            return NbtIo.read(path);
        } catch (Exception e) {
            AddonTemplate.LOG.error("[Devils] Failed to read addon module config from {}.", path, e);
            return null;
        }
    }

    private static NbtCompound readJsonTag(Path path) {
        if (!Files.exists(path)) return null;

        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            if (raw.isBlank()) return null;

            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) return null;
            JsonObject rootJson = parsed.getAsJsonObject();

            NbtCompound rootTag = new NbtCompound();
            NbtList modulesTag = new NbtList();

            JsonArray modules = rootJson.has(ROOT_MODULES_KEY) && rootJson.get(ROOT_MODULES_KEY).isJsonArray()
                ? rootJson.getAsJsonArray(ROOT_MODULES_KEY)
                : new JsonArray();

            for (JsonElement moduleElement : modules) {
                if (!moduleElement.isJsonObject()) continue;
                JsonObject moduleJson = moduleElement.getAsJsonObject();
                if (!moduleJson.has(MODULE_NBT_KEY)) continue;
                JsonElement nbtValue = moduleJson.get(MODULE_NBT_KEY);
                if (nbtValue == null || nbtValue.isJsonNull()) continue;

                try {
                    NbtCompound moduleTag;
                    if (nbtValue.isJsonPrimitive()) {
                        String moduleNbt = nbtValue.getAsString();
                        if (moduleNbt == null || moduleNbt.isBlank()) continue;
                        moduleTag = StringNbtReader.readCompound(moduleNbt);
                    } else {
                        NbtElement converted = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, nbtValue);
                        if (!(converted instanceof NbtCompound convertedCompound)) continue;
                        moduleTag = convertedCompound;
                    }
                    modulesTag.add(moduleTag);
                } catch (Exception moduleParseError) {
                    AddonTemplate.LOG.warn("[Devils] Skipping malformed module entry in {}.", path, moduleParseError);
                }
            }

            rootTag.put(ROOT_MODULES_KEY, modulesTag);
            rootTag.putInt("savedCount", modulesTag.size());
            return rootTag;
        } catch (Exception e) {
            AddonTemplate.LOG.error("[Devils] Failed to read addon module config from {}.", path, e);
            return null;
        }
    }

    private static void writeJsonTag(Path path, NbtCompound tag) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);

            JsonObject root = new JsonObject();
            root.addProperty("format", JSON_FORMAT);
            root.addProperty("savedAt", System.currentTimeMillis());

            NbtList modulesTag = tag.getListOrEmpty(ROOT_MODULES_KEY);
            root.addProperty("savedCount", modulesTag.size());

            JsonArray modules = new JsonArray();
            for (NbtElement moduleTagI : modulesTag) {
                if (!(moduleTagI instanceof NbtCompound moduleTag)) continue;

                JsonObject moduleJson = new JsonObject();
                moduleJson.addProperty(MODULE_NAME_KEY, moduleTag.getString(MODULE_NAME_KEY, ""));
                moduleJson.addProperty("active", moduleTag.getBoolean("active", false));
                JsonElement moduleNbtJson = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, moduleTag);
                moduleJson.add(MODULE_NBT_KEY, moduleNbtJson);
                modules.add(moduleJson);
            }
            root.add(ROOT_MODULES_KEY, modules);

            Path temp = Files.createTempFile(parent, "devils-modules-", ".tmp");
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);

            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            AddonTemplate.LOG.error("[Devils] Failed to write addon module config to {}.", path, e);
        }
    }

    private static void writeTag(Path path, NbtCompound tag) {
        writeJsonTag(path, tag);
    }
}



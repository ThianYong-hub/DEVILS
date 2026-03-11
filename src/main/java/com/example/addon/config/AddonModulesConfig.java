package com.example.addon.config;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.PostInit;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.EVENT_BUS;

public final class AddonModulesConfig {
    private static final AddonModulesConfig INSTANCE = new AddonModulesConfig();
    private static final Object IO_LOCK = new Object();

    private static final String ROOT_MODULES_KEY = "modules";
    private static final String MODULE_NAME_KEY = "name";

    private static final String ADDON_PACKAGE = AddonTemplate.class.getPackageName();
    private static final Path CONFIG_FILE = FabricLoader.getInstance()
        .getConfigDir()
        .resolve("devils-addon")
        .resolve("modules.nbt");

    private static boolean initialized;

    private AddonModulesConfig() {
    }

    public static void init() {
        if (initialized) return;
        initialized = true;

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
    }

    @EventHandler
    private void onGameLeft(meteordevelopment.meteorclient.events.game.GameLeftEvent event) {
        saveNow();
    }

    private void loadAndApplyNow() {
        NbtCompound tag;
        synchronized (IO_LOCK) {
            tag = readTag(CONFIG_FILE);
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

    private static NbtCompound readTag(Path path) {
        if (!Files.exists(path)) return null;

        try {
            return NbtIo.read(path);
        } catch (Exception e) {
            AddonTemplate.LOG.error("[Devils] Failed to read addon module config from {}.", path, e);
            return null;
        }
    }

    private static void writeTag(Path path, NbtCompound tag) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);

            Path temp = Files.createTempFile(parent, "devils-modules-", ".tmp");
            NbtIo.write(tag, temp);

            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            AddonTemplate.LOG.error("[Devils] Failed to write addon module config to {}.", path, e);
        }
    }
}

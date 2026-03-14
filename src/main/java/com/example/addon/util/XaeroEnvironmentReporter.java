package com.example.addon.util;

import com.example.addon.AddonTemplate;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class XaeroEnvironmentReporter {
    private static final Map<String, String> EXPECTED_VERSIONS = new LinkedHashMap<>();

    static {
        EXPECTED_VERSIONS.put("xaerominimap", "25.3.10");
        EXPECTED_VERSIONS.put("xaeroworldmap", "1.40.11");
        EXPECTED_VERSIONS.put("xaeroplus", "2.30.9");
    }

    private XaeroEnvironmentReporter() {
    }

    public static void logXaeroState() {
        logSingle("xaerominimap", "Xaero Minimap");
        logSingle("xaeroworldmap", "Xaero WorldMap");
        logSingle("xaeroplus", "XaeroPlus");
    }

    private static void logSingle(String modId, String display) {
        Optional<ModContainer> optional = FabricLoader.getInstance().getModContainer(modId);
        if (optional.isEmpty()) {
            AddonTemplate.LOG.warn("[Devils/Xaero] {} is not loaded.", display);
            return;
        }

        ModContainer container = optional.get();
        String version = container.getMetadata().getVersion().getFriendlyString();
        String origins = stringifyOrigins(container);
        boolean embedded = isEmbeddedMod(container) || isEmbeddedOrigin(origins);
        String expected = EXPECTED_VERSIONS.getOrDefault(modId, "");

        AddonTemplate.LOG.info(
            "[Devils/Xaero] {} loaded: version={} source={} origin={}",
            display,
            version,
            embedded ? "embedded" : "external",
            origins
        );

        if (!expected.isBlank() && !version.startsWith(expected)) {
            AddonTemplate.LOG.warn(
                "[Devils/Xaero] {} version mismatch. Loaded={}, expected={} for Devils sync bridge.",
                display,
                version,
                expected
            );
        }

        if (!embedded) {
            AddonTemplate.LOG.warn(
                "[Devils/Xaero] External {} detected. Fabric resolves root mods before nested jars; remove standalone {} jar to force embedded Devils stack.",
                display,
                display
            );
        }
    }

    private static String stringifyOrigins(ModContainer container) {
        try {
            List<Path> paths = container.getOrigin().getPaths();
            if (paths == null || paths.isEmpty()) return "unknown";
            return paths.stream()
                .map(path -> path == null ? "" : path.toAbsolutePath().normalize().toString())
                .collect(Collectors.joining(";"));
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static boolean isEmbeddedOrigin(String origins) {
        String value = origins == null ? "" : origins.toLowerCase(Locale.ROOT);
        return value.contains("meta-inf/jars")
            || value.contains("meta-inf\\jars");
    }

    private static boolean isEmbeddedMod(ModContainer container) {
        if (container == null) return false;
        try {
            Optional<ModContainer> parent = container.getContainingMod();
            if (parent.isEmpty()) return false;
            String parentId = parent.get().getMetadata().getId();
            return "devils-addon".equalsIgnoreCase(parentId);
        } catch (Throwable ignored) {
            return false;
        }
    }
}

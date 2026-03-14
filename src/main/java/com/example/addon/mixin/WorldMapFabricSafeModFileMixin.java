package com.example.addon.mixin;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.List;

@Pseudo
@Mixin(targets = "xaero.map.WorldMapFabric", remap = false)
public abstract class WorldMapFabricSafeModFileMixin {
    private static final String XAERO_WORLDMAP_ID = "xaeroworldmap";
    private static final String DEVILS_ADDON_ID = "devils-addon";
    private static final String FALLBACK_XAERO_WORLDMAP_VERSION = "1.40.11";

    @Inject(method = "fetchModFile", at = @At("HEAD"), cancellable = true, require = 0)
    private void devilsAddon$fetchModFileSafe(CallbackInfoReturnable<Path> cir) {
        Path resolved = resolveModOriginPath(XAERO_WORLDMAP_ID);
        if (resolved == null) resolved = resolveModOriginPath(DEVILS_ADDON_ID);
        if (resolved == null) resolved = resolveCodeSourcePath();
        if (resolved == null) resolved = FabricLoader.getInstance().getGameDir().resolve("mods").resolve("xaeroworldmap-embedded.jar");
        cir.setReturnValue(resolved);
    }

    @Inject(method = "getModInfoVersion", at = @At("HEAD"), cancellable = true, require = 0)
    private void devilsAddon$getModInfoVersionSafe(CallbackInfoReturnable<String> cir) {
        try {
            ModContainer modContainer = FabricLoader.getInstance().getModContainer(XAERO_WORLDMAP_ID).orElse(null);
            if (modContainer != null) {
                String version = modContainer.getMetadata().getVersion().getFriendlyString();
                if (version != null && !version.isBlank()) {
                    cir.setReturnValue(version + "_fabric");
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        cir.setReturnValue(FALLBACK_XAERO_WORLDMAP_VERSION + "_fabric");
    }

    private static Path resolveModOriginPath(String modId) {
        try {
            ModContainer modContainer = FabricLoader.getInstance().getModContainer(modId).orElse(null);
            if (modContainer == null) return null;

            ModOrigin origin = modContainer.getOrigin();
            if (origin == null) return null;

            List<Path> paths = origin.getPaths();
            if (paths == null || paths.isEmpty()) return null;

            for (Path path : paths) {
                if (path != null && path.getFileName() != null) return path;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Path resolveCodeSourcePath() {
        try {
            CodeSource source = WorldMapFabricSafeModFileMixin.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            URI uri = source.getLocation().toURI();
            if (!"file".equalsIgnoreCase(uri.getScheme())) return null;
            Path path = Paths.get(uri).toAbsolutePath().normalize();
            return path.getFileName() == null ? null : path;
        } catch (Throwable ignored) {
            return null;
        }
    }
}

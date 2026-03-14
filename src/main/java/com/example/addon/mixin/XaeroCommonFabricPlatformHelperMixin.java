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
import java.util.Optional;

@Pseudo
@Mixin(targets = "xaero.common.platform.services.FabricPlatformHelper", remap = false)
public abstract class XaeroCommonFabricPlatformHelperMixin {
    private static final String DEVILS_ADDON_ID = "devils-addon";

    @Inject(method = "getModFile", at = @At("RETURN"), cancellable = true, require = 0)
    private void devilsAddon$getModFileSafe(String modId, CallbackInfoReturnable<Path> cir) {
        Path current = cir.getReturnValue();
        if (isUsablePath(current)) return;

        Path resolved = resolveModOriginPath(modId);
        if (!isUsablePath(resolved)) resolved = resolveModOriginPath(DEVILS_ADDON_ID);
        if (!isUsablePath(resolved)) resolved = resolveCodeSourcePath();
        if (!isUsablePath(resolved)) {
            resolved = FabricLoader.getInstance().getGameDir().resolve("mods").resolve(modId + "-embedded.jar");
        }
        cir.setReturnValue(resolved);
    }

    private static boolean isUsablePath(Path path) {
        return path != null && path.getFileName() != null;
    }

    private static Path resolveModOriginPath(String modId) {
        try {
            Optional<ModContainer> optional = FabricLoader.getInstance().getModContainer(modId);
            if (optional.isEmpty()) return null;

            ModOrigin origin = optional.get().getOrigin();
            if (origin == null) return null;

            List<Path> paths = origin.getPaths();
            if (paths == null || paths.isEmpty()) return null;

            for (Path path : paths) {
                if (isUsablePath(path)) return path;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Path resolveCodeSourcePath() {
        try {
            CodeSource source = XaeroCommonFabricPlatformHelperMixin.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            URI uri = source.getLocation().toURI();
            if (!"file".equalsIgnoreCase(uri.getScheme())) return null;
            Path path = Paths.get(uri).toAbsolutePath().normalize();
            return isUsablePath(path) ? path : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}

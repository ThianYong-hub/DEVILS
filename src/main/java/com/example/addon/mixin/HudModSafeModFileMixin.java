package com.example.addon.mixin;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.List;

@Pseudo
@Mixin(targets = "xaero.common.HudMod", remap = false)
public abstract class HudModSafeModFileMixin {
    private static final String DEVILS_ADDON_ID = "devils-addon";

    @Redirect(
        method = "loadClient",
        at = @At(
            value = "INVOKE",
            target = "Lxaero/common/platform/services/IPlatformHelper;getModFile(Ljava/lang/String;)Ljava/nio/file/Path;"
        ),
        require = 0
    )
    private Path devilsAddon$safeGetModFile(@Coerce Object platformHelper, String modId) {
        Path modFile = null;

        if (platformHelper != null) {
            try {
                Object value = platformHelper.getClass().getMethod("getModFile", String.class).invoke(platformHelper, modId);
                if (value instanceof Path path && path.getFileName() != null) modFile = path;
            } catch (Throwable ignored) {
            }
        }

        if (modFile == null) modFile = resolveModOriginPath(modId);
        if (modFile == null) modFile = resolveModOriginPath(DEVILS_ADDON_ID);
        if (modFile == null) modFile = resolveCodeSourcePath();
        if (modFile == null) modFile = FabricLoader.getInstance().getGameDir().resolve("mods").resolve(modId + "-embedded.jar");
        return modFile;
    }

    private static Path resolveModOriginPath(String modId) {
        try {
            ModContainer container = FabricLoader.getInstance().getModContainer(modId).orElse(null);
            if (container == null) return null;

            ModOrigin origin = container.getOrigin();
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
            CodeSource source = HudModSafeModFileMixin.class.getProtectionDomain().getCodeSource();
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

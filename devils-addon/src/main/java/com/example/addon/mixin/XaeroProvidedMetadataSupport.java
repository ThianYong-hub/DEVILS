package com.example.addon.mixin;

import java.util.Map;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "xaeroplus.fabric.util.compat.XaeroPlusMinimapCompatibilityChecker", remap = false)
abstract class XaeroPlusCompatibilityMetadataMixin {
    private static final Map<String, String> DEVILS_PROVIDED_VERSIONS = Map.of(
        "xaerominimap", "25.3.10",
        "xaerobetterpvp", "25.3.10"
    );

    @Inject(method = "getVersion", at = @At("HEAD"), cancellable = true, require = 0)
    private static void devilsAddon$resolveProvidedVersion(String modId, CallbackInfoReturnable<Optional<Version>> cir) {
        String expectedVersion = DEVILS_PROVIDED_VERSIONS.get(modId);
        if (expectedVersion == null) return;

        ModContainer container = FabricLoader.getInstance().getModContainer(modId).orElse(null);
        if (container == null) return;
        if (!"devils-addon".equals(container.getMetadata().getId())) return;

        try {
            cir.setReturnValue(Optional.of(Version.parse(expectedVersion)));
        } catch (Throwable ignored) {
            cir.setReturnValue(Optional.empty());
        }
    }
}

@Pseudo
@Mixin(targets = "xaero.common.PlatformContextFabric", remap = false)
abstract class XaeroPlatformProvidedVersionMixin {
    private static final Map<String, String> DEVILS_PROVIDED_VERSIONS = Map.of(
        "xaerominimap", "25.3.10",
        "xaerobetterpvp", "25.3.10",
        "xaeroworldmap", "1.40.11"
    );

    @Inject(method = "getModInfoVersion", at = @At("HEAD"), cancellable = true, require = 0)
    private void devilsAddon$resolveProvidedVersionString(CallbackInfoReturnable<String> cir) {
        try {
            Object modMain = findFieldValue(this, "modMain");
            if (modMain == null) return;

            Object modIdValue = modMain.getClass().getMethod("getModId").invoke(modMain);
            if (!(modIdValue instanceof String modId)) return;

            String expectedVersion = DEVILS_PROVIDED_VERSIONS.get(modId);
            if (expectedVersion == null) return;

            ModContainer container = FabricLoader.getInstance().getModContainer(modId).orElse(null);
            if (container == null) return;
            if (!"devils-addon".equals(container.getMetadata().getId())) return;

            cir.setReturnValue(expectedVersion + "_fabric");
        } catch (Throwable ignored) {
        }
    }

    private static Object findFieldValue(Object instance, String fieldName) {
        Class<?> type = instance.getClass();
        while (type != null) {
            try {
                var field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(instance);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }
}

@Pseudo
@Mixin(targets = "xaeroplus.fabric.util.compat.XaeroPlusMinimapCompatibilityChecker", remap = false)
abstract class XaeroPlusVersionCheckResultMixin {
    @Inject(method = "versionCheck", at = @At("HEAD"), cancellable = true, require = 0)
    private static void devilsAddon$resolveProvidedVersionCheckResult(CallbackInfoReturnable<Object> cir) {
        ModContainer container = FabricLoader.getInstance().getModContainer("xaerominimap").orElse(null);
        if (container == null) return;
        if (!"devils-addon".equals(container.getMetadata().getId())) return;

        try {
            Version expected = Version.parse("25.3.10");
            Class<?> resultClass = Class.forName("xaeroplus.fabric.util.compat.VersionCheckResult");
            Object versionCheckResult = resultClass
                .getDeclaredConstructor(Optional.class, Optional.class, Version.class)
                .newInstance(Optional.of(expected), Optional.empty(), expected);
            cir.setReturnValue(versionCheckResult);
        } catch (Throwable ignored) {
        }
    }
}

@Pseudo
@Mixin(targets = "xaeroplus.fabric.XaeroPlusFabric", remap = false)
abstract class XaeroPlusInitializeCompatibilityMixin {
    @Inject(method = "initialize", at = @At("HEAD"), require = 0)
    private static void devilsAddon$forceCompatibleProvidedMinimap(CallbackInfo ci) {
        ModContainer container = FabricLoader.getInstance().getModContainer("xaerominimap").orElse(null);
        if (container == null) return;
        if (!"devils-addon".equals(container.getMetadata().getId())) return;

        try {
            Version expected = Version.parse("25.3.10");
            Class<?> resultClass = Class.forName("xaeroplus.fabric.util.compat.VersionCheckResult");
            Object versionCheckResult = resultClass
                .getDeclaredConstructor(Optional.class, Optional.class, Version.class)
                .newInstance(Optional.of(expected), Optional.empty(), expected);
            Class<?> checkerClass = Class.forName("xaeroplus.fabric.util.compat.XaeroPlusMinimapCompatibilityChecker");
            var field = checkerClass.getDeclaredField("versionCheckResult");
            field.setAccessible(true);
            field.set(null, versionCheckResult);
        } catch (Throwable ignored) {
        }
    }
}

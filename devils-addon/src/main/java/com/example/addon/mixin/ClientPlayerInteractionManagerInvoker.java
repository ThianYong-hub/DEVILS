package com.example.addon.mixin;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.XaeroSync;
import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.screens.settings.ItemListSettingScreen;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Names;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.RunArgs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.ClientConnection;
import net.minecraft.client.network.SequencedPacketCreator;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Mixin(ClientPlayerInteractionManager.class)
public interface ClientPlayerInteractionManagerInvoker {
    @Accessor("currentBreakingProgress")
    float devilsAddon$getCurrentBreakingProgress();

    @Accessor("currentBreakingProgress")
    void devilsAddon$setCurrentBreakingProgress(float progress);

    @Accessor("currentBreakingPos")
    BlockPos devilsAddon$getCurrentBreakingPos();

    @Invoker("isCurrentlyBreaking")
    boolean devilsAddon$isCurrentlyBreaking(BlockPos pos);

    @Invoker("sendSequencedPacket")
    void devilsAddon$sendSequencedPacket(ClientWorld world, SequencedPacketCreator packetCreator);
}

/**
 * Fix Meteor's ClientConnectionMixin NPE on disconnect.
 * Meteor's mixin does: Modules.get().get(HighwayBuilder.class).isActive()
 * If their HighwayBuilder module isn't registered, .get() returns null → NPE crash.
 * We inject before Meteor (priority 500 < 1000) and register their module if missing.
 * Our addon's module is named "highway-tools" so this won't conflict.
 */
@Mixin(value = ClientConnection.class, priority = 500)
abstract class ClientConnectionMixin {
    @Inject(method = "disconnect(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void fixHighwayBuilderNPE(Text reason, CallbackInfo ci) {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Module> meteorHW = (Class<? extends Module>)
                Class.forName("meteordevelopment.meteorclient.systems.modules.world.HighwayBuilder");
            if (Modules.get() != null && Modules.get().get(meteorHW) == null) {
                Modules.get().add(meteorHW.getDeclaredConstructor().newInstance());
            }
        } catch (Exception ignored) {}
    }
}

/**
 * Example Mixin class.
 * For more resources, visit:
 * <ul>
 * <li><a href="https://fabricmc.net/wiki/tutorial:mixin_introduction">The FabricMC wiki</a></li>
 * <li><a href="https://github.com/SpongePowered/Mixin/wiki">The Mixin wiki</a></li>
 * <li><a href="https://github.com/LlamaLad7/MixinExtras/wiki">The MixinExtras wiki</a></li>
 * <li><a href="https://jenkins.liteloader.com/view/Other/job/Mixin/javadoc/allclasses-noframe.html">The Mixin javadoc</a></li>
 * <li><a href="https://github.com/2xsaiko/mixin-cheatsheet">The Mixin cheatsheet</a></li>
 * </ul>
 */
@Mixin(MinecraftClient.class)
abstract class ExampleMixin {
    /**
     * Example Mixin injection targeting the {@code <init>} method (the constructor) at {@code TAIL} (end of method).
     */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void onGameLoaded(RunArgs args, CallbackInfo ci) {
        AddonTemplate.LOG.info("Hello from ExampleMixin!");
    }
}

@Pseudo
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
abstract class GuiMapXaeroSyncMixin {
    @Inject(method = "render", at = @At("TAIL"), require = 0, remap = false)
    private void devilsAddon$xaeroSyncGuiMapRenderHook(DrawContext drawContext, int mouseX, int mouseY, float tickDelta, CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        double cameraX = readDouble(self, "cameraX");
        double cameraZ = readDouble(self, "cameraZ");
        double scale = readDouble(self, "scale");
        XaeroSync.onXaeroMapRenderProjectedHook(self, drawContext, mouseX, mouseY, tickDelta, cameraX, cameraZ, scale);
    }

    private static double readDouble(Object owner, String fieldName) {
        if (owner == null || fieldName == null || fieldName.isBlank()) return Double.NaN;
        Class<?> cursor = owner.getClass();
        while (cursor != null) {
            try {
                Field field = cursor.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(owner);
                if (value instanceof Number number) return number.doubleValue();
                return Double.NaN;
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            } catch (Throwable ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }
}

@Mixin(ItemListSettingScreen.class)
abstract class ItemListSettingScreenMixin {
    @Inject(method = "getValueWidget", at = @At("HEAD"), cancellable = true)
    private void paradise$renderItemIcon(Item value, CallbackInfoReturnable<WWidget> cir) {
        cir.setReturnValue(GuiThemes.get().itemWithLabel(new ItemStack(value), Names.get(value)));
    }
}

@Mixin(value = Modules.class, priority = 2000, remap = false)
abstract class ModulesNameCollisionMixin {
    private static final String DEVILS_PACKAGE_PREFIX = "com.example.addon.";

    @Inject(method = "add", at = @At("HEAD"), cancellable = true, remap = false)
    private void keepDevilsModuleOnNameCollision(Module incoming, CallbackInfo ci) {
        if (incoming == null || incoming.name == null || incoming.name.isBlank()) return;

        Modules modules = Modules.get();
        if (modules == null) return;

        Module existing = modules.get(incoming.name);
        if (existing == null || existing == incoming) return;

        if (isDevilsModule(existing) && !isDevilsModule(incoming)) {
            // Our module with the same name is already registered, keep it.
            ci.cancel();
        }
    }

    private static boolean isDevilsModule(Module module) {
        return module != null && module.getClass().getName().startsWith(DEVILS_PACKAGE_PREFIX);
    }
}

@Pseudo
@Mixin(targets = "xaero.map.radar.tracker.PlayerTrackerMapElementRenderer", remap = false)
abstract class PlayerTrackerPingNameMixin {
    @Redirect(
        method = "renderElement",
        at = @At(value = "INVOKE", target = "Lcom/mojang/authlib/GameProfile;getName()Ljava/lang/String;"),
        require = 0
    )
    private String devilsAddon$renderPingName(GameProfile profile) {
        if (profile == null) return "";
        return XaeroSync.resolveTrackedPingDisplayName(profile.id(), profile.name());
    }
}

@Mixin(Screen.class)
abstract class ScreenXaeroSyncMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;IIF)V", at = @At("TAIL"), require = 0)
    private void devilsAddon$xaeroSyncRenderHook(DrawContext drawContext, int mouseX, int mouseY, float tickDelta, CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        if ("xaero.map.gui.GuiMap".equals(self.getClass().getName())) return;
        XaeroSync.onXaeroMapRenderHook(self, drawContext, mouseX, mouseY, tickDelta);
    }
}



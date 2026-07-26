package com.devils.addon.mixin;

import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.screens.settings.ItemListSettingScreen;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Names;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
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
import org.spongepowered.asm.mixin.Mixin;

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

@Mixin(ItemListSettingScreen.class)
abstract class ItemListSettingScreenMixin {
    @Inject(method = "getValueWidget", at = @At("HEAD"), cancellable = true)
    private void paradise$renderItemIcon(Item value, CallbackInfoReturnable<WWidget> cir) {
        cir.setReturnValue(GuiThemes.get().itemWithLabel(new ItemStack(value), Names.get(value)));
    }
}

@Mixin(value = Modules.class, priority = 2000, remap = false)
abstract class ModulesNameCollisionMixin {
    private static final String DEVILS_PACKAGE_PREFIX = "com.devils.addon.";

    @Inject(method = "add", at = @At("HEAD"), cancellable = true, remap = false)
    private void keepDevilsModuleOnNameCollision(Module incoming, CallbackInfo ci) {
        if (incoming == null || incoming.name == null || incoming.name.isBlank()) return;

        Modules modules = Modules.get();
        if (modules == null) return;

        Module existing = modules.get(incoming.name);
        if (existing == null || existing == incoming) return;

        if (isDevilsModule(existing) && !isDevilsModule(incoming)) {
            // Our module with the same name is already registered, keep it.
            if (ci.isCancellable()) ci.cancel();
        }
    }

    private static boolean isDevilsModule(Module module) {
        return module != null && module.getClass().getName().startsWith(DEVILS_PACKAGE_PREFIX);
    }
}

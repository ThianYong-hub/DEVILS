package com.example.addon.mixin;

import com.example.addon.AddonTemplate;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.screens.ModulesScreen;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import meteordevelopment.meteorclient.renderer.Texture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

@Mixin(ModulesScreen.class)
public abstract class ModulesScreenIconsMixin {
    @Unique
    private static Texture devilsIcon;

    @Unique
    private static boolean loadFailed = false;

    /**
     * Wraps the c.add(w) call inside createCategory.
     * c.add(w) triggers WWindow.init() which consumes beforeHeaderInit,
     * so we MUST replace it BEFORE the add() call, not after RETURN.
     */
    @WrapOperation(
        method = "createCategory",
        at = @At(value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/gui/widgets/containers/WContainer;add(Lmeteordevelopment/meteorclient/gui/widgets/WWidget;)Lmeteordevelopment/meteorclient/gui/utils/Cell;"),
        remap = false
    )
    private Cell<?> devils$beforeWindowInit(WContainer instance, WWidget widget, Operation<Cell<?>> original) {
        if (widget instanceof WWindow ww && "Devils".equals(ww.id)) {
            Texture icon = devils$getIcon();
            if (icon != null) {
                ww.beforeHeaderInit = header -> header.add(GuiThemes.get().texture(24, 24, 0, icon)).pad(2);
            }
        }

        return original.call(instance, widget);
    }

    @Unique
    private static Texture devils$getIcon() {
        if (devilsIcon != null) return devilsIcon;
        if (loadFailed) return null;

        try {
            Identifier id = Identifier.of("devils-addon", "category_icon.png");
            var resource = MinecraftClient.getInstance().getResourceManager().getResource(id);

            if (resource.isEmpty()) {
                AddonTemplate.LOG.error("[Devils] category_icon.png not found in resources");
                loadFailed = true;
                return null;
            }

            byte[] bytes;
            try (InputStream in = resource.get().getInputStream()) {
                bytes = in.readAllBytes();
            }

            ByteBuffer rawBuffer = BufferUtils.createByteBuffer(bytes.length);
            rawBuffer.put(bytes);
            rawBuffer.flip();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                IntBuffer ch = stack.mallocInt(1);

                ByteBuffer pixels = STBImage.stbi_load_from_memory(rawBuffer, w, h, ch, 4);
                if (pixels == null) {
                    AddonTemplate.LOG.error("[Devils] STBImage failed: {}", STBImage.stbi_failure_reason());
                    loadFailed = true;
                    return null;
                }

                devilsIcon = new Texture(w.get(0), h.get(0), TextureFormat.RGBA8, FilterMode.LINEAR, FilterMode.LINEAR);
                devilsIcon.upload(pixels);

                STBImage.stbi_image_free(pixels);
            }

            AddonTemplate.LOG.info("[Devils] Category icon loaded ({}x{})", devilsIcon.getWidth(), devilsIcon.getHeight());
        } catch (Exception e) {
            AddonTemplate.LOG.error("[Devils] Failed to load category icon", e);
            loadFailed = true;
        }

        return devilsIcon;
    }
}

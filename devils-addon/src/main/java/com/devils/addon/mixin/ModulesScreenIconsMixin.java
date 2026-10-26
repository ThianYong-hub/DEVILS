package com.devils.addon.mixin;

import com.devils.addon.DevilsAddon;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Restores the custom category icons in the modules screen window headers.
 * Without it the headers fall back to the placeholder items the categories
 * were registered with (nether star for Devils, diamond for Devils-Game).
 */
@Mixin(ModulesScreen.class)
public abstract class ModulesScreenIconsMixin {
    /** Category window id -> icon resource of the mod that owns the category. */
    @Unique
    private static final Map<String, Identifier> DEVILS_CATEGORY_ICONS = Map.of(
        "Devils", Identifier.of("devils-addon", "category_icon.png"),
        "Devils-Game", Identifier.of("devils-game", "icon.png")
    );

    @Unique
    private static final Map<String, Texture> devils$icons = new ConcurrentHashMap<>();

    @Unique
    private static boolean devils$loadFailed;

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
        if (widget instanceof WWindow ww) {
            Texture icon = devils$getIcon(ww.id);
            if (icon != null) {
                ww.beforeHeaderInit = header -> header.add(GuiThemes.get().texture(32, 32, 0, icon)).pad(2);
            }
        }

        return original.call(instance, widget);
    }

    @Unique
    private static Texture devils$getIcon(String categoryId) {
        if (categoryId == null) return null;
        Texture cached = devils$icons.get(categoryId);
        if (cached != null) return cached;
        if (devils$loadFailed) return null;

        Identifier id = DEVILS_CATEGORY_ICONS.get(categoryId);
        if (id == null) return null;

        try {
            var resource = MinecraftClient.getInstance().getResourceManager().getResource(id);
            if (resource.isEmpty()) {
                // Devils-Game icon is optional: the addon runs fine without the game jar.
                DevilsAddon.LOG.debug("[Devils] Category icon {} not found in resources", id);
                devils$loadFailed = categoryId.equals("Devils");
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
                    DevilsAddon.LOG.error("[Devils] STBImage failed for {}: {}", id, STBImage.stbi_failure_reason());
                    devils$loadFailed = true;
                    return null;
                }

                Texture texture = new Texture(w.get(0), h.get(0), TextureFormat.RGBA8, FilterMode.LINEAR, FilterMode.LINEAR);
                texture.upload(pixels);
                STBImage.stbi_image_free(pixels);
                devils$icons.put(categoryId, texture);

                DevilsAddon.LOG.info("[Devils] Category icon for '{}' loaded ({}x{})", categoryId, texture.getWidth(), texture.getHeight());
                return texture;
            }
        } catch (Exception e) {
            DevilsAddon.LOG.error("[Devils] Failed to load category icon for '{}'", categoryId, e);
            devils$loadFailed = true;
            return null;
        }
    }
}

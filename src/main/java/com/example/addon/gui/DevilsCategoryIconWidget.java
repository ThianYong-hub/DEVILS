package com.example.addon.gui;

import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.renderer.Texture;
import net.minecraft.util.Identifier;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class DevilsCategoryIconWidget extends WWidget {
    private static final Identifier ICON_ID = Identifier.of("devils-addon", "icon.png");

    private static Texture iconTexture;
    private static boolean loadAttempted;

    public static void addToHeader(WContainer container) {
        container.add(new DevilsCategoryIconWidget()).pad(2);
    }

    private static void loadTexture() {
        if (iconTexture != null) return;
        if (loadAttempted) return;
        if (mc == null) return;

        loadAttempted = true;

        try {
            var resource = mc.getResourceManager().getResource(ICON_ID);
            if (resource.isEmpty()) return;

            try (InputStream in = resource.get().getInputStream(); MemoryStack stack = MemoryStack.stackPush()) {
                byte[] bytes = in.readAllBytes();
                ByteBuffer raw = BufferUtils.createByteBuffer(bytes.length);
                raw.put(bytes);
                raw.rewind();

                IntBuffer width = stack.mallocInt(1);
                IntBuffer height = stack.mallocInt(1);
                IntBuffer channels = stack.mallocInt(1);

                ByteBuffer iconBuffer = STBImage.stbi_load_from_memory(raw, width, height, channels, 4);
                if (iconBuffer == null) return;

                Texture texture = new Texture(width.get(0), height.get(0), TextureFormat.RGBA8, FilterMode.LINEAR, FilterMode.LINEAR);
                texture.upload(iconBuffer);
                STBImage.stbi_image_free(iconBuffer);

                iconTexture = texture;
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onCalculateSize() {
        width = theme.scale(20);
        height = theme.scale(20);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        loadTexture();
        if (iconTexture != null) renderer.texture(x, y, width, height, 0.0, iconTexture);
    }
}

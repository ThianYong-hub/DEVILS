/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import net.minecraft.class_1011;
import net.minecraft.class_1044;

public class Texture extends class_1044 {
    public Texture(int width, int height, TextureFormat format, FilterMode min, FilterMode mag) {
        field_56974 = RenderSystem.getDevice().createTexture("", 15, format, width, height, 1, 1);
        field_56974.setTextureFilter(min, mag, false);

        field_60597 = RenderSystem.getDevice().createTextureView(field_56974);
    }

    public int getWidth() {
        return method_68004().getWidth(0);
    }

    public int getHeight() {
        return method_68004().getHeight(0);
    }

    public void upload(byte[] bytes) {
        upload(BufferUtils.createByteBuffer(bytes.length).put(bytes));
    }

    public void upload(ByteBuffer buffer) {
        var image = getImage();

        buffer.rewind();
        MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), image.method_67769(), buffer.remaining());

        RenderSystem.getDevice().createCommandEncoder().writeToTexture(field_56974, image);

        image.close();
    }

    private @NotNull class_1011 getImage() {
        class_1011.class_1012 imageFormat = switch (field_56974.getFormat()) {
            case RGBA8 -> class_1011.class_1012.field_4997;
            case RED8 -> class_1011.class_1012.field_4998;
            default -> throw new IllegalArgumentException();
        };

        // Workaround for writeToTexture(IntBuffer) overload comparing width * height to the size of the int buffer.
        // And since we are working with pixels which are only one byte in size, the sizes don't match
        return new class_1011(imageFormat, getWidth(), getHeight(), false);
    }
}

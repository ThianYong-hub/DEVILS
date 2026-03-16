package com.example.addon.util;

import com.example.addon.AddonTemplate;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class MapIconManager {
    public static final String ICONS_FOLDER = "devils-addon/icons";
    public static final String DEFAULT_EMBEDDED_ICON_PATH = "assets/devils-addon/textures/gui/devils_map_icon.png";
    private static final int TARGET_ICON_TEXTURE_SIZE = 128;
    private static final int ALPHA_CROP_THRESHOLD = 12;

    private static final String DYNAMIC_TEXTURE_PREFIX = "dynamic/devils-map-icon/";
    private static final Map<String, CachedIcon> CACHE = new HashMap<>();

    private MapIconManager() {
    }

    public static Path ensureIconsDirectory() {
        Path path = FabricLoader.getInstance().getGameDir().resolve(ICONS_FOLDER);
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            AddonTemplate.LOG.warn("[Devils] Failed to create icons directory '{}': {}", path, e.toString());
        }
        return path;
    }

    public static String normalizeIconPath(String raw) {
        if (raw == null) return "";
        return raw.trim().replace('\\', '/');
    }

    public static boolean drawCustomIcon(DrawContext drawContext, String iconPath, int x, int y, int size, int color) {
        CachedIcon icon = resolveIcon(iconPath);
        if (icon == null) return false;

        drawContext.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            icon.id(),
            x,
            y,
            icon.u(),
            icon.v(),
            size,
            size,
            icon.regionWidth(),
            icon.regionHeight(),
            icon.width(),
            icon.height(),
            color
        );
        return true;
    }

    public static IconSprite resolveIconSprite(String iconPath) {
        CachedIcon icon = resolveIcon(iconPath);
        if (icon == null) return null;
        return new IconSprite(
            icon.id(),
            icon.u(),
            icon.v(),
            icon.regionWidth(),
            icon.regionHeight(),
            icon.width(),
            icon.height()
        );
    }

    private static CachedIcon resolveIcon(String iconPath) {
        String normalized = normalizeIconPath(iconPath);
        if (normalized.isBlank()) return null;

        String classpathResourcePath = resolveClasspathResourcePath(normalized);
        if (classpathResourcePath != null) {
            String extension = extension(classpathResourcePath);
            if (!extension.equals("png") && !extension.equals("jpg") && !extension.equals("jpeg")) return null;

            String cacheKey = "classpath:" + classpathResourcePath.toLowerCase(Locale.ROOT);
            synchronized (CACHE) {
                CachedIcon cached = CACHE.get(cacheKey);
                if (cached != null) return cached;

                CachedIcon loaded = loadAndRegisterClasspath(cacheKey, classpathResourcePath);
                if (loaded == null) return null;

                CachedIcon previous = CACHE.put(cacheKey, loaded);
                close(previous);
                return loaded;
            }
        }

        Path path = resolveIconPath(normalized);
        if (path == null || !Files.isRegularFile(path)) return null;

        String extension = extension(path.getFileName().toString());
        if (!extension.equals("png") && !extension.equals("jpg") && !extension.equals("jpeg")) return null;

        long lastModified;
        long size;
        try {
            lastModified = Files.getLastModifiedTime(path).toMillis();
            size = Files.size(path);
        } catch (Exception e) {
            return null;
        }

        String cacheKey = path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        synchronized (CACHE) {
            CachedIcon cached = CACHE.get(cacheKey);
            if (cached != null && cached.lastModifiedMs() == lastModified && cached.sizeBytes() == size) {
                return cached;
            }

            CachedIcon loaded = loadAndRegister(cacheKey, path, lastModified, size);
            if (loaded == null) return null;

            CachedIcon previous = CACHE.put(cacheKey, loaded);
            close(previous);
            return loaded;
        }
    }

    private static CachedIcon loadAndRegister(String cacheKey, Path path, long lastModified, long size) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return null;

        try (InputStream input = Files.newInputStream(path)) {
            NativeImage image = NativeImage.read(input);
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return null;
            Crop crop = computeOpaqueCrop(image);
            NativeImage prepared = prepareIconImage(image, crop, TARGET_ICON_TEXTURE_SIZE);
            image.close();
            if (prepared == null || prepared.getWidth() <= 0 || prepared.getHeight() <= 0) return null;
            Crop preparedCrop = computeOpaqueCrop(prepared);

            Identifier id = Identifier.of("devils-addon", DYNAMIC_TEXTURE_PREFIX + Integer.toHexString(cacheKey.hashCode()));
            NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "devils-map-icon:" + cacheKey, prepared);
            TextureManager textureManager = mc.getTextureManager();
            textureManager.destroyTexture(id);
            textureManager.registerTexture(id, texture);
            return new CachedIcon(
                id,
                texture,
                prepared.getWidth(),
                prepared.getHeight(),
                preparedCrop.u(),
                preparedCrop.v(),
                preparedCrop.regionWidth(),
                preparedCrop.regionHeight(),
                lastModified,
                size
            );
        } catch (Throwable t) {
            AddonTemplate.LOG.warn("[Devils] Failed to load map icon '{}': {}", path, t.toString());
            return null;
        }
    }

    private static CachedIcon loadAndRegisterClasspath(String cacheKey, String classpathResourcePath) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return null;

        try (InputStream input = MapIconManager.class.getClassLoader().getResourceAsStream(classpathResourcePath)) {
            if (input == null) return null;
            NativeImage image = NativeImage.read(input);
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return null;
            Crop crop = computeOpaqueCrop(image);
            NativeImage prepared = prepareIconImage(image, crop, TARGET_ICON_TEXTURE_SIZE);
            image.close();
            if (prepared == null || prepared.getWidth() <= 0 || prepared.getHeight() <= 0) return null;
            Crop preparedCrop = computeOpaqueCrop(prepared);

            Identifier id = Identifier.of("devils-addon", DYNAMIC_TEXTURE_PREFIX + Integer.toHexString(cacheKey.hashCode()));
            NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "devils-map-icon:" + cacheKey, prepared);
            TextureManager textureManager = mc.getTextureManager();
            textureManager.destroyTexture(id);
            textureManager.registerTexture(id, texture);
            return new CachedIcon(
                id,
                texture,
                prepared.getWidth(),
                prepared.getHeight(),
                preparedCrop.u(),
                preparedCrop.v(),
                preparedCrop.regionWidth(),
                preparedCrop.regionHeight(),
                0L,
                0L
            );
        } catch (Throwable t) {
            AddonTemplate.LOG.warn("[Devils] Failed to load embedded map icon '{}': {}", classpathResourcePath, t.toString());
            return null;
        }
    }

    private static Crop computeOpaqueCrop(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getColorArgb(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha < ALPHA_CROP_THRESHOLD) continue;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            }
        }

        if (maxX < minX || maxY < minY) return new Crop(0, 0, width, height);
        return new Crop(minX, minY, Math.max(1, maxX - minX + 1), Math.max(1, maxY - minY + 1));
    }

    private static NativeImage prepareIconImage(NativeImage image, Crop crop, int targetSize) {
        if (image == null) return null;
        int srcWidth = image.getWidth();
        int srcHeight = image.getHeight();
        if (srcWidth <= 0 || srcHeight <= 0) return null;

        int cropU = clamp(crop.u(), 0, srcWidth - 1);
        int cropV = clamp(crop.v(), 0, srcHeight - 1);
        int cropW = Math.max(1, Math.min(crop.regionWidth(), srcWidth - cropU));
        int cropH = Math.max(1, Math.min(crop.regionHeight(), srcHeight - cropV));
        int side = Math.max(cropW, cropH);

        int srcX = cropU - (side - cropW) / 2;
        int srcY = cropV - (side - cropH) / 2;
        if (srcX < 0) srcX = 0;
        if (srcY < 0) srcY = 0;
        if (srcX + side > srcWidth) srcX = Math.max(0, srcWidth - side);
        if (srcY + side > srcHeight) srcY = Math.max(0, srcHeight - side);

        NativeImage normalized = new NativeImage(targetSize, targetSize, false);
        for (int y = 0; y < targetSize; y++) {
            int sampleY = srcY + (y * side) / targetSize;
            sampleY = clamp(sampleY, 0, srcHeight - 1);
            for (int x = 0; x < targetSize; x++) {
                int sampleX = srcX + (x * side) / targetSize;
                sampleX = clamp(sampleX, 0, srcWidth - 1);
                int argb = image.getColorArgb(sampleX, sampleY);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha < ALPHA_CROP_THRESHOLD) {
                    normalized.setColorArgb(x, y, 0);
                } else {
                    normalized.setColorArgb(x, y, argb);
                }
            }
        }
        return normalized;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        return Math.min(value, max);
    }

    private static Path resolveIconPath(String normalized) {
        Path root = ensureIconsDirectory().toAbsolutePath().normalize();
        Path candidate;
        try {
            candidate = Path.of(normalized);
        } catch (Exception ignored) {
            return null;
        }

        boolean absolute = candidate.isAbsolute();
        if (!absolute) candidate = root.resolve(candidate);
        candidate = candidate.normalize();

        // Keep relative icon paths inside the managed icons folder.
        if (!absolute && !candidate.startsWith(root)) return null;
        return candidate;
    }

    private static String resolveClasspathResourcePath(String normalized) {
        if (normalized == null || normalized.isBlank()) return null;
        String value = normalized.startsWith("/") ? normalized.substring(1) : normalized;
        if (value.isBlank()) return null;

        if (value.startsWith("assets/")) return value;

        int separator = value.indexOf(':');
        if (separator > 0 && separator < value.length() - 1) {
            // Ignore Windows absolute paths like C:/...
            if (!(separator == 1 && Character.isLetter(value.charAt(0)) && value.length() > 2 && value.charAt(2) == '/')) {
                String namespace = value.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                String path = value.substring(separator + 1).trim();
                if (!namespace.isBlank() && !path.isBlank()) {
                    if (path.startsWith("/")) path = path.substring(1);
                    return "assets/" + namespace + "/" + path;
                }
            }
        }

        return null;
    }

    private static String extension(String fileName) {
        if (fileName == null) return "";
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) return "";
        return fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private static void close(CachedIcon icon) {
        if (icon == null) return;
        try {
            icon.texture().close();
        } catch (Throwable ignored) {
        }
    }

    private record Crop(int u, int v, int regionWidth, int regionHeight) {}

    public record IconSprite(
        Identifier id,
        int u,
        int v,
        int regionWidth,
        int regionHeight,
        int textureWidth,
        int textureHeight
    ) {}

    private record CachedIcon(
        Identifier id,
        NativeImageBackedTexture texture,
        int width,
        int height,
        int u,
        int v,
        int regionWidth,
        int regionHeight,
        long lastModifiedMs,
        long sizeBytes
    ) {}
}



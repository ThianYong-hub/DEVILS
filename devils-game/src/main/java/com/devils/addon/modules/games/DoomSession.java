package com.devils.addon.modules.games;

import com.devils.addon.games.DevilsGameAddon;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
final class DoomSession {
    private static final String BUNDLED_BASE = "assets/devils-game/games/doom/runtime/";
    private static final String BUNDLED_ENGINE = "devilsdoom-runtime.jar";
    private static final String BUNDLED_IWAD_PRIMARY = "freedoom2.wad";
    private static final String BUNDLED_IWAD_SECONDARY = "freedoom1.wad";
    private static final String EMBED_PROPERTY = "devilsdoom.embed";

    private final AtomicReference<Image> pendingFrame = new AtomicReference<>();
    private final Map<Integer, Object> scanCodes = new HashMap<>();
    private volatile boolean starting;
    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile boolean inputFocused;
    private volatile String statusText = "DevilsDoom idle.";
    private volatile String logText = "Waiting for startup.";

    private URLClassLoader runtimeLoader;
    private Class<?> engineClass;
    private Object doomMain;
    private Method postEventMethod;
    private Constructor<?> keyEventCtor;
    private Constructor<?> mouseEventCtor;
    private Object evKeyDown;
    private Object evKeyUp;
    private Object evMouse;
    private Thread runtimeThread;
    private int mouseButtonsMask;

    private NativeImage frameImage;
    private NativeImageBackedTexture frameTexture;
    private Identifier frameTextureId;
    private int frameWidth;
    private int frameHeight;
    private int textureNonce;
    void startIfNeeded() {
        if (running || starting) return;
        starting = true;
        stopRequested = false;

        try {
            Path runtimeDir = resolveRuntimeDir();
            Files.createDirectories(runtimeDir);
            Path engineJar = extractResource(runtimeDir, BUNDLED_ENGINE);
            Path iwad = resolveIwad(runtimeDir);
            if (!Files.isRegularFile(engineJar)) {
                statusText = "DevilsDoom engine not found in resources.";
                logText = "Missing runtime jar: " + BUNDLED_ENGINE;
                starting = false;
                return;
            }
            if (!Files.isRegularFile(iwad)) {
                statusText = "DevilsDoom IWAD not found.";
                logText = "Missing bundled Freedoom files.";
                starting = false;
                return;
            }

            System.setProperty(EMBED_PROPERTY, "true");
            runtimeLoader = new URLClassLoader(new URL[] { engineJar.toUri().toURL() }, getClass().getClassLoader());
            engineClass = Class.forName("devilsdoom.Engine", true, runtimeLoader);
            Method clearStop = engineClass.getMethod("clearStopRequest");
            clearStop.invoke(null);
            Method setTitle = engineClass.getMethod("setEmbeddedWindowTitle", String.class);
            setTitle.invoke(null, "DEVILSDOOM");
            Method setConsumer = engineClass.getMethod("setEmbeddedFrameConsumer", Consumer.class);
            setConsumer.invoke(null, (Consumer<Image>) pendingFrame::set);

            Constructor<?> engineCtor = engineClass.getDeclaredConstructor(String[].class);
            engineCtor.setAccessible(true);
            String[] args = new String[] { "-iwad", iwad.toString(), "-window" };
            engineCtor.newInstance((Object) args);
            Method getDoomMain = engineClass.getMethod("getDoomMain");
            doomMain = getDoomMain.invoke(null);
            if (doomMain == null) {
                statusText = "DevilsDoom init failed.";
                logText = "Runtime created without DoomMain.";
                starting = false;
                return;
            }

            prepareInputBridge();
            Method setupLoop = doomMain.getClass().getMethod("setupLoop");

            runtimeThread = new Thread(() -> runLoop(setupLoop), "devilsdoom-runtime");
            runtimeThread.setDaemon(true);
            runtimeThread.setContextClassLoader(runtimeLoader);
            running = true;
            statusText = "DevilsDoom running.";
            logText = "Embedded runtime started.";
            runtimeThread.start();
        } catch (Throwable t) {
            running = false;
            statusText = "DevilsDoom startup failed.";
            logText = shorten(unwrap(t).toString());
            cleanupRuntimeHandles();
            DevilsGameAddon.LOG.warn("[DevilsDoom] Startup failed", t);
        } finally {
            starting = false;
        }
    }

    void shutdown() {
        stopRequested = true;
        inputFocused = false;
        mouseButtonsMask = 0;
        stopEmbeddedAudio();
        try {
            if (engineClass != null) {
                Method setConsumer = engineClass.getMethod("setEmbeddedFrameConsumer", Consumer.class);
                setConsumer.invoke(null, new Object[] { null });
                Method requestStop = engineClass.getMethod("requestStop");
                requestStop.invoke(null);
            }
        } catch (Throwable ignored) {
        }

        Thread thread = runtimeThread;
        if (thread != null && thread.isAlive()) {
            try {
                thread.join(1800L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                forceEmbeddedExit();
                thread.interrupt();
                try {
                    thread.join(800L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        stopEmbeddedAudio();
        running = false;
        starting = false;
        statusText = "DevilsDoom stopped.";
        logText = "Runtime stopped.";
        releaseTexture(MinecraftClient.getInstance());
        cleanupRuntimeHandles();
    }
    void restart() {
        shutdown();
        startIfNeeded();
    }
    boolean handleKey(int glfwKey, boolean pressed) {
        if (!running || doomMain == null || postEventMethod == null) return false;
        if (glfwKey == GLFW.GLFW_KEY_ESCAPE) return false;
        Object sc = scanCodes.get(glfwKey);
        if (sc == null) return false;
        try {
            Object event = keyEventCtor.newInstance(pressed ? evKeyDown : evKeyUp, sc);
            postEventMethod.invoke(doomMain, event);
            return true;
        } catch (Throwable t) {
            logText = shorten("Key bridge error: " + unwrap(t));
            return false;
        }
    }

    boolean handleMouseButton(int button, boolean pressed) {
        if (!running || doomMain == null || postEventMethod == null || mouseEventCtor == null) return false;
        int bit = switch (button) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 4;
            default -> 0;
        };
        if (bit == 0) return false;

        if (pressed) mouseButtonsMask |= bit;
        else mouseButtonsMask &= ~bit;

        try {
            Object event = mouseEventCtor.newInstance(evMouse, mouseButtonsMask, 0, 0);
            postEventMethod.invoke(doomMain, event);
            return true;
        } catch (Throwable t) {
            logText = shorten("Mouse bridge error: " + unwrap(t));
            return false;
        }
    }

    void updateFrameTexture(MinecraftClient mc) {
        if (mc == null || mc.getTextureManager() == null) return;
        Image image = pendingFrame.getAndSet(null);
        if (image == null) return;

        BufferedImage frame = asBuffered(image);
        if (frame == null) return;
        int w = frame.getWidth();
        int h = frame.getHeight();
        if (w <= 0 || h <= 0) return;

        ensureTexture(mc, w, h);
        if (frameImage == null || frameTexture == null) return;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                frameImage.setColorArgb(x, y, frame.getRGB(x, y));
            }
        }
        frameTexture.upload();
    }

    Identifier frameTextureId() {
        return frameTextureId;
    }

    int frameWidth() {
        return frameWidth;
    }

    int frameHeight() {
        return frameHeight;
    }

    boolean hasFrame() {
        return frameTextureId != null && frameWidth > 0 && frameHeight > 0;
    }

    boolean isRunning() {
        return running;
    }

    String statusText() {
        return statusText;
    }

    String logText() {
        return logText;
    }

    boolean inputFocused() {
        return inputFocused;
    }

    void setInputFocused(boolean value) {
        inputFocused = value;
        if (!value && mouseButtonsMask != 0) {
            handleMouseButton(0, false);
            handleMouseButton(1, false);
            handleMouseButton(2, false);
            mouseButtonsMask = 0;
        }
    }

    private void runLoop(Method setupLoop) {
        try {
            setupLoop.invoke(doomMain);
            if (stopRequested) {
                statusText = "DevilsDoom stopped.";
                logText = "Runtime exited by request.";
            } else {
                statusText = "DevilsDoom finished.";
                logText = "Runtime loop exited.";
            }
        } catch (InvocationTargetException ite) {
            Throwable cause = unwrap(ite);
            if (cause != null && "devilsdoom.Engine$DoomEmbeddedExit".equals(cause.getClass().getName())) {
                int code = readExitCode(cause);
                statusText = stopRequested ? "DevilsDoom stopped." : "DevilsDoom exited (" + code + ").";
                logText = "Embedded runtime exit code: " + code;
            } else {
                statusText = "DevilsDoom crashed.";
                logText = shorten(cause == null ? ite.toString() : cause.toString());
                DevilsGameAddon.LOG.warn("[DevilsDoom] Runtime crashed", ite);
            }
        } catch (Throwable t) {
            statusText = "DevilsDoom crashed.";
            logText = shorten(unwrap(t).toString());
            DevilsGameAddon.LOG.warn("[DevilsDoom] Runtime crashed", t);
        } finally {
            running = false;
            starting = false;
            if (!stopRequested) setInputFocused(false);
            cleanupRuntimeHandles();
        }
    }

    private void prepareInputBridge() throws Exception {
        Class<?> eventTypeClass = Class.forName("doom.evtype_t", true, runtimeLoader);
        Class<?> scanCodeClass = Class.forName("g.Signals$ScanCode", true, runtimeLoader);
        Class<?> eventInterface = Class.forName("doom.event_t", true, runtimeLoader);
        Class<?> keyEventClass = Class.forName("doom.event_t$keyevent_t", true, runtimeLoader);
        Class<?> mouseEventClass = Class.forName("doom.event_t$mouseevent_t", true, runtimeLoader);

        @SuppressWarnings({ "rawtypes", "unchecked" })
        Object keyDown = Enum.valueOf((Class<Enum>) eventTypeClass, "ev_keydown");
        @SuppressWarnings({ "rawtypes", "unchecked" })
        Object keyUp = Enum.valueOf((Class<Enum>) eventTypeClass, "ev_keyup");
        @SuppressWarnings({ "rawtypes", "unchecked" })
        Object mouse = Enum.valueOf((Class<Enum>) eventTypeClass, "ev_mouse");

        evKeyDown = keyDown;
        evKeyUp = keyUp;
        evMouse = mouse;
        keyEventCtor = keyEventClass.getConstructor(eventTypeClass, scanCodeClass);
        mouseEventCtor = mouseEventClass.getConstructor(eventTypeClass, int.class, int.class, int.class);
        postEventMethod = doomMain.getClass().getMethod("PostEvent", eventInterface);

        scanCodes.clear();
        registerKey(scanCodeClass, GLFW.GLFW_KEY_W, "SC_W");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_A, "SC_A");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_S, "SC_S");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_D, "SC_D");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_UP, "SC_UP");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_DOWN, "SC_DOWN");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_LEFT, "SC_LEFT");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_RIGHT, "SC_RIGHT");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_LEFT_SHIFT, "SC_LSHIFT");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_RIGHT_SHIFT, "SC_RSHIFT");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_LEFT_CONTROL, "SC_LCTRL");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_RIGHT_CONTROL, "SC_RCTRL");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_LEFT_ALT, "SC_LALT");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_RIGHT_ALT, "SC_RALT");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_SPACE, "SC_SPACE");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_ENTER, "SC_ENTER");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_TAB, "SC_TAB");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_1, "SC_1");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_2, "SC_2");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_3, "SC_3");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_4, "SC_4");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_5, "SC_5");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_6, "SC_6");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_7, "SC_7");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_8, "SC_8");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_9, "SC_9");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_0, "SC_0");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_F1, "SC_F1");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_F2, "SC_F2");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_F3, "SC_F3");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_F4, "SC_F4");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_F5, "SC_F5");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_F6, "SC_F6");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_F7, "SC_F7");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_F8, "SC_F8");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_F9, "SC_F9");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_F10, "SC_F10");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_F11, "SC_F11");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_F12, "SC_F12");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_MINUS, "SC_MINUS");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_EQUAL, "SC_EQUALS");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_BACKSPACE, "SC_BACKSPACE");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_COMMA, "SC_COMMA");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_PERIOD, "SC_PERIOD");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_SLASH, "SC_SLASH");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_APOSTROPHE, "SC_QUOTE");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_SEMICOLON, "SC_SEMICOLON");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_LEFT_BRACKET, "SC_LBRACE");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_RIGHT_BRACKET, "SC_RBRACE");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_BACKSLASH, "SC_BACKSLASH");
        registerKey(scanCodeClass, GLFW.GLFW_KEY_GRAVE_ACCENT, "SC_TILDE");
    }

    private void registerKey(Class<?> scanCodeClass, int glfw, String name) {
        try {
            @SuppressWarnings({ "rawtypes", "unchecked" })
            Object scan = Enum.valueOf((Class<Enum>) scanCodeClass, name);
            scanCodes.put(glfw, scan);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private Path resolveIwad(Path runtimeDir) throws IOException {
        Path primary = extractResource(runtimeDir, BUNDLED_IWAD_PRIMARY);
        if (Files.isRegularFile(primary)) return primary;
        Path secondary = extractResource(runtimeDir, BUNDLED_IWAD_SECONDARY);
        return Files.isRegularFile(secondary) ? secondary : primary;
    }

    private static Path extractResource(Path runtimeDir, String fileName) throws IOException {
        Path out = runtimeDir.resolve(fileName);
        if (Files.isRegularFile(out) && Files.size(out) > 0L) return out;
        String resourcePath = BUNDLED_BASE + fileName;
        try (InputStream in = DoomSession.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) return out;
            try (OutputStream os = Files.newOutputStream(out)) {
                in.transferTo(os);
            }
        }
        return out;
    }

    private static Path resolveRuntimeDir() {
        MinecraftClient mc = MinecraftClient.getInstance();
        Path base = (mc != null && mc.runDirectory != null)
            ? mc.runDirectory.toPath()
            : Paths.get(System.getProperty("user.home", "."), ".devils-game");
        return base.resolve("devils-runtime").resolve("doom");
    }

    private void ensureTexture(MinecraftClient mc, int width, int height) {
        if (frameTexture != null && frameImage != null && width == frameWidth && height == frameHeight) return;
        releaseTexture(mc);
        frameWidth = width;
        frameHeight = height;
        frameImage = new NativeImage(width, height, false);
        frameTexture = new NativeImageBackedTexture(() -> "devilsdoom-frame", frameImage);
        frameTextureId = Identifier.of("devils-game", "dynamic/devilsdoom/frame_" + (++textureNonce));
        mc.getTextureManager().registerTexture(frameTextureId, frameTexture);
    }

    private void releaseTexture(MinecraftClient mc) {
        if (frameTextureId != null && mc != null && mc.getTextureManager() != null) {
            mc.getTextureManager().destroyTexture(frameTextureId);
        }
        if (frameTexture != null) {
            try {
                frameTexture.close();
            } catch (Throwable ignored) {
            }
        }
        frameTexture = null;
        frameImage = null;
        frameTextureId = null;
        frameWidth = 0;
        frameHeight = 0;
    }

    private void cleanupRuntimeHandles() {
        pendingFrame.set(null);
        postEventMethod = null;
        keyEventCtor = null;
        mouseEventCtor = null;
        evKeyDown = null;
        evKeyUp = null;
        evMouse = null;
        scanCodes.clear();
        doomMain = null;
        engineClass = null;
        runtimeThread = null;

        if (runtimeLoader != null) {
            try {
                runtimeLoader.close();
            } catch (IOException ignored) {
            }
            runtimeLoader = null;
        }
    }

    private void stopEmbeddedAudio() {
        Object main = doomMain;
        if (main == null) return;

        try {
            Object doomSound = readField(main, "doomSound");
            invokeNoArgs(doomSound, "StopMusic");
        } catch (Throwable ignored) {
        }

        try {
            Object soundDriver = readField(main, "soundDriver");
            invokeNoArgs(soundDriver, "ShutdownSound");
        } catch (Throwable ignored) {
        }

        try {
            Object music = readField(main, "music");
            invokeNoArgs(music, "ShutdownMusic");
        } catch (Throwable ignored) {
        }
    }

    private void forceEmbeddedExit() {
        Object main = doomMain;
        if (main == null) return;
        try {
            Object doomSystem = readField(main, "doomSystem");
            invokeNoArgs(doomSystem, "Quit");
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            if (cause == null) return;
            String name = cause.getClass().getName();
            if (name.endsWith("DoomEmbeddedExit")) return;
            logText = shorten("Stop fallback failed: " + cause);
        }
    }

    private static Object readField(Object owner, String fieldName) throws Exception {
        if (owner == null || fieldName == null || fieldName.isBlank()) return null;
        Field field = owner.getClass().getField(fieldName);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void invokeNoArgs(Object target, String methodName) throws Exception {
        if (target == null || methodName == null || methodName.isBlank()) return;
        Method method = target.getClass().getMethod(methodName);
        method.invoke(target);
    }

    private static BufferedImage asBuffered(Image image) {
        if (image instanceof BufferedImage buffered) return buffered;
        int w = image.getWidth(null);
        int h = image.getHeight(null);
        if (w <= 0 || h <= 0) return null;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static Throwable unwrap(Throwable t) {
        Throwable current = t;
        while (current instanceof InvocationTargetException ite && ite.getTargetException() != null) {
            current = ite.getTargetException();
        }
        return current == null ? t : current;
    }

    private static int readExitCode(Throwable exit) {
        try {
            Method code = exit.getClass().getMethod("code");
            Object value = code.invoke(exit);
            return value instanceof Number n ? n.intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String shorten(String text) {
        if (text == null || text.isBlank()) return "<empty>";
        String clean = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() <= 180) return clean;
        return clean.substring(0, 177) + "...";
    }
}



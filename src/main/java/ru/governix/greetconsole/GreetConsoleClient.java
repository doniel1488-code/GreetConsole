package ru.governix.greetconsole;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.nio.file.Path;

public final class GreetConsoleClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("GreetConsole");

    private ConsoleWindow consoleWindow;
    private LogTailer logTailer;
    private KeyBinding openConsoleKey;

    @Override
    public void onInitializeClient() {
        installAwtErrorLogging();

        try {
            LOGGER.info("Desktop environment headless: {}", GraphicsEnvironment.isHeadless());
        } catch (Throwable error) {
            LOGGER.error("Could not inspect the desktop graphics environment.", error);
        }

        consoleWindow = new ConsoleWindow();
        Path latestLog = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
        LOGGER.info("Following Minecraft log file: {}", latestLog.toAbsolutePath());
        logTailer = new LogTailer(latestLog, consoleWindow);
        logTailer.start();

        openConsoleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.greetconsole.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                "category.greetconsole"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConsoleKey.wasPressed()) {
                toggleConsole("F8");
            }
        });

        // Fallback for launchers/keyboards where F8 is intercepted or unavailable.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("greetconsole")
                        .executes(context -> {
                            toggleConsole("/greetconsole");
                            return 1;
                        }))
        );

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (logTailer != null) logTailer.stop();
            if (consoleWindow != null) consoleWindow.shutdown();
        });

        LOGGER.info("GreetConsole initialized. Press F8 or run /greetconsole to open the separate log window.");
    }

    private void toggleConsole(String trigger) {
        LOGGER.info("Console open/toggle requested by {}.", trigger);
        try {
            consoleWindow.toggle();
        } catch (Throwable error) {
            LOGGER.error("Could not queue the desktop console window toggle.", error);
        }
    }

    /**
     * Swing creates its window asynchronously on the AWT event-dispatch thread.
     * Errors thrown there do not naturally appear in Minecraft's logger, so
     * route them back to the mod logger instead of silently losing the cause.
     */
    private static void installAwtErrorLogging() {
        try {
            EventQueue queue = Toolkit.getDefaultToolkit().getSystemEventQueue();
            queue.push(new EventQueue() {
                @Override
                protected void dispatchEvent(AWTEvent event) {
                    try {
                        super.dispatchEvent(event);
                    } catch (Throwable error) {
                        LOGGER.error("Desktop UI event failed: {}", event.getClass().getName(), error);
                    }
                }
            });
            LOGGER.info("Installed desktop UI error reporting.");
        } catch (Throwable error) {
            LOGGER.error("Could not install desktop UI error reporting.", error);
        }
    }
}

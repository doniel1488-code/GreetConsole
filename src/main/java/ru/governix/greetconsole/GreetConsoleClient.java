package ru.governix.greetconsole;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class GreetConsoleClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("GreetConsole");

    private ConsoleWindow consoleWindow;
    private LogTailer logTailer;
    private KeyBinding openConsoleKey;

    @Override
    public void onInitializeClient() {
        consoleWindow = new ConsoleWindow();
        Path latestLog = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
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
                consoleWindow.toggle();
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (logTailer != null) logTailer.stop();
            if (consoleWindow != null) consoleWindow.shutdown();
        });

        LOGGER.info("GreetConsole initialized. Press F8 to open the separate log window.");
    }
}

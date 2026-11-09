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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class GreetConsoleClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("GreetConsole");
    private static final String COMPANION_RESOURCE = "/assets/greetconsole/GreetConsole.exe";

    private Path latestLog;
    private Process companionProcess;
    private CommandBridge commandBridge;
    private BrowserBridge browserBridge;
    private KeyBinding openConsoleKey;

    @Override
    public void onInitializeClient() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
        latestLog = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");

        if (windows) {
            openConsoleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "key.greetconsole.open",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_F8,
                    "category.greetconsole"
            ));
        } else {
            LOGGER.warn("The separate C# desktop window is Windows-only. The browser extension can still be used.");
        }

        try {
            browserBridge = new BrowserBridge(latestLog);
            browserBridge.start();
            LOGGER.info("GreetConsole browser bridge is listening on http://127.0.0.1:{}/.", BrowserBridge.PORT);
        } catch (Exception error) {
            browserBridge = null;
            LOGGER.error("Could not start the GreetConsole browser bridge. Is port {} already in use?", BrowserBridge.PORT, error);
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (browserBridge != null) {
                browserBridge.updateStatus(client);
                browserBridge.drain(client);
            }
            if (commandBridge != null) {
                commandBridge.drain(client);
            }
            if (openConsoleKey != null) {
                while (openConsoleKey.wasPressed()) {
                    toggleCompanion("F8");
                }
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            stopCompanion();
            stopBrowserBridge();
        });

        LOGGER.info("GreetConsole initialized. Press F8 to open the separate C# log window (Windows).");
        LOGGER.info("Minecraft log file: {}", latestLog.toAbsolutePath());
    }

    private synchronized void toggleCompanion(String trigger) {
        LOGGER.info("Console open/toggle requested by {}.", trigger);
        try {
            if (companionProcess != null && companionProcess.isAlive()) {
                Process current = companionProcess;
                companionProcess = null;
                current.destroy();
                stopCommandBridge();
                LOGGER.info("Sent close request to GreetConsole desktop window.");
                return;
            }

            stopCommandBridge();
            Path companionDirectory = FabricLoader.getInstance().getConfigDir().resolve("greetconsole");
            Files.createDirectories(companionDirectory);
            Path executable = companionDirectory.resolve("GreetConsole.exe");
            extractCompanion(executable);

            CommandBridge bridge = new CommandBridge();
            CommandBridge.Connection connection = bridge.start();
            commandBridge = bridge;

            ProcessBuilder builder = new ProcessBuilder(
                    executable.toAbsolutePath().toString(),
                    "--log", latestLog.toAbsolutePath().toString(),
                    "--command-port", Integer.toString(connection.port()),
                    "--command-token", connection.token()
            );
            builder.directory(companionDirectory.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);

            Process started = builder.start();
            companionProcess = started;
            started.onExit().thenRun(() -> {
                synchronized (GreetConsoleClient.this) {
                    if (companionProcess == started) companionProcess = null;
                    if (commandBridge == bridge) {
                        bridge.close();
                        commandBridge = null;
                    }
                }
            });
            LOGGER.info("Started C# desktop companion (PID {}). Command bridge listening on 127.0.0.1:{}.", started.pid(), connection.port());
        } catch (Exception error) {
            stopCommandBridge();
            LOGGER.error("Could not start or stop the GreetConsole C# desktop companion.", error);
        }
    }

    private static void extractCompanion(Path destination) throws IOException {
        try (InputStream input = GreetConsoleClient.class.getResourceAsStream(COMPANION_RESOURCE)) {
            if (input == null) {
                throw new IOException("The bundled C# companion executable is missing from the mod JAR: " + COMPANION_RESOURCE);
            }

            Path temporary = destination.resolveSibling(destination.getFileName() + ".new");
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnavailable) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private synchronized void stopCommandBridge() {
        if (commandBridge != null) {
            commandBridge.close();
            commandBridge = null;
        }
    }

    private synchronized void stopCompanion() {
        if (companionProcess != null && companionProcess.isAlive()) companionProcess.destroy();
        companionProcess = null;
        stopCommandBridge();
    }

    private synchronized void stopBrowserBridge() {
        if (browserBridge != null) {
            browserBridge.close();
            browserBridge = null;
        }
    }
}

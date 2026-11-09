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
    private KeyBinding openConsoleKey;

    @Override
    public void onInitializeClient() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            LOGGER.error("GreetConsole desktop companion currently supports Windows only.");
            return;
        }

        latestLog = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
        openConsoleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.greetconsole.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                "category.greetconsole"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConsoleKey.wasPressed()) {
                toggleCompanion("F8");
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stopCompanion());

        LOGGER.info("GreetConsole initialized. Press F8 to open the separate C# log window.");
        LOGGER.info("Minecraft log file: {}", latestLog.toAbsolutePath());
    }

    private void toggleCompanion(String trigger) {
        LOGGER.info("Console open/toggle requested by {}.", trigger);
        try {
            if (companionProcess != null && companionProcess.isAlive()) {
                companionProcess.destroy();
                if (!companionProcess.waitFor(900, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    companionProcess.destroyForcibly();
                }
                companionProcess = null;
                LOGGER.info("GreetConsole desktop window closed.");
                return;
            }

            Path companionDirectory = FabricLoader.getInstance().getConfigDir().resolve("greetconsole");
            Files.createDirectories(companionDirectory);
            Path executable = companionDirectory.resolve("GreetConsole.exe");
            extractCompanion(executable);

            ProcessBuilder builder = new ProcessBuilder(
                    executable.toAbsolutePath().toString(),
                    "--log",
                    latestLog.toAbsolutePath().toString()
            );
            builder.directory(companionDirectory.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            companionProcess = builder.start();
            LOGGER.info("Started C# desktop companion (PID {}).", companionProcess.pid());
        } catch (Exception error) {
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

    private void stopCompanion() {
        if (companionProcess == null || !companionProcess.isAlive()) return;
        companionProcess.destroy();
        try {
            if (!companionProcess.waitFor(900, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                companionProcess.destroyForcibly();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            companionProcess.destroyForcibly();
        }
        companionProcess = null;
    }
}

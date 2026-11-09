package ru.governix.greetconsole;

import net.minecraft.client.MinecraftClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class CommandBridge implements AutoCloseable {
    private record PendingCommand(String command, PrintWriter response) {}
    public record Connection(int port, String token) {}

    private final ConcurrentLinkedQueue<PendingCommand> pending = new ConcurrentLinkedQueue<>();
    private volatile boolean running;
    private volatile Socket activeSocket;
    private ServerSocket serverSocket;
    private String token;

    public synchronized Connection start() throws IOException {
        close();
        token = UUID.randomUUID().toString();

        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
        serverSocket = server;
        running = true;

        Thread listener = new Thread(this::listen, "GreetConsole-command-bridge");
        listener.setDaemon(true);
        listener.start();

        return new Connection(server.getLocalPort(), token);
    }

    private void listen() {
        while (running) {
            try (Socket socket = serverSocket.accept()) {
                activeSocket = socket;
                socket.setTcpNoDelay(true);
                try (BufferedReader reader = new BufferedReader(
                             new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(
                             new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

                    String hello = reader.readLine();
                    if (!Objects.equals(hello, "HELLO " + token)) {
                        writer.println("ERR\tПроверка соединения не пройдена.");
                        continue;
                    }

                    writer.println("READY");
                    String line;
                    while (running && (line = reader.readLine()) != null) {
                        if (line.length() > 256) {
                            sendReply(writer, "ERR\tТекст слишком длинный (максимум 256 символов).");
                        } else {
                            pending.add(new PendingCommand(line, writer));
                        }
                    }
                } finally {
                    activeSocket = null;
                }
            } catch (SocketException error) {
                if (running) {
                    // The socket can be closed when the companion window exits.
                }
            } catch (IOException error) {
                if (running) {
                    // A disconnected companion can reconnect while the game remains open.
                }
            }
        }
    }

    public void drain(MinecraftClient client) {
        PendingCommand request;
        while ((request = pending.poll()) != null) {
            String input = request.command().trim();

            if (input.isEmpty()) {
                sendReply(request.response(), "ERR\tВведите сообщение или команду.");
                continue;
            }
            if (input.length() > 256) {
                sendReply(request.response(), "ERR\tТекст слишком длинный (максимум 256 символов).");
                continue;
            }
            if (input.chars().anyMatch(ch -> ch < 32 || ch == 127)) {
                sendReply(request.response(), "ERR\tТекст содержит недопустимые управляющие символы.");
                continue;
            }
            if (client.player == null || client.getNetworkHandler() == null) {
                sendReply(request.response(), "ERR\tСначала зайдите в одиночный мир или на сервер.");
                continue;
            }

            try {
                if (input.startsWith("/")) {
                    String command = input.substring(1).trim();
                    if (command.isEmpty()) {
                        sendReply(request.response(), "ERR\tПосле / нужно указать команду.");
                        continue;
                    }
                    client.getNetworkHandler().sendChatCommand(command);
                    sendReply(request.response(), "OK\tКоманда отправлена: /" + command.replace('\t', ' '));
                } else {
                    client.getNetworkHandler().sendChatMessage(input);
                    sendReply(request.response(), "OK\tСообщение отправлено в игровой чат.");
                }
            } catch (RuntimeException error) {
                sendReply(request.response(), "ERR\tНе удалось отправить текст: " + safeMessage(error));
            }
        }
    }

    private static void sendReply(PrintWriter writer, String message) {
        synchronized (writer) {
            writer.println(message.replace('\r', ' ').replace('\n', ' ').replace('\t', ' '));
            writer.flush();
        }
    }

    private static String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }

    @Override
    public synchronized void close() {
        running = false;
        pending.clear();
        try {
            if (activeSocket != null) activeSocket.close();
        } catch (IOException ignored) {
        }
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        activeSocket = null;
        serverSocket = null;
    }
}

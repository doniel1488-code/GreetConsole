package ru.governix.greetconsole;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class BrowserBridge implements AutoCloseable {
    public static final int PORT = 38761;
    private static final String EXTENSION_ORIGIN = "chrome-extension://mmecanljdhgkakmhdnmcjekmjhkgahlh";
    private static final int MAX_LOG_LINES = 2500;
    private static final long MAX_INITIAL_TAIL = 256 * 1024;
    private static final int MAX_COMMAND_LENGTH = 256;

    private record LogEntry(long id, String text) {}
    private record PendingCommand(String command, CompletableFuture<String> response) {}

    private final Path logPath;
    private final Gson gson = new Gson();
    private final String token = UUID.randomUUID().toString();
    private final ConcurrentLinkedQueue<PendingCommand> pendingCommands = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<LogEntry> logHistory = new ArrayDeque<>();
    private final AtomicLong logSequence = new AtomicLong();
    private final ByteArrayOutputStream pendingLine = new ByteArrayOutputStream();
    private final ScheduledExecutorService logExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "GreetConsole-browser-logs");
        thread.setDaemon(true);
        return thread;
    });

    private volatile HttpServer server;
    private volatile ExecutorService apiExecutor;
    private volatile long logPosition;
    private volatile String playerName = "";
    private volatile boolean playerConnected;
    private volatile String serverAddress = "";
    private volatile boolean running;

    public BrowserBridge(Path logPath) {
        this.logPath = logPath.toAbsolutePath().normalize();
    }

    public synchronized void start() throws IOException {
        if (running) return;

        HttpServer http = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 0);
        ExecutorService executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "GreetConsole-browser-api");
            thread.setDaemon(true);
            return thread;
        });
        http.setExecutor(executor);

        http.createContext("/api/bootstrap", this::handleBootstrap);
        http.createContext("/api/status", this::handleStatus);
        http.createContext("/api/logs", this::handleLogs);
        http.createContext("/api/command", this::handleCommand);

        loadInitialLog();
        apiExecutor = executor;
        server = http;
        running = true;
        http.start();
        logExecutor.scheduleWithFixedDelay(this::pollLogSafely, 200, 200, TimeUnit.MILLISECONDS);
    }

    public void updateStatus(MinecraftClient client) {
        if (client == null || client.player == null || client.getNetworkHandler() == null) {
            playerConnected = false;
            playerName = "";
            serverAddress = "";
            return;
        }

        playerConnected = true;
        playerName = client.player.getName().getString();
        if (client.getCurrentServerEntry() != null) {
            serverAddress = client.getCurrentServerEntry().address;
        } else {
            serverAddress = "Одиночный мир";
        }
    }

    public void drain(MinecraftClient client) {
        PendingCommand request;
        while ((request = pendingCommands.poll()) != null) {
            String input = request.command().trim();

            if (input.isEmpty()) {
                request.response().complete("Пустое сообщение.");
                continue;
            }
            if (input.length() > MAX_COMMAND_LENGTH) {
                request.response().complete("Текст слишком длинный (максимум 256 символов).");
                continue;
            }
            if (input.chars().anyMatch(ch -> ch < 32 || ch == 127)) {
                request.response().complete("Текст содержит недопустимые управляющие символы.");
                continue;
            }
            if (client.player == null || client.getNetworkHandler() == null) {
                request.response().complete("Сначала зайдите в одиночный мир или на сервер.");
                continue;
            }

            try {
                if (input.startsWith("/")) {
                    String command = input.substring(1).trim();
                    if (command.isEmpty()) {
                        request.response().complete("После / нужно указать команду.");
                        continue;
                    }
                    client.getNetworkHandler().sendChatCommand(command);
                    request.response().complete("Команда отправлена: /" + command);
                } else {
                    client.getNetworkHandler().sendChatMessage(input);
                    request.response().complete("Сообщение отправлено в игровой чат.");
                }
            } catch (RuntimeException error) {
                request.response().complete("Не удалось отправить текст: " + safeMessage(error));
            }
        }
    }

    private void handleBootstrap(HttpExchange exchange) throws IOException {
        if (!prepare(exchange, "GET")) return;
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("token", token);
        response.addProperty("port", PORT);
        response.addProperty("version", "1.3.2");
        sendJson(exchange, 200, response);
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!prepare(exchange, "GET")) return;
        if (!authorized(exchange)) {
            sendError(exchange, 401, "Локальная проверка доступа не пройдена.");
            return;
        }

        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("connected", playerConnected);
        response.addProperty("player", playerName);
        response.addProperty("server", serverAddress);
        sendJson(exchange, 200, response);
    }

    private void handleLogs(HttpExchange exchange) throws IOException {
        if (!prepare(exchange, "GET")) return;
        if (!authorized(exchange)) {
            sendError(exchange, 401, "Локальная проверка доступа не пройдена.");
            return;
        }

        long since = 0;
        try {
            String query = exchange.getRequestURI().getRawQuery();
            if (query != null) {
                for (String part : query.split("&")) {
                    if (part.startsWith("since=")) {
                        since = Math.max(0, Long.parseLong(part.substring("since=".length())));
                    }
                }
            }
        } catch (NumberFormatException ignored) {
        }

        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("cursor", logSequence.get());
        var lines = new com.google.gson.JsonArray();
        synchronized (logHistory) {
            for (LogEntry entry : logHistory) {
                if (entry.id() > since) {
                    JsonObject line = new JsonObject();
                    line.addProperty("id", entry.id());
                    line.addProperty("text", entry.text());
                    lines.add(line);
                }
            }
        }
        response.add("lines", lines);
        sendJson(exchange, 200, response);
    }

    private void handleCommand(HttpExchange exchange) throws IOException {
        if (!prepare(exchange, "POST")) return;
        if (!authorized(exchange)) {
            sendError(exchange, 401, "Локальная проверка доступа не пройдена.");
            return;
        }

        byte[] body;
        try (InputStream input = exchange.getRequestBody()) {
            body = input.readNBytes(4097);
        }
        if (body.length > 4096) {
            sendError(exchange, 413, "Слишком большой запрос.");
            return;
        }

        final JsonObject request;
        try {
            request = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException error) {
            sendError(exchange, 400, "Некорректный JSON запроса.");
            return;
        }

        String command = request.has("command") && !request.get("command").isJsonNull()
                ? request.get("command").getAsString() : "";
        if (command.length() > MAX_COMMAND_LENGTH) {
            sendError(exchange, 400, "Команда слишком длинная (максимум 256 символов).");
            return;
        }
        if (command.chars().anyMatch(ch -> ch < 32 || ch == 127)) {
            sendError(exchange, 400, "Команда содержит недопустимые управляющие символы.");
            return;
        }
        if (pendingCommands.size() >= 10) {
            sendError(exchange, 429, "Очередь команд заполнена. Попробуйте ещё раз.");
            return;
        }

        CompletableFuture<String> completion = new CompletableFuture<>();
        pendingCommands.add(new PendingCommand(command, completion));
        try {
            String message = completion.get(4, TimeUnit.SECONDS);
            boolean accepted = !message.startsWith("Не удалось")
                    && !message.startsWith("Сначала")
                    && !message.startsWith("Пустая")
                    && !message.startsWith("Текст слишком")
                    && !message.startsWith("Текст содержит")
                    && !message.startsWith("После /");
            JsonObject response = new JsonObject();
            response.addProperty("ok", accepted);
            response.addProperty("message", message);
            sendJson(exchange, 200, response);
        } catch (Exception error) {
            sendError(exchange, 504, "Minecraft не обработал команду за отведённое время.");
        }
    }

    private boolean prepare(HttpExchange exchange, String expectedMethod) throws IOException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String normalizedOrigin = origin == null ? "" : origin.trim().replaceAll("/+$", "");
        if (!EXTENSION_ORIGIN.equalsIgnoreCase(normalizedOrigin)) {
            String received = origin == null ? "<Origin отсутствует>" : origin;
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            sendError(exchange, 403,
                    "Origin не совпадает. Получено: " + received
                            + " | Ожидается: " + EXTENSION_ORIGIN
                            + " | Запрос: " + method + " " + path);
            return false;
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", EXTENSION_ORIGIN);
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type, X-GreetConsole-Token");
        headers.set("Access-Control-Allow-Private-Network", "true");
        headers.set("Access-Control-Max-Age", "600");
        headers.set("Vary", "Origin");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return false;
        }
        if (!expectedMethod.equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Метод не поддерживается.");
            return false;
        }
        return true;
    }

    private boolean authorized(HttpExchange exchange) {
        String supplied = exchange.getRequestHeaders().getFirst("X-GreetConsole-Token");
        if (supplied == null) return false;
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        JsonObject response = new JsonObject();
        response.addProperty("ok", false);
        response.addProperty("message", message);
        sendJson(exchange, status, response);
    }

    private void sendJson(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void loadInitialLog() {
        try {
            if (!Files.exists(logPath)) return;
            try (FileChannel channel = FileChannel.open(logPath, StandardOpenOption.READ)) {
                long size = channel.size();
                long start = Math.max(0, size - MAX_INITIAL_TAIL);
                channel.position(start);
                if (start > 0) {
                    ByteBuffer one = ByteBuffer.allocate(1);
                    while (channel.read(one) > 0) {
                        one.flip();
                        byte value = one.get();
                        one.clear();
                        if (value == (byte) '\n') break;
                    }
                }
                ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
                while (channel.read(buffer) > 0) {
                    buffer.flip();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    appendLogBytes(bytes);
                    buffer.clear();
                }
                logPosition = channel.size();
            }
        } catch (IOException ignored) {
            logPosition = 0;
        }
    }

    private void pollLogSafely() {
        try {
            pollLog();
        } catch (Exception ignored) {
            // The log can be rotated or temporarily unavailable while the game is starting.
        }
    }

    private void pollLog() throws IOException {
        if (!Files.exists(logPath)) return;
        try (FileChannel channel = FileChannel.open(logPath, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < logPosition) {
                logPosition = 0;
                synchronized (pendingLine) {
                    pendingLine.reset();
                }
            }
            if (size <= logPosition) return;
            channel.position(logPosition);
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            int read;
            while ((read = channel.read(buffer)) > 0) {
                logPosition += read;
                buffer.flip();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                appendLogBytes(bytes);
                buffer.clear();
            }
        }
    }

    private void appendLogBytes(byte[] bytes) {
        synchronized (pendingLine) {
            for (byte value : bytes) {
                if (value == (byte) '\n') {
                    byte[] line = pendingLine.toByteArray();
                    pendingLine.reset();
                    int length = line.length;
                    if (length > 0 && line[length - 1] == (byte) '\r') length--;
                    addLogLine(new String(line, 0, length, StandardCharsets.UTF_8));
                } else {
                    pendingLine.write(value);
                    if (pendingLine.size() > 2_000_000) {
                        pendingLine.reset();
                        addLogLine("[GreetConsole] Строка журнала длиннее 2 МБ — пропущена.");
                    }
                }
            }
        }
    }

    private void addLogLine(String line) {
        if (line.isEmpty()) return;
        long id = logSequence.incrementAndGet();
        synchronized (logHistory) {
            logHistory.addLast(new LogEntry(id, line));
            while (logHistory.size() > MAX_LOG_LINES) logHistory.removeFirst();
        }
    }

    private static String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }

    @Override
    public synchronized void close() {
        running = false;
        HttpServer current = server;
        server = null;
        if (current != null) current.stop(0);
        ExecutorService executor = apiExecutor;
        apiExecutor = null;
        if (executor != null) executor.shutdownNow();
        logExecutor.shutdownNow();
        PendingCommand pending;
        while ((pending = pendingCommands.poll()) != null) {
            pending.response().complete("GreetConsole остановлен.");
        }
        synchronized (pendingLine) {
            pendingLine.reset();
        }
    }
}

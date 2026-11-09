package ru.governix.greetconsole;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Follows the active Minecraft logs/latest.log file without blocking the game thread. */
final class LogTailer {
    private static final Logger LOGGER = Logger.getLogger(LogTailer.class.getName());
    private static final int INITIAL_TAIL_BYTES = 1_500_000;
    private static final int INITIAL_TAIL_LINES = 1_500;
    private static final int READ_BUFFER_SIZE = 64 * 1024;

    private final Path logFile;
    private final ConsoleWindow output;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "GreetConsole-LogTailer");
        thread.setDaemon(true);
        return thread;
    });
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    private volatile boolean stopped;
    private boolean initialized;
    private long position;
    private Object fileKey;

    LogTailer(Path logFile, ConsoleWindow output) {
        this.logFile = logFile;
        this.output = output;
    }

    void start() {
        executor.scheduleWithFixedDelay(this::pollSafely, 0, 200, TimeUnit.MILLISECONDS);
    }

    void stop() {
        stopped = true;
        executor.shutdownNow();
    }

    private void pollSafely() {
        if (stopped) return;
        try {
            poll();
        } catch (Exception exception) {
            LOGGER.log(Level.FINE, "Could not read Minecraft log file: " + logFile, exception);
        }
    }

    private void poll() throws IOException {
        if (!Files.isRegularFile(logFile)) return;

        BasicFileAttributes attributes = Files.readAttributes(logFile, BasicFileAttributes.class);
        Object currentFileKey = attributes.fileKey();
        long size = attributes.size();

        if (!initialized) {
            readInitialTail(size);
            fileKey = currentFileKey;
            initialized = true;
            return;
        }

        boolean replaced = fileKey != null && currentFileKey != null && !fileKey.equals(currentFileKey);
        if (replaced || size < position) {
            position = 0;
            pending.reset();
            fileKey = currentFileKey;
        }

        if (size <= position) return;

        try (FileChannel channel = FileChannel.open(logFile, StandardOpenOption.READ)) {
            channel.position(position);
            ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
            while (position < size) {
                buffer.clear();
                int read = channel.read(buffer);
                if (read <= 0) break;
                position += read;
                buffer.flip();
                byte[] bytes = new byte[read];
                buffer.get(bytes);
                consume(bytes);
            }
        }
    }

    private void readInitialTail(long size) throws IOException {
        long start = Math.max(0, size - INITIAL_TAIL_BYTES);
        byte[] data;
        try (FileChannel channel = FileChannel.open(logFile, StandardOpenOption.READ)) {
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate((int) (size - start));
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // Read the bounded tail into memory.
            }
            data = new byte[buffer.position()];
            buffer.flip();
            buffer.get(data);
        }

        int firstLine = 0;
        if (start > 0) {
            while (firstLine < data.length && data[firstLine] != '\n') firstLine++;
            if (firstLine < data.length) firstLine++;
        }

        String tail = new String(data, firstLine, data.length - firstLine, StandardCharsets.UTF_8);
        String[] lines = tail.split("\\n", -1);
        int completeCount = Math.max(0, lines.length - 1);
        int from = Math.max(0, completeCount - INITIAL_TAIL_LINES);
        for (int i = from; i < completeCount; i++) {
            String line = stripCarriageReturn(lines[i]);
            if (!line.isEmpty()) output.addLine(line);
        }

        int lastNewline = lastIndexOf(data, (byte) '\n');
        if (lastNewline >= 0 && lastNewline + 1 < data.length) {
            pending.write(data, lastNewline + 1, data.length - lastNewline - 1);
        } else if (lastNewline < 0 && start == 0) {
            pending.write(data);
        }
        position = size;
    }

    private void consume(byte[] bytes) {
        byte[] previous = pending.toByteArray();
        pending.reset();
        pending.write(previous, 0, previous.length);
        pending.write(bytes, 0, bytes.length);

        byte[] combined = pending.toByteArray();
        int lineStart = 0;
        for (int i = 0; i < combined.length; i++) {
            if (combined[i] == '\n') {
                String line = new String(combined, lineStart, i - lineStart, StandardCharsets.UTF_8);
                line = stripCarriageReturn(line);
                if (!line.isEmpty()) output.addLine(line);
                lineStart = i + 1;
            }
        }

        pending.reset();
        if (lineStart < combined.length) pending.write(combined, lineStart, combined.length - lineStart);
    }

    private static int lastIndexOf(byte[] bytes, byte value) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == value) return i;
        }
        return -1;
    }

    private static String stripCarriageReturn(String value) {
        if (value.endsWith("\r")) return value.substring(0, value.length() - 1);
        return value;
    }
}

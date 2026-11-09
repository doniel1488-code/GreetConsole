package ru.governix.greetconsole;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record LogLine(String text, Level level) {
    private static final Pattern LEVEL_PATTERN = Pattern.compile("\\[(?:[^\\]]*/)?(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\]", Pattern.CASE_INSENSITIVE);

    static LogLine from(String text) {
        Matcher matcher = LEVEL_PATTERN.matcher(text);
        if (matcher.find()) {
            try {
                return new LogLine(text, Level.valueOf(matcher.group(1).toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Fall through to OTHER.
            }
        }
        return new LogLine(text, Level.OTHER);
    }

    enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR, FATAL, OTHER
    }
}

package com.echo.utils;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

public class LogSanitizer {

    private static final int MAX_LOG_LENGTH = 200;
    private static final String TRUNCATED_SUFFIX = "...(truncated)";

    private LogSanitizer() {}

    public static String sanitize(Object input) {
        if (input == null) {
            return "null";
        }

        String str = input.toString();
        StringBuilder sb = new StringBuilder(str.length());

        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\u0000':
                case '\u001B':
                    sb.append(' ');
                    break;
                default:
                    sb.append(c);
            }
        }

        String result = sb.toString();
        if (result.length() > MAX_LOG_LENGTH) {
            return result.substring(0, MAX_LOG_LENGTH) + TRUNCATED_SUFFIX;
        }

        return result;
    }

    public static String sanitizeCollection(Collection<?> collection) {
        if (collection == null) {
            return "[]";
        }
        return collection.stream()
                .map(LogSanitizer::sanitize)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    public static String sanitizeMap(Map<?, ?> map) {
        if (map == null) {
            return "{}";
        }
        return map.entrySet().stream()
                .map(e -> sanitize(e.getKey()) + "=" + sanitize(e.getValue()))
                .collect(Collectors.joining(", ", "{", "}"));
    }
}

package io.github.verycooltimo.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Ant path subset: *, **, ?, slash separators; blank lines and # comments. */
public final class ReleasePatterns {
    private ReleasePatterns() {}
    private static final java.util.concurrent.ConcurrentMap<String, Pattern> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static List<String> read(Path file) throws IOException {
        return Files.readAllLines(file).stream().map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
    }

    public static boolean matches(List<String> patterns, String path) {
        return patterns.stream().anyMatch(pattern -> CACHE.computeIfAbsent(pattern, p -> Pattern.compile(regex(p))).matcher(path.replace('\\', '/')).matches());
    }

    private static String regex(String ant) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < ant.length(); i++) {
            char c = ant.charAt(i);
            if (c == '*') {
                if (i + 1 < ant.length() && ant.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < ant.length() && ant.charAt(i + 1) == '/') {
                        i++;
                        out.append("(?:.*/)?");
                    } else out.append(".*");
                } else out.append("[^/]*");
            } else if (c == '?') out.append("[^/]");
            else out.append(Pattern.quote(String.valueOf(c)));
        }
        return out.toString();
    }
}

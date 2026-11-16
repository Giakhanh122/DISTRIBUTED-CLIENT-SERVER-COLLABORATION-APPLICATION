package client.transfer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

public final class DownloadDirectory {
    private DownloadDirectory() {}

    public static File resolve() {
        Path home = Path.of(System.getProperty("user.home"));
        if (!home.isAbsolute()) throw new IllegalStateException("User home must be an absolute path");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            Path configured = linuxDownloads(home);
            if (configured != null) return configured.normalize().toFile();
        }
        return home.resolve("Downloads").normalize().toFile();
    }

    private static Path linuxDownloads(Path home) {
        String configHome = System.getenv("XDG_CONFIG_HOME");
        try {
            Path config = configHome == null || configHome.isBlank() ? home.resolve(".config") : Path.of(configHome);
            if (!config.isAbsolute()) config = home.resolve(".config");
            Path userDirs = config.resolve("user-dirs.dirs");
            if (!Files.isRegularFile(userDirs)) return null;
            for (String line : Files.readAllLines(userDirs)) {
                String entry = line.trim();
                int equals = entry.indexOf('=');
                if (equals < 0 || !entry.substring(0, equals).trim().equals("XDG_DOWNLOAD_DIR")) continue;
                String value = entry.substring(equals + 1).trim();
                if (!value.startsWith("\"")) continue;
                StringBuilder decoded = new StringBuilder();
                boolean ended = false;
                for (int i = 1; i < value.length(); i++) {
                    char ch = value.charAt(i);
                    if (ch == '"') { ended = true; break; }
                    if (ch == '\\' && i + 1 < value.length()) ch = value.charAt(++i);
                    decoded.append(ch);
                }
                if (!ended) continue;
                String directory = decoded.toString();
                if (directory.equals("$HOME") || directory.startsWith("$HOME/")) {
                    directory = home + directory.substring(5);
                } else if (directory.equals("${HOME}") || directory.startsWith("${HOME}/")) {
                    directory = home + directory.substring(7);
                }
                Path downloads = Path.of(directory);
                if (downloads.isAbsolute()) return downloads;
            }
        } catch (IOException | InvalidPathException | SecurityException ignored) {
            // Use the standard Downloads folder when no usable desktop setting is available.
        }
        return null;
    }
}

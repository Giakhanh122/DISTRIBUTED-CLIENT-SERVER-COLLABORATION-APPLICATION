package ui.util;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javafx.stage.Window;

public final class LocalFileOpener {
    private LocalFileOpener() {}

    public static void open(Window owner, String path, boolean folder) {
        Thread.startVirtualThread(() -> {
            String location = path == null ? "" : path;
            try {
                if (location.isBlank()) throw new IOException("No file path is available.");
                Path file = Path.of(location).toAbsolutePath().normalize();
                Path target = folder && !Files.isDirectory(file) ? file.getParent() : file;
                location = target == null ? file.toString() : target.toString();
                if (target == null || !Files.exists(target)) {
                    throw new IOException("The file or folder no longer exists.");
                }
                launch(target);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                showError(owner, location, "Opening the file or folder was interrupted.");
            } catch (IOException | RuntimeException e) {
                showError(owner, location, e.getMessage());
            }
        });
    }

    private static void showError(Window owner, String path, String reason) {
        JavaFxUtils.showPathError(owner, "Unable to open file or folder", reason, path);
    }

    private static void launch(Path target) throws IOException, InterruptedException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<List<String>> commands = new ArrayList<>();
        if (os.contains("linux")) {
            commands.add(List.of("xdg-open", target.toString()));
            commands.add(List.of("gio", "open", target.toUri().toASCIIString()));
        }
        IOException failure = new IOException("No application was found to open this path.");
        if (!os.contains("linux") && desktopOpen(target, failure)) return;
        if (os.contains("win")) commands.add(List.of("explorer.exe", target.toString()));
        else if (os.contains("mac")) commands.add(List.of("/usr/bin/open", target.toString()));
        for (List<String> command : commands) {
            try {
                Process process = new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                process.getOutputStream().close();
                if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() == 0) return;
                failure.addSuppressed(new IOException(command.getFirst() + " failed: " + process.exitValue()));
            } catch (IOException | SecurityException e) {
                failure.addSuppressed(e);
            }
        }
        if (os.contains("linux") && desktopOpen(target, failure)) return;
        throw failure;
    }

    private static boolean desktopOpen(Path target, IOException failure) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(target.toFile());
                return true;
            }
        } catch (IOException | RuntimeException e) {
            failure.addSuppressed(e);
        }
        return false;
    }
}

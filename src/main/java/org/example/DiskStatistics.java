package org.example;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class DiskStatistics {

    // Method to get total and free disk space
    public static List<Long> getDiskSpaceInfo(String drivePath) {
        File drive = new File(drivePath);

        long totalSpace = drive.getTotalSpace();
        long freeSpace = drive.getFreeSpace();
        long usableSpace = drive.getUsableSpace();

        return List.of(totalSpace, freeSpace, usableSpace);
    }

    // Method to get directory size using NIO
    public static long getDirectorySize(String directoryPath) {
        Path path = Paths.get(directoryPath);
        final AtomicLong size = new AtomicLong(0);

        try {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    size.addAndGet(attrs.size());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    System.err.println("Failed to access file: " + file + ", " + exc);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("Error calculating directory size: " + e.getMessage());
        }

        return size.get();
    }

    // Helper method to format byte sizes to human-readable format
    public static String formatSize(long bytes) {
        final String[] units = new String[] { "B", "KB", "MB", "GB", "TB" };
        int unitIndex = 0;
        double size = bytes;

        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }

        return String.format("%.2f %s", size, units[unitIndex]);
    }
}

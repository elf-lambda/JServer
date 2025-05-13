package org.example;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CleanupManager {

    public static int deleteFilesOlderThan(String dirPath, int days) {
        if (days < 0) {
            System.err.println("CleanupManager: 'days' parameter cannot be negative.");
            return 0;
        }

        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            System.err.println("CleanupManager: Cleanup directory not found or is not a directory: " + dirPath);
            return 0;
        }

        // Calculate the cutoff time in milliseconds since the epoch
        long cutoffTimeMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            System.out.println("CleanupManager: No files found in directory '" + dirPath + "'.");
            return 0;
        }

        // Filter for actual files (not directories), filter by age, and sort by last modified time (oldest first)
        List<File> filesToDelete = Stream.of(files)
                                         .filter(File::isFile)
                                         .filter(file -> file.lastModified() < cutoffTimeMillis)
                                         .sorted(Comparator.comparingLong(File::lastModified))
                                         .collect(Collectors.toList());

        if (filesToDelete.isEmpty()) {
            System.out.println("CleanupManager: No files found older than " + days + " days in '" + dirPath + "'.");
            return 0;
        }

        System.out.println("CleanupManager: Found " + filesToDelete.size() +
                " files older than " + days + " days in '" + dirPath + "'. Starting deletion...");

        int deletedCount = 0;

        // Iterate through the sorted list (oldest first) and delete
        for (File file : filesToDelete) {
            System.out.println("CleanupManager: Deleting old file: " + file.getName());
            if (file.delete()) {
                deletedCount++;
            } else {
                System.err.println("CleanupManager: Failed to delete file: " + file.getName());
            }
        }

        System.out.println("CleanupManager: Deletion finished. Successfully deleted " + deletedCount + " files older than " + days + " days.");

        return deletedCount;
    }

}

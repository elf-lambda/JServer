package org.example;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Scanner;

;

public class ConfigParser {
    public static Config parse(String config_path) throws FileNotFoundException {
        File config = new File(config_path);
        String RECORDING_CLIPS_DIR = "./clips";
        String CAMERA_DEVICE_PATH = "/dev/video98";
        String FFMPEG_LOG_FILE = "./ffmpeg.log";

        if (config.exists()) {
            Scanner scanner = new Scanner(config);
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                String[] tokens = line.split(":");
                String key = tokens[0];
                String value = tokens[1];
                if (tokens.length >= 2) {
                    switch (key) {
                        case "camera_url":
                            CAMERA_DEVICE_PATH = value;
                            break;
                        case "ffmpeg_log_file":
                            FFMPEG_LOG_FILE = value;
                            break;
                        case "recording_clips_dir":
                            RECORDING_CLIPS_DIR = value;
                            break;
                        default:
                            break;
                    }
                }
            }
            scanner.close();
        }

        return new Config(RECORDING_CLIPS_DIR, CAMERA_DEVICE_PATH, FFMPEG_LOG_FILE);
    }
}

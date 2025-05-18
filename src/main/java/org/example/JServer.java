package org.example;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;


import com.fasterxml.jackson.databind.ObjectMapper;

public class JServer {
    private final CopyOnWriteArrayList<OutputStream> streamClients = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final String sourceUrl;
    private final int relayPort;

    private volatile boolean isRecording = false;
    private Process recordingProcess = null;
    private static final String RECORDING_CLIPS_DIR = "./clips";
    private static final String CAMERA_DEVICE_PATH = "/dev/video98";
    private static final String FFMPEG_LOG_FILE = "./ffmpeg.log";
    private final File ffmpegLogFile;
    private long serverStartTimeMillis;
    private long recordingStartTimeMillis = -1;
    private boolean toResetCameraStream = false;

    public JServer(String sourceUrl, int relayPort) {
        this.sourceUrl = sourceUrl;
        this.relayPort = relayPort;

        new java.io.File(RECORDING_CLIPS_DIR).mkdirs();
        this.ffmpegLogFile = new File(FFMPEG_LOG_FILE);

        try {
            if (!ffmpegLogFile.exists()) {
                ffmpegLogFile.createNewFile();
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not create FFmpeg log file at " + FFMPEG_LOG_FILE + ": " + e.getMessage());
        }

        this.serverStartTimeMillis = System.currentTimeMillis();
    }

    public void start() throws Exception {
        // Start background broadcaster
        executor.submit(this::broadcastFrames);

        HttpServer server = HttpServer.create(new InetSocketAddress(relayPort), 0);

        server.createContext("/", this::handleHomepageRequest);
        server.createContext("/style.css", this::handleCssRequest);
        server.createContext("/stream", this::handleStreamRequest);
        server.createContext("/record", this::handleRecordRequest);
        server.createContext("/videos", this::handleVideosRequest);
        server.createContext("/clips/", this::handleClipDownload);
        server.createContext("/statistics", this::handleStatistics);
        server.createContext("/delete", this::handleDelete);
        server.createContext("/reset", this::handleReset);

        // Using shared thread pool for handling requests
        server.setExecutor(executor);
        server.start();

        System.out.println("Relay ready: http://0.0.0.0:" + relayPort);
        System.out.println("Homepage at http://0.0.0.0:" + relayPort + "/");
        System.out.println("Stream at http://0.0.0.0:" + relayPort + "/stream");
        System.out.println("CSS at http://0.0.0.0:" + relayPort + "/style.css");
        System.out.println("Recording control at http://0.0.0.0:" + relayPort + "/record (POST)");
        System.out.println("Videos list at http://0.0.0.0:" + relayPort + "/videos");
        System.out.println("Clips served from http://0.0.0.0:" + relayPort + "/clips/");
        System.out.println("Statistics at http://0.0.0.0:" + relayPort + "/statistics");
        System.out.println("Delete files at http://0.0.0.0:" + relayPort + "/delete");
        System.out.println("Delete files at http://0.0.0.0:" + relayPort + "/reset");


        // Add shutdown hook for graceful exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down server and executor.");
            server.stop(0);
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("Executor did not terminate cleanly.");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }));
    }

    // Reads resources (like static files) from the classpath (src/main/resources)
    private byte[] readResource(String resourcePath) throws IOException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = ClassLoader.getSystemClassLoader();
        }

        // Ensure path is relative to classpath root, exclude leading /
        String cleanResourcePath = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;

        try (InputStream is = classLoader.getResourceAsStream(cleanResourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + cleanResourcePath);
            }

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[1024];
            int nRead;
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            buffer.flush();
            return buffer.toByteArray();
        }
    }

    // Handle resetting MJPEG stream connection
    private void handleReset(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // Body should be reset=true/false
        String requestBody;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            requestBody = reader.readLine();
        }

        String responseMsg;
        int statusCode;

        if (requestBody != null && requestBody.startsWith("reset=")) {
            String resetStr = requestBody.substring("reset=".length()).trim();
            if (resetStr.equals("true")) {
                toResetCameraStream = true;
                responseMsg = "Set camera Reset to True";
                statusCode = 200;
            } else {
                responseMsg = "Set camera Reset to False";
                statusCode = 200;
            }
        } else {
            responseMsg = "Invalid request body. Expected 'reset=true' or 'reset=false'.";
            statusCode = 400;
        }

        byte[] responseBytes = responseMsg.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
            os.flush();
        }
    }


    // Handle deletion of files
    private void handleDelete(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // Body should be days=x
        String requestBody;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            requestBody = reader.readLine();
        }

        String responseMsg;
        int statusCode;

        if (requestBody != null && requestBody.startsWith("days=")) {
            String daysStr = requestBody.substring("days=".length()).trim();
            int days = Integer.parseInt(daysStr);
            int countOfDeletedFiles = CleanupManager.deleteFilesOlderThan(RECORDING_CLIPS_DIR, days);
            if (countOfDeletedFiles == 0) {
                responseMsg = "0 Files deleted.";
                statusCode = 200;
            } else {
                responseMsg = countOfDeletedFiles + " Files deleted";
                statusCode = 200;
            }
        } else {
            responseMsg = "Invalid request body. Expected 'days=start' or 'days=stop'.";
            statusCode = 400;
        }

        byte[] responseBytes = responseMsg.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
            os.flush();
        }


    }

    // Handle statistics of the diskpace where the server is running on
    private void handleStatistics(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        List<Long> diskStatistics = DiskStatistics.getDiskSpaceInfo("/");

        // Create a map to hold the values
        Map<String, Object> diskInfo = new HashMap<>();
        diskInfo.put("totalSpace", diskStatistics.get(0));
        diskInfo.put("totalSpaceFormatted", DiskStatistics.formatSize(diskStatistics.get(0)));
        diskInfo.put("freeSpace", diskStatistics.get(1));
        diskInfo.put("freeSpaceFormatted", DiskStatistics.formatSize(diskStatistics.get(1)));
        diskInfo.put("usableSpace", diskStatistics.get(2));
        diskInfo.put("usableSpaceFormatted", DiskStatistics.formatSize(diskStatistics.get(2)));
        diskInfo.put("serverStartTimeMillis", serverStartTimeMillis);
        diskInfo.put("recordingStartTimeMillis", recordingStartTimeMillis);

        ObjectMapper mapper = new ObjectMapper();
        String jsonResponse = mapper.writeValueAsString(diskInfo);
        byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, responseBytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }

        System.out.println("Sent Statistics to client");
    }

    // Handles requests for the homepage
    private void handleHomepageRequest(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        byte[] htmlBytes;
        try {
            htmlBytes = readResource("index.html");
        } catch (IOException e) {
            System.err.println("Error serving index.html: " + e.getMessage());
            exchange.sendResponseHeaders(500, -1);
            return;
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, htmlBytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(htmlBytes);
        }
    }

    // Handles requests for the CSS file
    private void handleCssRequest(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        byte[] cssBytes;
        try {
            cssBytes = readResource("style.css");
        } catch (IOException e) {
            System.err.println("Error serving style.css: " + e.getMessage());
            exchange.sendResponseHeaders(404, -1);
            return;
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/css; charset=UTF-8");
        exchange.sendResponseHeaders(200, cssBytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(cssBytes);
        }
    }


    // Handles requests for the MJPEG stream (/stream)
    private void handleStreamRequest(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        Headers headers = exchange.getResponseHeaders();

        headers.set("Content-Type", "multipart/x-mixed-replace; boundary=--frame");
        exchange.sendResponseHeaders(200, 0);

        // Get client's output stream
        OutputStream clientStream = exchange.getResponseBody();

        streamClients.add(clientStream);
        System.out.println("Stream client connected. Total stream clients: " + streamClients.size());
    }

    // Handles requests for recording control (/record) - POST
    private void handleRecordRequest(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // Body should be action=start/stop
        String requestBody;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            requestBody = reader.readLine();
        }

        String responseMsg;
        int statusCode;

        if (requestBody != null && requestBody.startsWith("action=")) {
            String action = requestBody.substring("action=".length()).trim();

            if ("start".equals(action)) {
                if (isRecording) {
                    responseMsg = "Recording is already active.";
                    statusCode = 409;
                } else {
                    if (startRecording()) {
                        responseMsg = "Recording started.";
                        statusCode = 200;
                    } else {
                        responseMsg = "Failed to start recording (FFmpeg issue). Check " +
                                        FFMPEG_LOG_FILE + " for details.";
                        statusCode = 500;
                    }
                }
            } else if ("stop".equals(action)) {
                if (!isRecording) {
                    responseMsg = "No recording is currently active.";
                    statusCode = 409;
                } else {
                    stopRecording();
                    responseMsg = "Recording stopped.";
                    statusCode = 200;
                }
            } else {
                responseMsg = "Invalid action. Use 'start' or 'stop'.";
                statusCode = 400;
            }
        } else {
            responseMsg = "Invalid request body. Expected 'action=start' or 'action=stop'.";
            statusCode = 400;
        }

        byte[] responseBytes = responseMsg.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    // Starts the FFMPEG recording process
    private synchronized boolean startRecording() {
        if (isRecording) {
            return false;
        }

        // Build the FFmpeg command
        List<String> command = getFFMPEGCommand();

        String commandString = String.join(" ", command);
        System.out.println("Attempting to start recording: " + commandString);

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new java.io.File("."));
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(ffmpegLogFile));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(ffmpegLogFile));

            recordingProcess = pb.start();
            isRecording = true;
            recordingStartTimeMillis = System.currentTimeMillis();
            System.out.println("Recording process started, output redirected to " + FFMPEG_LOG_FILE);

            executor.submit(() -> monitorRecordingProcess(recordingProcess));

            return true;

        } catch (IOException e) {
            System.err.println("Failed to start FFmpeg process: " + e.getMessage());
            e.printStackTrace();
            recordingProcess = null;
            isRecording = false;
            recordingStartTimeMillis = -1;
            return false;
        }

    }

    // Utility
    private static List<String> getFFMPEGCommand() {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-nostdin");
        command.add("-f");
        command.add("v4l2");
        command.add("-framerate");
        command.add("30");
        command.add("-video_size");
        command.add("1280x720");
        command.add("-i");
        command.add(JServer.CAMERA_DEVICE_PATH);

        command.add("-c:v");
        command.add("h264_v4l2m2m");

        command.add("-crf");
        command.add("0");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-b:v");
        command.add("1M");

        command.add("-f");
        command.add("segment");
        command.add("-reset_timestamps");
        command.add("1");
        command.add("-segment_time");
        command.add("1800");
        command.add("-segment_format");
        command.add("mkv");
        command.add("-segment_atclocktime");
        command.add("1");
        command.add("-strftime");
        command.add("1");

        command.add(JServer.RECORDING_CLIPS_DIR + "/%Y%m%dT%H%M%S.mkv");
        return command;
    }

    // Stops the FFmpeg recording process
    private synchronized void stopRecording() {
        if (recordingProcess != null && recordingProcess.isAlive()) {
            System.out.println("Stopping recording process...");

            recordingProcess.destroyForcibly();
        } else {
            System.out.println("No active recording process to stop.");
        }
    }

    // Watches FFMPEG Thread process
    private void monitorRecordingProcess(Process process) {
        try {
            int exitCode = process.waitFor();
            System.out.println("FFMPEG Thread finished with exit code " + exitCode);

            if (exitCode != 0) {
                System.err.println("FFMPEG Thread exited with non-zero status (" + exitCode + "), indicating a potential error during recording.");
                System.err.println("Check the log file " + FFMPEG_LOG_FILE + " for FFmpeg errors.");
            }

        } catch (InterruptedException e) {
            System.out.println("FFMPEG Thread monitor interrupted while waiting.");
            Thread.currentThread().interrupt();
        } finally {

            synchronized (this) {
                if (recordingProcess == process) {
                    System.out.println("Resetting recording state.");
                    isRecording = false;
                    recordingProcess = null;
                } else {
                    System.out.println("Monitor finished for an older FFmpeg process instance.");
                }
            }
        }
    }

    // Reads frames from the source URL and broadcasts to connected clients
    private void broadcastFrames() {
        while (!Thread.currentThread().isInterrupted()) {
            // Connect to C Server
            try (InputStream source = new URL(sourceUrl).openStream()) {
                System.out.println("Source stream connected: " + sourceUrl);
                byte[] buffer = new byte[8192];
                int bytesRead;

                // Read source stream and broadcast to all clients
                while ((bytesRead = source.read(buffer)) != -1 && !Thread.currentThread().isInterrupted() && !toResetCameraStream) {
                    final byte[] frameChunk = Arrays.copyOf(buffer, bytesRead);

                    List<OutputStream> currentClients = new ArrayList<>(streamClients);
                    for (OutputStream client : currentClients) {
                        try {
                            // Streaming the frame chunks happens here
                            client.write(frameChunk);
                            client.flush();
                        } catch (IOException e) {
                            // Remove client if write fails
                            System.out.println("Stream client disconnected or write error: " + e.getMessage());
                            streamClients.remove(client);
                            try { client.close(); } catch (IOException closeException) { /* ignore */ }
                        }
                    }
                }
                toResetCameraStream = false;
                System.out.println("Set Reset Camera to false");
                System.out.println("Source stream ended.");
            } catch (IOException e) {
                System.err.println("Error reading source URL: " + sourceUrl + " - " + e.getMessage());
            } catch (Exception e) {
                System.err.println("Unexpected error in broadcaster: " + e.getMessage());
                e.printStackTrace();
            }

            try {
                if (!Thread.currentThread().isInterrupted()) {
                    System.out.println("Attempting source reconnect in 1s...");
                    TimeUnit.SECONDS.sleep(1);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                System.out.println("Broadcaster interrupted.");
            }
        }
        System.out.println("Broadcaster thread exiting.");
    }

    // Handles requests for the videos listing page
    private void handleVideosRequest(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        File clipsDir = new File(RECORDING_CLIPS_DIR);
        // Use a filter to only get files ending with .mkv (case-insensitive)
        File[] videoFiles = clipsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".mkv"));

        StringBuilder htmlBuilder = new StringBuilder();
        htmlBuilder.append("<!DOCTYPE html>")
                   .append("<html lang=\"en\">")
                   .append("<head>")
                   .append("<meta charset=\"UTF-8\">")
                   .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
                   .append("<title>Recorded Videos</title>")
                   .append("<link rel=\"stylesheet\" href=\"/style.css\">")
                   .append("</head>")
                   .append("<body>")
                   .append("<div class=\"container\">")
                   .append("<h1>Recorded Videos</h1>");

        if (videoFiles != null && videoFiles.length > 0) {
            // Sort files by name for chronological order
            Arrays.sort(videoFiles);
            htmlBuilder.append("<ul>");
            for (File file : videoFiles) {
                String filename = file.getName();
                // Create the download URL using the /clips/ prefix and URL-encoded filename
                String downloadUrl = "/clips/" + URLEncoder.encode(filename, StandardCharsets.UTF_8.toString());
                htmlBuilder.append("<li>")
                           .append(filename)
                           .append(" (<a href=\"").append(downloadUrl).append("\">Download</a>)")
                           .append("</li>");
            }
            htmlBuilder.append("</ul>");
        } else {
            htmlBuilder.append("<p>No recorded videos found yet.</p>");
        }

        htmlBuilder.append("<p><a href=\"/\">Back to Homepage</a></p>");
        htmlBuilder.append("</div>");
        htmlBuilder.append("</body></html>");

        byte[] htmlBytes = htmlBuilder.toString().getBytes(StandardCharsets.UTF_8);

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, htmlBytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(htmlBytes);
        }

        System.out.println("Served /videos request");
    }

    // Handles requests for downloading individual video clips
    private void handleClipDownload(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // Get the requested path from the URI (e.g., "/clips/ 20250513T000000.mkv.mkv")
        String requestedPath = exchange.getRequestURI().getPath();
        // Extract the filename by removing the "/clips/" prefix
        // Add a safety check for path length
        String filename;
        if (requestedPath.startsWith("/clips/")) {
            // Ensure there is a filename after the prefix
            if (requestedPath.length() == "/clips/".length()) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            filename = requestedPath.substring("/clips/".length());
        } else {
            // Don't think this can be reached
            exchange.sendResponseHeaders(404, -1);
            return;
        }

        // Construct the file path relative to the clips directory
        File requestedFile = new File(RECORDING_CLIPS_DIR, filename);

        // Get the canonical path of the clips directory
        String clipsCanonicalPath;
        try {
            clipsCanonicalPath = new File(RECORDING_CLIPS_DIR).getCanonicalPath();
        } catch (IOException e) {
            System.err.println("Error getting canonical path for clips directory: " + e.getMessage());
            exchange.sendResponseHeaders(500, -1); // Internal Server Error
            return;
        }

        // Get the canonical path of the requested file
        String requestedFileCanonicalPath;
        try {
            requestedFileCanonicalPath = requestedFile.getCanonicalPath();
        } catch (IOException e) {
            System.err.println("Error getting canonical path for requested file " + filename + ": " + e.getMessage());
            exchange.sendResponseHeaders(400, -1); // Bad Request (e.g. malformed path)
            return;
        }

        // Check if the canonical path of the requested file starts with the canonical path
        // of the clips directory. This prevents requesting files outside the clips directory
        // using "../" or similar tricks. Add File.separator to prevent matching similarly named
        // directories (e.g. /clips_ malicious/../clips/file).
        if (!requestedFileCanonicalPath.startsWith(clipsCanonicalPath + File.separator)) {
            System.err.println("Attempted path traversal detected: " + requestedPath);
            exchange.sendResponseHeaders(403, -1);
            return;
        }

        // Also check if the requested item is actually a file and exists
        if (!requestedFile.isFile() || !requestedFile.exists()) {
            System.err.println("Requested clip not found or is not a file: " + requestedPath);
            exchange.sendResponseHeaders(404, -1);
            return;
        }

        String contentType = "video/x-matroska";

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);

        headers.set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        headers.set("Content-Length", String.valueOf(requestedFile.length()));

        exchange.sendResponseHeaders(200, requestedFile.length());

        // Stream the file content to the response body
        try (InputStream is = Files.newInputStream(requestedFile.toPath());
             OutputStream os = exchange.getResponseBody()) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            os.flush();

        } catch (IOException e) {
            System.err.println("Error streaming clip " + filename + ": " + e.getMessage());
        } finally {
            System.out.println("Served clip for download: " + filename);
        }
    }
}
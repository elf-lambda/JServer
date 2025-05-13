package org.example;

public class Main {

    private static final String SOURCE_URL = "http://localhost:8080";
    private static final int RELAY_PORT = 9090;


    public static void main(String[] args) throws Exception {
        JServer srv = new JServer(SOURCE_URL, RELAY_PORT);
        srv.start();
        System.out.println("JServer started. Press Ctrl+C to stop.");
    }

}

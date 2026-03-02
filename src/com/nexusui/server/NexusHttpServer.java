package com.nexusui.server;

import org.apache.log4j.Logger;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NexusHttpServer - Lightweight embedded HTTP server for NexusUI.
 *
 * Serves static files from an in-memory cache (loaded via Starsector's loadText API)
 * and provides REST API endpoints for game data. No java.io.File usage (blocked
 * by Starsector's security sandbox).
 *
 * Binds to 127.0.0.1 only (localhost) for security.
 */
public class NexusHttpServer {

    private static final Logger log = Logger.getLogger(NexusHttpServer.class);

    public interface ApiHandler {
        String handle(String method, String path, Map<String, String> headers);
    }

    private static NexusHttpServer instance;

    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean running;
    private int port;

    // In-memory web content cache (path -> content)
    private final ConcurrentHashMap<String, byte[]> staticContent = new ConcurrentHashMap<String, byte[]>();

    // API handlers
    private final ConcurrentHashMap<String, ApiHandler> apiHandlers = new ConcurrentHashMap<String, ApiHandler>();

    // Content type mappings
    private static final Map<String, String> CONTENT_TYPES = new HashMap<String, String>();
    static {
        CONTENT_TYPES.put("html", "text/html; charset=utf-8");
        CONTENT_TYPES.put("htm", "text/html; charset=utf-8");
        CONTENT_TYPES.put("css", "text/css; charset=utf-8");
        CONTENT_TYPES.put("js", "application/javascript; charset=utf-8");
        CONTENT_TYPES.put("json", "application/json; charset=utf-8");
        CONTENT_TYPES.put("png", "image/png");
        CONTENT_TYPES.put("jpg", "image/jpeg");
        CONTENT_TYPES.put("svg", "image/svg+xml");
        CONTENT_TYPES.put("ico", "image/x-icon");
    }

    public static NexusHttpServer getInstance() {
        return instance;
    }

    public NexusHttpServer(int port) {
        this.port = port;
        instance = this;
    }

    /** Load web content from a map of path -> string content. */
    public void setStaticContent(Map<String, String> files) {
        for (Map.Entry<String, String> entry : files.entrySet()) {
            try {
                staticContent.put(entry.getKey(), entry.getValue().getBytes("UTF-8"));
            } catch (UnsupportedEncodingException e) {
                staticContent.put(entry.getKey(), entry.getValue().getBytes());
            }
        }
    }

    /** Add a single web file to the content cache. */
    public void addStaticContent(String path, String content) {
        try {
            staticContent.put(path, content.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            staticContent.put(path, content.getBytes());
        }
    }

    /** Register a custom API handler for a path prefix. */
    public void registerHandler(String pathPrefix, ApiHandler handler) {
        apiHandlers.put(pathPrefix, handler);
    }

    /** Start the HTTP server on a background thread. */
    public void start() {
        if (running) return;
        running = true;
        threadPool = Executors.newCachedThreadPool(new java.util.concurrent.ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("NexusUI-HTTP");
                return t;
            }
        });

        Thread serverThread = new Thread(new Runnable() {
            public void run() {
                try {
                    serverSocket = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
                    log.info("NexusUI: HTTP server started on http://127.0.0.1:" + port);
                    while (running) {
                        try {
                            final Socket client = serverSocket.accept();
                            client.setSoTimeout(10000);
                            threadPool.submit(new Runnable() {
                                public void run() {
                                    handleClient(client);
                                }
                            });
                        } catch (IOException e) {
                            if (running) log.warn("NexusUI: Accept error: " + e.getMessage());
                        }
                    }
                } catch (IOException e) {
                    log.error("NexusUI: Failed to start server on port " + port + ": " + e.getMessage());
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.setName("NexusUI-Server");
        serverThread.start();
    }

    /** Stop the HTTP server. */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            // Ignore
        }
        if (threadPool != null) {
            threadPool.shutdownNow();
        }
        log.info("NexusUI: HTTP server stopped");
    }

    public boolean isRunning() { return running; }
    public int getPort() { return port; }

    private void handleClient(Socket client) {
        try {
            InputStream rawIn = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // Read request line and headers manually (no BufferedReader wrapping issues)
            StringBuilder reqBuilder = new StringBuilder();
            int b;
            while ((b = rawIn.read()) != -1) {
                reqBuilder.append((char) b);
                // Detect end of headers (\r\n\r\n)
                String built = reqBuilder.toString();
                if (built.endsWith("\r\n\r\n") || built.endsWith("\n\n")) break;
                if (built.length() > 8192) break; // Safety limit
            }

            String rawRequest = reqBuilder.toString();
            if (rawRequest.isEmpty()) return;

            String[] lines = rawRequest.split("\r\n|\n");
            if (lines.length == 0) return;

            String[] requestParts = lines[0].split(" ");
            if (requestParts.length < 2) return;

            String method = requestParts[0];
            String rawPath = requestParts[1];

            // Parse headers
            Map<String, String> headers = new HashMap<String, String>();
            for (int i = 1; i < lines.length; i++) {
                int colonPos = lines[i].indexOf(':');
                if (colonPos > 0) {
                    headers.put(
                        lines[i].substring(0, colonPos).trim().toLowerCase(),
                        lines[i].substring(colonPos + 1).trim()
                    );
                }
            }

            // CORS preflight
            if ("OPTIONS".equals(method)) {
                sendCorsResponse(out);
                return;
            }

            // Strip query string and decode
            String path = rawPath;
            int queryIdx = path.indexOf('?');
            if (queryIdx >= 0) path = path.substring(0, queryIdx);
            try { path = URLDecoder.decode(path, "UTF-8"); } catch (Exception e) { }

            // Route
            if (path.startsWith("/api/")) {
                handleApiRequest(method, path, headers, out);
            } else {
                handleFileRequest(path, out);
            }

        } catch (Exception e) {
            // Connection reset, timeout, etc - normal for HTTP
        } finally {
            try { client.close(); } catch (IOException e) { }
        }
    }

    private void handleApiRequest(String method, String path, Map<String, String> headers, OutputStream out) throws IOException {
        // Find matching handler (longest prefix match)
        String bestMatch = null;
        for (String prefix : apiHandlers.keySet()) {
            if (path.startsWith(prefix)) {
                if (bestMatch == null || prefix.length() > bestMatch.length()) {
                    bestMatch = prefix;
                }
            }
        }

        if (bestMatch != null) {
            try {
                String responseBody = apiHandlers.get(bestMatch).handle(method, path, headers);
                if (responseBody != null) {
                    sendJsonResponse(out, 200, responseBody);
                    return;
                }
            } catch (Exception e) {
                sendError(out, 500, "Internal Server Error");
                return;
            }
        }

        sendError(out, 404, "API endpoint not found: " + path);
    }

    private void handleFileRequest(String path, OutputStream out) throws IOException {
        if ("/".equals(path)) path = "/index.html";

        // Security: prevent traversal
        if (path.contains("..")) {
            sendError(out, 403, "Forbidden");
            return;
        }

        // Look up in memory cache
        byte[] content = staticContent.get(path);
        if (content == null) {
            sendError(out, 404, "Not Found: " + path);
            return;
        }

        String ext = getFileExtension(path);
        String contentType = CONTENT_TYPES.get(ext);
        if (contentType == null) contentType = "text/plain; charset=utf-8";

        sendResponse(out, 200, contentType, content);
    }

    // ========================================================================
    // Response helpers
    // ========================================================================

    private void sendResponse(OutputStream out, int statusCode, String contentType, byte[] body) throws IOException {
        String statusText = statusCode == 200 ? "OK" : statusCode == 404 ? "Not Found" : "Error";
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n");
        header.append("Content-Type: ").append(contentType).append("\r\n");
        header.append("Content-Length: ").append(body.length).append("\r\n");
        header.append("Connection: close\r\n");
        header.append("Access-Control-Allow-Origin: *\r\n");
        header.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        header.append("Access-Control-Allow-Headers: Content-Type\r\n");
        header.append("Cache-Control: no-cache\r\n");
        header.append("\r\n");
        out.write(header.toString().getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private void sendJsonResponse(OutputStream out, int statusCode, String json) throws IOException {
        sendResponse(out, statusCode, "application/json; charset=utf-8", json.getBytes("UTF-8"));
    }

    private void sendError(OutputStream out, int statusCode, String message) throws IOException {
        String json = "{\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        sendJsonResponse(out, statusCode, json);
    }

    private void sendCorsResponse(OutputStream out) throws IOException {
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 204 No Content\r\n");
        header.append("Access-Control-Allow-Origin: *\r\n");
        header.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        header.append("Access-Control-Allow-Headers: Content-Type\r\n");
        header.append("Access-Control-Max-Age: 86400\r\n");
        header.append("Connection: close\r\n");
        header.append("\r\n");
        out.write(header.toString().getBytes("UTF-8"));
        out.flush();
    }

    private String getFileExtension(String path) {
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && dot < path.length() - 1) {
            return path.substring(dot + 1).toLowerCase();
        }
        return "";
    }
}

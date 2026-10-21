package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.config.ServerConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class ApiServer {

    private static final int PORT = ServerConfig.HTTP_PORT;

    public static void startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

            server.createContext("/", new StaticFileHandler());
            server.createContext("/api/auth", new CorsHandlerWrapper(new AuthHandler()));
            server.createContext("/api/mails", new CorsHandlerWrapper(new MailHandler()));
            server.createContext("/api/admin", new CorsHandlerWrapper(new AdminHandler()));

            server.setExecutor(Executors.newFixedThreadPool(ServerConfig.THREAD_POOL_SIZE));
            server.start();
            System.out.println("=== Web API Server running at: http://localhost:" + PORT + " ===");

        } catch (IOException e) {
            System.err.println("Error starting Web Server: " + e.getMessage());
        }
    }

    static class CorsHandlerWrapper implements HttpHandler {
        private final HttpHandler handler;
        private static final String ALLOWED_ORIGIN = System.getenv().getOrDefault("PBL4_ALLOWED_ORIGIN", "http://localhost:8080");

        public CorsHandlerWrapper(HttpHandler handler) {
            this.handler = handler;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");

            exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com https://fonts.googleapis.com; font-src 'self' https://cdnjs.cloudflare.com https://fonts.gstatic.com; img-src 'self' data:;");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
            exchange.getResponseHeaders().set("X-XSS-Protection", "1; mode=block");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            handler.handle(exchange);
        }
    }

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            
            if (path.equals("/") || path.equals("/index") || path.equals("/index.html")) {
                path = "/index.html";
            } else if (path.equals("/main") || path.equals("/main.html")) {
                path = "/main.html";
            } else if (path.equals("/admin") || path.equals("/admin.html")) {
                path = "/admin.html";
            }

            String resourcePath = "/static" + path;
            InputStream is = getClass().getResourceAsStream(resourcePath);

            if (is != null) {
                byte[] bytes = is.readAllBytes();
                String contentType = getContentType(path);
                
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com https://fonts.googleapis.com; font-src 'self' https://cdnjs.cloudflare.com https://fonts.gstatic.com; img-src 'self' data:;");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
                exchange.getResponseHeaders().set("X-XSS-Protection", "1; mode=block");

                exchange.sendResponseHeaders(200, bytes.length);
                
                OutputStream os = exchange.getResponseBody();
                os.write(bytes);
                os.close();
                is.close();
            } else {
                String response = "404 Not Found";
                exchange.sendResponseHeaders(404, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        }

        private String getContentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=UTF-8";
            if (path.endsWith(".css")) return "text/css; charset=UTF-8";
            if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
            if (path.endsWith(".png")) return "image/png";
            if (path.endsWith(".jpg")) return "image/jpeg";
            if (path.endsWith(".svg")) return "image/svg+xml";
            return "text/plain";
        }
    }
}
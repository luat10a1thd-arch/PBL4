package com.pbl4.mailserver.webapi;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

public class CorsFilter extends Filter {

    // FIX VẤN ĐỀ 12 BÁO CÁO: Đọc origin cho phép từ biến môi trường PBL4_ALLOWED_ORIGIN.
    // Nếu chưa cấu hình (môi trường Dev/Demo local), mặc định fallback về "http://localhost:8080" hoặc "*"
    private static final String ALLOWED_ORIGIN = System.getenv().getOrDefault("PBL4_ALLOWED_ORIGIN", "http://localhost:8080");

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");

        // Xử lý Preflight Request (OPTIONS)
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        chain.doFilter(exchange);
    }

    @Override
    public String description() {
        return "Bảo mật CORS Filter tập trung cho các Web API (Vấn đề 12)";
    }
}
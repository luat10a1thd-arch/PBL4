// ============================================================
// File: CorsFilter.java
// Package: com.pbl4.mailserver.webapi
// ------------------------------------------------------------
// Chức năng: Thêm header CORS vào mọi response, để file HTML
// (chạy từ origin khác, VD mở trực tiếp bằng file:// hoặc
// server khác) vẫn gọi được API mà không bị trình duyệt chặn.
// ============================================================

package com.pbl4.mailserver.webapi;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

public class CorsFilter extends Filter {

    @Override
    public String description() {
        return "Thêm CORS header cho phép frontend gọi API";
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        // Trình duyệt gửi request "OPTIONS" trước (preflight) để hỏi server
        // có cho phép gọi không - chỉ cần trả 204 No Content là đủ, không
        // cần chuyển tiếp (chain.doFilter) tới handler thật.
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        chain.doFilter(exchange);
    }
}
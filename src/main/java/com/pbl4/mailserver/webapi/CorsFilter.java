package com.pbl4.mailserver.webapi;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * Bộ lọc CORS giúp Web API chấp nhận các yêu cầu từ Frontend giao tiếp trơn tru.
 */
public class CorsFilter extends Filter {

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
        
        // Cho phép xử lý tiếp request
        chain.doFilter(exchange);
    }

    @Override
    public String description() {
        return "CORS Filter for HTTP Server";
    }
}
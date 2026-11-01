package com.pbl4.mailserver.smtp;

import com.pbl4.mailserver.config.ServerConfig;

/**
 * Cổng SMTP dev/test cũ (SMTP_PORT, mặc định 2525) chạy SubmissionHandler qua SmtpListener.
 * Giữ nguyên tên startServer()/stopServer(). Có thể start lại sau khi stop.
 */
public class SmtpServerManager {

    private static SmtpListener listener;

    public static synchronized void startServer() {
        if (listener != null && listener.isRunning()) {
            return;
        }
        listener = new SmtpListener("smtp-" + ServerConfig.SMTP_PORT, ServerConfig.SMTP_PORT, false, SubmissionHandler::new);
        listener.start();
    }

    public static synchronized void stopServer() {
        if (listener != null) {
            listener.stop();
            listener = null;
        }
    }
}
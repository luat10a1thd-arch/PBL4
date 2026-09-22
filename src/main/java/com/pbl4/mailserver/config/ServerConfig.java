package com.pbl4.mailserver.config;

public class ServerConfig {
    public static final int SMTP_PORT = 2525;
    public static final int POP3_PORT = 1110;
    public static final int WEB_API_PORT = 8080;
    
    public static final String MAIL_STORAGE_PATH = "data/mailboxes/";
    public static final String USERS_FILE_PATH = "data/users.json";
}
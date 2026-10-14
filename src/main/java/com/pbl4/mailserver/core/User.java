package com.pbl4.mailserver.core;

public class User {
    private String username;
    private String passwordHash;
    private String salt;   // Base64 salt
    private String status; // "ACTIVE" hoặc "LOCKED"
    private String role;   // "ADMIN" hoặc "USER"

    public User() {}

    public User(String username, String passwordHash, String salt, String status, String role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.salt = salt;
        this.status = status;
        this.role = role;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getSalt() { return salt; }
    public void setSalt(String salt) { this.salt = salt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
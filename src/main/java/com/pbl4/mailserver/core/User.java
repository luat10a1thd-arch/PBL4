// FILE: src/main/java/com/pbl4/mailserver/core/User.java
package com.pbl4.mailserver.core;

public class User {
    private String username;
    private String passwordHash;
    private String salt;
    private String status;
    private String role;
    /** Thời điểm tạo tài khoản (epoch ms). 0 = không biết (tài khoản tạo trước khi có trường này). */
    private long createdAt;

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

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return "User{username='" + username + "', status='" + status + "', role='" + role + "'}";
    }
}
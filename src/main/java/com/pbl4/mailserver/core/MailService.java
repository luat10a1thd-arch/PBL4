package com.pbl4.mailserver.core;

import java.text.SimpleDateFormat;
import java.util.*;

public class MailService {

    public static class DeliveryResult {
        public final boolean success;
        public final int successCount;
        public final int failCount;
        public final String message;

        public DeliveryResult(boolean success, int successCount, int failCount, String message) {
            this.success = success;
            this.successCount = successCount;
            this.failCount = failCount;
            this.message = message;
        }
    }

    public static DeliveryResult deliver(String sender, String recipientsRaw, String subject, String body) {
        if (sender == null || recipientsRaw == null || subject == null || body == null) {
            return new DeliveryResult(false, 0, 0, "Thiếu thông tin gửi thư!");
        }

        String safeSubject = subject.replace('\r', ' ').replace('\n', ' ').trim();
        String[] recipientsArr = recipientsRaw.trim().split("[\\s,;]+");
        
        Set<String> validRecipients = new LinkedHashSet<>();
        int failCount = 0;

        for (String to : recipientsArr) {
            to = to.trim();
            if (to.isEmpty()) continue;

            String canonicalTo = UserStore.canonical(to);
            if (canonicalTo != null && UserStore.exists(canonicalTo)) {
                validRecipients.add(canonicalTo);
            } else {
                failCount++;
            }
        }

        if (validRecipients.isEmpty()) {
            return new DeliveryResult(false, 0, failCount, "Gửi thư thất bại cho toàn bộ người nhận!");
        }

        String canonicalSender = UserStore.canonical(sender);
        if (canonicalSender == null) {
            canonicalSender = sender.toLowerCase(Locale.ROOT);
        }

        String dateStr = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH).format(new Date());
        String fullToHeader = String.join(", ", validRecipients);

        String rawEmail = "From: " + canonicalSender + "\n" +
                         "To: " + fullToHeader + "\n" +
                         "Subject: " + safeSubject + "\n" +
                         "Date: " + dateStr + "\n\n" +
                         body;

        int successCount = 0;
        for (String canonicalTo : validRecipients) {
            boolean savedRecipient = MailStorageEngine.saveEmail(canonicalTo, rawEmail);
            if (savedRecipient) {
                successCount++;
            } else {
                failCount++;
            }
        }

        if (successCount > 0) {
            boolean savedSent = MailStorageEngine.saveEmail(canonicalSender + "_sent", rawEmail);
            if (!savedSent) {
                System.err.println("[MAIL SERVICE] Không thể lưu bản sao _sent cho người gửi: " + canonicalSender);
            }

            String msg = "Đã gửi thư thành công tới " + successCount + " người nhận!";
            if (failCount > 0) msg += " (" + failCount + " người nhận thất bại)";
            return new DeliveryResult(true, successCount, failCount, msg);
        } else {
            return new DeliveryResult(false, 0, failCount, "Gửi thư thất bại cho toàn bộ người nhận!");
        }
    }

    public static DeliveryResult deliverRaw(String sender, Set<String> recipients, String rawMessage) {
        if (sender == null || recipients == null || recipients.isEmpty() || rawMessage == null) {
            return new DeliveryResult(false, 0, 0, "Thiếu thông tin gửi thư thô!");
        }

        String extractedSubject = "No Subject";
        String[] lines = rawMessage.split("\n");
        StringBuilder bodyBuilder = new StringBuilder();
        boolean inHeaders = true;

        for (String line : lines) {
            if (inHeaders) {
                if (line.trim().isEmpty()) {
                    inHeaders = false;
                    continue;
                }
                if (line.toLowerCase().startsWith("subject:")) {
                    extractedSubject = line.substring(8).trim();
                }
            } else {
                bodyBuilder.append(line).append("\n");
            }
        }

        String body = bodyBuilder.length() > 0 ? bodyBuilder.toString() : rawMessage;
        String recipientsRaw = String.join(",", recipients);

        return deliver(sender, recipientsRaw, extractedSubject, body);
    }

    public static List<String> listInbox(String username) {
        String canonicalUser = UserStore.canonical(username);
        return MailStorageEngine.readAllEmails(canonicalUser != null ? canonicalUser : username);
    }

    public static List<String> listSent(String username) {
        String canonicalUser = UserStore.canonical(username);
        String target = canonicalUser != null ? canonicalUser + "_sent" : username + "_sent";
        return MailStorageEngine.readAllEmails(target);
    }
}
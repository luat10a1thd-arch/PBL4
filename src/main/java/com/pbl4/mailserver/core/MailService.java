package com.pbl4.mailserver.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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
        String[] recipients = recipientsRaw.trim().split("[\\s,;]+");
        String dateStr = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH).format(new Date());

        int successCount = 0;
        int failCount = 0;

        for (String to : recipients) {
            to = to.trim();
            if (to.isEmpty()) continue;

            String canonicalTo = UserStore.canonical(to);
            if (canonicalTo == null || !UserStore.exists(canonicalTo)) {
                failCount++;
                continue;
            }

            String canonicalSender = UserStore.canonical(sender);
            if (canonicalSender == null) {
                canonicalSender = sender.toLowerCase(Locale.ROOT);
            }

            String rawEmail = "From: " + canonicalSender + "\n" +
                             "To: " + canonicalTo + "\n" +
                             "Subject: " + safeSubject + "\n" +
                             "Date: " + dateStr + "\n\n" +
                             body;

            boolean savedRecipient = MailStorageEngine.saveEmail(canonicalTo, rawEmail);

            if (savedRecipient) {
                successCount++;
                boolean savedSent = MailStorageEngine.saveEmail(canonicalSender + "_sent", rawEmail);
                if (!savedSent) {
                    System.err.println("[MAIL SERVICE] Không thể lưu bản sao _sent cho người gửi: " + canonicalSender);
                }
            } else {
                failCount++;
            }
        }

        if (successCount > 0) {
            String msg = "Đã gửi thư thành công tới " + successCount + " người nhận!";
            if (failCount > 0) msg += " (" + failCount + " người nhận thất bại)";
            return new DeliveryResult(true, successCount, failCount, msg);
        } else {
            return new DeliveryResult(false, 0, failCount, "Gửi thư thất bại cho toàn bộ người nhận!");
        }
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
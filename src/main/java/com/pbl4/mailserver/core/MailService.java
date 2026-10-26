package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;

import java.text.SimpleDateFormat;
import java.util.*;

public class MailService {

    public static class DeliveryResult {
        public final boolean success;
        public final int successCount;
        public final int failCount;
        public final String message;
        // true khi MỌI người nhận nội bộ đều thất bại do hòm thư đầy (OVER_QUOTA) -> tầng SMTP có thể trả 552
        public final boolean quotaExceeded;

        public DeliveryResult(boolean success, int successCount, int failCount, String message) {
            this(success, successCount, failCount, message, false);
        }

        public DeliveryResult(boolean success, int successCount, int failCount, String message, boolean quotaExceeded) {
            this.success = success;
            this.successCount = successCount;
            this.failCount = failCount;
            this.message = message;
            this.quotaExceeded = quotaExceeded;
        }
    }

    public static DeliveryResult deliver(String sender, String recipientsRaw, String subject, String body) {
        if (sender == null || recipientsRaw == null || subject == null || body == null) {
            return new DeliveryResult(false, 0, 0, "Thiếu thông tin gửi thư!");
        }

        String safeSubject = subject.replace('\r', ' ').replace('\n', ' ').trim();
        String[] recipientsArr = recipientsRaw.trim().split("[\\s,;]+");

        Set<String> allRecipients = new LinkedHashSet<>();
        for (String to : recipientsArr) {
            to = to.trim();
            if (!to.isEmpty()) {
                allRecipients.add(to);
            }
        }

        if (allRecipients.isEmpty()) {
            return new DeliveryResult(false, 0, 0, "Địa chỉ người nhận trống!");
        }

        String canonicalSender = UserStore.canonical(sender);
        if (canonicalSender == null) {
            canonicalSender = sender.toLowerCase(Locale.ROOT);
        }

        String dateStr = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH).format(new Date());
        String fullToHeader = String.join(", ", allRecipients);

        String rawEmail = "From: " + canonicalSender + "\n" +
                         "To: " + fullToHeader + "\n" +
                         "Subject: " + safeSubject + "\n" +
                         "Date: " + dateStr + "\n" +
                         "MIME-Version: 1.0\n" +
                         "Content-Type: text/plain; charset=UTF-8\n\n" +
                         body;

        return deliverRaw(canonicalSender, allRecipients, rawEmail);
    }

    public static DeliveryResult deliverRaw(String sender, Set<String> recipients, String rawMessage) {
        if (sender == null || recipients == null || recipients.isEmpty() || rawMessage == null) {
            return new DeliveryResult(false, 0, 0, "Thiếu thông tin gửi thư thô!");
        }

        String canonicalSender = UserStore.canonical(sender);
        if (canonicalSender == null) {
            canonicalSender = sender.toLowerCase(Locale.ROOT);
        }

        Set<String> localRecipients = new LinkedHashSet<>();
        Set<String> externalRecipients = new LinkedHashSet<>();
        int failCount = 0;

        for (String rcpt : recipients) {
            if (rcpt == null || rcpt.trim().isEmpty()) continue;
            String cleanRcpt = rcpt.trim().toLowerCase(Locale.ROOT);

            if (isLocalDomain(cleanRcpt)) {
                String canonicalRcpt = UserStore.canonical(cleanRcpt);
                if (canonicalRcpt != null && UserStore.exists(canonicalRcpt)) {
                    localRecipients.add(canonicalRcpt);
                } else {
                    failCount++;
                }
            } else {
                externalRecipients.add(cleanRcpt);
            }
        }

        int successTotal = 0;
        int failTotal = failCount;
        int quotaFailCount = 0;
        boolean quotaExceeded = false;
        List<String> messages = new ArrayList<>();

        if (!localRecipients.isEmpty()) {
            int localSuccess = 0;
            for (String canonicalTo : localRecipients) {
                MailStorageEngine.SaveResult saveResult = MailStorageEngine.saveEmailResult(canonicalTo, rawMessage);
                if (saveResult == MailStorageEngine.SaveResult.OK) {
                    localSuccess++;
                } else {
                    failTotal++;
                    if (saveResult == MailStorageEngine.SaveResult.OVER_QUOTA) {
                        quotaFailCount++;
                    }
                }
            }
            // Mọi người nhận nội bộ (kể cả địa chỉ không tồn tại) đều thất bại và tất cả đều do vượt quota
            quotaExceeded = (failCount == 0 && quotaFailCount == localRecipients.size());
            if (localSuccess > 0) {
                successTotal += localSuccess;
                messages.add("Đã lưu nội bộ cho " + localSuccess + " người nhận");
            }
        }

        // Lưu bản sao thư đã gửi đồng bộ ngay lập tức
        MailStorageEngine.saveEmail(canonicalSender + "_sent", rawMessage);

        if (!externalRecipients.isEmpty()) {
            boolean enqueued = OutboundQueue.enqueue(canonicalSender, externalRecipients, rawMessage);
            if (enqueued) {
                successTotal += externalRecipients.size();
                messages.add("Thư gửi ra ngoài đã được xếp hàng (" + externalRecipients.size() + " người nhận)");
            } else {
                failTotal += externalRecipients.size();
                messages.add("Hàng đợi gửi thư ra ngoài đã đầy, vui lòng thử lại sau!");
            }
        }

        // Nêu rõ số người nhận thất bại (và bao nhiêu trong số đó do hòm thư đầy)
        if (failTotal > 0) {
            String failMsg = "Thất bại " + failTotal + " người nhận";
            if (quotaFailCount > 0) {
                failMsg += " (" + quotaFailCount + " hòm thư đã đầy dung lượng)";
            }
            messages.add(failMsg);
        }

        if (successTotal > 0) {
            return new DeliveryResult(true, successTotal, failTotal, String.join(" | ", messages), quotaExceeded);
        } else {
            return new DeliveryResult(false, 0, failTotal, messages.isEmpty() ? "Gửi thư thất bại!" : String.join(" | ", messages), quotaExceeded);
        }
    }

    private static boolean isLocalDomain(String email) {
        if (!email.contains("@")) return true;
        String domain = email.substring(email.indexOf('@') + 1).trim();
        return domain.equalsIgnoreCase(ServerConfig.MAIL_DOMAIN) || domain.equalsIgnoreCase("localhost");
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

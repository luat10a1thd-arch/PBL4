package com.pbl4.mailserver.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class OutboundQueue {

    public static class OutboundTask {
        public final String taskId;
        public final String sender;
        public final Set<String> externalRecipients;
        public final String rawMessage;
        public int retries;

        public OutboundTask(String taskId, String sender, Set<String> externalRecipients, String rawMessage, int retries) {
            this.taskId = taskId;
            this.sender = sender;
            this.externalRecipients = externalRecipients;
            this.rawMessage = rawMessage;
            this.retries = retries;
        }
    }

    private static final int QUEUE_CAPACITY = 200;
    private static final BlockingQueue<OutboundTask> taskQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static final String DIR_PATH = "data/outbound/";
    private static OutboundSender outboundSender = OutboundMailService.getInstance();

    static {
        File dir = new File(DIR_PATH);
        if (!dir.exists()) dir.mkdirs();

        loadPersistedTasks();

        // 2 luồng Worker xử lý gửi thư nền (Daemon Threads)
        for (int i = 0; i < 2; i++) {
            Thread worker = new Thread(OutboundQueue::processQueue, "outbound-worker-" + i);
            worker.setDaemon(true);
            worker.start();
        }
    }

    public static void setOutboundSender(OutboundSender sender) {
        if (sender != null) {
            outboundSender = sender;
        }
    }

    public static boolean enqueue(String sender, Set<String> externalRecipients, String rawMessage) {
        String taskId = "task_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        OutboundTask task = new OutboundTask(taskId, sender, externalRecipients, rawMessage, 0);

        if (!saveTaskToDisk(task)) {
            return false;
        }

        if (!taskQueue.offer(task)) {
            deleteTaskFromDisk(taskId);
            return false;
        }

        return true;
    }

    private static void processQueue() {
        while (true) {
            try {
                OutboundTask task = taskQueue.take();

                MailService.DeliveryResult result = outboundSender.send(task.sender, task.externalRecipients, task.rawMessage);

                if (result.success) {
                    deleteTaskFromDisk(task.taskId);
                } else {
                    task.retries++;
                    saveTaskToDisk(task);

                    if (task.retries >= 4) {
                        deleteTaskFromDisk(task.taskId);
                        sendFailureNotification(task, result.message);
                    } else {
                        long delayMs = getRetryDelayMs(task.retries);
                        new Thread(() -> {
                            try {
                                Thread.sleep(delayMs);
                                taskQueue.put(task);
                            } catch (InterruptedException ignored) {}
                        }).start();
                    }
                }
            } catch (Exception e) {
                System.err.println("[OUTBOUND QUEUE] Worker lỗi: " + e.getMessage());
            }
        }
    }

    private static long getRetryDelayMs(int retryCount) {
        if (retryCount == 1) return 60 * 1000L;       // 1 phút
        if (retryCount == 2) return 5 * 60 * 1000L;   // 5 phút
        return 15 * 60 * 1000L;                       // 15 phút
    }

    private static void sendFailureNotification(OutboundTask task, String reason) {
        try {
            String subject = "Thư không thể gửi tới người nhận ngoài";
            String body = "Thư gửi tới các địa chỉ: " + String.join(", ", task.externalRecipients) + "\n"
                    + "Đã thất bại sau " + task.retries + " lần thử.\n"
                    + "Lý do: " + reason;

            String nowStr = ZonedDateTime.now().toString();
            String rawErrorEmail = "From: MAILER-DAEMON@pbl4.com\n"
                    + "To: " + task.sender + "\n"
                    + "Subject: " + subject + "\n"
                    + "Date: " + nowStr + "\n\n"
                    + body;

            MailStorageEngine.saveEmail(task.sender, rawErrorEmail);
        } catch (Exception e) {
            System.err.println("[OUTBOUND QUEUE] Lỗi tạo thông báo thất bại: " + e.getMessage());
        }
    }

    private static synchronized boolean saveTaskToDisk(OutboundTask task) {
        try {
            File file = new File(DIR_PATH, task.taskId + ".task");
            Properties props = new Properties();
            props.setProperty("taskId", task.taskId);
            props.setProperty("sender", task.sender);
            props.setProperty("externalRecipients", String.join(",", task.externalRecipients));
            props.setProperty("retries", String.valueOf(task.retries));

            try (FileOutputStream fos = new FileOutputStream(file)) {
                props.store(fos, "Outbound Task");
            }

            File msgFile = new File(DIR_PATH, task.taskId + ".msg");
            Files.write(msgFile.toPath(), task.rawMessage.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            System.err.println("[OUTBOUND QUEUE] Lỗi lưu task xuống ổ đĩa: " + e.getMessage());
            return false;
        }
    }

    private static synchronized void deleteTaskFromDisk(String taskId) {
        try {
            File file = new File(DIR_PATH, taskId + ".task");
            File msgFile = new File(DIR_PATH, taskId + ".msg");
            if (file.exists()) file.delete();
            if (msgFile.exists()) msgFile.delete();
        } catch (Exception ignored) {}
    }

    private static void loadPersistedTasks() {
        File dir = new File(DIR_PATH);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".task"));
        if (files == null) return;

        for (File file : files) {
            try {
                Properties props = new Properties();
                try (FileInputStream fis = new FileInputStream(file)) {
                    props.load(fis);
                }

                String taskId = props.getProperty("taskId");
                String sender = props.getProperty("sender");
                String rcptsStr = props.getProperty("externalRecipients");
                int retries = Integer.parseInt(props.getProperty("retries", "0"));

                File msgFile = new File(DIR_PATH, taskId + ".msg");
                if (!msgFile.exists()) continue;

                String rawMsg = new String(Files.readAllBytes(msgFile.toPath()), StandardCharsets.UTF_8);
                Set<String> rcpts = new HashSet<>(Set.of(rcptsStr.split(",")));

                OutboundTask task = new OutboundTask(taskId, sender, rcpts, rawMsg, retries);
                taskQueue.offer(task);
            } catch (Exception e) {
                System.err.println("[OUTBOUND QUEUE] Lỗi nạp lại task chưa hoàn thành: " + e.getMessage());
            }
        }
    }
}
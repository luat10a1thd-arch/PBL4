package com.pbl4.mailserver.mime;

import java.util.List;

/**
 * Kết quả đọc một thư MIME. Mọi trường chuỗi đều không null (rỗng nếu thiếu).
 * from/to/subject/date đã qua {@link MimeParser#decodeHeader(String)}; text là văn bản thuần an toàn
 * (HTML nếu có đã được chuyển sang văn bản, không bao giờ chứa HTML thô).
 */
public record ParsedMail(String from, String to, String subject, String date, String text,
                         List<AttachmentInfo> attachments) {

    public ParsedMail {
        from = from == null ? "" : from;
        to = to == null ? "" : to;
        subject = subject == null ? "" : subject;
        date = date == null ? "" : date;
        text = text == null ? "" : text;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
package com.pbl4.mailserver.mime;

import java.util.List;

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

package com.pbl4.mailserver.core;

import java.util.Set;

public interface OutboundSender {
    MailService.DeliveryResult send(String sender, Set<String> externalRecipients, String rawEmailContent);
}
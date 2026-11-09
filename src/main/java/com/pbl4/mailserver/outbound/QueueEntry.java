package com.pbl4.mailserver.outbound;

import java.util.List;

public record QueueEntry(String id, String envelopeFrom, String domain, List<String> rcpts,
                         int attempts, long nextAttemptEpochMs, String lastError) {
    public QueueEntry {
        rcpts = (rcpts == null) ? List.of() : List.copyOf(rcpts);
        if (envelopeFrom == null) envelopeFrom = "";
        if (lastError == null) lastError = "";
    }
}

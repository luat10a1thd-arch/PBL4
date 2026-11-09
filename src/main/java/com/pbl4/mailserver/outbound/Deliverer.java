package com.pbl4.mailserver.outbound;

import java.util.List;

public interface Deliverer {

    List<RcptResult> deliver(String envelopeFrom, String domain, List<String> rcpts, byte[] raw);
}

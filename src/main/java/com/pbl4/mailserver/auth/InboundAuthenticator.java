package com.pbl4.mailserver.auth;

/** Xác thực thư đến (SPF + DKIM + DMARC) và ra quyết định. Không bao giờ ném exception. */
public interface InboundAuthenticator {

    InboundVerdict evaluate(InboundContext ctx, byte[] raw);

    static InboundAuthenticator get() {
        return InboundAuthenticatorHolder.get();
    }

    /** Dành cho test của các agent khác. set(null) trả về cài đặt mặc định. */
    static void set(InboundAuthenticator a) {
        InboundAuthenticatorHolder.set(a);
    }
}

final class InboundAuthenticatorHolder {
    private static volatile InboundAuthenticator override;
    private static final InboundAuthenticator DEFAULT = new DefaultInboundAuthenticator();

    private InboundAuthenticatorHolder() {}

    static InboundAuthenticator get() {
        InboundAuthenticator o = override;
        return o != null ? o : DEFAULT;
    }

    static void set(InboundAuthenticator a) {
        override = a;
    }
}
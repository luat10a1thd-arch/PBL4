package com.pbl4.mailserver.auth;

public interface InboundAuthenticator {

    InboundVerdict evaluate(InboundContext ctx, byte[] raw);

    static InboundAuthenticator get() {
        return InboundAuthenticatorHolder.get();
    }

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

package com.pbl4.mailserver.outbound;

import com.pbl4.mailserver.config.ServerConfig;

public final class Deliverers {

    private static volatile Deliverer override;
    private static volatile Deliverer cached;

    private Deliverers() {}

    public static Deliverer get() {
        Deliverer o = override;
        if (o != null) return o;
        Deliverer c = cached;
        if (c == null) {
            synchronized (Deliverers.class) {
                c = cached;
                if (c == null) {
                    c = "RELAY".equals(ServerConfig.OUTBOUND_MODE) ? new RelayDeliverer() : new MxDeliverer();
                    cached = c;
                }
            }
        }
        return c;
    }

    public static void set(Deliverer d) {
        override = d;
    }
}

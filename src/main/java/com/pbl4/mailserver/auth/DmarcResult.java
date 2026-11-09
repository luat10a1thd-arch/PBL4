package com.pbl4.mailserver.auth;

public record DmarcResult(String result, String disposition, String fromDomain, String recordDomain,
                          String policy, boolean spfAligned, boolean dkimAligned, String reason) {

    static DmarcResult of(String result, String fromDomain, String reason) {
        return new DmarcResult(result, "none", fromDomain, null, null, false, false, reason);
    }
}

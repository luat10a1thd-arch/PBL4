package com.pbl4.mailserver.auth;

/** Kết quả kiểm tra SPF theo RFC 7208 mục 2.6. */
public enum SpfResult { PASS, FAIL, SOFTFAIL, NEUTRAL, NONE, TEMPERROR, PERMERROR }
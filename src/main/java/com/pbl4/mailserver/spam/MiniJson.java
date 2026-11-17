// FILE: src/main/java/com/pbl4/mailserver/spam/MiniJson.java
package com.pbl4.mailserver.spam;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON tối giản (chỉ JDK) cho file cài đặt của từng user. */
final class MiniJson {
    private MiniJson() {
    }

    static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        w(sb, o, 0);
        return sb.toString();
    }

    private static void w(StringBuilder sb, Object o, int d) {
        if (d > 20) throw new IllegalArgumentException("json quá sâu");
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String s) {
            str(sb, s);
        } else if (o instanceof Boolean || o instanceof Number) {
            sb.append(o);
        } else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                str(sb, String.valueOf(e.getKey()));
                sb.append(':');
                w(sb, e.getValue(), d + 1);
            }
            sb.append('}');
        } else if (o instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object x : c) {
                if (!first) sb.append(',');
                first = false;
                w(sb, x, d + 1);
            }
            sb.append(']');
        } else {
            str(sb, o.toString());
        }
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    static Object parse(String s) {
        P p = new P(s);
        Object v = p.value(0);
        p.ws();
        if (p.i != s.length()) throw new IllegalArgumentException("json thừa ký tự");
        return v;
    }

    private static final class P {
        final String s;
        int i;

        P(String s) {
            this.s = s;
        }

        IllegalArgumentException bad() {
            return new IllegalArgumentException("json không hợp lệ tại " + i);
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        void expect(char c) {
            if (!peek(c)) throw bad();
            i++;
        }

        Object value(int d) {
            if (d > 20) throw bad();
            ws();
            if (i >= s.length()) throw bad();
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return obj(d);
                case '[':
                    return arr(d);
                case '"':
                    return str();
                case 't':
                    lit("true");
                    return Boolean.TRUE;
                case 'f':
                    lit("false");
                    return Boolean.FALSE;
                case 'n':
                    lit("null");
                    return null;
                default:
                    return num();
            }
        }

        void lit(String t) {
            if (!s.startsWith(t, i)) throw bad();
            i += t.length();
        }

        Object obj(int d) {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{');
            ws();
            if (peek('}')) {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = str();
                ws();
                expect(':');
                m.put(k, value(d + 1));
                ws();
                if (peek(',')) {
                    i++;
                    continue;
                }
                expect('}');
                return m;
            }
        }

        Object arr(int d) {
            List<Object> l = new ArrayList<>();
            expect('[');
            ws();
            if (peek(']')) {
                i++;
                return l;
            }
            while (true) {
                l.add(value(d + 1));
                ws();
                if (peek(',')) {
                    i++;
                    continue;
                }
                expect(']');
                return l;
            }
        }

        String str() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length() || sb.length() > 200_000) throw bad();
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) throw bad();
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '"', '\\', '/' -> sb.append(e);
                    case 'u' -> {
                        if (i + 4 > s.length()) throw bad();
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw bad();
                        }
                        i += 4;
                    }
                    default -> throw bad();
                }
            }
        }

        Object num() {
            int st = i;
            while (i < s.length() && ",}] \t\r\n".indexOf(s.charAt(i)) < 0) i++;
            String t = s.substring(st, i);
            if (t.isEmpty()) throw bad();
            try {
                if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) return Long.parseLong(t);
                return Double.parseDouble(t);
            } catch (NumberFormatException e) {
                throw bad();
            }
        }
    }
}
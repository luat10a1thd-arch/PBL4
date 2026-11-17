// FILE: src/main/java/com/pbl4/mailserver/forward/ForwardStore.java
package com.pbl4.mailserver.forward;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Kho lưu luật chuyển tiếp: data/forward/&lt;user&gt;.json, mỗi user một file.
 * Ghi nguyên tử (file tạm cùng thư mục + ATOMIC_MOVE), quyền rw------- khi hệ thống hỗ trợ POSIX.
 * Tên file chỉ sinh từ user đã chuẩn hóa; nếu user có ký tự lạ thì dùng SHA-256 của nó.
 * Đọc lỗi/hỏng => ném UncheckedIOException (đóng, không coi file hỏng là "chưa có gì" để khỏi ghi đè).
 */
final class ForwardStore {

    /** Một luật + trạng thái mã xác minh (mã chỉ lưu dạng băm có muối). */
    static final class Rec {
        String id = "";
        String target = "";
        String fromContains = "";
        String subjectContains = "";
        boolean verified;
        boolean enabled = true;
        boolean keepCopy = true;
        long createdAt;
        String codeHash = "";
        String codeSalt = "";
        long codeExp;
        int codeTries;

        ForwardRule toRule() {
            return new ForwardRule(id, target, verified, enabled, keepCopy, fromContains, subjectContains, createdAt);
        }

        static Rec fromMap(Map<?, ?> m) {
            Rec r = new Rec();
            r.id = str(m, "id", "");
            r.target = str(m, "target", "");
            if (r.id.isEmpty() || r.target.isEmpty()) {
                throw new IllegalArgumentException("Bản ghi luật thiếu id/target");
            }
            r.fromContains = str(m, "fromContains", "");
            r.subjectContains = str(m, "subjectContains", "");
            r.verified = bool(m, "verified", false);
            r.enabled = bool(m, "enabled", true);
            r.keepCopy = bool(m, "keepCopy", true);
            r.createdAt = lng(m, "createdAt", 0L);
            r.codeHash = str(m, "codeHash", "");
            r.codeSalt = str(m, "codeSalt", "");
            r.codeExp = lng(m, "codeExp", 0L);
            r.codeTries = (int) lng(m, "codeTries", 0L);
            return r;
        }
    }

    static final class Data {
        final List<Rec> rules = new ArrayList<>();
        /** Thời điểm (ms) các lần gửi mã xác minh gần đây, để giới hạn theo giờ. */
        final List<Long> codeSends = new ArrayList<>();
    }

    private static final long MAX_FILE_BYTES = 1024 * 1024;
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}@[a-z0-9][a-z0-9.-]{0,99}$");

    static volatile Path baseDir = Paths.get("data", "forward");

    private ForwardStore() {
    }

    // ------------------------------------------------------------------ đọc / ghi

    static Path fileFor(String userKey) {
        String name = SAFE_NAME.matcher(userKey).matches() ? userKey + ".json" : sha256Hex(userKey) + ".json";
        return baseDir.resolve(name);
    }

    static Data load(String userKey) {
        Data d = new Data();
        Path p = fileFor(userKey);
        if (!Files.exists(p)) {
            return d;
        }
        try {
            if (Files.size(p) > MAX_FILE_BYTES) {
                throw new IOException("File luật chuyển tiếp quá lớn");
            }
            Object root = Json.parse(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            if (!(root instanceof Map)) {
                throw new IOException("File luật chuyển tiếp không đúng cấu trúc");
            }
            Map<?, ?> m = (Map<?, ?>) root;
            Object sends = m.get("codeSends");
            if (sends instanceof List) {
                for (Object o : (List<?>) sends) {
                    if (o instanceof Number) d.codeSends.add(((Number) o).longValue());
                }
            }
            Object rules = m.get("rules");
            if (rules instanceof List) {
                for (Object o : (List<?>) rules) {
                    if (!(o instanceof Map)) throw new IOException("Bản ghi luật không hợp lệ");
                    d.rules.add(Rec.fromMap((Map<?, ?>) o));
                }
            }
            return d;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            throw new UncheckedIOException(new IOException("File luật chuyển tiếp hỏng", e));
        }
    }

    static void save(String userKey, Data d) {
        try {
            Path dir = baseDir;
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                restrict(dir, true);
            }
            Path target = fileFor(userKey);
            Path tmp = Files.createTempFile(dir, "fwd-", ".tmp");
            try {
                Files.write(tmp, toJson(d).getBytes(StandardCharsets.UTF_8));
                restrict(tmp, false);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void restrict(Path path, boolean isDir) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(isDir ? "rwx------" : "rw-------"));
        } catch (Exception ignored) {
            // hệ thống không hỗ trợ POSIX (Windows)
        }
    }

    // ------------------------------------------------------------------ JSON

    private static String toJson(Data d) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n  \"codeSends\": [");
        for (int i = 0; i < d.codeSends.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(d.codeSends.get(i));
        }
        sb.append("],\n  \"rules\": [");
        for (int i = 0; i < d.rules.size(); i++) {
            Rec r = d.rules.get(i);
            sb.append(i == 0 ? "\n" : ",\n").append("    {");
            sb.append("\"id\": ").append(quote(r.id));
            sb.append(", \"target\": ").append(quote(r.target));
            sb.append(", \"verified\": ").append(r.verified);
            sb.append(", \"enabled\": ").append(r.enabled);
            sb.append(", \"keepCopy\": ").append(r.keepCopy);
            sb.append(", \"fromContains\": ").append(quote(r.fromContains));
            sb.append(", \"subjectContains\": ").append(quote(r.subjectContains));
            sb.append(", \"createdAt\": ").append(r.createdAt);
            sb.append(", \"codeHash\": ").append(quote(r.codeHash));
            sb.append(", \"codeSalt\": ").append(quote(r.codeSalt));
            sb.append(", \"codeExp\": ").append(r.codeExp);
            sb.append(", \"codeTries\": ").append(r.codeTries);
            sb.append("}");
        }
        sb.append(d.rules.isEmpty() ? "]\n}" : "\n  ]\n}");
        return sb.toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String str(Map<?, ?> m, String k, String def) {
        Object v = m.get(k);
        return v instanceof String ? (String) v : def;
    }

    private static boolean bool(Map<?, ?> m, String k, boolean def) {
        Object v = m.get(k);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    private static long lng(Map<?, ?> m, String k, long def) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Bộ đọc JSON tối thiểu (object/array/string/number/boolean/null), giới hạn độ sâu. */
    private static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            Json j = new Json(text);
            Object v = j.value(0);
            j.skip();
            if (j.i != j.s.length()) throw new IllegalArgumentException("JSON thừa dữ liệu");
            return v;
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private void expect(char c) {
            skip();
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("JSON thiếu '" + c + "'");
            i++;
        }

        private Object value(int depth) {
            if (depth > 8) throw new IllegalArgumentException("JSON quá sâu");
            skip();
            if (i >= s.length()) throw new IllegalArgumentException("JSON cụt");
            char c = s.charAt(i);
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        private Map<String, Object> object(int depth) {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skip();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                skip();
                String k = string();
                expect(':');
                m.put(k, value(depth + 1));
                skip();
                if (i >= s.length()) throw new IllegalArgumentException("JSON cụt");
                char d = s.charAt(i++);
                if (d == ',') continue;
                if (d == '}') return m;
                throw new IllegalArgumentException("JSON sai ký tự trong object");
            }
        }

        private List<Object> array(int depth) {
            expect('[');
            List<Object> l = new ArrayList<>();
            skip();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value(depth + 1));
                skip();
                if (i >= s.length()) throw new IllegalArgumentException("JSON cụt");
                char d = s.charAt(i++);
                if (d == ',') continue;
                if (d == ']') return l;
                throw new IllegalArgumentException("JSON sai ký tự trong array");
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case '"': case '\\': case '/': sb.append(e); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new IllegalArgumentException("JSON \\u cụt");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new IllegalArgumentException("JSON escape lạ");
                }
            }
            throw new IllegalArgumentException("JSON chuỗi không đóng");
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw new IllegalArgumentException("JSON giá trị lạ");
            String t = s.substring(start, i);
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException e) {
                return (long) Double.parseDouble(t);
            }
        }
    }
}
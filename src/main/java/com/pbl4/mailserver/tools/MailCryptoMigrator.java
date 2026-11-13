// FILE: src/main/java/com/pbl4/mailserver/tools/MailCryptoMigrator.java
package com.pbl4.mailserver.tools;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.MailCrypto;
import com.pbl4.mailserver.core.SecurityUtils;
import com.pbl4.mailserver.core.UserStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

/**
 * Công cụ chạy MỘT LẦN: đọc từng file thư CBC cũ rồi ghi lại bằng AES-256-GCM (cùng tên file, cùng thời gian sửa đổi).
 *
 * An toàn:
 *  - Mỗi file: giải mã -> mã hóa GCM -> giải mã thử và so từng byte -> ghi file tạm 600 -> fsync -> rename nguyên tử đè file cũ.
 *    Bị ngắt ở bất kỳ lúc nào thì mỗi file hoặc còn là CBC cũ hoặc đã là GCM; chạy lại sẽ tiếp tục (idempotent).
 *  - File không giải mã được thì GIỮ NGUYÊN và được báo cáo (không bao giờ ghi đè bằng rác).
 *  - Luôn sao lưu toàn bộ thư mục thư trước khi sửa (trừ --dry-run, vì không ghi gì).
 *  - Không in nội dung thư, chỉ in tên hòm, id file và số lượng.
 *
 * Cách chạy (DỪNG SERVER TRƯỚC, chạy trong thư mục làm việc của server, cùng biến môi trường PBL4_SECRET_KEY):
 *   java -cp target/classes:<thư viện> com.pbl4.mailserver.tools.MailCryptoMigrator --dry-run
 *   java -cp target/classes:<thư viện> com.pbl4.mailserver.tools.MailCryptoMigrator
 * Tùy chọn: --yes (bỏ bước gõ YES), --data-dir=đường/dẫn/mailboxes
 * Mã thoát: 0 = xong, 2 = có file không chuyển được, 1 = lỗi nghiêm trọng/hủy.
 */
public final class MailCryptoMigrator {

    private static final String TMP_SUFFIX = ".mig.tmp";

    public static final class Result {
        public int converted;
        public int alreadyGcm;
        public int failed;
        public int skippedNoUser;
        public int tmpCleaned;
    }

    private MailCryptoMigrator() {}

    // ------------------------------------------------------------------ lõi (dễ kiểm thử)

    /**
     * @param saltOf trả salt của người dùng gốc (đã bỏ hậu tố hòm) hoặc null nếu không có người dùng đó
     */
    public static Result migrate(Path base, Function<String, byte[]> saltOf, String secret,
                                 boolean dryRun, PrintStream out) throws IOException {
        Result r = new Result();
        List<Path> boxes = new ArrayList<>();
        try (var ds = Files.newDirectoryStream(base, p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))) {
            for (Path p : ds) boxes.add(p);
        }
        boxes.sort(null);

        for (Path box : boxes) {
            String boxName = box.getFileName().toString();
            String clean = MailCrypto.cleanUserOf(boxName);
            byte[] salt = saltOf.apply(clean);
            if (salt == null) {
                out.println("  Bỏ qua hòm không có người dùng tương ứng: " + boxName);
                r.skippedNoUser++;
                continue;
            }
            MailCrypto.KeyRing ring = new MailCrypto.KeyRing(secret, clean, salt);

            List<Path> files = new ArrayList<>();
            try (var ds = Files.newDirectoryStream(box)) {
                for (Path p : ds) files.add(p);
            }
            files.sort(null);

            for (Path p : files) {
                String fname = p.getFileName().toString();
                if (fname.endsWith(TMP_SUFFIX)) {
                    if (!dryRun) Files.deleteIfExists(p);
                    r.tmpCleaned++;
                    continue;
                }
                if (!fname.endsWith(".enc") || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;

                try {
                    byte[] file = Files.readAllBytes(p);
                    if (MailCrypto.isGcm(file)) {
                        r.alreadyGcm++;
                        continue;
                    }
                    byte[] aad = MailCrypto.aad(boxName, fname);
                    byte[] plain = MailCrypto.decrypt(file, ring, aad, true);
                    if (plain == null) {
                        r.failed++;
                        out.println("  KHÔNG GIẢI MÃ ĐƯỢC (giữ nguyên): " + boxName + "/" + fname);
                        continue;
                    }
                    byte[] gcm = MailCrypto.encryptGcm(plain, ring.gcm(), aad);
                    byte[] check = MailCrypto.decrypt(gcm, ring, aad, false);
                    if (!Arrays.equals(plain, check)) {
                        r.failed++;
                        out.println("  KIỂM TRA LẠI THẤT BẠI (giữ nguyên): " + boxName + "/" + fname);
                        continue;
                    }
                    if (!dryRun) {
                        replaceAtomically(box, p, gcm);
                    }
                    r.converted++;
                } catch (Exception e) {
                    r.failed++;
                    out.println("  LỖI (giữ nguyên): " + boxName + "/" + fname + " [" + e.getClass().getSimpleName() + "]");
                }
            }
        }
        return r;
    }

    private static void replaceAtomically(Path box, Path target, byte[] newContent) throws IOException {
        Path tmp = box.resolve(target.getFileName().toString() + TMP_SUFFIX);
        try {
            SecurityUtils.writeOwnerOnly(tmp, newContent);
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            // Giữ thời gian sửa đổi cũ: purge thư rác/thùng rác tính tuổi 30 ngày theo mốc này.
            Files.setLastModifiedTime(tmp, Files.getLastModifiedTime(target));
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ------------------------------------------------------------------ sao lưu

    /** Chép cả cây thư mục thư (+ users.json, users.json.bak nếu có) sang thư mục sao lưu, rồi kiểm tra số file và số byte. */
    static Path backup(Path base, PrintStream out) throws IOException {
        Path parent = base.getParent();
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dest = parent.resolve("mailboxes-backup-" + ts);

        long[] src = countTree(base);
        long usable = Files.getFileStore(parent).getUsableSpace();
        if (usable < src[1] + 64L * 1024 * 1024) {
            throw new IOException("Không đủ dung lượng trống để sao lưu (cần ~" + (src[1] >> 20) + " MB)");
        }

        Files.createDirectories(dest);
        SecurityUtils.restrictToOwner(dest, true);
        Path destMail = dest.resolve("mailboxes");
        copyTree(base, destMail);

        long[] dst = countTree(destMail);
        if (src[0] != dst[0] || src[1] != dst[1]) {
            throw new IOException("Bản sao lưu không khớp bản gốc (số file/byte khác nhau)");
        }
        for (String n : new String[]{"users.json", "users.json.bak"}) {
            Path f = parent.resolve(n);
            if (Files.isRegularFile(f)) {
                SecurityUtils.writeOwnerOnly(dest.resolve(n), Files.readAllBytes(f));
            }
        }
        out.println("Đã sao lưu " + src[0] + " file (" + (src[1] >> 10) + " KB) vào: " + dest);
        return dest;
    }

    private static long[] countTree(Path root) throws IOException {
        long[] c = new long[2];
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    c[0]++;
                    c[1] += attrs.size();
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return c;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path t = to.resolve(from.relativize(dir).toString());
                Files.createDirectories(t);
                SecurityUtils.restrictToOwner(t, true);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    Path t = to.resolve(from.relativize(file).toString());
                    Files.copy(file, t, StandardCopyOption.COPY_ATTRIBUTES);
                    SecurityUtils.restrictToOwner(t, false);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ------------------------------------------------------------------ main

    private static void banner(PrintStream out, boolean dry) {
        out.println("################################################################");
        out.println("#  CHUYỂN ĐỔI MÃ HÓA THƯ: AES-CBC  ->  AES-256-GCM              #");
        out.println("#                                                              #");
        out.println("#  1. HÃY DỪNG SERVER (HTTP/SMTP/POP3) TRƯỚC KHI CHẠY.         #");
        out.println("#  2. Dùng ĐÚNG PBL4_SECRET_KEY như server đang chạy.          #");
        out.println("#     Sai khóa => không giải mã được, file được giữ nguyên.    #");
        out.println("#  3. " + (dry
                ? "ĐANG Ở CHẾ ĐỘ --dry-run: không ghi gì, không sao lưu.      "
                : "Công cụ SẼ TỰ SAO LƯU toàn bộ thư mục thư trước khi sửa.   ") + "#");
        out.println("#  4. Sau khi chuyển xong, bản chạy cũ (chỉ biết CBC) KHÔNG    #");
        out.println("#     còn đọc được thư: muốn quay lại phải khôi phục từ backup.#");
        out.println("################################################################");
    }

    public static void main(String[] args) {
        boolean dry = false;
        boolean yes = false;
        String dataDir = ServerConfig.BASE_DATA_DIR;
        for (String a : args) {
            if ("--dry-run".equals(a)) dry = true;
            else if ("--yes".equals(a)) yes = true;
            else if (a.startsWith("--data-dir=")) dataDir = a.substring("--data-dir=".length());
            else {
                System.err.println("Tham số không hợp lệ: " + a + "  (dùng --dry-run, --yes, --data-dir=...)");
                System.exit(1);
                return;
            }
        }

        banner(System.out, dry);

        try {
            String secret = ServerConfig.SECRET_KEY;
            if (secret == null || secret.isEmpty()) {
                System.err.println("Thiếu PBL4_SECRET_KEY. Hủy.");
                System.exit(1);
                return;
            }
            Path base = Paths.get(dataDir).toAbsolutePath().normalize();
            if (!Files.isDirectory(base)) {
                System.err.println("Không thấy thư mục dữ liệu thư: " + base);
                System.exit(1);
                return;
            }
            System.out.println("Thư mục dữ liệu thư: " + base);

            if (!dry && !yes) {
                System.out.print("Bạn đã DỪNG SERVER và muốn tiếp tục? Gõ YES để chạy: ");
                System.out.flush();
                String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
                if (line == null || !"YES".equals(line.trim())) {
                    System.out.println("Đã hủy, chưa thay đổi gì.");
                    System.exit(1);
                    return;
                }
            }

            if (!dry) {
                backup(base, System.out);
                System.out.println("Nhớ sao lưu riêng cả PBL4_SECRET_KEY (hoặc data/secret.key): backup không có khóa thì không đọc được.");
            }

            Function<String, byte[]> salts = u -> {
                String s = UserStore.findSalt(u);
                return (s == null || s.trim().isEmpty()) ? null : Base64.getDecoder().decode(s);
            };
            Result r = migrate(base, salts, secret, dry, System.out);

            System.out.println("----------------------------------------------------------------");
            System.out.println((dry ? "[DRY-RUN] sẽ chuyển: " : "Đã chuyển: ") + r.converted
                    + " | đã là GCM: " + r.alreadyGcm
                    + " | thất bại: " + r.failed
                    + " | hòm không có user: " + r.skippedNoUser
                    + " | file tạm đã dọn: " + r.tmpCleaned);
            if (r.failed == 0 && !dry) {
                System.out.println("Xong. Có thể đặt PBL4_ALLOW_LEGACY_CBC=false rồi khởi động lại server.");
            }
            if (r.failed > 0) {
                System.out.println("CÒN " + r.failed + " file chưa chuyển (xem danh sách ở trên). Đừng tắt đọc CBC cũ.");
            }
            System.exit(r.failed > 0 ? 2 : 0);
        } catch (Exception e) {
            System.err.println("LỖI NGHIÊM TRỌNG: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }
    }
}
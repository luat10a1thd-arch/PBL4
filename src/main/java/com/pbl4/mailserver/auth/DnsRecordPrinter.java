package com.pbl4.mailserver.auth;

import com.pbl4.mailserver.config.ServerConfig;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * In các bản ghi DNS cần khai để mail server hoạt động liên mạng.
 *
 * Cách chạy:  java -cp target/mailserver-1.0.0.jar com.pbl4.mailserver.auth.DnsRecordPrinter &lt;IP công khai&gt;
 * Tên domain/hostname/selector/thư mục khóa lấy từ cấu hình (PBL4_MAIL_DOMAIN, PBL4_MAIL_HOSTNAME, PBL4_DKIM_*).
 * Lần chạy đầu tiên sẽ tự sinh khóa DKIM nếu chưa có.
 */
public final class DnsRecordPrinter {

    private static final int TXT_CHUNK = 255;
    private static final String TTL = "3600";

    private DnsRecordPrinter() {}

    public static void main(String[] args) {
        String ip = args.length > 0 ? args[0].trim() : "";
        if (ip.isEmpty()) {
            System.err.println("Cách dùng: DnsRecordPrinter <IP công khai của server>");
            System.err.println("(tiếp tục với IP giả định để bạn xem định dạng)");
            ip = "203.0.113.10";
        } else if (!isIpLiteral(ip)) {
            System.err.println("[LỖI] \"" + ip + "\" không phải địa chỉ IPv4/IPv6 hợp lệ.");
            System.exit(2);
            return;
        }
        System.out.print(render(ip, ServerConfig.MAIL_DOMAIN, ServerConfig.MAIL_HOSTNAME,
                ServerConfig.DKIM_SELECTOR, Paths.get(ServerConfig.DKIM_KEY_DIR)));
    }

    /** Dựng toàn bộ nội dung hướng dẫn (tách riêng để test). Ném IllegalStateException nếu không tạo được khóa DKIM. */
    public static String render(String ip, String domain, String hostname, String selector, Path keyDir) {
        boolean v6 = ip.indexOf(':') >= 0;
        String dkim = DkimSigner.publicKeyRecord(domain, selector, keyDir);
        String spf = "v=spf1 mx " + (v6 ? "ip6:" : "ip4:") + ip + " -all";
        String dmarc = "v=DMARC1; p=none; rua=mailto:postmaster@" + domain + "; adkim=r; aspf=r";

        StringBuilder sb = new StringBuilder();
        sb.append("=== BẢN GHI DNS CẦN KHAI CHO ").append(domain).append(" ===\n");
        sb.append("(TTL gợi ý ").append(TTL).append("; khai tại nhà cung cấp DNS của domain)\n\n");

        sb.append("1) MX - nơi các máy chủ khác gửi thư tới domain\n");
        sb.append("   ").append(domain).append(".  ").append(TTL).append("  IN  MX  10  ").append(hostname).append(".\n\n");

        sb.append("2) ").append(v6 ? "AAAA" : "A").append(" cho tên máy chủ thư (MAIL_HOSTNAME)\n");
        sb.append("   ").append(hostname).append(".  ").append(TTL).append("  IN  ").append(v6 ? "AAAA" : "A")
                .append("  ").append(ip).append("\n\n");

        sb.append("3) SPF - cho phép đúng máy chủ này gửi thư thay mặt domain\n");
        sb.append("   ").append(domain).append(".  ").append(TTL).append("  IN  TXT  ").append(quoteTxt(spf)).append("\n");
        sb.append("   (nếu dùng thêm dịch vụ gửi thư khác, thêm include:<dịch vụ> trước -all)\n\n");

        sb.append("4) DKIM - khóa công khai để người nhận kiểm tra chữ ký (selector \"").append(selector).append("\")\n");
        sb.append("   ").append(selector).append("._domainkey.").append(domain).append(".  ").append(TTL)
                .append("  IN  TXT  ").append(quoteTxt(dkim)).append("\n");
        sb.append("   (chuỗi dài hơn 255 byte đã được cắt thành nhiều đoạn trong dấu nháy; giữ nguyên khi khai)\n\n");

        sb.append("5) DMARC - khởi đầu ở chế độ theo dõi (p=none)\n");
        sb.append("   _dmarc.").append(domain).append(".  ").append(TTL).append("  IN  TXT  ").append(quoteTxt(dmarc)).append("\n");
        sb.append("   Khuyến nghị: chạy ổn định vài tuần, xem báo cáo/log rồi nâng lên p=quarantine, sau đó p=reject.\n");
        sb.append("   (rua= là tùy chọn; bỏ đi nếu hộp thư postmaster@").append(domain).append(" chưa tồn tại)\n\n");

        sb.append("6) PTR (reverse DNS) - KHÔNG khai ở DNS của domain\n");
        sb.append("   Nhờ nhà cung cấp VPS/ISP đặt PTR của ").append(reverseName(ip)).append(" trỏ về: ").append(hostname).append(".\n");
        sb.append("   PTR phải KHỚP MAIL_HOSTNAME, và bản ghi ").append(v6 ? "AAAA" : "A").append(" của ").append(hostname)
                .append(" phải trỏ lại đúng ").append(ip).append(". Gmail/Outlook thường từ chối nếu thiếu.\n\n");

        sb.append("Kiểm tra sau khi khai (thay bằng domain thật):\n");
        sb.append("   dig +short MX ").append(domain).append("\n");
        sb.append("   dig +short TXT ").append(domain).append("\n");
        sb.append("   dig +short TXT ").append(selector).append("._domainkey.").append(domain).append("\n");
        sb.append("   dig +short TXT _dmarc.").append(domain).append("\n");
        sb.append("   dig +short -x ").append(ip).append("\n");
        return sb.toString();
    }

    /** Cắt chuỗi thành các đoạn tối đa 255 byte (giới hạn của một character-string trong TXT). */
    public static List<String> splitTxt(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < bytes.length; i += TXT_CHUNK) {
            int end = Math.min(bytes.length, i + TXT_CHUNK);
            parts.add(new String(bytes, i, end - i, StandardCharsets.UTF_8));
        }
        if (parts.isEmpty()) {
            parts.add("");
        }
        return parts;
    }

    /** "đoạn1" "đoạn2" ... đúng cú pháp file zone. */
    static String quoteTxt(String value) {
        StringBuilder sb = new StringBuilder();
        for (String p : splitTxt(value)) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append('"').append(p.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.toString();
    }

    static boolean isIpLiteral(String s) {
        if (s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            for (String part : s.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            }
            return true;
        }
        return s.indexOf(':') >= 0 && s.matches("[0-9A-Fa-f:.]+");
    }

    /** Tên reverse (in-addr.arpa / ip6.arpa) của IP literal; không phát sinh truy vấn DNS. */
    static String reverseName(String ip) {
        try {
            byte[] a = InetAddress.getByName(ip).getAddress();
            StringBuilder sb = new StringBuilder();
            if (a.length == 4) {
                for (int i = 3; i >= 0; i--) {
                    sb.append(a[i] & 0xff).append('.');
                }
                return sb.append("in-addr.arpa.").toString();
            }
            for (int i = a.length - 1; i >= 0; i--) {
                int v = a[i] & 0xff;
                sb.append(Integer.toHexString(v & 0xf)).append('.').append(Integer.toHexString(v >> 4)).append('.');
            }
            return sb.append("ip6.arpa.").toString();
        } catch (Exception e) {
            return ip;
        }
    }
}

package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UserStore {

    private static final String DEFAULT_USERS_FILE_PATH = "data/users.json";
    // Đường dẫn file người dùng (mặc định data/users.json; chỉ test mới đổi qua resetForTest)
    private static volatile String usersFilePath = DEFAULT_USERS_FILE_PATH;
    private static final ConcurrentHashMap<String, User> userMap = new ConcurrentHashMap<>();
    private static volatile boolean isLoaded = false;
    // false khi users.json đang hỏng và chưa khôi phục được: tuyệt đối không chép nó đè lên bản .bak
    private static volatile boolean mainFileTrusted = true;

    private static void ensureLoaded() {
        if (!isLoaded) {
            synchronized (UserStore.class) {
                if (!isLoaded) {
                    loadUsersFromFile();
                    isLoaded = true;
                }
            }
        }
    }

    public static synchronized void initDefaultUsers() {
        // Ném lỗi (dừng khởi động) nếu users.json hỏng và không có bản .bak đọc được
        ensureLoaded();
        // Chỉ tạo admin mặc định khi chưa có người dùng nào (file chưa tồn tại hoặc rỗng hợp lệ)
        if (userMap.isEmpty()) {
            byte[] salt = SecurityUtils.generateSalt();
            String saltBase64 = Base64.getEncoder().encodeToString(salt);
            String hash = SecurityUtils.hashPassword(ServerConfig.ADMIN_DEFAULT_PASS);
            addUser("admin@pbl4.com", hash, saltBase64, "ACTIVE", "ADMIN");
        } else if (!exists("admin@pbl4.com")) {
            System.err.println("[USERSTORE] CẢNH BÁO: users.json đã có người dùng nhưng không có admin@pbl4.com, KHÔNG tự tạo lại admin mặc định.");
        }
    }

    // Chỉ dùng cho test: trỏ UserStore sang file khác (null = về mặc định) và buộc nạp lại từ file đó
    static synchronized void resetForTest(String path) {
        usersFilePath = (path != null) ? path : DEFAULT_USERS_FILE_PATH;
        userMap.clear();
        mainFileTrusted = true;
        isLoaded = false;
    }

    public static String canonical(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        ensureLoaded();

        String cleanName = name.trim();
        boolean isSent = cleanName.endsWith("_sent");
        if (isSent) {
            cleanName = cleanName.substring(0, cleanName.length() - 5);
        }

        User u = resolveUser(cleanName.toLowerCase(Locale.ROOT));
        if (u == null) return null;

        String canonicalUsername = u.getUsername().toLowerCase(Locale.ROOT);
        return isSent ? canonicalUsername + "_sent" : canonicalUsername;
    }

    private static synchronized void loadUsersFromFile() {
        File file = new File(usersFilePath);
        File backup = new File(usersFilePath + ".bak");

        if (!file.exists()) {
            // Lần chạy đầu tiên (chưa có file): để trống, initDefaultUsers sẽ tạo admin mặc định
            userMap.clear();
            mainFileTrusted = true;
            if (backup.exists()) {
                System.err.println("[USERSTORE] CẢNH BÁO: không thấy users.json nhưng còn users.json.bak. Nếu đây không phải lần chạy đầu, hãy khôi phục từ file .bak trước khi chạy tiếp.");
            }
            return;
        }

        Map<String, User> loaded;
        boolean recoveredFromBackup = false;
        try {
            loaded = parseUsersFile(file);
        } catch (Exception primaryError) {
            System.err.println("[USERSTORE] users.json bị lỗi: " + primaryError.getMessage());
            mainFileTrusted = false;
            if (!backup.exists()) {
                throw new IllegalStateException("users.json bị hỏng và không có users.json.bak để khôi phục. "
                        + "Dừng khởi động để không ghi đè dữ liệu người dùng: " + primaryError.getMessage(), primaryError);
            }
            try {
                loaded = parseUsersFile(backup);
                recoveredFromBackup = true;
                System.err.println("[USERSTORE] Đã khôi phục " + loaded.size() + " người dùng từ users.json.bak");
            } catch (Exception backupError) {
                throw new IllegalStateException("Cả users.json lẫn users.json.bak đều không đọc được. "
                        + "Dừng khởi động để không ghi đè dữ liệu người dùng: " + backupError.getMessage(), primaryError);
            }
        }

        userMap.clear();
        userMap.putAll(loaded);

        if (recoveredFromBackup) {
            restoreMainFileFromBackup(file, backup);
        } else {
            mainFileTrusted = true;
        }

        boolean dirty = false;
        for (User user : userMap.values()) {
            if (user.getSalt() == null || user.getSalt().trim().isEmpty()) {
                byte[] newSalt = SecurityUtils.generateSalt();
                user.setSalt(Base64.getEncoder().encodeToString(newSalt));
                dirty = true;
            }
        }

        if (dirty) {
            saveUsersToFile();
        }
    }

    // Đọc và kiểm tra một file users.json; ném lỗi nếu không phải mảng JSON hoàn chỉnh hoặc có bản ghi hỏng.
    // File rỗng (chỉ có khoảng trắng) được coi là hợp lệ và không có người dùng nào.
    private static Map<String, User> parseUsersFile(File file) throws IOException {
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (json.startsWith("\uFEFF")) {
            json = json.substring(1); // bỏ BOM (Notepad trên Windows hay thêm)
        }
        json = json.trim();

        Map<String, User> result = new LinkedHashMap<>();
        if (json.isEmpty()) return result;

        if (!json.startsWith("[") || !json.endsWith("]")) {
            throw new IllegalStateException("Nội dung " + file.getName() + " không phải mảng JSON hoàn chỉnh (có thể bị cắt dở)");
        }

        int openBraces = 0;
        int closeBraces = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') openBraces++;
            else if (c == '}') closeBraces++;
        }

        Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(json);
        int objects = 0;
        while (m.find()) {
            objects++;
            User user = parseUserObject(m.group());
            if (user == null || user.getUsername() == null || user.getUsername().trim().isEmpty()) {
                throw new IllegalStateException("Bản ghi người dùng thứ " + objects + " trong " + file.getName() + " không hợp lệ");
            }
            result.put(user.getUsername().toLowerCase(Locale.ROOT).trim(), user);
        }

        // Số cặp ngoặc không khớp số bản ghi đọc được => file bị cắt dở/hỏng, không được nạp một phần
        if (openBraces != closeBraces || openBraces != objects) {
            throw new IllegalStateException("Cấu trúc " + file.getName() + " bị hỏng (ngoặc không khớp)");
        }
        return result;
    }

    // Giữ lại file hỏng (đổi tên .corrupt-<thời gian>) rồi chép .bak thành users.json. KHÔNG đụng tới .bak.
    private static void restoreMainFileFromBackup(File main, File backup) {
        try {
            Path corrupt = Paths.get(usersFilePath + ".corrupt-" + System.currentTimeMillis());
            Files.move(main.toPath(), corrupt, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(backup.toPath(), main.toPath(), StandardCopyOption.REPLACE_EXISTING);
            setFilePermissions(main.toPath(), false);
            mainFileTrusted = true;
            System.err.println("[USERSTORE] File hỏng được giữ lại tại " + corrupt.getFileName());
        } catch (Exception e) {
            // Vẫn dùng dữ liệu trong RAM; mainFileTrusted=false để lần ghi sau không chép file hỏng đè lên .bak
            System.err.println("[USERSTORE] Không khôi phục được users.json từ .bak: " + e.getMessage());
        }
    }

    private static synchronized void saveUsersToFile() {
        try {
            File dataDir = new File(usersFilePath).getAbsoluteFile().getParentFile();
            if (dataDir != null && !dataDir.exists()) {
                dataDir.mkdirs();
                setFilePermissions(dataDir.toPath(), true);
            }

            StringBuilder sb = new StringBuilder("[\n");
            int count = 0;
            int total = userMap.size();

            for (User u : userMap.values()) {
                count++;
                sb.append("  {\n");
                sb.append("    \"username\": \"").append(escapeJson(u.getUsername())).append("\",\n");
                sb.append("    \"passwordHash\": \"").append(escapeJson(u.getPasswordHash())).append("\",\n");
                sb.append("    \"salt\": \"").append(escapeJson(u.getSalt() == null ? "" : u.getSalt())).append("\",\n");
                sb.append("    \"status\": \"").append(escapeJson(u.getStatus() == null ? "ACTIVE" : u.getStatus())).append("\",\n");
                sb.append("    \"role\": \"").append(escapeJson(u.getRole() == null ? "USER" : u.getRole())).append("\"\n");
                sb.append("  }").append(count < total ? ",\n" : "\n");
            }
            sb.append("]");

            Path target = Paths.get(usersFilePath);
            Path backup = Paths.get(usersFilePath + ".bak");
            Path tmp = Paths.get(usersFilePath + ".tmp");

            if (Files.exists(target) && mainFileTrusted) {
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                setFilePermissions(backup, false);
            }

            Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
            setFilePermissions(tmp, false);

            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            setFilePermissions(target, false);
            mainFileTrusted = true;
        } catch (Exception e) {
            System.err.println("[USERSTORE] Lỗi ghi dữ liệu users.json: " + e.getMessage());
        }
    }

    private static void setFilePermissions(Path path, boolean isDir) {
        try {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Set<PosixFilePermission> perms = isDir
                    ? PosixFilePermissions.fromString("rwx------")
                    : PosixFilePermissions.fromString("rw-------");
                Files.setPosixFilePermissions(path, perms);
            }
        } catch (Exception ignored) {}
    }

    public static boolean exists(String username) {
        if (username == null) return false;
        ensureLoaded();
        return resolveUser(username.toLowerCase(Locale.ROOT).trim()) != null;
    }

    // ---- Alias bắt buộc theo RFC 5321/2142: postmaster@ và abuse@ chuyển về tài khoản admin ----
    // Chỉ ảnh hưởng exists()/canonical() (để MxHandler nhận thư và MailService giao vào hòm admin).
    // KHÔNG nằm trong userMap: không xuất hiện trong getAllUsers() (trang admin), không đăng nhập được,
    // không ghi vào users.json. Nếu có tài khoản THẬT trùng tên thì tài khoản thật được ưu tiên.
    private static final String ADMIN_USERNAME = "admin@pbl4.com";
    private static final Set<String> ALIAS_LOCAL_PARTS = Set.of("postmaster", "abuse");

    private static boolean isAliasName(String lowerName) {
        if (lowerName == null) return false;
        int at = lowerName.indexOf('@');
        if (at <= 0) return false; // chỉ nhận dạng đầy đủ local@domain, tránh nuốt tên trần "postmaster"
        if (!ALIAS_LOCAL_PARTS.contains(lowerName.substring(0, at))) return false;
        String domain = lowerName.substring(at + 1);
        return domain.equals(ServerConfig.MAIL_DOMAIN.toLowerCase(Locale.ROOT))
                || domain.equals("pbl4.com")
                || domain.equals("localhost");
    }

    // Tài khoản thật ứng với tên (đã trim + lower-case): chính nó, hoặc admin nếu là alias; null nếu không có.
    private static User resolveUser(String lowerName) {
        User direct = userMap.get(lowerName);
        if (direct != null) return direct;
        if (isAliasName(lowerName)) return userMap.get(ADMIN_USERNAME);
        return null;
    }

    public static User getUser(String username) {
        if (username == null) return null;
        ensureLoaded();
        return userMap.get(username.toLowerCase(Locale.ROOT).trim());
    }

    public static String findHash(String username) {
        User u = getUser(username);
        return u != null ? u.getPasswordHash() : null;
    }

    public static String findSalt(String username) {
        User u = getUser(username);
        return u != null ? u.getSalt() : null;
    }

    public static String getStatus(String username) {
        User u = getUser(username);
        return u != null ? u.getStatus() : "ACTIVE";
    }

    public static synchronized boolean addUserIfAbsent(String username, String passwordHash) {
        ensureLoaded();
        String key = username.toLowerCase(Locale.ROOT).trim();
        if (userMap.containsKey(key) || isAliasName(key)) {
            return false; // đã có người dùng, hoặc là tên alias dành riêng (postmaster@/abuse@)
        }
        byte[] saltBytes = SecurityUtils.generateSalt();
        String saltBase64 = Base64.getEncoder().encodeToString(saltBytes);
        String role = "admin@pbl4.com".equalsIgnoreCase(username) ? "ADMIN" : "USER";

        User user = new User(username.trim(), passwordHash, saltBase64, "ACTIVE", role);
        userMap.put(key, user);
        saveUsersToFile();
        return true;
    }

    public static synchronized void addUser(String username, String passwordHash) {
        byte[] saltBytes = SecurityUtils.generateSalt();
        String saltBase64 = Base64.getEncoder().encodeToString(saltBytes);
        String role = "admin@pbl4.com".equalsIgnoreCase(username) ? "ADMIN" : "USER";

        addUser(username, passwordHash, saltBase64, "ACTIVE", role);
    }

    public static synchronized void addUser(String username, String passwordHash, String saltBase64, String status, String role) {
        ensureLoaded();
        User user = new User(username.trim(), passwordHash, saltBase64, status, role);
        userMap.put(username.toLowerCase(Locale.ROOT).trim(), user);
        saveUsersToFile();
    }

    public static synchronized boolean setStatus(String username, String newStatus) {
        ensureLoaded();
        User u = userMap.get(username.toLowerCase(Locale.ROOT).trim());
        if (u != null) {
            u.setStatus(newStatus);
            saveUsersToFile();
            return true;
        }
        return false;
    }

    public static boolean deleteUser(String username) {
        if (username == null) return false;
        String storedName;
        synchronized (UserStore.class) {
            ensureLoaded();
            String key = username.toLowerCase(Locale.ROOT).trim();
            User removed = userMap.remove(key);
            if (removed == null) {
                return false;
            }
            storedName = removed.getUsername();
            saveUsersToFile();
        }

        // Dọn index/cache/lock của hòm thư trong RAM NGOÀI monitor của UserStore (tránh bế tắc với lock hòm thư).
        // Nhờ vậy đăng ký lại cùng tên không bị dính quota/danh sách thư của người dùng cũ.
        MailStorageEngine.evictUser(key(username));
        if (storedName != null && !storedName.trim().equalsIgnoreCase(username.trim())) {
            MailStorageEngine.evictUser(storedName);
        }
        return true;
    }

    private static String key(String username) {
        return username.trim();
    }

    public static synchronized List<User> getAllUsers() {
        ensureLoaded();
        return new ArrayList<>(userMap.values());
    }

    private static User parseUserObject(String objStr) {
        try {
            User u = new User();
            u.setUsername(extractField(objStr, "username"));
            u.setPasswordHash(extractField(objStr, "passwordHash"));
            u.setSalt(extractField(objStr, "salt"));

            String status = extractField(objStr, "status");
            u.setStatus(status != null ? status : "ACTIVE");

            String role = extractField(objStr, "role");
            u.setRole(role != null ? role : "USER");

            return u;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractField(String json, String field) {
        String key = "\"" + field + "\":";
        int start = json.indexOf(key);
        if (start == -1) return null;

        start += key.length();
        int quoteStart = json.indexOf("\"", start);
        if (quoteStart == -1) return null;

        int quoteEnd = json.indexOf("\"", quoteStart + 1);
        if (quoteEnd == -1) return null;

        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
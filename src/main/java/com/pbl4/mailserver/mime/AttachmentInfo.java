package com.pbl4.mailserver.mime;

/**
 * Một tệp đính kèm đã được giải mã (base64 / quoted-printable đã gỡ).
 * Chữ ký theo CONTRACT v2: (name, contentType, data). Không bao giờ null: name/contentType rỗng được thay bằng giá trị mặc định.
 */
public record AttachmentInfo(String name, String contentType, byte[] data) {

    public AttachmentInfo {
        name = name == null ? "" : name;
        contentType = (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;
        data = data == null ? new byte[0] : data;
    }

    /** Không in nội dung byte ra log. */
    @Override
    public String toString() {
        return "AttachmentInfo[name=" + name + ", contentType=" + contentType + ", size=" + data.length + "]";
    }
}
package com.pbl4.mailserver.mime;

public record AttachmentInfo(String name, String contentType, byte[] data) {

    public AttachmentInfo {
        name = name == null ? "" : name;
        contentType = (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;
        data = data == null ? new byte[0] : data;
    }

    @Override
    public String toString() {
        return "AttachmentInfo[name=" + name + ", contentType=" + contentType + ", size=" + data.length + "]";
    }
}

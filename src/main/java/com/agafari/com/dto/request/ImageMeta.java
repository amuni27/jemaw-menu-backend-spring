package com.agafari.com.dto.request;

import lombok.Data;

@Data
public class ImageMeta {
    private String fileName;     // e.g. burger.webp
    private String contentType;  // e.g. image/webp
}


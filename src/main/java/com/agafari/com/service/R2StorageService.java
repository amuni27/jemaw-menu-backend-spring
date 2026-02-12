package com.agafari.com.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface R2StorageService {

    String upload(String name, MultipartFile file) throws IOException;
    byte[] download(String name);
}

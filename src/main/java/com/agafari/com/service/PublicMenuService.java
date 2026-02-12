package com.agafari.com.service;


import com.agafari.com.dto.response.PublicBusinessMenuResponse;

public interface PublicMenuService {
    PublicBusinessMenuResponse getBusinessMenu(String businessId);
}

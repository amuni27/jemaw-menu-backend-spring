package com.agafari.com.dto.response;


import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PublicBusinessMenuResponse {
    private BusinessPublicResponse business;
    private List<MenuWithCategoriesResponse> menus;
}


package com.agafari.com.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;


import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PublicBusinessMenuResponse {
    private BusinessPublicResponse business;
    private List<MenuWithCategoriesResponse> menus;
    /** AGF E4-M7: present only for an eligible Model 2 restaurant with ads enabled; otherwise omitted (response unchanged). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private SponsoredAd sponsored;

    public PublicBusinessMenuResponse(BusinessPublicResponse business, List<MenuWithCategoriesResponse> menus) {
        this(business, menus, null);
    }
}


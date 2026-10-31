package com.agafari.com.dto.response;

import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.enums.SpiceLevel;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class MenuItemPublicResponse {
    private String id;
    private String name;
    private String description;
    private BigDecimal price;
    private String imageUrl;

    private Integer calories;
    private boolean isFeatured;
    private Integer prepTimeMinutes;

    private SpiceLevel spiceLevel;
    private MenuItemStatus status;

    private int sortOrder;

    private List<String> ingredients;
    private List<String> allergens;
    private List<String> tags;
    /** The restaurant's currency (AGF-S2-02) */
    private String currency;

    /** AGF-S2-25: only while a special applies now (active and below the regular price); otherwise absent */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.math.BigDecimal specialPrice;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String specialNote;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String specialNoteAm;
    /** When the current special period ends (instant), for live expiry on an open page; null = not today */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.time.Instant specialEndsAt;
    /** Today's local end time ("HH:mm") when the special has a daily window, for "until 18:00" */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String specialUntil;

    /** AGF-S2-23: Amharic translation, or null */
    private String nameAm;
    private String descriptionAm;
}

package com.agafari.com.dto.response;

import lombok.Builder;
import lombok.Data;

/** AGF-S2-18: the new menu and what was copied into it. */
@Data
@Builder
public class MenuDuplicateResponse {
    private MenuResponse menu;
    private int categoriesCopied;
    private int itemsCopied;
    /** Items copied without their image because the storage copy failed (D-S2-18-5). */
    private int imagesNotCopied;
}

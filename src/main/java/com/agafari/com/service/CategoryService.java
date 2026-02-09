package com.agafari.com.service;



import com.agafari.com.dto.request.CategoryCreateRequest;
import com.agafari.com.dto.request.CategoryUpdateRequest;
import com.agafari.com.dto.response.CategoryReorderRequest;
import com.agafari.com.dto.response.CategoryResponse;

import java.util.List;

public interface CategoryService {

    List<CategoryResponse> listByMenu(String menuId);

    CategoryResponse create(String menuId, CategoryCreateRequest request);

    CategoryResponse patch(String categoryId, CategoryUpdateRequest request);

    void delete(String categoryId);

    void reorder(String menuId, CategoryReorderRequest request);
}

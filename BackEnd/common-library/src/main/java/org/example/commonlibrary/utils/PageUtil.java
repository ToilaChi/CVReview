package org.example.commonlibrary.utils;

import org.example.commonlibrary.dto.response.PageResponse;
import org.springframework.data.domain.Page;

public class PageUtil {

    public static <T> PageResponse<T> toPageResponse(Page<T> page) {
        return PageResponse.<T>builder()
                .content(page.getContent())
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }
}

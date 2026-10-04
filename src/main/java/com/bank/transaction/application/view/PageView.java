package com.bank.transaction.application.view;

import java.util.List;

public record PageView<T>(List<T> items, int page, int size, long totalElements) {

    public PageView {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> PageView<T> of(List<T> items, PageRequest request, long totalElements) {
        return new PageView<>(items, request.page(), request.size(), totalElements);
    }
}

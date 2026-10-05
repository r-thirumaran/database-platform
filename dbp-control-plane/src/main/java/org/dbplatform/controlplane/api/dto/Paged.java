package org.dbplatform.controlplane.api.dto;

import java.util.List;

/** {@code { items, page, size, total }} — returned only when the caller passes a {@code page} parameter. */
public record Paged<T>(List<T> items, int page, int size, long total) {

    /** Returns the plain list when {@code page} is null, else a page slice. */
    public static <T> Object of(List<T> all, Integer page, Integer size) {
        if (page == null) return all;
        int p = Math.max(0, page);
        int s = size == null || size <= 0 ? 50 : Math.min(size, 1000);
        int from = Math.min(p * s, all.size());
        int to = Math.min(from + s, all.size());
        return new Paged<>(all.subList(from, to), p, s, all.size());
    }
}

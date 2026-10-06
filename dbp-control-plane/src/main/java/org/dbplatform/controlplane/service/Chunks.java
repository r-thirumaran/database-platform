package org.dbplatform.controlplane.service;

import java.util.ArrayList;
import java.util.List;

/** Splits work into slices that are committed one transaction each (short write transactions → short row-lock hold times). */
public final class Chunks {
    private Chunks() {}

    /** Consecutive views of at most {@code size} elements; empty for an empty or null list. */
    public static <T> List<List<T>> of(List<T> all, int size) {
        if (size < 1) throw new IllegalArgumentException("size must be >= 1");
        List<List<T>> out = new ArrayList<>();
        if (all == null) return out;
        for (int i = 0; i < all.size(); i += size) out.add(all.subList(i, Math.min(all.size(), i + size)));
        return out;
    }
}

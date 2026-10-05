package org.dbplatform.common.sql;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** Small thread-safe LRU cache (access order) used to memoise SQL analyses. */
final class LruCache<K, V> {

    private final int maxSize;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<K, V> map;

    LruCache(int maxSize) {
        this.maxSize = Math.max(0, maxSize);
        this.map = new LinkedHashMap<>(Math.min(Math.max(16, maxSize * 4 / 3 + 1), 1 << 16), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > LruCache.this.maxSize;
            }
        };
    }

    V get(K key) {
        if (maxSize == 0) {
            return null;
        }
        lock.lock();
        try {
            return map.get(key);
        } finally {
            lock.unlock();
        }
    }

    void put(K key, V value) {
        if (maxSize == 0) {
            return;
        }
        lock.lock();
        try {
            map.put(key, value);
        } finally {
            lock.unlock();
        }
    }

    int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    int maxSize() {
        return maxSize;
    }

    void clear() {
        lock.lock();
        try {
            map.clear();
        } finally {
            lock.unlock();
        }
    }
}

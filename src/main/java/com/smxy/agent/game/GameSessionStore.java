package com.smxy.agent.game;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量级游戏对局内存存储（带 TTL 过期）。
 * 用于海龟汤 / 趣味问答 / 数字炸弹 / 成语接龙等单局状态保存。
 */
public class GameSessionStore<T> {

    /** 超过该容量时触发一次过期清理，防止内存无限增长 */
    private static final int PURGE_THRESHOLD = 500;

    private final Map<String, Entry<T>> store = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public GameSessionStore(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    public void put(String id, T value) {
        if (store.size() >= PURGE_THRESHOLD) {
            purgeExpired();
        }
        store.put(id, new Entry<>(value, System.currentTimeMillis() + ttlMillis));
    }

    /** 读取对局状态；不存在或已过期返回 null */
    public T get(String id) {
        if (id == null) return null;
        Entry<T> e = store.get(id);
        if (e == null) return null;
        if (e.expireAt < System.currentTimeMillis()) {
            store.remove(id);
            return null;
        }
        return e.value;
    }

    public T remove(String id) {
        if (id == null) return null;
        Entry<T> e = store.remove(id);
        return e == null ? null : e.value;
    }

    public void purgeExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(en -> en.getValue().expireAt < now);
    }

    private static class Entry<T> {
        final T value;
        final long expireAt;

        Entry(T value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }
    }
}

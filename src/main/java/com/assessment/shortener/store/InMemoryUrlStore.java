package com.assessment.shortener.store;

import com.assessment.shortener.domain.ClickEvent;
import com.assessment.shortener.domain.ShortUrl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Thread-safe in-memory store. */
public class InMemoryUrlStore implements UrlStore {

    private final Map<String, ShortUrl> urls = new ConcurrentHashMap<>();
    private final Map<String, List<ClickEvent>> clicks = new ConcurrentHashMap<>();

    @Override
    public boolean putIfAbsent(ShortUrl url) {
        return urls.putIfAbsent(url.getCode(), url) == null;
    }

    @Override
    public Optional<ShortUrl> find(String code) {
        return Optional.ofNullable(urls.get(code));
    }

    @Override
    public boolean delete(String code) {
        clicks.remove(code);
        return urls.remove(code) != null;
    }

    @Override
    public void recordClick(ClickEvent event) {
        clicks.computeIfAbsent(event.getCode(), k -> new CopyOnWriteArrayList<>()).add(event);
    }

    @Override
    public List<ClickEvent> clicks(String code) {
        return Collections.unmodifiableList(new ArrayList<>(clicks.getOrDefault(code, Collections.emptyList())));
    }

    @Override
    public long count() {
        return urls.size();
    }
}

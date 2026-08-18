package com.assessment.shortener.store;

import com.assessment.shortener.domain.ClickEvent;
import com.assessment.shortener.domain.ShortUrl;

import java.util.List;
import java.util.Optional;

/**
 * Storage abstraction. The prototype ships an in-memory implementation;
 * a JDBC/Redis implementation can be swapped in without touching services.
 */
public interface UrlStore {

    /** Atomically stores the mapping if the code is free. Returns false on collision. */
    boolean putIfAbsent(ShortUrl url);

    Optional<ShortUrl> find(String code);

    boolean delete(String code);

    void recordClick(ClickEvent event);

    List<ClickEvent> clicks(String code);

    long count();
}

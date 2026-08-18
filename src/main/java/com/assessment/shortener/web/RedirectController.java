package com.assessment.shortener.web;

import com.assessment.shortener.domain.ShortUrl;
import com.assessment.shortener.service.UrlShortenerService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedirectController {

    private final UrlShortenerService service;

    public RedirectController(UrlShortenerService service) {
        this.service = service;
    }

    @GetMapping("/r/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code,
                                         @RequestHeader(value = "User-Agent", required = false) String userAgent,
                                         @RequestHeader(value = "Referer", required = false) String referrer) {
        ShortUrl url = service.resolveForRedirect(code, userAgent, referrer);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, url.getOriginalUrl())
                .build();
    }
}

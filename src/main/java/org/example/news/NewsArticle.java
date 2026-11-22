package org.example.news;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NewsArticle(
        String id,
        String region,
        String category,
        String title,
        String summary,
        List<String> highlights,
        String source,
        Instant publishedAt,
        String url,
        String imageUrl,
        String author,
        String sourceCountry,
        String language,
        String provider) {

    public NewsArticle {
        highlights = highlights == null ? List.of() : List.copyOf(highlights);
    }
}

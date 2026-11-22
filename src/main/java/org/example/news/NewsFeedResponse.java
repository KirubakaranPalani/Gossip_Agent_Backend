package org.example.news;

import java.time.Instant;
import java.util.List;

public record NewsFeedResponse(int page, boolean hasMore, Instant updatedAt, List<NewsArticle> articles) {
    public NewsFeedResponse {
        articles = List.copyOf(articles);
    }
}

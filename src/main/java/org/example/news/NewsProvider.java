package org.example.news;

import java.util.List;

public interface NewsProvider {
    String name();

    boolean enabled();

    default boolean searchOnly() {
        return false;
    }

    default boolean supportsSearch() {
        return false;
    }

    List<NewsArticle> fetch(String region, String query);
}

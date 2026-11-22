package org.example.api;

import java.util.Locale;
import java.util.Set;

import org.example.news.NewsFeedResponse;
import org.example.news.NewsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = {
        "https://gossip-agent.kirubakaran275p.workers.dev",
        "http://localhost:5173",
        "http://localhost:4173"
})
public class NewsController {
    private static final Set<String> REGIONS = Set.of("india", "world");
    private static final Set<String> CATEGORIES = Set.of(
            "all", "politics", "business", "technology", "science", "sports", "culture");

    private final NewsService newsService;

    public NewsController(NewsService newsService) {
        this.newsService = newsService;
    }

    @GetMapping("/api/v1/news")
    public NewsFeedResponse getNews(
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) String language) {
        String normalizedRegion = region == null ? "" : region.trim().toLowerCase(Locale.ROOT);
        if (!REGIONS.contains(normalizedRegion)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REGION", "region must be india or world");
        }

        String normalizedCategory = category == null || category.isBlank()
                ? "all"
                : category.trim().toLowerCase(Locale.ROOT);
        if (!CATEGORIES.contains(normalizedCategory)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_CATEGORY",
                    "category must be all, politics, business, technology, science, sports, or culture");
        }

        int pageNumber = parseInteger(page, "page", 0, true);
        int pageSize = parseInteger(limit, "limit", 20, false);
        if (query != null && query.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "q must be 500 characters or fewer");
        }
        String normalizedLanguage = language == null || language.isBlank()
                ? "en"
                : language.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("en", "ta").contains(normalizedLanguage)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LANGUAGE", "language must be en or ta");
        }
        return newsService.getFeed(
                normalizedRegion, normalizedCategory, pageNumber, pageSize, query, normalizedLanguage);
    }

    private int parseInteger(String value, String parameter, int defaultValue, boolean required) {
        if (value == null || value.isBlank()) {
            if (!required) {
                return defaultValue;
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "page is required and must be 0 or higher");
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || ("limit".equals(parameter) && parsed == 0)) {
                String message = "page".equals(parameter) ? "page must be 0 or higher" : "limit must be positive";
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_" + parameter.toUpperCase(Locale.ROOT), message);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            String message = "page".equals(parameter)
                    ? "page must be an integer 0 or higher"
                    : "limit must be a positive integer";
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_" + parameter.toUpperCase(Locale.ROOT), message);
        }
    }
}

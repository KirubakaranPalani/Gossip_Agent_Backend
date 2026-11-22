package org.example.news;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.example.api.ApiException;
import org.example.config.NewsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;

@Service
public class NewsService {
    private static final Logger log = LoggerFactory.getLogger(NewsService.class);
    private static final Duration FUTURE_CLOCK_SKEW = Duration.ofMinutes(10);
    private static final Duration TITLE_DEDUPE_WINDOW = Duration.ofHours(36);
    private static final int CACHE_LIMIT = 128;
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final List<NewsProvider> providers;
    private final NewsProperties properties;
    private final ArticleNormalizer normalizer;
    private final ArticleTranslationService translationService;
    private final Executor providerExecutor;
    private final ConcurrentHashMap<String, CachedArticles> cache = new ConcurrentHashMap<>();

    public NewsService(
            List<NewsProvider> providers,
            NewsProperties properties,
            ArticleNormalizer normalizer,
            ArticleTranslationService translationService,
            @Qualifier("newsProviderExecutor") Executor providerExecutor) {
        this.providers = List.copyOf(providers);
        this.properties = properties;
        this.normalizer = normalizer;
        this.translationService = translationService;
        this.providerExecutor = providerExecutor;
    }

    public NewsFeedResponse getFeed(String region, String category, int page, int limit, String query) {
        return getFeed(region, category, page, limit, query, "en");
    }

    public NewsFeedResponse getFeed(
            String region, String category, int page, int limit, String query, String language) {
        String normalizedQuery = query == null ? "" : query.trim();
        CachedArticles base = getOrLoad("feed:" + region,
                () -> fetchProviders(region, "", provider -> !provider.searchOnly(), false));

        List<NewsArticle> candidates = new ArrayList<>(base.articles());
        Instant updatedAt = base.updatedAt();
        if (!normalizedQuery.isBlank()) {
            CachedArticles search = getOrLoad("search:" + region + ":" + normalizedQuery.toLowerCase(Locale.ROOT),
                    () -> fetchProviders(region, normalizedQuery,
                            provider -> provider.searchOnly() || provider.supportsSearch(), true));
            candidates.addAll(search.articles());
            if (search.updatedAt().isAfter(updatedAt)) {
                updatedAt = search.updatedAt();
            }
        }

        Instant now = Instant.now();
        List<NewsArticle> articles = deduplicate(candidates).stream()
                .filter(article -> isFresh(article, now))
                .filter(article -> "all".equals(category) || category.equals(article.category()))
                .filter(article -> normalizedQuery.isBlank() || matches(article, normalizedQuery))
                .sorted(Comparator.comparing(NewsArticle::publishedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(NewsArticle::id))
                .toList();

        long startLong = (long) page * limit;
        if (startLong >= articles.size()) {
            return new NewsFeedResponse(page, false, updatedAt, List.of());
        }
        int start = (int) startLong;
        int end = (int) Math.min((long) start + limit, articles.size());
        List<NewsArticle> pageArticles = articles.subList(start, end);
        if ("ta".equals(language)) {
            pageArticles = translationService.translateToTamil(pageArticles);
        }
        return new NewsFeedResponse(page, end < articles.size(), updatedAt, pageArticles);
    }

    private CachedArticles getOrLoad(String key, Loader loader) {
        CachedArticles loaded = cache.compute(key, (cacheKey, cached) -> {
            Instant now = Instant.now();
            if (cached != null && cached.updatedAt().plus(properties.getCacheTtl()).isAfter(now)) {
                return cached;
            }
            return loader.load();
        });
        trimCache();
        return loaded;
    }

    private CachedArticles fetchProviders(
            String region,
            String query,
            Predicate<NewsProvider> selected,
            boolean emptyWhenNoProvider) {
        List<NewsProvider> active = providers.stream()
                .filter(NewsProvider::enabled)
                .filter(selected)
                .toList();
        if (active.isEmpty()) {
            if (emptyWhenNoProvider) {
                return new CachedArticles(Instant.now(), List.of());
            }
            throw unavailable("No news providers are enabled");
        }

        List<NewsArticle> articles = new ArrayList<>();
        int succeeded = 0;
        boolean rateLimited = false;
        List<CompletableFuture<ProviderResult>> requests = active.stream()
                .map(provider -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return new ProviderResult(provider, provider.fetch(region, query), null);
                    } catch (RuntimeException exception) {
                        return new ProviderResult(provider, List.of(), exception);
                    }
                }, providerExecutor))
                .toList();
        for (CompletableFuture<ProviderResult> request : requests) {
            ProviderResult result;
            try {
                result = request.join();
            } catch (RuntimeException exception) {
                // Also handle executor failures (for example, a saturated provider pool).
                log.warn("A news provider task failed for region {} ({})", region,
                        exception.getClass().getSimpleName());
                continue;
            }
            if (result.failure() == null) {
                if (result.articles() != null) {
                    articles.addAll(result.articles());
                }
                succeeded++;
            } else {
                RuntimeException exception = result.failure();
                rateLimited |= isRateLimited(exception);
                // Do not log exception messages: API request URIs from some providers contain the key.
                log.warn("News provider {} failed for region {} ({})", result.provider().name(), region,
                        exception.getClass().getSimpleName());
            }
        }
        if (succeeded == 0) {
            if (rateLimited) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "NEWS_PROVIDER_RATE_LIMITED",
                        "News providers are rate limiting requests; retry later");
            }
            throw unavailable("News providers are temporarily unavailable");
        }
        return new CachedArticles(Instant.now(), List.copyOf(articles));
    }

    private List<NewsArticle> deduplicate(List<NewsArticle> candidates) {
        Instant now = Instant.now();
        List<NewsArticle> ordered = candidates.stream()
                .filter(article -> article != null && article.url() != null && !article.url().isBlank())
                .sorted(Comparator.comparingInt(this::providerPriority)
                        .thenComparing(Comparator.comparingInt(this::completeness).reversed())
                        .thenComparing(NewsArticle::id))
                .toList();

        Map<String, Integer> byUrl = new HashMap<>();
        Map<String, List<Integer>> byTitle = new HashMap<>();
        List<NewsArticle> result = new ArrayList<>();
        for (NewsArticle article : ordered) {
            String urlKey = normalizer.canonicalUrl(article.url());
            String titleKey = titleKey(article.title());
            Integer duplicateIndex = byUrl.get(urlKey);
            if (duplicateIndex == null && !titleKey.isBlank()) {
                for (Integer titleIndex : byTitle.getOrDefault(titleKey, List.of())) {
                    if (withinDedupeWindow(result.get(titleIndex).publishedAt(), article.publishedAt(), now)) {
                        duplicateIndex = titleIndex;
                        break;
                    }
                }
            }
            if (duplicateIndex == null) {
                int newIndex = result.size();
                result.add(article);
                byUrl.put(urlKey, newIndex);
                if (!titleKey.isBlank()) {
                    byTitle.computeIfAbsent(titleKey, ignored -> new ArrayList<>()).add(newIndex);
                }
            } else {
                NewsArticle merged = merge(result.get(duplicateIndex), article);
                result.set(duplicateIndex, merged);
                byUrl.put(urlKey, duplicateIndex);
                String mergedTitleKey = titleKey(merged.title());
                if (!mergedTitleKey.isBlank()) {
                    List<Integer> matchingTitles = byTitle.computeIfAbsent(mergedTitleKey, ignored -> new ArrayList<>());
                    if (!matchingTitles.contains(duplicateIndex)) matchingTitles.add(duplicateIndex);
                }
            }
        }
        return result;
    }

    private NewsArticle merge(NewsArticle preferred, NewsArticle other) {
        List<String> highlights = new ArrayList<>(preferred.highlights());
        Set<String> existing = new HashSet<>(highlights);
        for (String highlight : other.highlights()) {
            if (highlight != null && !highlight.isBlank() && existing.add(highlight)) {
                highlights.add(highlight);
            }
        }
        Instant publishedAt = preferred.publishedAt() == null ? other.publishedAt()
                : other.publishedAt() == null || preferred.publishedAt().isAfter(other.publishedAt())
                        ? preferred.publishedAt() : other.publishedAt();
        return new NewsArticle(
                preferred.id(),
                preferred.region(),
                preferred.category() == null ? other.category() : preferred.category(),
                firstNonBlank(preferred.title(), other.title()),
                betterSummary(preferred.summary(), preferred.title(), other.summary(), other.title()),
                highlights,
                firstNonBlank(preferred.source(), other.source()),
                publishedAt,
                preferred.url(),
                firstNonBlank(preferred.imageUrl(), other.imageUrl()),
                firstNonBlank(preferred.author(), other.author()),
                firstNonBlank(preferred.sourceCountry(), other.sourceCountry()),
                firstNonBlank(preferred.language(), other.language()),
                preferred.provider());
    }

    private boolean isFresh(NewsArticle article, Instant now) {
        Instant publishedAt = article.publishedAt();
        return publishedAt != null
                && !publishedAt.isBefore(now.minus(properties.getMaxArticleAge()))
                && !publishedAt.isAfter(now.plus(FUTURE_CLOCK_SKEW));
    }

    private boolean matches(NewsArticle article, String query) {
        String searchable = (article.title() + " " + article.summary() + " " + article.source())
                .toLowerCase(Locale.ROOT);
        return searchable.contains(query.toLowerCase(Locale.ROOT));
    }

    private boolean withinDedupeWindow(Instant first, Instant second, Instant now) {
        if (first == null || second == null) {
            return false;
        }
        Duration difference = Duration.between(first, second).abs();
        return difference.compareTo(TITLE_DEDUPE_WINDOW) <= 0
                && !first.isAfter(now.plus(FUTURE_CLOCK_SKEW))
                && !second.isAfter(now.plus(FUTURE_CLOCK_SKEW));
    }

    private int providerPriority(NewsArticle article) {
        String provider = article.provider() == null ? "" : article.provider().toLowerCase(Locale.ROOT);
        if (provider.contains("rss") || provider.contains("guardian") || provider.contains("new york times")) {
            return 0;
        }
        if (provider.contains("gdelt")) {
            return 1;
        }
        if (provider.contains("newsapi")) {
            return 2;
        }
        return 3;
    }

    private int completeness(NewsArticle article) {
        int score = 0;
        if (hasText(article.summary()) && !article.summary().equals(article.title())) score += 2;
        if (hasText(article.imageUrl())) score++;
        if (hasText(article.author())) score++;
        if (!article.highlights().isEmpty()) score++;
        if (hasText(article.source())) score++;
        return score;
    }

    private String betterSummary(
            String preferredSummary,
            String preferredTitle,
            String otherSummary,
            String otherTitle) {
        if (hasText(preferredSummary) && !preferredSummary.equals(preferredTitle)) return preferredSummary;
        if (hasText(otherSummary) && !otherSummary.equals(otherTitle)) return otherSummary;
        return firstNonBlank(preferredSummary, otherSummary);
    }

    private String titleKey(String title) {
        if (title == null) return "";
        return NON_WORD.matcher(title.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) return value;
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isRateLimited(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof HttpStatusCodeException statusException
                    && statusException.getStatusCode().value() == 429) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private ApiException unavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NEWS_PROVIDERS_UNAVAILABLE", message);
    }

    private void trimCache() {
        if (cache.size() <= CACHE_LIMIT) return;
        cache.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().updatedAt()))
                .limit(cache.size() - CACHE_LIMIT)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(cache::remove);
    }

    private record CachedArticles(Instant updatedAt, List<NewsArticle> articles) {}

    private record ProviderResult(NewsProvider provider, List<NewsArticle> articles, RuntimeException failure) {}

    @FunctionalInterface
    private interface Loader {
        CachedArticles load();
    }
}

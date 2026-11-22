package org.example.news;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.NewsProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class NewsApiProvider implements NewsProvider {
    private static final String ENDPOINT = "https://newsapi.org/v2/everything";

    private final NewsProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ArticleNormalizer normalizer;

    public NewsApiProvider(
            NewsProperties properties,
            RestClient newsRestClient,
            ObjectMapper objectMapper,
            ArticleNormalizer normalizer) {
        this.properties = properties;
        this.restClient = newsRestClient;
        this.objectMapper = objectMapper;
        this.normalizer = normalizer;
    }

    @Override
    public String name() {
        return "NewsAPI";
    }

    @Override
    public boolean enabled() {
        return properties.isNewsApiEnabled()
                && properties.getNewsApiKey() != null
                && !properties.getNewsApiKey().isBlank();
    }

    @Override
    public boolean searchOnly() {
        return true;
    }

    @Override
    public boolean supportsSearch() {
        return true;
    }

    @Override
    public List<NewsArticle> fetch(String region, String query) {
        // NewsAPI's Everything endpoint requires a search term. It is only used
        // for explicit searches, avoiding low-value default queries and quota use.
        if (query == null || query.isBlank()) {
            return List.of();
        }
        URI uri = UriComponentsBuilder.fromUriString(ENDPOINT)
                .queryParam("q", query)
                .queryParam("pageSize", 100)
                .queryParam("page", 1)
                .queryParam("sortBy", "publishedAt")
                .queryParam("language", "en")
                .build()
                .encode()
                .toUri();

        String body = restClient.get()
                .uri(uri)
                .header(HttpHeaders.ACCEPT, "application/json")
                .header("X-Api-Key", properties.getNewsApiKey())
                .retrieve()
                .body(String.class);
        if (body == null || body.isBlank()) {
            return List.of();
        }

        try {
            JsonNode response = objectMapper.readTree(body);
            if (!"ok".equals(response.path("status").asText())) {
                throw new IllegalStateException("NewsAPI returned an error response");
            }
            JsonNode rows = response.path("articles");
            List<NewsArticle> articles = new ArrayList<>();
            if (!rows.isArray()) {
                return articles;
            }
            for (JsonNode row : rows) {
                String url = text(row, "url");
                String title = text(row, "title");
                Instant publishedAt = parseDate(text(row, "publishedAt"));
                if (url.isBlank() || title.isBlank() || publishedAt == null) {
                    continue;
                }
                NewsSourceCatalog.Publisher publisher = NewsSourceCatalog.fromUrl(url);
                if (publisher == null || ("india".equals(region) && !"India".equals(publisher.country()))) {
                    continue;
                }
                articles.add(normalizer.create(
                        region,
                        title,
                        firstNonBlank(text(row, "description"), text(row, "content")),
                        publisher.name(),
                        publisher.country(),
                        text(row, "author"),
                        publishedAt,
                        url,
                        text(row, "urlToImage"),
                        "en",
                        name(),
                        ""));
            }
            return articles;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not parse NewsAPI response", exception);
        }
    }

    private String text(JsonNode node, String property) {
        JsonNode value = node.get(property);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private Instant parseDate(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception ignored) {
            try {
                return Instant.parse(value);
            } catch (Exception alsoIgnored) {
                return null;
            }
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}

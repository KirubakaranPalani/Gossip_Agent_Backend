package org.example.news;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.NewsProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GuardianNewsProvider implements NewsProvider {
    private static final String ENDPOINT = "https://content.guardianapis.com/search";

    private final NewsProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ArticleNormalizer normalizer;

    public GuardianNewsProvider(
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
        return "Guardian Open Platform";
    }

    @Override
    public boolean enabled() {
        return properties.getGuardianApiKey() != null && !properties.getGuardianApiKey().isBlank();
    }

    @Override
    public List<NewsArticle> fetch(String region, String query) {
        if (!"world".equals(region)) {
            return List.of();
        }
        URI uri = UriComponentsBuilder.fromUriString(ENDPOINT)
                .queryParam("order-by", "newest")
                .queryParam("page-size", 50)
                .queryParam("show-fields", "trailText,thumbnail,byline,firstPublicationDate")
                .queryParam("api-key", properties.getGuardianApiKey())
                .build()
                .encode()
                .toUri();
        String body = restClient.get().uri(uri).retrieve().body(String.class);
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            JsonNode rows = objectMapper.readTree(body).path("response").path("results");
            List<NewsArticle> articles = new ArrayList<>();
            if (!rows.isArray()) {
                return articles;
            }
            for (JsonNode row : rows) {
                String url = text(row, "webUrl");
                String title = text(row, "webTitle");
                Instant publishedAt = parseDate(text(row, "webPublicationDate"));
                if (url.isBlank() || title.isBlank() || publishedAt == null) {
                    continue;
                }
                JsonNode fields = row.path("fields");
                articles.add(normalizer.create(
                        region,
                        title,
                        text(fields, "trailText"),
                        "The Guardian",
                        "United Kingdom",
                        text(fields, "byline"),
                        publishedAt,
                        url,
                        text(fields, "thumbnail"),
                        "en",
                        name(),
                        text(row, "sectionName")));
            }
            return articles;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not parse Guardian API response", exception);
        }
    }

    private String text(JsonNode node, String property) {
        JsonNode value = node.get(property);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private Instant parseDate(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }
}

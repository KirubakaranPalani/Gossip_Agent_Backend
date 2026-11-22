package org.example.news;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.NewsProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class NyTimesNewsProvider implements NewsProvider {
    private static final String ENDPOINT = "https://api.nytimes.com/svc/topstories/v2/world.json";

    private final NewsProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ArticleNormalizer normalizer;

    public NyTimesNewsProvider(
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
        return "New York Times Top Stories API";
    }

    @Override
    public boolean enabled() {
        return properties.getNyTimesApiKey() != null && !properties.getNyTimesApiKey().isBlank();
    }

    @Override
    public List<NewsArticle> fetch(String region, String query) {
        if (!"world".equals(region)) {
            return List.of();
        }
        URI uri = UriComponentsBuilder.fromUriString(ENDPOINT)
                .queryParam("api-key", properties.getNyTimesApiKey())
                .build()
                .encode()
                .toUri();
        String body = restClient.get().uri(uri).retrieve().body(String.class);
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            JsonNode rows = objectMapper.readTree(body).path("results");
            List<NewsArticle> articles = new ArrayList<>();
            if (!rows.isArray()) {
                return articles;
            }
            for (JsonNode row : rows) {
                String url = text(row, "url");
                String title = firstNonBlank(text(row, "title"), text(row, "title_display"));
                Instant publishedAt = parseDate(firstNonBlank(text(row, "published_date"), text(row, "updated_date")));
                if (url.isBlank() || title.isBlank() || publishedAt == null) {
                    continue;
                }
                String section = firstNonBlank(text(row, "section"), text(row, "subsection"));
                articles.add(normalizer.create(
                        region,
                        title,
                        text(row, "abstract"),
                        "The New York Times",
                        "United States",
                        text(row.path("byline"), "original"),
                        publishedAt,
                        url,
                        imageUrl(row.path("multimedia")),
                        "en",
                        name(),
                        section));
            }
            return articles;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not parse New York Times API response", exception);
        }
    }

    private String imageUrl(JsonNode multimedia) {
        if (!multimedia.isArray()) {
            return "";
        }
        for (JsonNode item : multimedia) {
            String url = text(item, "url");
            if (!url.isBlank()) {
                return url.startsWith("http") ? url : "https://static01.nyt.com/" + url.replaceFirst("^/", "");
            }
        }
        return "";
    }

    private String text(JsonNode node, String property) {
        JsonNode value = node.get(property);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private Instant parseDate(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            try {
                return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
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

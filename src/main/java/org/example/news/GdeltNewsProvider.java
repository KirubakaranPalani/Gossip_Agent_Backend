package org.example.news;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.NewsProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GdeltNewsProvider implements NewsProvider {
    private static final DateTimeFormatter GDELT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT);

    private final NewsProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ArticleNormalizer normalizer;

    public GdeltNewsProvider(
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
        return "GDELT";
    }

    @Override
    public boolean enabled() {
        return properties.isGdeltEnabled();
    }

    @Override
    public boolean supportsSearch() {
        return true;
    }

    @Override
    public List<NewsArticle> fetch(String region, String query) {
        List<String> domains = NewsSourceCatalog.forRegion(region).stream()
                .flatMap(publisher -> publisher.domains().stream())
                .distinct()
                .toList();
        String gdeltQuery = domains.stream()
                .map(domain -> "domain:" + domain)
                .collect(Collectors.joining(" OR ", "(", ")"));
        if (query != null && !query.isBlank()) {
            gdeltQuery += " \"" + query.replace("\"", "") + "\"";
        }
        URI uri = UriComponentsBuilder.fromUriString(properties.getGdeltEndpoint())
                .queryParam("query", gdeltQuery)
                .queryParam("mode", "ArtList")
                .queryParam("format", "json")
                .queryParam("sort", "datedesc")
                .queryParam("maxrecords", 250)
                .queryParam("timespan", "1week")
                .build()
                .encode()
                .toUri();

        String body = restClient.get().uri(uri).retrieve().body(String.class);
        if (body == null || body.isBlank()) {
            return List.of();
        }

        try {
            JsonNode response = objectMapper.readTree(body);
            JsonNode rows = response.path("articles");
            List<NewsArticle> articles = new ArrayList<>();
            if (!rows.isArray()) {
                return articles;
            }
            for (JsonNode row : rows) {
                String url = text(row, "url");
                String title = text(row, "title");
                Instant publishedAt = parseGdeltDate(text(row, "seendate"));
                if (url.isBlank() || title.isBlank() || publishedAt == null) {
                    continue;
                }
                NewsSourceCatalog.Publisher publisher = NewsSourceCatalog.fromUrl(url);
                String source = publisher == null ? text(row, "domain") : publisher.name();
                String sourceCountry = publisher == null ? text(row, "sourcecountry") : publisher.country();
                articles.add(normalizer.create(
                        region,
                        title,
                        "",
                        source.isBlank() ? "GDELT" : source,
                        sourceCountry,
                        "",
                        publishedAt,
                        url,
                        text(row, "socialimage"),
                        text(row, "language"),
                        name(),
                        ""));
            }
            return articles;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not parse GDELT response", exception);
        }
    }

    private String text(JsonNode node, String property) {
        JsonNode value = node.get(property);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private Instant parseGdeltDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (Exception ignored) {
            // GDELT normally uses yyyyMMdd'T'HHmmss in seendate.
        }
        if (trimmed.endsWith("Z")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        try {
            return LocalDateTime.parse(trimmed, GDELT_DATE).toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {
            return null;
        }
    }
}

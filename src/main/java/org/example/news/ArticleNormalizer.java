package org.example.news;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class ArticleNormalizer {
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public NewsArticle create(
            String region,
            String title,
            String description,
            String source,
            String sourceCountry,
            String author,
            Instant publishedAt,
            String url,
            String imageUrl,
            String language,
            String provider,
            String sourceCategory) {
        String cleanTitle = clean(title);
        String cleanSummary = clean(description);
        if (cleanSummary.isBlank()) {
            // GDELT supplies headlines and links, but not an article excerpt.
            // Keep the API's required summary field useful without generating text.
            cleanSummary = cleanTitle;
        }

        List<String> highlights = clean(description).isBlank() ? List.of() : List.of(cleanSummary);
        String category = classify(cleanTitle + " " + cleanSummary, sourceCategory);
        return new NewsArticle(
                stableId(url),
                region,
                category,
                cleanTitle,
                cleanSummary,
                highlights,
                clean(source),
                publishedAt,
                url,
                blankToNull(imageUrl),
                blankToNull(clean(author)),
                blankToNull(clean(sourceCountry)),
                blankToNull(clean(language)),
                provider);
    }

    public String clean(String input) {
        if (input == null) {
            return "";
        }
        String value = input.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ");
        value = HTML_TAG.matcher(value).replaceAll(" ");
        return WHITESPACE.matcher(value).replaceAll(" ").trim();
    }

    public String stableId(String url) {
        String stableUrl = canonicalUrl(url);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(stableUrl.getBytes(StandardCharsets.UTF_8));
            return "story-" + HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    public String canonicalUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(url).normalize();
            if (uri.getScheme() == null || uri.getHost() == null) {
                return uri.toASCIIString();
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) {
                port = -1;
            }
            String authority = uri.getRawUserInfo() == null ? "" : uri.getRawUserInfo() + "@";
            authority += host.contains(":") ? "[" + host + "]" : host;
            if (port >= 0) {
                authority += ":" + port;
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }
            List<String> queryParts = new ArrayList<>();
            if (uri.getRawQuery() != null) {
                for (String part : uri.getRawQuery().split("&")) {
                    String key = part.contains("=") ? part.substring(0, part.indexOf('=')) : part;
                    String decodedKey = URLDecoder.decode(key, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    if (decodedKey.startsWith("utm_")
                            || List.of("fbclid", "gclid", "dclid", "mc_cid", "mc_eid", "ocid").contains(decodedKey)) {
                        continue;
                    }
                    queryParts.add(part);
                }
            }
            queryParts.sort(Comparator.naturalOrder());
            String query = queryParts.isEmpty() ? "" : "?" + String.join("&", queryParts);
            return URI.create(scheme + "://" + authority + path + query).normalize().toASCIIString();
        } catch (Exception ignored) {
            return url.trim();
        }
    }

    private String classify(String text, String sourceCategory) {
        String normalized = (text + " " + (sourceCategory == null ? "" : sourceCategory)).toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "election", "parliament", "minister", "government", "president", "politic", "congress", "court", "policy")) {
            return "politics";
        }
        if (containsAny(normalized, "market", "stock", "business", "econom", "company", "trade", "bank", "inflation", "finance")) {
            return "business";
        }
        if (containsAny(normalized, "technology", "tech", "software", "artificial intelligence", "startup", "cyber", "chip", "device")) {
            return "technology";
        }
        if (containsAny(normalized, "science", "research", "climate", "space", "health", "medical", "scientist", "study")) {
            return "science";
        }
        if (containsAny(normalized, "sport", "cricket", "football", "soccer", "tennis", "olympic", "nba", "ipl")) {
            return "sports";
        }
        if (containsAny(normalized, "film", "music", "culture", "arts", "book", "celebrity", "festival", "entertainment")) {
            return "culture";
        }
        return "culture";
    }

    private boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

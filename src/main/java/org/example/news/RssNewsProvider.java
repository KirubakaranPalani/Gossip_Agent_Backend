package org.example.news;

import java.io.StringReader;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.example.config.NewsProperties;
import org.example.config.NewsProperties.FeedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@Component
public class RssNewsProvider implements NewsProvider {
    private static final Logger log = LoggerFactory.getLogger(RssNewsProvider.class);
    private static final List<String> DATE_FIELDS = List.of("pubDate", "published", "updated", "date", "created");

    private final NewsProperties properties;
    private final RestClient restClient;
    private final ArticleNormalizer normalizer;
    private final Executor feedExecutor;

    public RssNewsProvider(
            NewsProperties properties,
            RestClient newsRestClient,
            ArticleNormalizer normalizer,
            @Qualifier("newsFeedExecutor") Executor feedExecutor) {
        this.properties = properties;
        this.restClient = newsRestClient;
        this.normalizer = normalizer;
        this.feedExecutor = feedExecutor;
    }

    @Override
    public String name() {
        return "publisher-rss";
    }

    @Override
    public boolean enabled() {
        return properties.getIndiaFeeds().stream().anyMatch(FeedSource::isEnabled)
                || properties.getUsFeeds().stream().anyMatch(FeedSource::isEnabled)
                || properties.getChinaFeeds().stream().anyMatch(FeedSource::isEnabled)
                || properties.getWorldFeeds().stream().anyMatch(FeedSource::isEnabled);
    }

    @Override
    public List<NewsArticle> fetch(String region, String query) {
        List<FeedSource> feeds = properties.feedsFor(region).stream().filter(FeedSource::isEnabled).toList();
        List<NewsArticle> articles = new ArrayList<>();
        int successfulFeeds = 0;
        RuntimeException lastFailure = null;

        List<CompletableFuture<FeedResult>> requests = feeds.stream()
                .map(feed -> CompletableFuture.supplyAsync(() -> fetchFeed(feed, region), feedExecutor))
                .toList();
        for (int i = 0; i < requests.size(); i++) {
            FeedResult result;
            try {
                result = requests.get(i).join();
            } catch (RuntimeException exception) {
                result = new FeedResult(List.of(), new IllegalStateException("RSS feed task failed", exception));
            }
            if (result.failure() == null) {
                successfulFeeds++;
                articles.addAll(result.articles());
            } else {
                lastFailure = result.failure();
                log.warn("RSS feed {} failed", feeds.get(i).getPublisher(), result.failure());
            }
        }

        if (!feeds.isEmpty() && successfulFeeds == 0) {
            throw lastFailure == null ? new IllegalStateException("All configured RSS feeds failed") : lastFailure;
        }
        return articles;
    }

    private FeedResult fetchFeed(FeedSource feed, String region) {
        try {
            String xml = restClient.get().uri(feed.getUrl()).retrieve().body(String.class);
            if (xml == null || xml.isBlank()) {
                return new FeedResult(List.of(), null);
            }
            return new FeedResult(parseFeed(xml, feed, region), null);
        } catch (Exception exception) {
            return new FeedResult(List.of(), new IllegalStateException("RSS feed failed: " + feed.getPublisher(), exception));
        }
    }

    private List<NewsArticle> parseFeed(String xml, FeedSource feed, String region) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            List<Element> entries = elements(document, "item");
            if (entries.isEmpty()) {
                entries = elements(document, "entry");
            }

            List<NewsArticle> articles = new ArrayList<>();
            for (Element entry : entries) {
                String title = childText(entry, "title");
                String url = firstNonBlank(childText(entry, "link"), linkHref(entry));
                String description = firstNonBlank(
                        childText(entry, "description"),
                        childText(entry, "summary"),
                        childText(entry, "content"),
                        childText(entry, "encoded"));
                String dateText = firstNonBlank(childText(entry, DATE_FIELDS.toArray(String[]::new)));
                Optional<Instant> publishedAt = parseDate(dateText);
                if (title.isBlank() || url.isBlank() || publishedAt.isEmpty() || !isHttpUrl(url)) {
                    continue;
                }

                String imageUrl = firstNonBlank(enclosureUrl(entry), mediaContentUrl(entry));
                String sourceCategory = childText(entry, "category");
                String author = firstNonBlank(childText(entry, "creator"), childText(entry, "author"));
                articles.add(normalizer.create(
                        region,
                        title,
                        description,
                        feed.getPublisher(),
                        feed.getSourceCountry(),
                        author,
                        publishedAt.get(),
                        url.trim(),
                        imageUrl,
                        feed.getLanguage(),
                        name(),
                        sourceCategory));
            }
            return articles;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not parse RSS from " + feed.getPublisher(), exception);
        }
    }

    private List<Element> elements(Document document, String wantedName) {
        List<Element> result = new ArrayList<>();
        NodeList all = document.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node node = all.item(i);
            if (node instanceof Element element && localName(element).equalsIgnoreCase(wantedName)) {
                result.add(element);
            }
        }
        return result;
    }

    private List<Element> elements(Element parent, String wantedName) {
        List<Element> result = new ArrayList<>();
        NodeList all = parent.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node node = all.item(i);
            if (node instanceof Element element && localName(element).equalsIgnoreCase(wantedName)) {
                result.add(element);
            }
        }
        return result;
    }

    private String childText(Element parent, String... wantedNames) {
        for (String wantedName : wantedNames) {
            for (Element element : elements(parent, wantedName)) {
                if (!element.hasAttribute("href")) {
                    String text = element.getTextContent();
                    if (text != null && !text.isBlank()) {
                        return text.trim();
                    }
                }
            }
        }
        return "";
    }

    private String linkHref(Element parent) {
        for (Element element : elements(parent, "link")) {
            if (element.hasAttribute("href")) {
                String rel = element.getAttribute("rel");
                if (rel.isBlank() || rel.equalsIgnoreCase("alternate")) {
                    return element.getAttribute("href").trim();
                }
            }
        }
        return "";
    }

    private String enclosureUrl(Element parent) {
        for (Element element : elements(parent, "enclosure")) {
            if (element.hasAttribute("url")) {
                return element.getAttribute("url").trim();
            }
        }
        return "";
    }

    private String mediaContentUrl(Element parent) {
        for (Element element : elements(parent, "content")) {
            if (element.hasAttribute("url")) {
                return element.getAttribute("url").trim();
            }
        }
        return "";
    }

    private Optional<Instant> parseDate(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        try {
            return Optional.of(Instant.parse(trimmed));
        } catch (Exception ignored) {
            // Continue with the date formats used by RSS and Atom publishers.
        }
        try {
            return Optional.of(OffsetDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (Exception ignored) {
            // Some feeds use ISO offsets that RFC 1123 does not accept.
        }
        try {
            return Optional.of(OffsetDateTime.parse(trimmed).toInstant());
        } catch (Exception ignored) {
            // A final attempt for an RFC 1123 date with a named zone.
        }
        try {
            return Optional.of(ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private boolean isHttpUrl(String value) {
        return value.startsWith("https://") || value.startsWith("http://");
    }

    private String localName(Element element) {
        String localName = element.getLocalName();
        if (localName != null) {
            return localName;
        }
        String name = element.getTagName();
        int separator = name.indexOf(':');
        return separator >= 0 ? name.substring(separator + 1) : name;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private record FeedResult(List<NewsArticle> articles, RuntimeException failure) {}
}

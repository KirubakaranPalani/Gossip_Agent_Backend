package org.example.news;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Central registry for the publisher domains used by discovery and normalization. */
public final class NewsSourceCatalog {
    private static final List<Publisher> INDIA = List.of(
            publisher("Times of India", "India", "timesofindia.indiatimes.com"),
            publisher("NDTV", "India", "ndtv.com"),
            publisher("The Hindu", "India", "thehindu.com"),
            publisher("The Indian Express", "India", "indianexpress.com"),
            publisher("Hindustan Times", "India", "hindustantimes.com"),
            publisher("Mint", "India", "livemint.com"),
            publisher("The Economic Times", "India", "economictimes.indiatimes.com"),
            publisher("Deccan Herald", "India", "deccanherald.com"),
            publisher("The Telegraph India", "India", "telegraphindia.com"),
            publisher("Press Trust of India", "India", "ptinews.com"));

    private static final List<Publisher> UNITED_STATES = List.of(
            publisher("Associated Press", "United States", "apnews.com"),
            publisher("The New York Times", "United States", "nytimes.com"),
            publisher("The Washington Post", "United States", "washingtonpost.com"),
            publisher("NPR", "United States", "npr.org"),
            publisher("CNN", "United States", "cnn.com"),
            publisher("ABC News", "United States", "abcnews.go.com"),
            publisher("CBS News", "United States", "cbsnews.com"),
            publisher("Fox News", "United States", "foxnews.com"),
            publisher("NBC News", "United States", "nbcnews.com"),
            publisher("The Wall Street Journal", "United States", "wsj.com"));

    private static final List<Publisher> CHINA = List.of(
            publisher("Xinhua", "China", "news.cn", "xinhuanet.com"),
            publisher("CGTN", "China", "cgtn.com"),
            publisher("China Daily", "China", "chinadaily.com.cn"),
            publisher("Global Times", "China", "globaltimes.cn"),
            publisher("China News Service / ECNS", "China", "ecns.cn"),
            publisher("People's Daily", "China", "people.cn"),
            publisher("Caixin Global", "China", "caixinglobal.com"),
            publisher("Sixth Tone", "China", "sixthtone.com"),
            publisher("Yicai Global", "China", "yicaiglobal.com"),
            publisher("South China Morning Post", "Hong Kong", "scmp.com"));

    private static final List<Publisher> WORLDWIDE = List.of(
            publisher("BBC News", "United Kingdom", "bbc.com", "bbc.co.uk"),
            publisher("Reuters", "United Kingdom / Global", "reuters.com"),
            publisher("The Guardian", "United Kingdom", "theguardian.com"),
            publisher("Al Jazeera", "Qatar", "aljazeera.com"),
            publisher("Deutsche Welle", "Germany", "dw.com"),
            publisher("France 24", "France", "france24.com"),
            publisher("Euronews", "Europe", "euronews.com"),
            publisher("Sky News", "United Kingdom", "news.sky.com"),
            publisher("NHK World", "Japan", "nhk.or.jp"),
            publisher("ABC News Australia", "Australia", "abc.net.au"));

    private static final Map<String, Publisher> BY_DOMAIN = buildDomainIndex();

    private NewsSourceCatalog() {}

    public static List<Publisher> forRegion(String region) {
        if ("india".equals(region)) {
            return INDIA;
        }
        Map<String, Publisher> all = new LinkedHashMap<>();
        addAll(all, INDIA);
        addAll(all, UNITED_STATES);
        addAll(all, CHINA);
        addAll(all, WORLDWIDE);
        return List.copyOf(all.values());
    }

    public static Publisher fromUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return null;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            Publisher exact = BY_DOMAIN.get(normalizedHost);
            if (exact != null) {
                return exact;
            }
            return BY_DOMAIN.entrySet().stream()
                    .filter(entry -> normalizedHost.endsWith("." + entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, Publisher> buildDomainIndex() {
        Map<String, Publisher> index = new LinkedHashMap<>();
        addAll(index, INDIA);
        addAll(index, UNITED_STATES);
        addAll(index, CHINA);
        addAll(index, WORLDWIDE);
        return Map.copyOf(index);
    }

    private static void addAll(Map<String, Publisher> target, List<Publisher> publishers) {
        for (Publisher publisher : publishers) {
            for (String domain : publisher.domains()) {
                target.put(domain, publisher);
            }
        }
    }

    private static Publisher publisher(String name, String country, String... domains) {
        return new Publisher(name, country, List.of(domains));
    }

    public record Publisher(String name, String country, List<String> domains) {
        public Publisher {
            domains = List.copyOf(domains);
        }
    }
}

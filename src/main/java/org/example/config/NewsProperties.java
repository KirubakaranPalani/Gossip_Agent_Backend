package org.example.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gossip.news")
public class NewsProperties {
    private Duration cacheTtl = Duration.ofMinutes(15);
    private Duration maxArticleAge = Duration.ofDays(14);
    private boolean gdeltEnabled = true;
    private String gdeltEndpoint = "https://api.gdeltproject.org/api/v2/doc/doc";
    private boolean newsApiEnabled = false;
    private String newsApiKey = "";
    private String guardianApiKey = "";
    private String nyTimesApiKey = "";
    private String translatorEndpoint = "https://api.cognitive.microsofttranslator.com/translate";
    private String translatorKey = "";
    private String translatorRegion = "";
    private List<FeedSource> indiaFeeds = new ArrayList<>();
    private List<FeedSource> usFeeds = new ArrayList<>();
    private List<FeedSource> chinaFeeds = new ArrayList<>();
    private List<FeedSource> worldFeeds = new ArrayList<>();

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public Duration getMaxArticleAge() {
        return maxArticleAge;
    }

    public void setMaxArticleAge(Duration maxArticleAge) {
        this.maxArticleAge = maxArticleAge;
    }

    public boolean isGdeltEnabled() {
        return gdeltEnabled;
    }

    public void setGdeltEnabled(boolean gdeltEnabled) {
        this.gdeltEnabled = gdeltEnabled;
    }

    public String getGdeltEndpoint() {
        return gdeltEndpoint;
    }

    public void setGdeltEndpoint(String gdeltEndpoint) {
        this.gdeltEndpoint = gdeltEndpoint;
    }

    public String getNewsApiKey() {
        return newsApiKey;
    }

    public void setNewsApiKey(String newsApiKey) {
        this.newsApiKey = newsApiKey;
    }

    public boolean isNewsApiEnabled() {
        return newsApiEnabled;
    }

    public void setNewsApiEnabled(boolean newsApiEnabled) {
        this.newsApiEnabled = newsApiEnabled;
    }

    public String getGuardianApiKey() {
        return guardianApiKey;
    }

    public void setGuardianApiKey(String guardianApiKey) {
        this.guardianApiKey = guardianApiKey;
    }

    public String getNyTimesApiKey() {
        return nyTimesApiKey;
    }

    public void setNyTimesApiKey(String nyTimesApiKey) {
        this.nyTimesApiKey = nyTimesApiKey;
    }

    public String getTranslatorEndpoint() {
        return translatorEndpoint;
    }

    public void setTranslatorEndpoint(String translatorEndpoint) {
        this.translatorEndpoint = translatorEndpoint;
    }

    public String getTranslatorKey() {
        return translatorKey;
    }

    public void setTranslatorKey(String translatorKey) {
        this.translatorKey = translatorKey;
    }

    public String getTranslatorRegion() {
        return translatorRegion;
    }

    public void setTranslatorRegion(String translatorRegion) {
        this.translatorRegion = translatorRegion;
    }

    public List<FeedSource> getIndiaFeeds() {
        return indiaFeeds;
    }

    public void setIndiaFeeds(List<FeedSource> indiaFeeds) {
        this.indiaFeeds = indiaFeeds;
    }

    public List<FeedSource> getWorldFeeds() {
        return worldFeeds;
    }

    public void setWorldFeeds(List<FeedSource> worldFeeds) {
        this.worldFeeds = worldFeeds;
    }

    public List<FeedSource> getUsFeeds() {
        return usFeeds;
    }

    public void setUsFeeds(List<FeedSource> usFeeds) {
        this.usFeeds = usFeeds;
    }

    public List<FeedSource> getChinaFeeds() {
        return chinaFeeds;
    }

    public void setChinaFeeds(List<FeedSource> chinaFeeds) {
        this.chinaFeeds = chinaFeeds;
    }

    public List<FeedSource> feedsFor(String region) {
        if ("india".equals(region)) {
            return indiaFeeds;
        }
        List<FeedSource> sources = new ArrayList<>();
        sources.addAll(indiaFeeds);
        sources.addAll(usFeeds);
        sources.addAll(chinaFeeds);
        sources.addAll(worldFeeds);
        return sources;
    }

    public static class FeedSource {
        private String publisher;
        private String url;
        private String sourceCountry;
        private String language = "en";
        private boolean enabled = true;

        public String getPublisher() {
            return publisher;
        }

        public void setPublisher(String publisher) {
            this.publisher = publisher;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getSourceCountry() {
            return sourceCountry;
        }

        public void setSourceCountry(String sourceCountry) {
            this.sourceCountry = sourceCountry;
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}

package org.example.news;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.api.ApiException;
import org.example.config.NewsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class ArticleTranslationService {
    private static final Logger log = LoggerFactory.getLogger(ArticleTranslationService.class);
    private static final int MAX_REQUEST_ITEMS = 1_000;
    private static final int MAX_REQUEST_CHARACTERS = 50_000;
    private static final int MAX_CACHED_TRANSLATIONS = 50_000;
    private static final String TARGET_LANGUAGE = "ta";

    private final RestClient restClient;
    private final NewsProperties properties;
    private final ConcurrentHashMap<String, String> translations = new ConcurrentHashMap<>();

    public ArticleTranslationService(RestClient newsRestClient, NewsProperties properties) {
        this.restClient = newsRestClient;
        this.properties = properties;
    }

    public List<NewsArticle> translateToTamil(List<NewsArticle> articles) {
        if (articles.isEmpty()) return articles;
        if (properties.getTranslatorKey() == null || properties.getTranslatorKey().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TRANSLATION_NOT_CONFIGURED",
                    "Tamil translation is unavailable; configure AZURE_TRANSLATOR_KEY");
        }

        LinkedHashSet<String> uniqueText = new LinkedHashSet<>();
        for (NewsArticle article : articles) {
            addText(uniqueText, article.title());
            addText(uniqueText, article.summary());
            article.highlights().forEach(highlight -> addText(uniqueText, highlight));
        }

        Map<String, String> translatedText = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (String text : uniqueText) {
            String cached = translations.get(text);
            if (cached == null) missing.add(text);
            else translatedText.put(text, cached);
        }
        translateMissing(missing, translatedText);

        return articles.stream().map(article -> new NewsArticle(
                article.id(),
                article.region(),
                article.category(),
                translatedText.getOrDefault(article.title(), article.title()),
                translatedText.getOrDefault(article.summary(), article.summary()),
                article.highlights().stream()
                        .map(highlight -> translatedText.getOrDefault(highlight, highlight))
                        .toList(),
                article.source(),
                article.publishedAt(),
                article.url(),
                article.imageUrl(),
                article.author(),
                article.sourceCountry(),
                article.language(),
                article.provider())).toList();
    }

    private void addText(LinkedHashSet<String> texts, String text) {
        if (text != null && !text.isBlank()) texts.add(text);
    }

    private void translateMissing(List<String> missing, Map<String, String> translatedText) {
        List<String> batch = new ArrayList<>();
        int characterCount = 0;
        for (String text : missing) {
            if (text.length() > MAX_REQUEST_CHARACTERS) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "TRANSLATION_TEXT_TOO_LONG",
                        "A news excerpt is too long for the translation provider");
            }
            if (!batch.isEmpty() && (batch.size() == MAX_REQUEST_ITEMS
                    || characterCount + text.length() > MAX_REQUEST_CHARACTERS)) {
                translateBatch(batch, translatedText);
                batch = new ArrayList<>();
                characterCount = 0;
            }
            batch.add(text);
            characterCount += text.length();
        }
        if (!batch.isEmpty()) translateBatch(batch, translatedText);
    }

    private void translateBatch(List<String> batch, Map<String, String> translatedText) {
        List<Map<String, Object>> inputs = batch.stream()
                .map(text -> Map.<String, Object>of(
                        "text", text,
                        "targets", List.of(Map.of("language", TARGET_LANGUAGE))))
                .toList();
        Map<String, Object> requestBody = Map.of("inputs", inputs);

        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(properties.getTranslatorEndpoint() + "?api-version=2026-06-06")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Ocp-Apim-Subscription-Key", properties.getTranslatorKey());
            if (properties.getTranslatorRegion() != null && !properties.getTranslatorRegion().isBlank()) {
                request.header("Ocp-Apim-Subscription-Region", properties.getTranslatorRegion());
            }

            JsonNode response = request.body(requestBody).retrieve().body(JsonNode.class);
            JsonNode values = response == null ? null : response.path("value");
            if (values == null || !values.isArray() || values.size() != batch.size()) {
                throw translationUnavailable("Translator returned an invalid response");
            }

            if (translations.size() + batch.size() > MAX_CACHED_TRANSLATIONS) translations.clear();
            for (int index = 0; index < batch.size(); index++) {
                JsonNode targetTranslations = values.get(index).path("translations");
                String translated = targetTranslations.isArray() && !targetTranslations.isEmpty()
                        ? targetTranslations.get(0).path("text").asText(null)
                        : null;
                if (translated == null || translated.isBlank()) {
                    throw translationUnavailable("Translator returned an empty translation");
                }
                translatedText.put(batch.get(index), translated);
                translations.put(batch.get(index), translated);
            }
        } catch (HttpStatusCodeException exception) {
            if (exception.getStatusCode().value() == 429) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TRANSLATION_RATE_LIMITED",
                        "The translation provider is rate limiting requests; retry later");
            }
            log.warn("Tamil translation provider failed with HTTP status {}", exception.getStatusCode().value());
            throw translationUnavailable("The translation provider is temporarily unavailable");
        } catch (RestClientException exception) {
            log.warn("Tamil translation provider request failed ({})", exception.getClass().getSimpleName());
            throw translationUnavailable("The translation provider is temporarily unavailable");
        }
    }

    private ApiException translationUnavailable(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "TRANSLATION_PROVIDER_UNAVAILABLE", message);
    }
}

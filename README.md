# Gossip Agents API

Spring Boot MVC middleware that collects publisher RSS/API metadata and GDELT discovery results, normalizes them into the UI feed contract, and applies shared filtering and pagination.

## Run locally

Requires Java 17 and Maven.

```bash
mvn spring-boot:run
```

The server listens on port `8080`. If another process is using it, start with `PORT=8081 mvn spring-boot:run`.

## Feed endpoint

```http
GET /api/v1/news?region=india&category=all&page=0&limit=20&q=optional&language=en
```

`region` is required (`india` or `world`), `page` is required and zero based, `category` defaults to `all`, `limit` defaults to `20`, and `language` defaults to `en`. Supported languages are `en` and `ta`; any other value returns HTTP 400. Tamil requests translate article titles, summaries, and highlights. Publisher/source names, URLs, and attribution remain unchanged.

```bash
curl 'http://localhost:8080/api/v1/news?region=india&category=all&page=0&limit=20'
```

Tamil feed:

```bash
curl 'http://localhost:8080/api/v1/news?region=india&category=all&page=0&limit=20&language=ta'
```

### Tamil translation setup

Tamil translation uses the standard Azure Translator REST API. Create an Azure Translator F0 resource and set its key and region in the backend environment; the F0 tier currently includes 2 million standard translation characters per month. The service only sends article title, summary, and highlights, batches requests, and caches translated strings in memory. Without a key, English requests still work and Tamil requests return HTTP 503.

```bash
export AZURE_TRANSLATOR_KEY='your-translator-resource-key'
export AZURE_TRANSLATOR_REGION='your-resource-region'
# Optional: use a resource-specific endpoint if required by your Azure setup.
export AZURE_TRANSLATOR_ENDPOINT='https://api.cognitive.microsofttranslator.com/translate'
mvn spring-boot:run
```

The endpoint defaults to Azure's global Translator endpoint. Keep the key in the backend environment; never place it in the React app.

### CORS

The API allows the production UI origin `https://gossip-agent.kirubakaran275p.workers.dev` and local Vite origins `http://localhost:5173` and `http://localhost:4173`.

The `world` edition aggregates the US, China, India, and worldwide source groups. `india` is restricted to Indian publisher sources. The source lists are curated from the project directory; they are not a live ranking of publishers.

## Source coverage

Direct feeds are configured where an official public feed was identified. GDELT domain discovery supplements those feeds for publishers without an enabled public feed. The registry covers these ten publishers in each group:

| Edition group | Publishers |
| --- | --- |
| India | Times of India, NDTV, The Hindu, The Indian Express, Hindustan Times, Mint, The Economic Times, Deccan Herald, The Telegraph India, PTI |
| US | AP, The New York Times, The Washington Post, NPR, CNN, ABC News, CBS News, Fox News, NBC News, The Wall Street Journal |
| China | Xinhua, CGTN, China Daily, Global Times, China News Service/ECNS, People's Daily, Caixin Global, Sixth Tone, Yicai Global, South China Morning Post |
| Worldwide | BBC, Reuters, The Guardian, Al Jazeera, Deutsche Welle, France 24, Euronews, Sky News, NHK World, ABC News Australia |

Configured RSS feeds are listed in `src/main/resources/application.yml`. Direct feeds include NDTV, Indian Express, and Hindustan Times for India; NPR, ABC, CBS, Fox, and NYT for the US; Xinhua and CGTN for China; and BBC, Guardian, Al Jazeera, DW, France 24, Euronews, and Sky for worldwide coverage. Guardian and NYT APIs are available when their keys are configured. Other catalog publishers are discovered through GDELT; this project does not scrape their websites or bypass licensed access. Feed availability can change, so treat the YAML list as configuration to recheck periodically.

Times of India's RSS entry is disabled by default because its terms allow personal access but prohibit hosting/aggregating the feed without express consent. Its publisher domain remains available to GDELT discovery. ABC News Australia says its RSS feeds are no longer updated; its domain is also discovery-only. ECNS is listed for GDELT discovery, but its unconfirmed feed URL is disabled. Review each publisher's current terms before enabling a feed or persisting/reproducing its content.

## Optional provider keys

Provider secrets are read from environment variables and are not committed to the repository:

```bash
export GUARDIAN_API_KEY='your-guardian-key'
export NYT_API_KEY='your-nytimes-key'
export NEWS_API_KEY='your-newsapi-key'
export NEWS_API_ENABLED=true
```

Guardian and New York Times APIs are opt-in when their keys exist. NewsAPI is disabled unless both its key and `NEWS_API_ENABLED=true` are set; it is queried only for explicit `q` searches. GDELT and RSS require no keys. NewsAPI's free access is intended for development/testing, so check the provider's current quota and terms before use.

## Central news tuning

`NewsService` is the single aggregation path. It:

1. Loads a cached source snapshot per edition (15-minute default).
2. Uses stable IDs derived from canonical article URLs, removing common tracking parameters from the ID calculation.
3. Merges duplicates by canonical URL and exact normalized headline within a 36-hour window, preferring direct publisher metadata and filling missing fields from other providers.
4. Drops stories older than 14 days or more than ten minutes in the future.
5. Applies category and `q` matching, sorts newest first, and slices the requested zero-based page.

The cache keeps page ordering stable while the user scrolls. Search results from query-capable providers are separately cached. Category assignment is heuristic because this version intentionally does not use AI. Exact-headline deduplication can merge separate stories with identical headlines inside the time window; adjust the threshold in `NewsService` if that happens for your sources.

The response includes `page`, `hasMore`, `updatedAt`, and `articles`. Article summaries/highlights use publisher excerpts when the feed provides them. GDELT does not provide article body text, so its headline is used as the summary and its highlights are empty. This service does not fetch full article pages or create AI summaries.

Provider failures are logged. If at least one provider responds, available articles are returned. When all active providers fail, the API returns HTTP 503, or HTTP 429 when all failures are rate-limit responses.

## Persistence

A database is not needed for the current middleware: it keeps only a bounded in-memory metadata cache, which is cleared on restart. Add durable storage when you need article history across restarts, saved stories/accounts, or replayable pagination. Keep any future article body storage separate from source metadata and confirm provider retention terms first.

## Project structure

- `api`: Spring MVC controller and consistent error responses.
- `news`: provider adapters, source catalog, normalization, aggregation, deduplication, caching, and pagination.
- `config`: source settings, environment-bound secrets, outbound HTTP timeouts, and RSS fetch executor.

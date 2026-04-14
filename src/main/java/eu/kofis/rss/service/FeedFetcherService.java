package eu.kofis.rss.service;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import eu.kofis.rss.dto.RefreshResultDto;
import eu.kofis.rss.entity.Article;
import eu.kofis.rss.entity.Feed;
import eu.kofis.rss.entity.User;
import eu.kofis.rss.entity.UserFeed;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class FeedFetcherService {

    private static final Logger LOG = Logger.getLogger(FeedFetcherService.class);

    @ConfigProperty(name = "kfs.rss.fetch-timeout", defaultValue = "15")
    int fetchTimeoutSeconds;

    @ConfigProperty(name = "kfs.rss.retention-days", defaultValue = "30")
    int retentionDays;

    @ConfigProperty(name = "kfs.rss.max-articles-per-feed", defaultValue = "100")
    int maxArticlesPerFeed;

    @ConfigProperty(name = "kfs.rss.user-agent", defaultValue = "kfs-rss/2.0")
    String userAgent;

    @Inject
    ArticleExtractorService extractor;

    @Scheduled(cron = "{kfs.rss.fetch-cron}")
    @Transactional
    void scheduledFetch() {
        LOG.info("Scheduled feed fetch starting");
        List<Feed> allFeeds = Feed.findAllActive();
        RefreshResultDto result = fetchFeeds(allFeeds);
        LOG.infof("Scheduled fetch done: %d fetched, %d cached, %d errors",
                result.fetched, result.cached, result.errors);
        cleanup();
    }

    @Transactional
    public RefreshResultDto refreshUserFeeds(User user) {
        List<UserFeed> subs = UserFeed.findByUser(user);
        List<Feed> feeds = subs.stream().map(uf -> uf.feed).distinct().collect(Collectors.toList());
        RefreshResultDto result = fetchFeeds(feeds);
        cleanup();
        return result;
    }

    RefreshResultDto fetchFeeds(List<Feed> feeds) {
        int fetched = 0, cached = 0, errors = 0;

        for (Feed feed : feeds) {
            try {
                boolean updated = fetchOneFeed(feed);
                if (updated) fetched++;
                else cached++;
            } catch (Exception e) {
                errors++;
                feed.lastError = e.getMessage();
                feed.persist();
                LOG.warnf("Error fetching %s: %s", feed.url, e.getMessage());
            }
        }

        return new RefreshResultDto(fetched, cached, errors);
    }

    boolean fetchOneFeed(Feed feed) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(feed.url).toURL().openConnection();
        conn.setConnectTimeout(fetchTimeoutSeconds * 1000);
        conn.setReadTimeout(fetchTimeoutSeconds * 1000);
        conn.setRequestProperty("User-Agent", userAgent);

        // Conditional GET
        if (feed.etag != null) conn.setRequestProperty("If-None-Match", feed.etag);
        if (feed.lastModified != null) conn.setRequestProperty("If-Modified-Since", feed.lastModified);

        int status = conn.getResponseCode();

        if (status == 304) {
            feed.lastFetchedAt = Instant.now();
            feed.lastError = null;
            feed.persist();
            return false;
        }

        if (status != 200) {
            throw new RuntimeException("HTTP " + status);
        }

        // Update cache headers
        feed.etag = conn.getHeaderField("ETag");
        feed.lastModified = conn.getHeaderField("Last-Modified");
        feed.lastFetchedAt = Instant.now();
        feed.lastError = null;

        // Parse feed
        try (InputStream is = conn.getInputStream()) {
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed syndFeed = input.build(new XmlReader(is));

            // Update feed metadata
            if (syndFeed.getTitle() != null) feed.title = syndFeed.getTitle();
            if (syndFeed.getLink() != null) feed.siteUrl = syndFeed.getLink();
            feed.persist();

            // Process entries
            for (SyndEntry entry : syndFeed.getEntries()) {
                upsertArticle(feed, entry);
            }
        }

        return true;
    }

    void upsertArticle(Feed feed, SyndEntry entry) {
        String guid = extractor.extractGuid(entry);
        Article existing = Article.findByFeedAndGuid(feed, guid);

        Article article = existing != null ? existing : new Article();
        article.feed = feed;
        article.guid = guid;
        article.title = entry.getTitle() != null ? entry.getTitle() : "";
        article.link = extractor.extractLink(entry);
        article.author = extractor.extractAuthor(entry);

        String content = extractor.extractContent(entry);
        article.content = content;
        article.summary = extractor.extractSummary(entry, content);
        article.image = extractor.extractImage(entry);
        article.publishedAt = extractor.extractDate(entry);

        if (existing == null) {
            article.fetchedAt = Instant.now();
        }

        article.persist();
    }

    void cleanup() {
        // Delete articles older than retention period (unless starred)
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        long deleted = Article.deleteOlderThan(cutoff);
        if (deleted > 0) {
            LOG.infof("Retention cleanup: deleted %d old articles", deleted);
        }

        // Enforce per-feed limit
        List<Feed> allFeeds = Feed.findAllActive();
        for (Feed feed : allFeeds) {
            long count = Article.countByFeed(feed);
            if (count > maxArticlesPerFeed) {
                long removed = Article.deleteExcessForFeed(feed, maxArticlesPerFeed);
                if (removed > 0) {
                    LOG.infof("Feed %s: removed %d excess articles", feed.feedHash, removed);
                }
            }
        }
    }
}

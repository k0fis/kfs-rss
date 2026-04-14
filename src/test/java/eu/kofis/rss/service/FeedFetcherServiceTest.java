package eu.kofis.rss.service;

import com.rometools.rome.feed.synd.SyndContentImpl;
import com.rometools.rome.feed.synd.SyndEntryImpl;
import eu.kofis.rss.entity.Article;
import eu.kofis.rss.entity.Feed;
import eu.kofis.rss.dto.RefreshResultDto;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@Transactional
class FeedFetcherServiceTest {

    @Inject
    FeedFetcherService service;

    @InjectMock
    ArticleExtractorService extractor;

    Feed testFeed;

    @BeforeEach
    void setUp() {
        // Clean up H2
        Article.deleteAll();
        Feed.deleteAll();

        // Persist a real Feed for upsertArticle tests
        testFeed = new Feed();
        testFeed.url = "http://test.example.com/feed.xml";
        testFeed.feedHash = "test1234";
        testFeed.persist();
    }

    // --- upsertArticle ---

    @Test
    void upsertArticle_createsNewArticle() {
        var entry = createEntry("Test Title", "http://example.com/1");

        when(extractor.extractGuid(any())).thenReturn("guid-1");
        when(extractor.extractLink(any())).thenReturn("http://example.com/1");
        when(extractor.extractAuthor(any())).thenReturn("Author");
        when(extractor.extractContent(any())).thenReturn("<p>Content</p>");
        when(extractor.extractSummary(any(), anyString())).thenReturn("Content");
        when(extractor.extractImage(any())).thenReturn("http://example.com/img.jpg");
        when(extractor.extractDate(any())).thenReturn(Instant.parse("2025-06-15T10:00:00Z"));

        service.upsertArticle(testFeed, entry);

        Article article = Article.findByFeedAndGuid(testFeed, "guid-1");
        assertNotNull(article);
        assertEquals("Test Title", article.title);
        assertEquals("http://example.com/1", article.link);
        assertEquals("Author", article.author);
        assertEquals("<p>Content</p>", article.content);
        assertEquals("Content", article.summary);
        assertEquals("http://example.com/img.jpg", article.image);
        assertNotNull(article.fetchedAt);
    }

    @Test
    void upsertArticle_updatesExistingPreservesFetchedAt() {
        Instant originalFetchedAt = Instant.parse("2025-01-01T00:00:00Z");

        // Create existing article
        var existing = new Article();
        existing.feed = testFeed;
        existing.guid = "guid-1";
        existing.title = "Old Title";
        existing.fetchedAt = originalFetchedAt;
        existing.publishedAt = Instant.now();
        existing.persist();

        var entry = createEntry("Updated Title", "http://example.com/1");
        when(extractor.extractGuid(any())).thenReturn("guid-1");
        when(extractor.extractLink(any())).thenReturn("http://example.com/1");
        when(extractor.extractAuthor(any())).thenReturn("");
        when(extractor.extractContent(any())).thenReturn("New content");
        when(extractor.extractSummary(any(), anyString())).thenReturn("New");
        when(extractor.extractImage(any())).thenReturn("");
        when(extractor.extractDate(any())).thenReturn(Instant.now());

        service.upsertArticle(testFeed, entry);

        Article updated = Article.findByFeedAndGuid(testFeed, "guid-1");
        assertEquals("Updated Title", updated.title);
        assertEquals(originalFetchedAt, updated.fetchedAt);
    }

    @Test
    void upsertArticle_nullTitleSetsEmpty() {
        var entry = new SyndEntryImpl();
        entry.setTitle(null);

        when(extractor.extractGuid(any())).thenReturn("guid-null");
        when(extractor.extractLink(any())).thenReturn("");
        when(extractor.extractAuthor(any())).thenReturn("");
        when(extractor.extractContent(any())).thenReturn("");
        when(extractor.extractSummary(any(), anyString())).thenReturn("");
        when(extractor.extractImage(any())).thenReturn("");
        when(extractor.extractDate(any())).thenReturn(Instant.now());

        service.upsertArticle(testFeed, entry);

        Article article = Article.findByFeedAndGuid(testFeed, "guid-null");
        assertNotNull(article);
        assertEquals("", article.title);
    }

    @Test
    void upsertArticle_delegatesToExtractor() {
        var entry = createEntry("T", "http://x.com");

        when(extractor.extractGuid(any())).thenReturn("guid-d");
        when(extractor.extractLink(any())).thenReturn("");
        when(extractor.extractAuthor(any())).thenReturn("");
        when(extractor.extractContent(any())).thenReturn("");
        when(extractor.extractSummary(any(), anyString())).thenReturn("");
        when(extractor.extractImage(any())).thenReturn("");
        when(extractor.extractDate(any())).thenReturn(Instant.now());

        service.upsertArticle(testFeed, entry);

        verify(extractor).extractGuid(entry);
        verify(extractor).extractLink(entry);
        verify(extractor).extractAuthor(entry);
        verify(extractor).extractContent(entry);
        verify(extractor).extractSummary(eq(entry), anyString());
        verify(extractor).extractImage(entry);
        verify(extractor).extractDate(entry);
    }

    // --- cleanup ---

    @Test
    void cleanup_deletesOldArticles() {
        // Create an old article
        var old = new Article();
        old.feed = testFeed;
        old.guid = "old-1";
        old.publishedAt = Instant.now().minus(60, ChronoUnit.DAYS);
        old.persist();

        // Create a recent article
        var recent = new Article();
        recent.feed = testFeed;
        recent.guid = "recent-1";
        recent.publishedAt = Instant.now();
        recent.persist();

        assertEquals(2, Article.countByFeed(testFeed));

        service.cleanup();

        assertEquals(1, Article.countByFeed(testFeed));
        assertNull(Article.findByFeedAndGuid(testFeed, "old-1"));
        assertNotNull(Article.findByFeedAndGuid(testFeed, "recent-1"));
    }

    @Test
    void cleanup_keepsRecentArticles() {
        for (int i = 0; i < 5; i++) {
            var a = new Article();
            a.feed = testFeed;
            a.guid = "recent-" + i;
            a.publishedAt = Instant.now().minus(i, ChronoUnit.DAYS);
            a.persist();
        }

        service.cleanup();

        assertEquals(5, Article.countByFeed(testFeed));
    }

    // --- fetchFeeds ---

    @Test
    void fetchFeeds_emptyListReturnsZeros() {
        RefreshResultDto result = service.fetchFeeds(Collections.emptyList());
        assertEquals(0, result.fetched);
        assertEquals(0, result.cached);
        assertEquals(0, result.errors);
    }

    @Test
    void fetchFeeds_invalidUrlCountsAsError() {
        var feed = new Feed();
        feed.url = "not-a-valid-url://broken";
        feed.feedHash = "broken12";
        feed.persist();

        RefreshResultDto result = service.fetchFeeds(List.of(feed));

        assertEquals(0, result.fetched);
        assertEquals(0, result.cached);
        assertEquals(1, result.errors);
        assertNotNull(feed.lastError);
    }

    // --- helpers ---

    private SyndEntryImpl createEntry(String title, String link) {
        var entry = new SyndEntryImpl();
        entry.setTitle(title);
        entry.setLink(link);
        var content = new SyndContentImpl();
        content.setValue("<p>" + title + "</p>");
        entry.setContents(List.of(content));
        return entry;
    }
}

package eu.kofis.rss.service;

import com.rometools.rome.feed.synd.SyndContentImpl;
import com.rometools.rome.feed.synd.SyndEnclosureImpl;
import com.rometools.rome.feed.synd.SyndEntryImpl;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArticleExtractorServiceTest {

    ArticleExtractorService svc;

    @BeforeEach
    void setUp() {
        svc = new ArticleExtractorService();
    }

    // --- extractGuid ---

    @Test
    void extractGuid_prefersUri() {
        var entry = new SyndEntryImpl();
        entry.setUri("urn:guid:abc");
        entry.setLink("http://example.com/post");
        assertEquals("urn:guid:abc", svc.extractGuid(entry));
    }

    @Test
    void extractGuid_fallsBackToLink() {
        var entry = new SyndEntryImpl();
        entry.setUri(null);
        entry.setLink("http://example.com/post");
        assertEquals("http://example.com/post", svc.extractGuid(entry));
    }

    @Test
    void extractGuid_fallsBackToTitle() {
        var entry = new SyndEntryImpl();
        entry.setUri(null);
        entry.setLink(null);
        entry.setTitle("My Great Post");
        assertEquals("My Great Post", svc.extractGuid(entry));
    }

    @Test
    void extractGuid_allNullReturnsNonEmpty() {
        var entry = new SyndEntryImpl();
        entry.setUri(null);
        entry.setLink(null);
        entry.setTitle(null);
        String guid = svc.extractGuid(entry);
        assertNotNull(guid);
        assertFalse(guid.isBlank());
    }

    // --- extractContent ---

    @Test
    void extractContent_picksLongestFromMultiple() {
        var entry = new SyndEntryImpl();
        var short1 = new SyndContentImpl();
        short1.setValue("short");
        var long1 = new SyndContentImpl();
        long1.setValue("this is a much longer content entry");
        entry.setContents(List.of(short1, long1));
        assertEquals("this is a much longer content entry", svc.extractContent(entry));
    }

    @Test
    void extractContent_prefersDescriptionIfLonger() {
        var entry = new SyndEntryImpl();
        var content = new SyndContentImpl();
        content.setValue("tiny");
        entry.setContents(List.of(content));
        var desc = new SyndContentImpl();
        desc.setValue("this description is longer than the content");
        entry.setDescription(desc);
        assertEquals("this description is longer than the content", svc.extractContent(entry));
    }

    @Test
    void extractContent_emptyWhenNothingPresent() {
        var entry = new SyndEntryImpl();
        assertEquals("", svc.extractContent(entry));
    }

    @Test
    void extractContent_singleContentEntry() {
        var entry = new SyndEntryImpl();
        var c = new SyndContentImpl();
        c.setValue("<p>Hello world</p>");
        entry.setContents(List.of(c));
        assertEquals("<p>Hello world</p>", svc.extractContent(entry));
    }

    // --- extractSummary ---

    @Test
    void extractSummary_stripsHtmlTags() {
        var entry = new SyndEntryImpl();
        String content = "<p>Hello <strong>world</strong> <a href='x'>link</a></p>";
        assertEquals("Hello world link", svc.extractSummary(entry, content));
    }

    @Test
    void extractSummary_truncatesAt300() {
        var entry = new SyndEntryImpl();
        String longText = "A".repeat(400);
        String result = svc.extractSummary(entry, longText);
        assertEquals(300, result.length());
        assertTrue(result.endsWith("..."));
        assertEquals("A".repeat(297) + "...", result);
    }

    @Test
    void extractSummary_shortTextNotTruncated() {
        var entry = new SyndEntryImpl();
        String shortText = "Just a short summary.";
        assertEquals("Just a short summary.", svc.extractSummary(entry, shortText));
    }

    @Test
    void extractSummary_usesDescriptionWhenShorter() {
        var entry = new SyndEntryImpl();
        var desc = new SyndContentImpl();
        desc.setValue("<b>Brief</b>");
        entry.setDescription(desc);
        String longContent = "<div>" + "X".repeat(500) + "</div>";
        String result = svc.extractSummary(entry, longContent);
        assertEquals("Brief", result);
    }

    // --- extractImage ---

    @Test
    void extractImage_fromForeignMarkupThumbnail() {
        var entry = new SyndEntryImpl();
        var thumb = new Element("thumbnail", Namespace.getNamespace("media", "http://search.yahoo.com/mrss/"));
        thumb.setAttribute("url", "https://example.com/thumb.jpg");
        entry.setForeignMarkup(List.of(thumb));
        assertEquals("https://example.com/thumb.jpg", svc.extractImage(entry));
    }

    @Test
    void extractImage_fromEnclosure() {
        var entry = new SyndEntryImpl();
        var enc = new SyndEnclosureImpl();
        enc.setType("image/jpeg");
        enc.setUrl("https://example.com/photo.jpg");
        entry.setEnclosures(List.of(enc));
        assertEquals("https://example.com/photo.jpg", svc.extractImage(entry));
    }

    @Test
    void extractImage_fromImgTagInContent() {
        var entry = new SyndEntryImpl();
        var c = new SyndContentImpl();
        c.setValue("<p>Text <img src=\"https://example.com/pic.png\" alt=\"pic\"> more</p>");
        entry.setContents(List.of(c));
        assertEquals("https://example.com/pic.png", svc.extractImage(entry));
    }

    @Test
    void extractImage_noImageReturnsEmpty() {
        var entry = new SyndEntryImpl();
        var c = new SyndContentImpl();
        c.setValue("<p>No images here</p>");
        entry.setContents(List.of(c));
        assertEquals("", svc.extractImage(entry));
    }

    // --- extractDate ---

    @Test
    void extractDate_usesPublishedDate() {
        var entry = new SyndEntryImpl();
        var date = Date.from(Instant.parse("2025-06-15T10:00:00Z"));
        entry.setPublishedDate(date);
        entry.setUpdatedDate(Date.from(Instant.parse("2025-06-16T10:00:00Z")));
        assertEquals(Instant.parse("2025-06-15T10:00:00Z"), svc.extractDate(entry));
    }

    @Test
    void extractDate_fallsBackToUpdatedDate() {
        var entry = new SyndEntryImpl();
        entry.setPublishedDate(null);
        var date = Date.from(Instant.parse("2025-06-16T10:00:00Z"));
        entry.setUpdatedDate(date);
        assertEquals(Instant.parse("2025-06-16T10:00:00Z"), svc.extractDate(entry));
    }

    @Test
    void extractDate_noDatesReturnsApproximatelyNow() {
        var entry = new SyndEntryImpl();
        Instant before = Instant.now().minus(1, ChronoUnit.SECONDS);
        Instant result = svc.extractDate(entry);
        Instant after = Instant.now().plus(1, ChronoUnit.SECONDS);
        assertTrue(result.isAfter(before) && result.isBefore(after));
    }

    // --- extractAuthor ---

    @Test
    void extractAuthor_returnsPresent() {
        var entry = new SyndEntryImpl();
        entry.setAuthor("Jane Doe");
        assertEquals("Jane Doe", svc.extractAuthor(entry));
    }

    @Test
    void extractAuthor_blankReturnsEmpty() {
        var entry = new SyndEntryImpl();
        entry.setAuthor("   ");
        assertEquals("", svc.extractAuthor(entry));
    }

    // --- extractLink ---

    @Test
    void extractLink_prefersLink() {
        var entry = new SyndEntryImpl();
        entry.setLink("http://example.com/post");
        entry.setUri("urn:uuid:123");
        assertEquals("http://example.com/post", svc.extractLink(entry));
    }

    @Test
    void extractLink_fallsBackToUri() {
        var entry = new SyndEntryImpl();
        entry.setLink(null);
        entry.setUri("urn:uuid:123");
        assertEquals("urn:uuid:123", svc.extractLink(entry));
    }
}

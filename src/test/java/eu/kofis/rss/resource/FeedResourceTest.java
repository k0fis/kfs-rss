package eu.kofis.rss.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FeedResourceTest {

    @Test
    void computeHash_producesEightCharHex() {
        String hash = FeedResource.computeHash("https://example.com/feed.xml");
        assertNotNull(hash);
        assertEquals(8, hash.length());
        assertTrue(hash.matches("[0-9a-f]{8}"));
    }

    @Test
    void computeHash_sameUrlSameHash() {
        String hash1 = FeedResource.computeHash("https://example.com/feed.xml");
        String hash2 = FeedResource.computeHash("https://example.com/feed.xml");
        assertEquals(hash1, hash2);
    }
}

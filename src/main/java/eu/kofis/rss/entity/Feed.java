package eu.kofis.rss.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "feeds")
public class Feed extends PanacheEntity {

    @Column(unique = true, nullable = false)
    public String url;

    @Column(name = "feed_hash", unique = true, nullable = false, length = 8)
    public String feedHash;

    @Column(length = 500)
    public String title;

    @Column(name = "site_url")
    public String siteUrl;

    @Column(name = "last_fetched_at")
    public Instant lastFetchedAt;

    @Column(name = "last_error")
    public String lastError;

    public String etag;

    @Column(name = "last_modified")
    public String lastModified;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    public static Feed findByHash(String hash) {
        return find("feedHash", hash).firstResult();
    }

    public static Feed findByUrl(String url) {
        return find("url", url).firstResult();
    }

    public static List<Feed> findAllActive() {
        return find("SELECT DISTINCT f FROM Feed f JOIN UserFeed uf ON uf.feed = f").list();
    }
}

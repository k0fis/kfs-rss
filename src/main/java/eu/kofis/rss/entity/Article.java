package eu.kofis.rss.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "articles", uniqueConstraints = @UniqueConstraint(columnNames = {"feed_id", "guid"}))
public class Article extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    public Feed feed;

    @Column(nullable = false)
    public String guid;

    @Column(nullable = false)
    public String title = "";

    @Column(nullable = false)
    public String link = "";

    @Column(nullable = false)
    public String author = "";

    @Column(nullable = false, columnDefinition = "TEXT")
    public String summary = "";

    @Column(nullable = false, columnDefinition = "TEXT")
    public String content = "";

    @Column(nullable = false)
    public String image = "";

    @Column(name = "published_at")
    public Instant publishedAt;

    @Column(name = "fetched_at", nullable = false)
    public Instant fetchedAt = Instant.now();

    public static List<Article> findByFeed(Feed feed) {
        return find("feed = ?1 ORDER BY publishedAt DESC", feed).list();
    }

    public static Article findByFeedAndGuid(Feed feed, String guid) {
        return find("feed = ?1 AND guid = ?2", feed, guid).firstResult();
    }

    public static long countByFeed(Feed feed) {
        return count("feed", feed);
    }

    public static long deleteOlderThan(Instant cutoff) {
        return delete("publishedAt < ?1 AND id NOT IN " +
                "(SELECT ua.article.id FROM UserArticle ua WHERE ua.isStarred = true)", cutoff);
    }

    public static long deleteExcessForFeed(Feed feed, int maxArticles) {
        List<Article> excess = find(
                "feed = ?1 AND id NOT IN (SELECT ua.article.id FROM UserArticle ua WHERE ua.isStarred = true) " +
                "ORDER BY publishedAt DESC", feed)
                .page(maxArticles, Integer.MAX_VALUE).list();
        if (excess.isEmpty()) return 0;
        List<Long> ids = excess.stream().map(a -> a.id).toList();
        return delete("id IN ?1", ids);
    }
}

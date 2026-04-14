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
@Table(name = "user_articles", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "article_id"}))
public class UserArticle extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    public User user;

    @ManyToOne(fetch = FetchType.LAZY)
    public Article article;

    @Column(name = "is_read", nullable = false)
    public boolean isRead = false;

    @Column(name = "is_starred", nullable = false)
    public boolean isStarred = false;

    @Column(name = "read_at")
    public Instant readAt;

    @Column(name = "starred_at")
    public Instant starredAt;

    public static UserArticle findByUserAndArticle(User user, Article article) {
        return find("user = ?1 AND article = ?2", user, article).firstResult();
    }

    public static UserArticle getOrCreate(User user, Article article) {
        UserArticle ua = findByUserAndArticle(user, article);
        if (ua == null) {
            ua = new UserArticle();
            ua.user = user;
            ua.article = article;
        }
        return ua;
    }

    public static List<UserArticle> findStarred(User user) {
        return find("user = ?1 AND isStarred = true ORDER BY starredAt DESC", user).list();
    }

    public static long countUnreadForFeed(User user, Feed feed) {
        long total = Article.count("feed", feed);
        long read = count("user = ?1 AND article.feed = ?2 AND isRead = true", user, feed);
        return total - read;
    }

    public static void markReadByGuids(User user, List<String> guids) {
        List<Article> articles = Article.find("guid IN ?1", guids).list();
        Instant now = Instant.now();
        for (Article a : articles) {
            UserArticle ua = getOrCreate(user, a);
            ua.isRead = true;
            ua.readAt = now;
            ua.persist();
        }
    }

    public static void markUnreadByGuids(User user, List<String> guids) {
        List<Article> articles = Article.find("guid IN ?1", guids).list();
        for (Article a : articles) {
            UserArticle ua = findByUserAndArticle(user, a);
            if (ua != null) {
                ua.isRead = false;
                ua.readAt = null;
                ua.persist();
            }
        }
    }
}

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
@Table(name = "user_feeds", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "feed_id"}))
public class UserFeed extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    public User user;

    @ManyToOne(fetch = FetchType.LAZY)
    public Feed feed;

    @Column(nullable = false)
    public String category = "";

    @Column(name = "added_at", nullable = false)
    public Instant addedAt = Instant.now();

    public static List<UserFeed> findByUser(User user) {
        return find("user", user).list();
    }

    public static UserFeed findByUserAndFeed(User user, Feed feed) {
        return find("user = ?1 AND feed = ?2", user, feed).firstResult();
    }

    public static List<Feed> feedsForUser(User user) {
        return find("user = ?1", user).project(Feed.class).list();
    }
}

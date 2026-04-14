package eu.kofis.rss.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User extends PanacheEntity {

    @Column(unique = true, nullable = false, length = 64)
    public String username;

    @Column(nullable = false)
    public String password;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    public static User findByUsername(String username) {
        return find("username", username).firstResult();
    }
}

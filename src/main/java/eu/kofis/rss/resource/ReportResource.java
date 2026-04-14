package eu.kofis.rss.resource;

import eu.kofis.rss.dto.ArticleDto;
import eu.kofis.rss.entity.Article;
import eu.kofis.rss.entity.Feed;
import eu.kofis.rss.entity.User;
import eu.kofis.rss.entity.UserArticle;
import eu.kofis.rss.entity.UserFeed;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Path("/reports")
@RolesAllowed("user")
@Produces(MediaType.APPLICATION_JSON)
public class ReportResource {

    @GET
    @Path("/daily")
    public Map<String, Object> daily(@Context SecurityContext sec) {
        return report(sec, 1, "last 24h");
    }

    @GET
    @Path("/weekly")
    public Map<String, Object> weekly(@Context SecurityContext sec) {
        return report(sec, 7, "last 7 days");
    }

    private Map<String, Object> report(SecurityContext sec, int days, String period) {
        User user = User.findByUsername(sec.getUserPrincipal().getName());
        List<UserFeed> subs = UserFeed.findByUser(user);
        List<Feed> feeds = subs.stream().map(uf -> uf.feed).toList();

        if (feeds.isEmpty()) {
            return Map.of("generated", DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                    "period", period, "count", 0, "articles", List.of());
        }

        // Build feed → category map
        Map<Long, String> categoryMap = subs.stream()
                .collect(Collectors.toMap(uf -> uf.feed.id, uf -> uf.category, (a, b) -> a));

        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<Article> articles = Article.find(
                "feed IN ?1 AND publishedAt > ?2 ORDER BY publishedAt DESC",
                feeds, since).list();

        List<Map<String, String>> items = articles.stream().map(a -> Map.of(
                "title", a.title,
                "link", a.link,
                "date", a.publishedAt != null ? DateTimeFormatter.ISO_INSTANT.format(a.publishedAt) : "",
                "feed", a.feed.title != null ? a.feed.title : "",
                "category", categoryMap.getOrDefault(a.feed.id, "")
        )).toList();

        return Map.of(
                "generated", DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                "period", period,
                "count", items.size(),
                "articles", items
        );
    }
}

package eu.kofis.rss.resource;

import eu.kofis.rss.dto.ArticleDto;
import eu.kofis.rss.entity.Article;
import eu.kofis.rss.entity.User;
import eu.kofis.rss.entity.UserArticle;
import eu.kofis.rss.entity.UserFeed;
import jakarta.annotation.security.RolesAllowed;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Path("/search")
@RolesAllowed("user")
@Produces(MediaType.APPLICATION_JSON)
public class SearchResource {

    @GET
    @SuppressWarnings("unchecked")
    public Map<String, List<ArticleDto>> search(
            @QueryParam("q") String query,
            @QueryParam("limit") @DefaultValue("50") int limit,
            @Context SecurityContext sec) {

        if (query == null || query.isBlank()) {
            return Map.of("articles", List.of());
        }

        User user = User.findByUsername(sec.getUserPrincipal().getName());

        // Get user's subscribed feed IDs
        List<UserFeed> subs = UserFeed.findByUser(user);
        Set<Long> feedIds = subs.stream().map(uf -> uf.feed.id).collect(Collectors.toSet());
        if (feedIds.isEmpty()) {
            return Map.of("articles", List.of());
        }

        // Build category map
        Map<Long, String> categoryMap = subs.stream()
                .collect(Collectors.toMap(uf -> uf.feed.id, uf -> uf.category, (a, b) -> a));

        // Fulltext search with tsvector
        String tsQuery = query.trim().replaceAll("\\s+", " & ");

        EntityManager em = Article.getEntityManager();
        List<Article> results = em.createNativeQuery(
                "SELECT a.* FROM articles a " +
                "WHERE a.feed_id IN :feedIds " +
                "AND a.search_vector @@ to_tsquery('simple', :query) " +
                "ORDER BY ts_rank(a.search_vector, to_tsquery('simple', :query)) DESC " +
                "LIMIT :limit", Article.class)
                .setParameter("feedIds", feedIds)
                .setParameter("query", tsQuery)
                .setParameter("limit", limit)
                .getResultList();

        // Load user state
        Set<Long> articleIds = results.stream().map(a -> a.id).collect(Collectors.toSet());
        Map<Long, UserArticle> stateMap = articleIds.isEmpty() ? Map.of() :
                UserArticle.find("user = ?1 AND article.id IN ?2", user, articleIds)
                        .<UserArticle>list()
                        .stream()
                        .collect(Collectors.toMap(ua -> ua.article.id, ua -> ua));

        List<ArticleDto> dtos = results.stream().map(a -> {
            ArticleDto dto = new ArticleDto();
            dto.guid = a.guid;
            dto.title = a.title;
            dto.link = a.link;
            dto.date = a.publishedAt != null
                    ? DateTimeFormatter.ISO_INSTANT.format(a.publishedAt) : null;
            dto.author = a.author;
            dto.summary = a.summary;
            dto.content = a.content;
            dto.image = a.image;
            dto.feedId = a.feed.feedHash;
            dto.feedTitle = a.feed.title;
            dto.category = categoryMap.getOrDefault(a.feed.id, "");

            UserArticle ua = stateMap.get(a.id);
            dto.read = ua != null && ua.isRead;
            dto.starred = ua != null && ua.isStarred;
            return dto;
        }).toList();

        return Map.of("articles", dtos);
    }
}

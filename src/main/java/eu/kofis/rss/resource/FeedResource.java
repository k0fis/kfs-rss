package eu.kofis.rss.resource;

import eu.kofis.rss.dto.ArticleDto;
import eu.kofis.rss.dto.FeedArticlesDto;
import eu.kofis.rss.dto.FeedDto;
import eu.kofis.rss.dto.FeedIndexDto;
import eu.kofis.rss.entity.Article;
import eu.kofis.rss.entity.Feed;
import eu.kofis.rss.entity.User;
import eu.kofis.rss.entity.UserArticle;
import eu.kofis.rss.entity.UserFeed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Path("/feeds")
@Produces(MediaType.APPLICATION_JSON)
public class FeedResource {

    @GET
    public FeedIndexDto listFeeds(@Context SecurityContext sec) {
        User user = User.findByUsername(sec.getUserPrincipal().getName());
        List<UserFeed> subs = UserFeed.findByUser(user);

        List<FeedDto> feeds = new ArrayList<>();
        for (UserFeed uf : subs) {
            Feed f = uf.feed;
            FeedDto dto = new FeedDto();
            dto.id = f.feedHash;
            dto.title = f.title;
            dto.url = f.url;
            dto.siteUrl = f.siteUrl;
            dto.category = uf.category;
            dto.articleCount = Article.countByFeed(f);
            dto.lastUpdated = f.lastFetchedAt != null
                    ? DateTimeFormatter.ISO_INSTANT.format(f.lastFetchedAt)
                    : null;
            dto.error = f.lastError;
            dto.unreadCount = UserArticle.countUnreadForFeed(user, f);
            feeds.add(dto);
        }

        feeds.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                a.title != null ? a.title : "", b.title != null ? b.title : ""));

        return new FeedIndexDto(
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                feeds);
    }

    @GET
    @Path("/{hash}/articles")
    public FeedArticlesDto feedArticles(@PathParam("hash") String hash, @Context SecurityContext sec) {
        User user = User.findByUsername(sec.getUserPrincipal().getName());
        Feed feed = Feed.findByHash(hash);
        if (feed == null) {
            throw new jakarta.ws.rs.NotFoundException("Feed not found: " + hash);
        }

        List<Article> articles = Article.findByFeed(feed);

        // Batch load user state for these articles
        Set<Long> articleIds = articles.stream().map(a -> a.id).collect(Collectors.toSet());
        Map<Long, UserArticle> stateMap = UserArticle
                .find("user = ?1 AND article.id IN ?2", user, articleIds)
                .<UserArticle>list()
                .stream()
                .collect(Collectors.toMap(ua -> ua.article.id, ua -> ua));

        // Find category from user's subscription
        UserFeed uf = UserFeed.findByUserAndFeed(user, feed);
        String category = uf != null ? uf.category : "";

        List<ArticleDto> dtos = articles.stream().map(a -> {
            ArticleDto dto = new ArticleDto();
            dto.guid = a.guid;
            dto.title = a.title;
            dto.link = a.link;
            dto.date = a.publishedAt != null
                    ? DateTimeFormatter.ISO_INSTANT.format(a.publishedAt)
                    : null;
            dto.author = a.author;
            dto.summary = a.summary;
            dto.content = a.content;
            dto.image = a.image;
            dto.feedId = feed.feedHash;
            dto.feedTitle = feed.title;
            dto.category = category;

            UserArticle ua = stateMap.get(a.id);
            dto.read = ua != null && ua.isRead;
            dto.starred = ua != null && ua.isStarred;

            return dto;
        }).toList();

        return new FeedArticlesDto(feed.feedHash, feed.title, dtos);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response subscribe(Map<String, String> body, @Context SecurityContext sec) {
        User user = User.findByUsername(sec.getUserPrincipal().getName());
        String url = body.get("url");
        String category = body.getOrDefault("category", "");

        if (url == null || url.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "url required")).build();
        }

        Feed feed = Feed.findByUrl(url);
        if (feed == null) {
            feed = new Feed();
            feed.url = url;
            feed.feedHash = computeHash(url);
            feed.persist();
        }

        UserFeed existing = UserFeed.findByUserAndFeed(user, feed);
        if (existing != null) {
            return Response.ok(Map.of("id", feed.feedHash, "status", "already subscribed")).build();
        }

        UserFeed uf = new UserFeed();
        uf.user = user;
        uf.feed = feed;
        uf.category = category;
        uf.persist();

        return Response.ok(Map.of("id", feed.feedHash, "title", feed.title != null ? feed.title : "")).build();
    }

    @DELETE
    @Path("/{hash}")
    @Transactional
    public Response unsubscribe(@PathParam("hash") String hash, @Context SecurityContext sec) {
        User user = User.findByUsername(sec.getUserPrincipal().getName());
        Feed feed = Feed.findByHash(hash);
        if (feed == null) return Response.status(Response.Status.NOT_FOUND).build();

        UserFeed uf = UserFeed.findByUserAndFeed(user, feed);
        if (uf != null) uf.delete();

        return Response.noContent().build();
    }

    @PATCH
    @Path("/{hash}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response updateFeed(@PathParam("hash") String hash, Map<String, String> body,
                               @Context SecurityContext sec) {
        User user = User.findByUsername(sec.getUserPrincipal().getName());
        Feed feed = Feed.findByHash(hash);
        if (feed == null) return Response.status(Response.Status.NOT_FOUND).build();

        UserFeed uf = UserFeed.findByUserAndFeed(user, feed);
        if (uf == null) return Response.status(Response.Status.NOT_FOUND).build();

        if (body.containsKey("category")) {
            uf.category = body.get("category");
            uf.persist();
        }

        return Response.noContent().build();
    }

    public static String computeHash(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(url.getBytes("UTF-8"));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

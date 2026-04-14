package eu.kofis.rss.resource;

import eu.kofis.rss.dto.ArticleDto;
import eu.kofis.rss.entity.Article;
import eu.kofis.rss.entity.Feed;
import eu.kofis.rss.entity.User;
import eu.kofis.rss.entity.UserArticle;
import eu.kofis.rss.entity.UserFeed;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Path("/articles")
@RolesAllowed("user")
@Produces(MediaType.APPLICATION_JSON)
public class ArticleResource {

    @POST
    @Path("/read")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response markRead(Map<String, List<String>> body, @Context SecurityContext sec) {
        User user = User.findByUsername(sec.getUserPrincipal().getName());
        List<String> guids = body.get("guids");
        if (guids == null || guids.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        UserArticle.markReadByGuids(user, guids);
        return Response.noContent().build();
    }

    @DELETE
    @Path("/read")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response markUnread(Map<String, List<String>> body, @Context SecurityContext sec) {
        User user = User.findByUsername(sec.getUserPrincipal().getName());
        List<String> guids = body.get("guids");
        if (guids == null || guids.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        UserArticle.markUnreadByGuids(user, guids);
        return Response.noContent().build();
    }

    @POST
    @Path("/star")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response star(Map<String, String> body, @Context SecurityContext sec) {
        User user = User.findByUsername(sec.getUserPrincipal().getName());
        String guid = body.get("guid");
        if (guid == null) return Response.status(Response.Status.BAD_REQUEST).build();

        Article article = Article.find("guid", guid).firstResult();
        if (article == null) return Response.status(Response.Status.NOT_FOUND).build();

        UserArticle ua = UserArticle.getOrCreate(user, article);
        ua.isStarred = true;
        ua.starredAt = Instant.now();
        ua.persist();

        return Response.noContent().build();
    }

    @DELETE
    @Path("/star")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response unstar(Map<String, String> body, @Context SecurityContext sec) {
        User user = User.findByUsername(sec.getUserPrincipal().getName());
        String guid = body.get("guid");
        if (guid == null) return Response.status(Response.Status.BAD_REQUEST).build();

        Article article = Article.find("guid", guid).firstResult();
        if (article == null) return Response.noContent().build();

        UserArticle ua = UserArticle.findByUserAndArticle(user, article);
        if (ua != null) {
            ua.isStarred = false;
            ua.starredAt = null;
            ua.persist();
        }

        return Response.noContent().build();
    }

    @POST
    @Path("/read/all")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response markAllRead(Map<String, String> body, @Context SecurityContext sec) {
        User user = User.findByUsername(sec.getUserPrincipal().getName());
        String feedHash = body.get("feedHash");

        List<Article> articles;
        if (feedHash != null && !feedHash.isBlank()) {
            Feed feed = Feed.findByHash(feedHash);
            if (feed == null) return Response.status(Response.Status.NOT_FOUND).build();
            articles = Article.findByFeed(feed);
        } else {
            // All articles from user's subscribed feeds
            List<UserFeed> subs = UserFeed.findByUser(user);
            List<Feed> feeds = subs.stream().map(uf -> uf.feed).toList();
            articles = Article.find("feed IN ?1", feeds).list();
        }

        Instant now = Instant.now();
        for (Article a : articles) {
            UserArticle ua = UserArticle.getOrCreate(user, a);
            if (!ua.isRead) {
                ua.isRead = true;
                ua.readAt = now;
                ua.persist();
            }
        }

        return Response.noContent().build();
    }

    @GET
    @Path("/starred")
    public Map<String, List<ArticleDto>> starred(@Context SecurityContext sec) {
        User user = User.findByUsername(sec.getUserPrincipal().getName());
        List<UserArticle> starred = UserArticle.findStarred(user);

        List<ArticleDto> dtos = starred.stream().map(ua -> {
            Article a = ua.article;
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
            dto.read = ua.isRead;
            dto.starred = true;
            dto.feedId = a.feed.feedHash;
            dto.feedTitle = a.feed.title;

            UserFeed uf = UserFeed.findByUserAndFeed(user, a.feed);
            dto.category = uf != null ? uf.category : "";

            return dto;
        }).toList();

        return Map.of("articles", dtos);
    }
}

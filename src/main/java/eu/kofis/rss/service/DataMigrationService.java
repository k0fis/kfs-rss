package eu.kofis.rss.service;

import eu.kofis.rss.entity.Article;
import eu.kofis.rss.entity.Feed;
import eu.kofis.rss.entity.User;
import eu.kofis.rss.entity.UserArticle;
import eu.kofis.rss.entity.UserFeed;
import eu.kofis.rss.resource.FeedResource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class DataMigrationService {

    private static final Logger LOG = Logger.getLogger(DataMigrationService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @ConfigProperty(name = "kfs.rss.migration.enabled", defaultValue = "false")
    boolean migrationEnabled;

    @ConfigProperty(name = "kfs.rss.migration.data-dir", defaultValue = "/var/www/html/rss/data")
    String dataDir;

    @ConfigProperty(name = "kfs.rss.migration.webdav-dir", defaultValue = "/media/storage/webdav/rss")
    String webdavDir;

    @ConfigProperty(name = "kfs.rss.migration.cache-file", defaultValue = "/opt/rss/cache.json")
    String cacheFile;

    void onStart(@Observes StartupEvent event) {
        if (migrationEnabled) {
            LOG.info("=== DATA MIGRATION STARTING ===");
            try {
                migrateAll();
                LOG.info("=== DATA MIGRATION COMPLETE ===");
            } catch (Exception e) {
                LOG.error("Migration failed", e);
            }
        }
    }

    @Transactional
    void migrateAll() throws Exception {
        // 1. Import feeds from index.json
        Map<String, Feed> feedMap = importFeeds();

        // 2. Import articles from feed-{hash}.json files
        importArticles(feedMap);

        // 3. Import cache (etag/last-modified) from cache.json
        importCache(feedMap);

        // 4. For each user directory in webdav dir: import OPML, state, starred
        File webdav = new File(webdavDir);
        if (webdav.isDirectory()) {
            File[] userDirs = webdav.listFiles(File::isDirectory);
            if (userDirs != null) {
                for (File userDir : userDirs) {
                    importUser(userDir, feedMap);
                }
            }
        }
    }

    Map<String, Feed> importFeeds() throws Exception {
        Map<String, Feed> feedMap = new HashMap<>();
        File indexFile = new File(dataDir, "index.json");
        if (!indexFile.exists()) {
            LOG.warn("index.json not found at " + indexFile);
            return feedMap;
        }

        JsonNode root = JSON.readTree(indexFile);
        JsonNode feeds = root.get("feeds");
        if (feeds == null || !feeds.isArray()) return feedMap;

        for (JsonNode fn : feeds) {
            String url = fn.has("url") ? fn.get("url").asText() : null;
            String hash = fn.has("id") ? fn.get("id").asText() : null;
            if (url == null || hash == null) continue;

            Feed existing = Feed.findByHash(hash);
            if (existing != null) {
                feedMap.put(hash, existing);
                continue;
            }

            Feed feed = new Feed();
            feed.url = url;
            feed.feedHash = hash;
            feed.title = fn.has("title") ? fn.get("title").asText() : null;
            feed.siteUrl = fn.has("siteUrl") ? fn.get("siteUrl").asText() : null;
            if (fn.has("lastUpdated") && !fn.get("lastUpdated").isNull()) {
                try { feed.lastFetchedAt = Instant.parse(fn.get("lastUpdated").asText()); }
                catch (Exception ignored) {}
            }
            if (fn.has("error") && !fn.get("error").isNull()) {
                feed.lastError = fn.get("error").asText();
            }
            feed.persist();
            feedMap.put(hash, feed);
            LOG.infof("  Feed imported: %s (%s)", feed.title, hash);
        }

        LOG.infof("Imported %d feeds", feedMap.size());
        return feedMap;
    }

    void importArticles(Map<String, Feed> feedMap) throws Exception {
        File feedsDir = new File(dataDir, "feeds");
        if (!feedsDir.isDirectory()) {
            LOG.warn("feeds directory not found at " + feedsDir);
            return;
        }

        int total = 0;
        File[] feedFiles = feedsDir.listFiles((d, name) -> name.startsWith("feed-") && name.endsWith(".json"));
        if (feedFiles == null) return;

        for (File file : feedFiles) {
            String hash = file.getName().replace("feed-", "").replace(".json", "");
            Feed feed = feedMap.get(hash);
            if (feed == null) {
                LOG.warnf("  No feed for hash %s, skipping", hash);
                continue;
            }

            JsonNode root = JSON.readTree(file);
            JsonNode articles = root.get("articles");
            if (articles == null || !articles.isArray()) continue;

            int count = 0;
            for (JsonNode an : articles) {
                String guid = an.has("guid") ? an.get("guid").asText() : null;
                if (guid == null) continue;

                Article existing = Article.findByFeedAndGuid(feed, guid);
                if (existing != null) continue;

                Article a = new Article();
                a.feed = feed;
                a.guid = guid;
                a.title = jsonStr(an, "title");
                a.link = jsonStr(an, "link");
                a.author = jsonStr(an, "author");
                a.summary = jsonStr(an, "summary");
                a.content = jsonStr(an, "content");
                a.image = jsonStr(an, "image");
                if (an.has("date") && !an.get("date").isNull()) {
                    try { a.publishedAt = Instant.parse(an.get("date").asText()); }
                    catch (Exception ignored) {}
                }
                a.fetchedAt = Instant.now();
                a.persist();
                count++;
            }
            total += count;
        }
        LOG.infof("Imported %d articles", total);
    }

    void importCache(Map<String, Feed> feedMap) throws Exception {
        File cache = new File(cacheFile);
        if (!cache.exists()) return;

        JsonNode root = JSON.readTree(cache);
        int updated = 0;
        var it = root.fields();
        while (it.hasNext()) {
            var entry = it.next();
            String url = entry.getKey();
            JsonNode val = entry.getValue();

            // Find feed by URL
            Feed feed = Feed.findByUrl(url);
            if (feed == null) continue;

            if (val.has("etag") && !val.get("etag").isNull()) {
                feed.etag = val.get("etag").asText();
            }
            if (val.has("last_modified") && !val.get("last_modified").isNull()) {
                feed.lastModified = val.get("last_modified").asText();
            }
            feed.persist();
            updated++;
        }
        LOG.infof("Updated cache for %d feeds", updated);
    }

    void importUser(File userDir, Map<String, Feed> feedMap) throws Exception {
        String username = userDir.getName();
        LOG.infof("Importing user: %s", username);

        // Find or skip user (user must be created via /auth/setup first)
        User user = User.findByUsername(username);
        if (user == null) {
            LOG.warnf("  User '%s' not found in DB, skipping. Create via POST /auth/setup first.", username);
            return;
        }

        // 1. Import OPML subscriptions
        File opmlFile = new File(userDir, "feeds.opml");
        if (opmlFile.exists()) {
            importOpml(user, opmlFile, feedMap);
        }

        // 2. Import read/starred state
        File stateFile = new File(userDir, "state.json");
        if (stateFile.exists()) {
            importState(user, stateFile);
        }

        // 3. Import starred article content
        File starredFile = new File(userDir, "starred.json");
        if (starredFile.exists()) {
            importStarredContent(user, starredFile, feedMap);
        }
    }

    void importOpml(User user, File opmlFile, Map<String, Feed> feedMap) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(opmlFile);
        NodeList outlines = doc.getElementsByTagName("outline");

        int added = 0;
        for (int i = 0; i < outlines.getLength(); i++) {
            Element el = (Element) outlines.item(i);
            String xmlUrl = el.getAttribute("xmlUrl");
            if (xmlUrl == null || xmlUrl.isBlank()) continue;

            // Determine category
            String category = "";
            if (el.getParentNode() instanceof Element parent) {
                if ("outline".equals(parent.getTagName()) && parent.getAttribute("xmlUrl").isBlank()) {
                    category = parent.getAttribute("text");
                    if (category == null) category = "";
                }
            }

            // Find or create feed
            Feed feed = Feed.findByUrl(xmlUrl);
            if (feed == null) {
                feed = new Feed();
                feed.url = xmlUrl;
                feed.feedHash = FeedResource.computeHash(xmlUrl);
                String t = el.getAttribute("text");
                if (t == null || t.isBlank()) t = el.getAttribute("title");
                feed.title = t;
                feed.siteUrl = el.getAttribute("htmlUrl");
                feed.persist();
                feedMap.put(feed.feedHash, feed);
            }

            // Create subscription if not exists
            UserFeed existing = UserFeed.findByUserAndFeed(user, feed);
            if (existing == null) {
                UserFeed uf = new UserFeed();
                uf.user = user;
                uf.feed = feed;
                uf.category = category;
                uf.persist();
                added++;
            }
        }
        LOG.infof("  OPML: %d subscriptions added", added);
    }

    void importState(User user, File stateFile) throws Exception {
        JsonNode root = JSON.readTree(stateFile);

        int readCount = 0, starredCount = 0;

        // Import read GUIDs
        JsonNode readArr = root.get("read");
        if (readArr != null && readArr.isArray()) {
            for (JsonNode guidNode : readArr) {
                String guid = guidNode.asText();
                Article article = Article.find("guid", guid).firstResult();
                if (article == null) continue;

                UserArticle ua = UserArticle.getOrCreate(user, article);
                ua.isRead = true;
                ua.readAt = Instant.now();
                ua.persist();
                readCount++;
            }
        }

        // Import starred GUIDs
        JsonNode starredArr = root.get("starred");
        if (starredArr != null && starredArr.isArray()) {
            for (JsonNode guidNode : starredArr) {
                String guid = guidNode.asText();
                Article article = Article.find("guid", guid).firstResult();
                if (article == null) continue;

                UserArticle ua = UserArticle.getOrCreate(user, article);
                ua.isStarred = true;
                ua.starredAt = Instant.now();
                ua.persist();
                starredCount++;
            }
        }

        LOG.infof("  State: %d read, %d starred", readCount, starredCount);
    }

    void importStarredContent(User user, File starredFile, Map<String, Feed> feedMap) throws Exception {
        JsonNode root = JSON.readTree(starredFile);
        JsonNode articles = root.get("articles");
        if (articles == null || !articles.isArray()) return;

        int imported = 0;
        for (JsonNode an : articles) {
            String guid = an.has("guid") ? an.get("guid").asText() : null;
            if (guid == null) continue;

            // Check if article exists
            Article existing = Article.find("guid", guid).firstResult();
            if (existing != null) continue;

            // Article was purged from feeds but user had it starred — re-create
            String feedId = an.has("feedId") ? an.get("feedId").asText() : null;
            Feed feed = feedId != null ? feedMap.get(feedId) : null;
            if (feed == null) {
                // Create a placeholder feed if needed
                feed = feedMap.values().stream().findFirst().orElse(null);
                if (feed == null) continue;
            }

            Article a = new Article();
            a.feed = feed;
            a.guid = guid;
            a.title = jsonStr(an, "title");
            a.link = jsonStr(an, "link");
            a.author = jsonStr(an, "author");
            a.summary = jsonStr(an, "summary");
            a.content = jsonStr(an, "content");
            a.image = jsonStr(an, "image");
            if (an.has("date") && !an.get("date").isNull()) {
                try { a.publishedAt = Instant.parse(an.get("date").asText()); }
                catch (Exception ignored) {}
            }
            a.fetchedAt = Instant.now();
            a.persist();

            // Mark as starred
            UserArticle ua = new UserArticle();
            ua.user = user;
            ua.article = a;
            ua.isStarred = true;
            ua.starredAt = Instant.now();
            ua.persist();
            imported++;
        }

        if (imported > 0) {
            LOG.infof("  Starred content: %d orphaned articles re-imported", imported);
        }
    }

    private static String jsonStr(JsonNode node, String field) {
        if (node.has(field) && !node.get(field).isNull()) return node.get(field).asText();
        return "";
    }
}

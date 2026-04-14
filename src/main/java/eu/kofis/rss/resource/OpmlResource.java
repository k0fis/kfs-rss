package eu.kofis.rss.resource;

import eu.kofis.rss.entity.Feed;
import eu.kofis.rss.entity.User;
import eu.kofis.rss.entity.UserFeed;
import eu.kofis.rss.resource.FeedResource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.io.StringWriter;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Path("/feeds/opml")
@RolesAllowed("user")
public class OpmlResource {

    @GET
    @Produces("text/xml")
    public String exportOpml(@Context SecurityContext sec) throws Exception {
        User user = User.findByUsername(sec.getUserPrincipal().getName());
        List<UserFeed> subs = UserFeed.findByUser(user);

        // Group by category
        Map<String, List<UserFeed>> byCategory = new LinkedHashMap<>();
        for (UserFeed uf : subs) {
            byCategory.computeIfAbsent(uf.category, k -> new java.util.ArrayList<>()).add(uf);
        }

        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = builder.newDocument();

        Element opml = doc.createElement("opml");
        opml.setAttribute("version", "1.0");
        doc.appendChild(opml);

        Element head = doc.createElement("head");
        Element title = doc.createElement("title");
        title.setTextContent(user.username + " RSS feeds");
        head.appendChild(title);
        Element dateCreated = doc.createElement("dateCreated");
        dateCreated.setTextContent(ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME));
        head.appendChild(dateCreated);
        opml.appendChild(head);

        Element body = doc.createElement("body");
        opml.appendChild(body);

        for (Map.Entry<String, List<UserFeed>> entry : byCategory.entrySet()) {
            String cat = entry.getKey();
            List<UserFeed> feeds = entry.getValue();

            Element parent;
            if (cat != null && !cat.isBlank()) {
                parent = doc.createElement("outline");
                parent.setAttribute("text", cat);
                parent.setAttribute("title", cat);
                body.appendChild(parent);
            } else {
                parent = body;
            }

            for (UserFeed uf : feeds) {
                Feed f = uf.feed;
                Element outline = doc.createElement("outline");
                outline.setAttribute("type", "rss");
                outline.setAttribute("text", f.title != null ? f.title : f.url);
                outline.setAttribute("title", f.title != null ? f.title : f.url);
                outline.setAttribute("xmlUrl", f.url);
                if (f.siteUrl != null) outline.setAttribute("htmlUrl", f.siteUrl);
                parent.appendChild(outline);
            }
        }

        Transformer tf = TransformerFactory.newInstance().newTransformer();
        tf.setOutputProperty(OutputKeys.INDENT, "yes");
        tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter sw = new StringWriter();
        tf.transform(new DOMSource(doc), new StreamResult(sw));
        return sw.toString();
    }

    @POST
    @Consumes(MediaType.APPLICATION_XML)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Map<String, Integer> importOpml(InputStream input, @Context SecurityContext sec) throws Exception {
        User user = User.findByUsername(sec.getUserPrincipal().getName());

        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = builder.parse(input);

        // Collect existing feed URLs for this user
        List<UserFeed> existing = UserFeed.findByUser(user);
        Set<String> existingUrls = new HashSet<>();
        for (UserFeed uf : existing) {
            existingUrls.add(uf.feed.url);
        }

        int added = 0, skipped = 0;

        NodeList outlines = doc.getElementsByTagName("outline");
        for (int i = 0; i < outlines.getLength(); i++) {
            Element el = (Element) outlines.item(i);
            String xmlUrl = el.getAttribute("xmlUrl");
            if (xmlUrl == null || xmlUrl.isBlank()) continue;

            if (existingUrls.contains(xmlUrl)) {
                skipped++;
                continue;
            }

            // Determine category from parent
            String category = "";
            if (el.getParentNode() instanceof Element parent) {
                if ("outline".equals(parent.getTagName()) && parent.getAttribute("xmlUrl").isBlank()) {
                    category = parent.getAttribute("text");
                    if (category == null) category = parent.getAttribute("title");
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
            }

            UserFeed uf = new UserFeed();
            uf.user = user;
            uf.feed = feed;
            uf.category = category;
            uf.persist();

            existingUrls.add(xmlUrl);
            added++;
        }

        return Map.of("added", added, "skipped", skipped);
    }
}

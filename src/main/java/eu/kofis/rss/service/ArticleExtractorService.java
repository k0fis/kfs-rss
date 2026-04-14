package eu.kofis.rss.service;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEnclosure;
import com.rometools.rome.feed.synd.SyndEntry;
import org.jdom2.Element;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class ArticleExtractorService {

    private static final Pattern IMG_PATTERN = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    public String extractGuid(SyndEntry entry) {
        if (entry.getUri() != null && !entry.getUri().isBlank()) return entry.getUri();
        if (entry.getLink() != null && !entry.getLink().isBlank()) return entry.getLink();
        if (entry.getTitle() != null) return entry.getTitle();
        return String.valueOf(System.nanoTime());
    }

    public String extractContent(SyndEntry entry) {
        String best = "";
        // Check all content entries, pick longest
        if (entry.getContents() != null) {
            for (SyndContent c : entry.getContents()) {
                if (c.getValue() != null && c.getValue().length() > best.length()) {
                    best = c.getValue();
                }
            }
        }
        // Also check description
        if (entry.getDescription() != null && entry.getDescription().getValue() != null) {
            String desc = entry.getDescription().getValue();
            if (desc.length() > best.length()) {
                best = desc;
            }
        }
        return best;
    }

    public String extractSummary(SyndEntry entry, String content) {
        // Prefer description as summary if it's shorter than content
        String desc = "";
        if (entry.getDescription() != null && entry.getDescription().getValue() != null) {
            desc = entry.getDescription().getValue();
        }

        String source = desc.length() > 0 && desc.length() < content.length() ? desc : content;

        // Strip HTML, limit to 300 chars
        String plain = HTML_TAG.matcher(source).replaceAll("").trim();
        plain = plain.replaceAll("\\s+", " ");
        if (plain.length() > 300) {
            plain = plain.substring(0, 297) + "...";
        }
        return plain;
    }

    public String extractImage(SyndEntry entry) {
        // 1. Media RSS modules (media:content, media:thumbnail)
        List<Element> foreignMarkup = entry.getForeignMarkup();
        if (foreignMarkup != null) {
            for (Element el : foreignMarkup) {
                if ("thumbnail".equals(el.getName()) || "content".equals(el.getName())) {
                    String url = el.getAttributeValue("url");
                    if (url != null && isImageUrl(url)) return url;
                }
            }
        }

        // 2. Enclosures (image/*)
        if (entry.getEnclosures() != null) {
            for (SyndEnclosure enc : entry.getEnclosures()) {
                if (enc.getType() != null && enc.getType().startsWith("image/")) {
                    return enc.getUrl();
                }
            }
        }

        // 3. First <img> in content
        String content = extractContent(entry);
        if (content != null) {
            Matcher m = IMG_PATTERN.matcher(content);
            if (m.find()) {
                return m.group(1);
            }
        }

        return "";
    }

    public Instant extractDate(SyndEntry entry) {
        Date d = entry.getPublishedDate();
        if (d == null) d = entry.getUpdatedDate();
        if (d == null) return Instant.now();
        return d.toInstant();
    }

    public String extractAuthor(SyndEntry entry) {
        if (entry.getAuthor() != null && !entry.getAuthor().isBlank()) {
            return entry.getAuthor();
        }
        return "";
    }

    public String extractLink(SyndEntry entry) {
        if (entry.getLink() != null) return entry.getLink();
        if (entry.getUri() != null) return entry.getUri();
        return "";
    }

    private boolean isImageUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png")
                || lower.contains(".gif") || lower.contains(".webp")
                || lower.contains("image");
    }
}

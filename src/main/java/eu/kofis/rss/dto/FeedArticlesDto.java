package eu.kofis.rss.dto;

import java.util.List;

public class FeedArticlesDto {
    public String id;
    public String title;
    public List<ArticleDto> articles;

    public FeedArticlesDto(String id, String title, List<ArticleDto> articles) {
        this.id = id;
        this.title = title;
        this.articles = articles;
    }
}

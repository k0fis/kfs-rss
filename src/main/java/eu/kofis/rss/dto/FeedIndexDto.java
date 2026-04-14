package eu.kofis.rss.dto;

import java.util.List;

public class FeedIndexDto {
    public String updated;
    public List<FeedDto> feeds;

    public FeedIndexDto(String updated, List<FeedDto> feeds) {
        this.updated = updated;
        this.feeds = feeds;
    }
}

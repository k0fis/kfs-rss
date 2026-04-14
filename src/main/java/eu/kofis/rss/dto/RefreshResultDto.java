package eu.kofis.rss.dto;

public class RefreshResultDto {
    public int fetched;
    public int cached;
    public int errors;

    public RefreshResultDto(int fetched, int cached, int errors) {
        this.fetched = fetched;
        this.cached = cached;
        this.errors = errors;
    }
}

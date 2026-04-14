package eu.kofis.rss.resource;

import eu.kofis.rss.dto.RefreshResultDto;
import eu.kofis.rss.entity.User;
import eu.kofis.rss.service.FeedFetcherService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

@Path("/feeds/refresh")
@RolesAllowed("user")
@Produces(MediaType.APPLICATION_JSON)
public class RefreshResource {

    @Inject
    FeedFetcherService fetcherService;

    @POST
    public RefreshResultDto refresh(@Context SecurityContext sec) {
        User user = User.findByUsername(sec.getUserPrincipal().getName());
        return fetcherService.refreshUserFeeds(user);
    }
}

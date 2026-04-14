package eu.kofis.rss.resource;

import eu.kofis.rss.entity.User;
import jakarta.annotation.security.PermitAll;
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
import org.mindrot.jbcrypt.BCrypt;
import java.util.Map;

@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

    @GET
    @Path("/check")
    @RolesAllowed("user")
    public Map<String, String> check(@Context SecurityContext sec) {
        return Map.of("username", sec.getUserPrincipal().getName());
    }

    /**
     * Create a user — only works when no users exist (initial setup)
     * or when called by an authenticated user (admin).
     */
    @POST
    @Path("/setup")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response setup(Map<String, String> body) {
        long userCount = User.count();

        // Only allow unauthenticated setup when DB has no users
        if (userCount > 0) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Users already exist. Use authenticated endpoint."))
                    .build();
        }

        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null || username.isBlank() || password.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "username and password required"))
                    .build();
        }

        User user = new User();
        user.username = username.trim();
        user.password = BCrypt.hashpw(password, BCrypt.gensalt());
        user.persist();

        return Response.ok(Map.of("username", user.username, "message", "User created")).build();
    }
}

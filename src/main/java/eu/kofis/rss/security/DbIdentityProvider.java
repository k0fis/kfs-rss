package eu.kofis.rss.security;

import eu.kofis.rss.entity.User;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.mindrot.jbcrypt.BCrypt;

@ApplicationScoped
public class DbIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            UsernamePasswordAuthenticationRequest request,
            AuthenticationRequestContext context) {
        return context.runBlocking(() -> validate(request));
    }

    @Transactional
    SecurityIdentity validate(UsernamePasswordAuthenticationRequest request) {
        String username = request.getUsername();
        String password = new String(request.getPassword().getPassword());

        User user = User.findByUsername(username);
        if (user == null || !BCrypt.checkpw(password, user.password)) {
            throw new io.quarkus.security.AuthenticationFailedException("Invalid credentials");
        }

        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(username))
                .addRole("user")
                .build();
    }
}

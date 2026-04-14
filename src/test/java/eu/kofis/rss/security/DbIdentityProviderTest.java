package eu.kofis.rss.security;

import eu.kofis.rss.entity.User;
import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.credential.PasswordCredential;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@QuarkusTest
class DbIdentityProviderTest {

    @Inject
    DbIdentityProvider provider;

    static final String TEST_USER = "testuser";
    static final String TEST_PASS = "secret123";
    static final String TEST_HASH = BCrypt.hashpw(TEST_PASS, BCrypt.gensalt());

    @BeforeEach
    void setUp() {
        PanacheMock.mock(User.class);
    }

    @Test
    void validate_successReturnsIdentityWithRole() {
        var user = new User();
        user.username = TEST_USER;
        user.password = TEST_HASH;
        when(User.findByUsername(TEST_USER)).thenReturn(user);

        SecurityIdentity identity = provider.validate(createRequest(TEST_USER, TEST_PASS));

        assertNotNull(identity);
        assertTrue(identity.getRoles().contains("user"));
    }

    @Test
    void validate_correctPrincipalName() {
        var user = new User();
        user.username = TEST_USER;
        user.password = TEST_HASH;
        when(User.findByUsername(TEST_USER)).thenReturn(user);

        SecurityIdentity identity = provider.validate(createRequest(TEST_USER, TEST_PASS));

        assertEquals(TEST_USER, identity.getPrincipal().getName());
    }

    @Test
    void validate_unknownUserThrows() {
        when(User.findByUsername("nobody")).thenReturn(null);

        assertThrows(AuthenticationFailedException.class,
                () -> provider.validate(createRequest("nobody", "pass")));
    }

    @Test
    void validate_wrongPasswordThrows() {
        var user = new User();
        user.username = TEST_USER;
        user.password = TEST_HASH;
        when(User.findByUsername(TEST_USER)).thenReturn(user);

        assertThrows(AuthenticationFailedException.class,
                () -> provider.validate(createRequest(TEST_USER, "wrongpass")));
    }

    private UsernamePasswordAuthenticationRequest createRequest(String username, String password) {
        return new UsernamePasswordAuthenticationRequest(username,
                new PasswordCredential(password.toCharArray()));
    }
}

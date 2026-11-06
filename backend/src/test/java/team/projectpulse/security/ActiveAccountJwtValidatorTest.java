package team.projectpulse.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import team.projectpulse.user.UserService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * FR-SEC-active-account at the validator: which {@code userId} claims name an account, and that the account's
 * current state decides the result. The end-to-end behavior over a real token is in {@code ActiveAccountIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class ActiveAccountJwtValidatorTest {

    @Mock
    UserService userService;

    @InjectMocks
    ActiveAccountJwtValidator validator;

    private static Jwt jwtWithClaims(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .subject("j.smith@abc.edu");
        claims.forEach(builder::claim);
        return builder.build();
    }

    @Test
    void testActiveAccountPasses() {
        given(this.userService.isAccountActive(4)).willReturn(true);

        assertThat(this.validator.validate(jwtWithClaims(Map.of("userId", 4))).hasErrors()).isFalse();
        // A numeric string, and a JSON number decoded as a Long, name the same account
        assertThat(this.validator.validate(jwtWithClaims(Map.of("userId", "4"))).hasErrors()).isFalse();
        assertThat(this.validator.validate(jwtWithClaims(Map.of("userId", 4L))).hasErrors()).isFalse();
    }

    @Test
    void testInactiveOrUnknownAccountFails() {
        given(this.userService.isAccountActive(4)).willReturn(false); // Deactivated
        given(this.userService.isAccountActive(999)).willReturn(false); // No such user

        assertThat(this.validator.validate(jwtWithClaims(Map.of("userId", 4))).getErrors())
                .containsExactly(ActiveAccountJwtValidator.INACTIVE_ACCOUNT);
        assertThat(this.validator.validate(jwtWithClaims(Map.of("userId", 999))).hasErrors()).isTrue();
    }

    @Test
    void testMalformedUserIdFailsWithoutALookup() {
        List<Object> malformed = List.of("abc", "", "4.0", 4.5, 0, -1, (long) Integer.MAX_VALUE + 1, "99999999999", true, List.of(4), Map.of("id", 4));
        for (Object claim : malformed) {
            assertThat(this.validator.validate(jwtWithClaims(Map.of("userId", claim))).hasErrors())
                    .as("userId claim %s", claim).isTrue();
        }
        assertThat(this.validator.validate(jwtWithClaims(Map.of())).hasErrors()).as("missing userId").isTrue();
        verify(this.userService, never()).isAccountActive(anyInt());
    }

}

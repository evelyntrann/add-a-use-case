package team.projectpulse.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import team.projectpulse.user.UserService;

/**
 * Refuses a correctly signed, unexpired token whose account is no longer active (FR-SEC-active-account,
 * UC-STU-deactivate-student extension 6a).
 *
 * <p>Signature and expiry prove only that this server issued the token and that it has not aged out. Whether the
 * account it names may still be used is a separate question, answered here from the database on every request, so a
 * deactivation takes effect on the next protected request rather than when the token expires. Composed with
 * {@code JwtValidators.createDefault()} in {@link SecurityConfiguration#jwtDecoder}, so the normal timestamp checks
 * still run.
 *
 * <p>The account is named by the {@code userId} claim, which {@link JwtProvider} issues as a JSON number from the
 * user's {@code Integer} id. A numeric string is accepted defensively. Anything else (missing, fractional, zero or
 * negative, beyond {@code Integer} range, not a number) names no account and fails, as does an unknown id.
 */
@Component
public class ActiveAccountJwtValidator implements OAuth2TokenValidator<Jwt> {

    static final OAuth2Error INACTIVE_ACCOUNT = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
            "The account this token was issued to is not active.", null);

    private final UserService userService;


    public ActiveAccountJwtValidator(UserService userService) {
        this.userService = userService;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Integer userId = readUserId(jwt.getClaims().get("userId"));
        if (userId == null || !this.userService.isAccountActive(userId)) {
            return OAuth2TokenValidatorResult.failure(INACTIVE_ACCOUNT);
        }
        return OAuth2TokenValidatorResult.success();
    }

    /**
     * @return the claim as a positive {@code Integer} id, or {@code null} if it is not one
     */
    static Integer readUserId(Object claim) {
        long value;
        if (claim instanceof Integer || claim instanceof Long || claim instanceof Short || claim instanceof Byte) {
            value = ((Number) claim).longValue();
        } else if (claim instanceof String s) {
            try {
                value = Long.parseLong(s.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        } else {
            return null; // Missing, a fractional number, a boolean, an object or an array
        }
        if (value < 1 || value > Integer.MAX_VALUE) {
            return null;
        }
        return (int) value;
    }

}

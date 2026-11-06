package team.projectpulse.security;

import jakarta.persistence.EntityManager;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.system.StatusCode;
import team.projectpulse.user.PeerEvaluationUser;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-SEC-active-account end to end (UC-STU-deactivate-student extension 6a): a real token issued while the account was
 * active stops working on the next protected request once the account is deactivated, without waiting for it to
 * expire; and the normal JWT checks still run alongside. The deactivation here is a direct write, because what is
 * under test is the token check, not the action that changes the flag.
 */
@DisplayName("Integration tests for active-account authentication")
class ActiveAccountIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    EntityManager entityManager;
    @Autowired
    JwtEncoder jwtEncoder;

    @Value("${api.endpoint.base-url}")
    String baseUrl;

    private String login(String username) throws Exception {
        String body = this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic(username, "123456")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + new JSONObject(body).getJSONObject("data").getString("token");
    }

    private PeerEvaluationUser user(String email) {
        return this.entityManager.createQuery("select u from PeerEvaluationUser u where u.email = :email", PeerEvaluationUser.class)
                .setParameter("email", email).getSingleResult();
    }

    private void setEnabled(String email, boolean enabled) {
        user(email).setEnabled(enabled);
        this.entityManager.flush();
    }

    private String signed(Object userId, Instant expiresAt) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer("self").subject("j.smith@abc.edu")
                .issuedAt(expiresAt.minus(2, ChronoUnit.HOURS)).expiresAt(expiresAt).claim("authorities", "ROLE_student");
        if (userId != null) {
            claims.claim("userId", userId);
        }
        return "Bearer " + this.jwtEncoder.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();
    }

    private void expectUnauthorized(String authorization) throws Exception {
        var request = get(this.baseUrl + "/students/4");
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        this.mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED));
    }

    @Test
    void testTokenIssuedBeforeDeactivationIsRefusedOnTheNextRequest() throws Exception {
        // Given: John's real, unexpired token works
        String johnToken = login("j.smith@abc.edu");
        this.mockMvc.perform(get(this.baseUrl + "/students/4").header(HttpHeaders.AUTHORIZATION, johnToken))
                .andExpect(jsonPath("$.flag").value(true));

        // When
        setEnabled("j.smith@abc.edu", false);

        // Then: the exact same token is now refused with the shared 401 envelope
        expectUnauthorized(johnToken);
    }

    @Test
    void testDeactivatedStudentCannotLogIn() throws Exception {
        setEnabled("j.smith@abc.edu", false);

        this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic("j.smith@abc.edu", "123456")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED));
    }

    @Test
    void testDeactivatedInstructorTokenIsRefused() throws Exception {
        String billToken = login("b.gates@abc.edu");
        setEnabled("b.gates@abc.edu", false);

        this.mockMvc.perform(get(this.baseUrl + "/students/4").header(HttpHeaders.AUTHORIZATION, billToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED));
    }

    @Test
    void testActiveAccountKeepsWorkingAfterAUsernameChange() throws Exception {
        // The account is looked up by the immutable userId, not the token's sub
        String johnToken = login("j.smith@abc.edu");
        user("j.smith@abc.edu").setUsername("john.smith@abc.edu");
        this.entityManager.flush();

        this.mockMvc.perform(get(this.baseUrl + "/students/4").header(HttpHeaders.AUTHORIZATION, johnToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true));
    }

    @Test
    void testMalformedMissingAndUnknownUserIdsAreRefused() throws Exception {
        Instant inAnHour = Instant.now().plus(1, ChronoUnit.HOURS);
        expectUnauthorized(signed("abc", inAnHour));
        expectUnauthorized(signed(null, inAnHour));
        expectUnauthorized(signed(999_999, inAnHour));
        expectUnauthorized(signed(-4, inAnHour));
    }

    @Test
    void testNormalJwtChecksStillRun() throws Exception {
        // A correctly signed token for an active account works...
        this.mockMvc.perform(get(this.baseUrl + "/students/4").header(HttpHeaders.AUTHORIZATION, signed(4, Instant.now().plus(1, ChronoUnit.HOURS))))
                .andExpect(status().isOk());
        // ...but not once expired, nor with a tampered signature, nor with no credentials at all
        expectUnauthorized(signed(4, Instant.now().minus(10, ChronoUnit.MINUTES)));
        String valid = login("j.smith@abc.edu");
        expectUnauthorized(valid.substring(0, valid.length() - 4) + (valid.endsWith("AAAA") ? "BBBB" : "AAAA"));
        expectUnauthorized(null);
    }

}

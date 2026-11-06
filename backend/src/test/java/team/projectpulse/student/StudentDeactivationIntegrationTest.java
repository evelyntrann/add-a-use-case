package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.evaluation.dto.PeerEvaluationDto;
import team.projectpulse.evaluation.dto.RatingDto;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.section.Section;
import team.projectpulse.system.StatusCode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-STU-deactivate-student over HTTP: the dedicated action's main flow (step 6), extension 6b, every refusal under
 * extension 1a including permission lost after the warning opened, extension 6a with the action itself, and the
 * existing paths the design keeps: the generic profile update, legacy reactivation, and peer evaluation writes after
 * deactivation (BR-evaluation-active-evaluatee). The dev clock is fixed at 2023-08-20, so 2023-W32 is the open week.
 *
 * <p>Seed: John (4), Eric (5), Jerry (6) are Team1 and Woody (7), Amanda (8) Team2, in course section 2. Bill is
 * assigned there; Wei owns its course; Tim owns another course.
 */
@DisplayName("Integration tests for deactivating a student")
class StudentDeactivationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JsonMapper jsonMapper;
    @Autowired
    EntityManager entityManager;

    @Value("${api.endpoint.base-url}")
    String baseUrl;

    String weiToken;
    String billToken;
    String timToken;
    String johnToken;
    String woodyToken;

    private String login(String username) throws Exception {
        String body = this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic(username, "123456")))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + new JSONObject(body).getJSONObject("data").getString("token");
    }

    @BeforeEach
    void setUp() throws Exception {
        this.weiToken = login("b.wei@abc.edu");
        this.billToken = login("b.gates@abc.edu");
        this.timToken = login("t.cook@abc.edu");
        this.johnToken = login("j.smith@abc.edu");
        this.woodyToken = login("w.allen@abc.edu");
    }

    private ResultActions deactivate(String studentId, String token) throws Exception {
        var request = post(this.baseUrl + "/students/" + studentId + "/deactivation").accept(MediaType.APPLICATION_JSON);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, token);
        }
        return this.mockMvc.perform(request);
    }

    private Student student(int id) {
        this.entityManager.flush();
        this.entityManager.clear();
        return this.entityManager.find(Student.class, id);
    }

    private void expectNoPermission(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.FORBIDDEN))
                .andExpect(jsonPath("$.message").value("No permission."));
    }

    private void unassignBill() {
        Instructor bill = this.entityManager.createQuery("select i from Instructor i where i.email = 'b.gates@abc.edu'", Instructor.class).getSingleResult();
        this.entityManager.find(Section.class, 2).removeInstructor(bill);
        this.entityManager.flush();
    }

    private void setEnabled(int studentId, boolean enabled) {
        this.entityManager.find(Student.class, studentId).setEnabled(enabled);
        this.entityManager.flush();
    }

    @Test
    void testAssignedInstructorDeactivatesThenRepeats() throws Exception {
        deactivate("4", this.billToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.code").value(StatusCode.SUCCESS))
                .andExpect(jsonPath("$.message").value("Student deactivated successfully"))
                .andExpect(jsonPath("$.data").isEmpty());
        Student john = student(4);
        assertThat(john.isEnabled()).isFalse();
        assertThat(john.getTeam().getTeamName()).isEqualTo("Team1");

        // Extension 6b: a second request, including one from another permitted actor, is a successful no-op
        deactivate("4", this.weiToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.message").value("Student is already deactivated"));
        assertThat(student(4).isEnabled()).isFalse();
    }

    @Test
    void testOwningAdminUnassignedToTheCourseSectionDeactivates() throws Exception {
        Instructor wei = this.entityManager.createQuery("select i from Instructor i where i.email = 'b.wei@abc.edu'", Instructor.class).getSingleResult();
        this.entityManager.find(Section.class, 2).removeInstructor(wei);
        this.entityManager.flush();

        deactivate("5", this.weiToken).andExpect(jsonPath("$.message").value("Student deactivated successfully"));
        assertThat(student(5).isEnabled()).isFalse();
    }

    @Test
    void testExistingSessionEndsOnTheNextRequest() throws Exception {
        // Extension 6a with the action itself: John's token, issued while he was active, stops working
        deactivate("4", this.billToken).andExpect(status().isOk());

        this.mockMvc.perform(get(this.baseUrl + "/students/4").header(HttpHeaders.AUTHORIZATION, this.johnToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED));
    }

    @Test
    void testRefusalsChangeNothing() throws Exception {
        // Another course's admin, the student herself, a missing id, and a malformed one: all the same 403
        expectNoPermission(deactivate("4", this.timToken));
        expectNoPermission(deactivate("4", this.johnToken));
        expectNoPermission(deactivate("999999", this.billToken));
        expectNoPermission(deactivate("abc", this.billToken));
        // No credentials at all
        deactivate("4", null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.flag").value(false));

        Student john = student(4);
        assertThat(john.isEnabled()).isTrue();
        assertThat(john.getTeam().getTeamName()).isEqualTo("Team1");
    }

    @Test
    void testUnassignedInstructorAndUnauthorizedAlreadyInactiveRequestsAreRefused() throws Exception {
        unassignBill();
        expectNoPermission(deactivate("4", this.billToken));

        setEnabled(4, false);
        // Refused rather than told the student is already deactivated
        expectNoPermission(deactivate("4", this.timToken));
        expectNoPermission(deactivate("4", this.billToken));
    }

    @Test
    void testPermissionLostAfterTheWarningOpenedIsRefusedAtConfirm() throws Exception {
        for (boolean enabled : List.of(true, false)) {
            // Given: Bill views John's details (step 2), so the warning can open
            setEnabled(4, enabled);
            this.mockMvc.perform(get(this.baseUrl + "/students/4").header(HttpHeaders.AUTHORIZATION, this.billToken))
                    .andExpect(status().isOk());

            // When: his assignment is removed before he confirms
            unassignBill();

            // Then: 403, not 404 and not the already-deactivated message
            expectNoPermission(deactivate("4", this.billToken));
            assertThat(student(4).isEnabled()).isEqualTo(enabled);

            Instructor bill = this.entityManager.createQuery("select i from Instructor i where i.email = 'b.gates@abc.edu'", Instructor.class).getSingleResult();
            this.entityManager.find(Section.class, 2).addInstructor(bill);
            this.entityManager.flush();
        }
    }

    private String profileUpdate(boolean enabled) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", "j.smith@abc.edu");
        body.put("firstName", "John (updated)");
        body.put("lastName", "Smith");
        body.put("email", "j.smith@abc.edu");
        body.put("enabled", enabled);
        return this.jsonMapper.writeValueAsString(body);
    }

    private ResultActions updateJohn(String token, boolean enabled) throws Exception {
        return this.mockMvc.perform(put(this.baseUrl + "/students/4").contentType(MediaType.APPLICATION_JSON)
                .content(profileUpdate(enabled)).header(HttpHeaders.AUTHORIZATION, token));
    }

    @Test
    void testProfileUpdateCannotDeactivate() throws Exception {
        for (String token : List.of(this.billToken, this.weiToken)) {
            updateJohn(token, false)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.flag").value(false))
                    .andExpect(jsonPath("$.code").value(StatusCode.INVALID_ARGUMENT))
                    .andExpect(jsonPath("$.message").value("Use the Deactivate action to deactivate a student."));
        }
        Student john = student(4);
        assertThat(john.isEnabled()).isTrue();
        assertThat(john.getFirstName()).isEqualTo("John"); // No profile field changed either
    }

    @Test
    void testProfileEditsAndLegacyReactivationStillWork() throws Exception {
        // Active to active
        updateJohn(this.billToken, true).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));

        // Inactive to inactive, then the existing inactive-to-active path
        deactivate("4", this.billToken).andExpect(status().isOk());
        updateJohn(this.billToken, false).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
        updateJohn(this.billToken, true).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));
    }

    @Test
    void testReactivationKeepsTheThenCurrentNullTeam() throws Exception {
        // Given: Eric is deactivated, then separately removed from his team
        deactivate("5", this.billToken).andExpect(status().isOk());
        Student eric = this.entityManager.find(Student.class, 5);
        eric.getTeam().removeStudent(eric);
        this.entityManager.flush();

        // When: reactivated through the existing path
        Map<String, Object> body = new HashMap<>(Map.of("username", "e.hudson@abc.edu", "firstName", "Eric", "lastName", "Hudson",
                "email", "e.hudson@abc.edu", "enabled", true));
        this.mockMvc.perform(put(this.baseUrl + "/students/5").contentType(MediaType.APPLICATION_JSON)
                        .content(this.jsonMapper.writeValueAsString(body)).header(HttpHeaders.AUTHORIZATION, this.billToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));

        // Then: no old assignment is restored
        assertThat(student(5).getTeam()).isNull();
    }

    private String evaluation(int evaluateeId, String week) {
        List<RatingDto> ratings = List.of(new RatingDto(null, 1, 8.0), new RatingDto(null, 2, 8.0), new RatingDto(null, 3, 8.0),
                new RatingDto(null, 4, 8.0), new RatingDto(null, 5, 8.0), new RatingDto(null, 6, 8.0));
        return this.jsonMapper.writeValueAsString(new PeerEvaluationDto(null, week, 0, null, evaluateeId, null, ratings, null, "public", "private", null, null));
    }

    private long evaluations(int evaluatorId, int evaluateeId) {
        return this.entityManager.createQuery("select count(e) from PeerEvaluation e where e.evaluator.id = :evaluator and e.evaluatee.id = :evaluatee", Long.class)
                .setParameter("evaluator", evaluatorId).setParameter("evaluatee", evaluateeId).getSingleResult();
    }

    @Test
    void testNoNewEvaluationOfADeactivatedTeammate() throws Exception {
        // Given
        long before = evaluations(7, 8);
        deactivate("8", this.billToken).andExpect(status().isOk());

        // When: Woody posts directly, as a form opened before the deactivation would
        this.mockMvc.perform(post(this.baseUrl + "/evaluations").contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation(8, "2023-W32")).header(HttpHeaders.AUTHORIZATION, this.woodyToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.message").value("The selected evaluatee is deactivated."));

        // Then
        assertThat(evaluations(7, 8)).isEqualTo(before);
    }

    @Test
    void testNoEditOfAnEvaluationOfADeactivatedTeammate() throws Exception {
        // Given: John's saved evaluation of Eric for the open week
        Object[] stored = this.entityManager.createQuery(
                        "select e.peerEvaluationId, e.totalScore, e.publicComment, e.privateComment from PeerEvaluation e where e.week = '2023-W32' and e.evaluator.id = 4 and e.evaluatee.id = 5", Object[].class)
                .getSingleResult();
        deactivate("5", this.billToken).andExpect(status().isOk());

        // When: the body names Jerry, who is active; the stored evaluatee is what counts
        this.mockMvc.perform(put(this.baseUrl + "/evaluations/" + stored[0]).contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation(6, "2023-W32")).header(HttpHeaders.AUTHORIZATION, this.johnToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The selected evaluatee is deactivated."));

        // Then: scores and both comments unchanged
        this.entityManager.clear();
        Object[] after = this.entityManager.createQuery(
                        "select e.peerEvaluationId, e.totalScore, e.publicComment, e.privateComment from PeerEvaluation e where e.peerEvaluationId = :id", Object[].class)
                .setParameter("id", stored[0]).getSingleResult();
        assertThat(after).isEqualTo(stored);
    }

    @Test
    void testReactivationDoesNotReopenAClosedWeek() throws Exception {
        // Given: Amanda is deactivated and reactivated
        deactivate("8", this.billToken).andExpect(status().isOk());
        setEnabled(8, true);

        // When / Then: an evaluation of her for 2023-W31, now closed, is still refused, for a create and for an edit
        this.mockMvc.perform(post(this.baseUrl + "/evaluations").contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation(8, "2023-W31")).header(HttpHeaders.AUTHORIZATION, this.woodyToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You can only submit evaluations for the previous week."));
        Integer closed = this.entityManager.createQuery(
                        "select e.peerEvaluationId from PeerEvaluation e where e.week = '2023-W31' and e.evaluator.id = 7 and e.evaluatee.id = 8", Integer.class)
                .getSingleResult();
        this.mockMvc.perform(put(this.baseUrl + "/evaluations/" + closed).contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation(8, "2023-W31")).header(HttpHeaders.AUTHORIZATION, this.woodyToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The submission window for this peer evaluation has closed, so it can no longer be changed."));
    }

}

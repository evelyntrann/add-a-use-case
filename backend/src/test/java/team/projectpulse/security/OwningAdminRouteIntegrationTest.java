package team.projectpulse.security;

import jakarta.persistence.EntityManager;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.section.Section;
import team.projectpulse.system.StatusCode;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The route alternatives docs/design/stu.md adds so the course admin who owns a course, without being assigned to its
 * course section, can reach a student to deactivate (UC-STU-deactivate-student step 2, BR-student-lifecycle): select
 * the course section, read it, read the student, and read the section's peer evaluation report and a student's
 * details. Each refusal is checked too: another course's admin, and a student for the instructor-only details.
 */
@DisplayName("Integration tests for the owning course admin's route rules")
class OwningAdminRouteIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    EntityManager entityManager;

    @Value("${api.endpoint.base-url}")
    String baseUrl;

    String weiToken; // Owns course 1; unassigned from course section 2 below
    String timToken; // Owns course 3 only
    String johnToken; // Student in course section 2

    private String login(String username) throws Exception {
        String body = this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic(username, "123456")))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + new JSONObject(body).getJSONObject("data").getString("token");
    }

    @BeforeEach
    void setUp() throws Exception {
        Instructor wei = this.entityManager.createQuery("select i from Instructor i where i.email = 'b.wei@abc.edu'", Instructor.class).getSingleResult();
        this.entityManager.find(Section.class, 2).removeInstructor(wei);
        this.entityManager.flush();
        this.weiToken = login("b.wei@abc.edu");
        this.timToken = login("t.cook@abc.edu");
        this.johnToken = login("j.smith@abc.edu");
    }

    private List<MockHttpServletRequestBuilder> ownerReads() {
        return List.of(
                put(this.baseUrl + "/instructors/sections/2/default"),
                get(this.baseUrl + "/sections/2"),
                get(this.baseUrl + "/students/4"),
                get(this.baseUrl + "/evaluations/sections/2/week/2023-W31"),
                get(this.baseUrl + "/evaluations/students/5/week/2023-W31/details"));
    }

    @Test
    void testOwningAdminUnassignedToTheCourseSectionReachesTheStudent() throws Exception {
        for (MockHttpServletRequestBuilder request : ownerReads()) {
            this.mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, this.weiToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.flag").value(true));
        }
        // The details include the instructor-only private comments
        this.mockMvc.perform(get(this.baseUrl + "/evaluations/students/5/week/2023-W31/details").header(HttpHeaders.AUTHORIZATION, this.weiToken))
                .andExpect(jsonPath("$.data[0].privateComment").exists());
    }

    @Test
    void testAnotherCoursesAdminIsRefusedEveryOne() throws Exception {
        for (MockHttpServletRequestBuilder request : ownerReads()) {
            this.mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, this.timToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(StatusCode.FORBIDDEN));
        }
    }

    @Test
    void testStudentCannotReadPrivateDetailsEvenHerOwn() throws Exception {
        for (String studentId : List.of("4", "5")) {
            this.mockMvc.perform(get(this.baseUrl + "/evaluations/students/" + studentId + "/week/2023-W31/details").header(HttpHeaders.AUTHORIZATION, this.johnToken))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void testStudentStillReadsHerselfAndHerCourseSection() throws Exception {
        this.mockMvc.perform(get(this.baseUrl + "/students/4").header(HttpHeaders.AUTHORIZATION, this.johnToken)).andExpect(status().isOk());
        this.mockMvc.perform(get(this.baseUrl + "/sections/2").header(HttpHeaders.AUTHORIZATION, this.johnToken)).andExpect(status().isOk());
    }

}

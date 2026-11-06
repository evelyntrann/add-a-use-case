package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.course.Course;
import team.projectpulse.evaluation.EvaluationService;
import team.projectpulse.evaluation.PeerEvaluationAverage;
import team.projectpulse.evaluation.WeeklyPeerEvaluationReport;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.section.Section;
import team.projectpulse.section.SectionService;
import team.projectpulse.system.exception.ObjectNotFoundException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * UC-STU-deactivate-student at the service layer, against MySQL: the lifecycle scope (BR-student-lifecycle,
 * BR-section-scoped-access), the outcome and what deactivation leaves unchanged (BR-student-deactivation-team), and the
 * owner-or-assigned scoping of the supporting course-section and report reads. Each test runs in the base class's
 * rolled-back transaction. The route rules are tested over HTTP in {@code StudentDeactivationIntegrationTest}, and
 * the lock ordering across separate transactions in {@code StudentDeactivationConcurrencyIntegrationTest}.
 *
 * <p>Seed data: Bingyang Wei owns course 1 (course sections 1 and 2) and is assigned to both; Bill Gates is assigned to
 * both but owns nothing; Tim Cook owns course 3 only. John, Eric and Jerry are Team1 in course section 2.
 */
@DisplayName("Integration tests for the student lifecycle scope")
class StudentLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    StudentService studentService;
    @Autowired
    SectionService sectionService;
    @Autowired
    EvaluationService evaluationService;
    @Autowired
    EntityManager entityManager;

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    static void actAs(Integer userId, String... authorities) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("userId", userId).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    private Student student(String email) {
        return this.entityManager.createQuery("select s from Student s where s.email = :email", Student.class)
                .setParameter("email", email).getSingleResult();
    }

    private Instructor instructor(String email) {
        return this.entityManager.createQuery("select i from Instructor i where i.email = :email", Instructor.class)
                .setParameter("email", email).getSingleResult();
    }

    private Section section2() {
        return this.entityManager.find(Section.class, 2);
    }

    private void unassign(Instructor instructor, Section section) {
        section.removeInstructor(instructor);
        this.entityManager.flush();
    }

    private long count(String jpql, Student student) {
        return this.entityManager.createQuery(jpql, Long.class).setParameter("student", student).getSingleResult();
    }

    private Student reloaded(Student student) {
        this.entityManager.flush();
        this.entityManager.clear();
        return this.entityManager.find(Student.class, student.getId());
    }

    @Test
    void testOwningAdminUnassignedToTheCourseSectionDeactivatesAndPreservesEverything() {
        // Given: Wei owns course 1 but is no longer assigned to course section 2
        Instructor wei = instructor("b.wei@abc.edu");
        unassign(wei, section2());
        Student john = student("j.smith@abc.edu");
        Integer teamId = john.getTeam().getTeamId();
        long activities = count("select count(a) from Activity a where a.student = :student", john);
        long written = count("select count(e) from PeerEvaluation e where e.evaluator = :student", john);
        long received = count("select count(e) from PeerEvaluation e where e.evaluatee = :student", john);
        long ratings = count("select count(r) from Rating r where r.peerEvaluation.evaluatee = :student", john);
        List<String> comments = this.entityManager.createQuery(
                        "select concat(e.publicComment, '|', e.privateComment) from PeerEvaluation e where e.evaluatee = :student order by e.peerEvaluationId", String.class)
                .setParameter("student", john).getResultList();
        actAs(wei.getId(), "ROLE_admin", "ROLE_instructor");

        // When
        DeactivationOutcome outcome = this.studentService.deactivateStudent(john.getId());

        // Then
        assertThat(outcome).isEqualTo(DeactivationOutcome.DEACTIVATED);
        Student after = reloaded(john);
        assertThat(after.isEnabled()).isFalse();
        assertThat(after.getUsername()).isEqualTo("j.smith@abc.edu");
        assertThat(after.getFirstName()).isEqualTo("John");
        assertThat(after.getRoles()).isEqualTo("student");
        assertThat(after.getSection().getSectionId()).isEqualTo(2);
        assertThat(after.getTeam().getTeamId()).isEqualTo(teamId);
        assertThat(count("select count(a) from Activity a where a.student = :student", after)).isEqualTo(activities);
        assertThat(count("select count(e) from PeerEvaluation e where e.evaluator = :student", after)).isEqualTo(written);
        assertThat(count("select count(e) from PeerEvaluation e where e.evaluatee = :student", after)).isEqualTo(received);
        assertThat(count("select count(r) from Rating r where r.peerEvaluation.evaluatee = :student", after)).isEqualTo(ratings);
        assertThat(this.entityManager.createQuery(
                        "select concat(e.publicComment, '|', e.privateComment) from PeerEvaluation e where e.evaluatee = :student order by e.peerEvaluationId", String.class)
                .setParameter("student", after).getResultList()).isEqualTo(comments);
    }

    @Test
    void testOwningAdminDeactivatesInACourseSectionWithNoAssignedInstructors() {
        // Given: a course section of course 1 that nobody is assigned to, with one student on no team
        Instructor wei = instructor("b.wei@abc.edu");
        Course course1 = section2().getCourse();
        Section empty = new Section("Unstaffed", LocalDate.of(2023, 8, 14), LocalDate.of(2024, 4, 29), true, DayOfWeek.MONDAY, LocalTime.of(23, 59), DayOfWeek.TUESDAY, LocalTime.of(23, 59));
        course1.addSection(empty);
        this.entityManager.persist(empty);
        Student nora = new Student("n.ortiz@abc.edu", "Nora", "Ortiz", "n.ortiz@abc.edu", "encoded", true, "student");
        empty.addStudent(nora);
        this.entityManager.persist(nora);
        this.entityManager.flush();
        actAs(wei.getId(), "ROLE_admin", "ROLE_instructor");

        // When / Then: she can read the course section, read the student and deactivate her; no team is acquired
        assertThat(this.sectionService.findSectionInCallerScope(empty.getSectionId()).getSectionName()).isEqualTo("Unstaffed");
        assertThat(this.studentService.findStudentInCallerScope(nora.getId()).getFirstName()).isEqualTo("Nora");
        assertThat(this.studentService.deactivateStudent(nora.getId())).isEqualTo(DeactivationOutcome.DEACTIVATED);
        Student after = reloaded(nora);
        assertThat(after.isEnabled()).isFalse();
        assertThat(after.getTeam()).isNull();
    }

    @Test
    void testAssignedInstructorDeactivatesAStudentWithNoTeam() {
        // Given: Bill is assigned to course section 2; Tracy is enrolled there on no team
        Student tracy = student("t.nicholson@abc.edu");
        actAs(instructor("b.gates@abc.edu").getId(), "ROLE_instructor");

        // When
        DeactivationOutcome outcome = this.studentService.deactivateStudent(tracy.getId());

        // Then
        assertThat(outcome).isEqualTo(DeactivationOutcome.DEACTIVATED);
        Student after = reloaded(tracy);
        assertThat(after.isEnabled()).isFalse();
        assertThat(after.getTeam()).isNull();
    }

    @Test
    void testAdminOfAnotherCourseQualifiesOnlyThroughAnAssignment() {
        Instructor tim = instructor("t.cook@abc.edu");
        Student john = student("j.smith@abc.edu");
        actAs(tim.getId(), "ROLE_admin", "ROLE_instructor");

        // Not assigned to course section 2, and owns course 3 only: refused, nothing changes
        assertThat(catchThrowable(() -> this.studentService.deactivateStudent(john.getId()))).isInstanceOf(AccessDeniedException.class);
        assertThat(reloaded(john).isEnabled()).isTrue();

        // Assigned to course section 2: the instructor grant admits him
        section2().addInstructor(instructor("t.cook@abc.edu"));
        this.entityManager.flush();
        assertThat(this.studentService.deactivateStudent(john.getId())).isEqualTo(DeactivationOutcome.DEACTIVATED);
    }

    @Test
    void testRefusalsLeaveAccountTeamAndWorkUnchanged() {
        // Given
        Instructor bill = instructor("b.gates@abc.edu");
        unassign(bill, section2()); // An unassigned plain instructor
        Student eric = student("e.hudson@abc.edu");
        Integer teamId = eric.getTeam().getTeamId();
        long received = count("select count(e) from PeerEvaluation e where e.evaluatee = :student", eric);

        // When / Then: the unassigned instructor, the other course's admin, the student herself and a missing id
        actAs(bill.getId(), "ROLE_instructor");
        assertThat(catchThrowable(() -> this.studentService.deactivateStudent(eric.getId()))).isInstanceOf(AccessDeniedException.class);
        actAs(instructor("t.cook@abc.edu").getId(), "ROLE_admin", "ROLE_instructor");
        assertThat(catchThrowable(() -> this.studentService.deactivateStudent(eric.getId()))).isInstanceOf(AccessDeniedException.class);
        actAs(eric.getId(), "ROLE_student");
        assertThat(catchThrowable(() -> this.studentService.deactivateStudent(eric.getId()))).isInstanceOf(AccessDeniedException.class);
        actAs(instructor("b.wei@abc.edu").getId(), "ROLE_admin", "ROLE_instructor");
        assertThat(catchThrowable(() -> this.studentService.deactivateStudent(999_999))).isInstanceOf(AccessDeniedException.class);

        Student after = reloaded(eric);
        assertThat(after.isEnabled()).isTrue();
        assertThat(after.getTeam().getTeamId()).isEqualTo(teamId);
        assertThat(count("select count(e) from PeerEvaluation e where e.evaluatee = :student", after)).isEqualTo(received);
    }

    @Test
    void testRepeatedDeactivationIsASuccessfulNoOpEvenWithNoActiveStudentLeft() {
        // Given: every student of course section 2 is already deactivated
        this.entityManager.createQuery("update Student s set s.enabled = false where s.section.sectionId = 2").executeUpdate();
        this.entityManager.clear();
        Student john = student("j.smith@abc.edu");
        actAs(instructor("b.gates@abc.edu").getId(), "ROLE_instructor");

        // When / Then
        assertThat(this.studentService.deactivateStudent(john.getId())).isEqualTo(DeactivationOutcome.ALREADY_DEACTIVATED);
        assertThat(reloaded(john).isEnabled()).isFalse();
    }

    @Test
    void testUnauthorizedRequestForAnAlreadyDeactivatedStudentIsStillRefused() {
        // Given
        Student john = student("j.smith@abc.edu");
        john.setEnabled(false);
        this.entityManager.flush();
        actAs(instructor("t.cook@abc.edu").getId(), "ROLE_admin", "ROLE_instructor");

        // When / Then: refused, not the already-deactivated answer
        assertThat(catchThrowable(() -> this.studentService.deactivateStudent(john.getId()))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void testScopedStudentReaderAdmitsSelfButLifecycleDoesNot() {
        Student john = student("j.smith@abc.edu");

        // The student reads herself, but cannot deactivate herself
        actAs(john.getId(), "ROLE_student");
        assertThat(this.studentService.findStudentInCallerScope(john.getId()).getId()).isEqualTo(john.getId());
        assertThat(catchThrowable(() -> this.studentService.deactivateStudent(john.getId()))).isInstanceOf(AccessDeniedException.class);
        // ... and another student of her team is outside her scope
        assertThat(catchThrowable(() -> this.studentService.findStudentInCallerScope(student("e.hudson@abc.edu").getId())))
                .isInstanceOf(ObjectNotFoundException.class);

        // Another course's admin cannot read her
        actAs(instructor("t.cook@abc.edu").getId(), "ROLE_admin", "ROLE_instructor");
        assertThat(catchThrowable(() -> this.studentService.findStudentInCallerScope(john.getId()))).isInstanceOf(ObjectNotFoundException.class);
    }

    @Test
    void testScopedCourseSectionReader() {
        Instructor wei = instructor("b.wei@abc.edu");
        unassign(wei, section2());

        // Owner, unassigned
        actAs(wei.getId(), "ROLE_admin", "ROLE_instructor");
        assertThat(this.sectionService.findSectionInCallerScope(2).getSectionId()).isEqualTo(2);
        // Enrolled student
        actAs(student("j.smith@abc.edu").getId(), "ROLE_student");
        assertThat(this.sectionService.findSectionInCallerScope(2).getSectionId()).isEqualTo(2);
        // Another course's admin, and a student of another course section
        actAs(instructor("t.cook@abc.edu").getId(), "ROLE_admin", "ROLE_instructor");
        assertThat(catchThrowable(() -> this.sectionService.findSectionInCallerScope(2))).isInstanceOf(ObjectNotFoundException.class);
        actAs(student("j.norton@abc.edu").getId(), "ROLE_student");
        assertThat(catchThrowable(() -> this.sectionService.findSectionInCallerScope(2))).isInstanceOf(ObjectNotFoundException.class);
    }

    @Test
    void testReportKeepsTheInactiveStudentInEarlierAndLaterWeeks() {
        // Given: Eric is deactivated; Wei, the owner, is unassigned from the course section
        Instructor wei = instructor("b.wei@abc.edu");
        unassign(wei, section2());
        Student eric = student("e.hudson@abc.edu");
        actAs(wei.getId(), "ROLE_admin", "ROLE_instructor");
        PeerEvaluationAverage before = row(this.evaluationService.generateWeeklyPeerEvaluationReportForSection(2, "2023-W31"), eric);
        this.studentService.deactivateStudent(eric.getId());
        this.entityManager.flush();
        this.entityManager.clear();

        // When
        PeerEvaluationAverage earlier = row(this.evaluationService.generateWeeklyPeerEvaluationReportForSection(2, "2023-W31"), eric);
        PeerEvaluationAverage later = row(this.evaluationService.generateWeeklyPeerEvaluationReportForSection(2, "2023-W40"), eric);

        // Then: the earlier week keeps his stored score and comments; the later week, with no evaluations, keeps his
        // row at the zero-value convention; both carry his current status (BR-evaluation-inactive-report)
        assertThat(before.isEnabled()).isTrue();
        assertThat(earlier.isEnabled()).isFalse();
        assertThat(earlier.getAverageTotalScore()).isEqualTo(before.getAverageTotalScore());
        assertThat(earlier.getPublicComments()).isEqualTo(before.getPublicComments());
        assertThat(earlier.getPrivateComments()).isEqualTo(before.getPrivateComments());
        assertThat(later.isEnabled()).isFalse();
        assertThat(later.getAverageTotalScore()).isZero();
        assertThat(later.getPublicComments()).isEmpty();
    }

    @Test
    void testReportAndDetailsAreScopedToInstructorsAndOwner() {
        Instructor wei = instructor("b.wei@abc.edu");
        unassign(wei, section2());
        Student john = student("j.smith@abc.edu");

        // The owner, unassigned, reads the report and a student's details, private comments included
        actAs(wei.getId(), "ROLE_admin", "ROLE_instructor");
        assertThat(this.evaluationService.generateWeeklyPeerEvaluationReportForSection(2, "2023-W31").getPeerEvaluationAverages()).isNotEmpty();
        assertThat(this.evaluationService.getWeeklyEvaluationsForStudent(john.getId(), "2023-W31")).isNotEmpty();

        // Another course's admin, and the student herself, are refused both
        for (Integer callerId : List.of(instructor("t.cook@abc.edu").getId(), john.getId())) {
            actAs(callerId, "ROLE_instructor");
            assertThat(catchThrowable(() -> this.evaluationService.generateWeeklyPeerEvaluationReportForSection(2, "2023-W31")))
                    .isInstanceOf(ObjectNotFoundException.class);
            assertThat(catchThrowable(() -> this.evaluationService.getWeeklyEvaluationsForStudent(john.getId(), "2023-W31")))
                    .isInstanceOf(ObjectNotFoundException.class);
        }
    }

    private static PeerEvaluationAverage row(WeeklyPeerEvaluationReport report, Student student) {
        return report.getPeerEvaluationAverages().stream()
                .filter(average -> average.getStudentId().equals(student.getId()))
                .findFirst().orElseThrow();
    }

}

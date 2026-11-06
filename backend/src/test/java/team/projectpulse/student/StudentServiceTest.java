package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.AfterEach;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;
import team.projectpulse.system.exception.ObjectNotFoundException;
import team.projectpulse.team.Team;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.section.Section;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.system.UserUtils;
import team.projectpulse.user.MyUserPrincipal;
import team.projectpulse.user.userinvitation.UserInvitationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StudentServiceTest {

    @Mock
    StudentRepository studentRepository;
    @Mock
    SectionRepository sectionRepository;
    @Mock
    UserInvitationService userInvitationService;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    UserUtils userUtils;
    @Mock
    EntityManager entityManager;

    @InjectMocks
    private StudentService studentService;

    List<Student> students;

    @BeforeEach
    void setUp() {
        // Create students
        Student john = new Student("john", "John", "Smith", "j.smith@abc.edu", "123456", true, "student");
        Student eric = new Student("eric", "Eric", "Hudson", "e.hudson@abc.edu", "123456", true, "student");
        Student jerry = new Student("jerry", "Jerry", "Moon", "j.moon@abc.edu", "123456", true, "student");
        Student woody = new Student("woody", "Woody", "Allen", "w.allen@abc.edu", "123456", true, "student");
        Student amanda = new Student("amanda", "Amanda", "Wagner", "a.wagner@abc.edu", "123456", true, "student");
        Student cora = new Student("cora", "Cora", "Manning", "c.manning@abc.edu", "123456", true, "student");
        Student agustin = new Student("agustin", "Agustin", "Freeman", "a.freeman@abc.edu", "123456", true, "student");
        Student mavis = new Student("mavis", "Mavis", "Huber", "m.huber@abc.edu", "123456", true, "student");
        Student mary = new Student("mary", "Mary", "Vargas", "m.vargas@abc.edu", "123456", true, "student");
        Student rosendo = new Student("rosendo", "Rosendo", "Maxwell", "r.maxwell@abc.edu", "123456", true, "student");

        this.students = List.of(john, eric, jerry, woody, amanda, cora, agustin, mavis, mary, rosendo);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext(); // Clear the security context after each test to avoid interference between tests
    }

    @Test
    void testFindByCriteria() {
        // Given
        Map<String, String> searchCriteria = Map.of("firstName", "john");
        PageImpl expectedPage = new PageImpl(List.of(this.students.get(0)));

        given(this.userUtils.getUserSectionId()).willReturn(2);
        given(this.studentRepository.findAll(any(Specification.class), any(Pageable.class))).willReturn(expectedPage);
        // When
        Page<Student> result = this.studentService.findByCriteria(searchCriteria, PageRequest.of(0, 20));

        // Then
        assertThat(result.getContent().size()).isEqualTo(1);
    }

    @Test
    void testFindStudentById() {
        // Given
        Student john = this.students.get(0);
        given(this.studentRepository.findById(1)).willReturn(Optional.of(john));

        // When
        Student result = this.studentService.findStudentById(1);

        // Then
        assertThat(result.getFirstName()).isEqualTo("John");
        assertThat(result.getLastName()).isEqualTo("Smith");
    }

    @Test
    void testSaveStudent() {
        // Given
        Student jan = new Student("jan", "Jan", "Mckinney", "j.mckinney@abc.edu", "Abc123456", true, "student");
        doNothing().when(this.userInvitationService).validateUserInvitation(anyString(), anyString(), anyInt(), anyInt(), anyString());
        given(this.sectionRepository.findById(1)).willReturn(Optional.of(new Section()));
        given(this.passwordEncoder.encode(anyString())).willReturn("Encoded Password");
        given(this.studentRepository.save(jan)).willReturn(jan);

        // When
        Student result = this.studentService.saveStudent(jan, 1, 1, "token", "student");

        // Then
        assertThat(result.getFirstName()).isEqualTo("Jan");
        assertThat(result.getLastName()).isEqualTo("Mckinney");
        assertThat(result.getEmail()).isEqualTo("j.mckinney@abc.edu");
        assertThat(result.getRoles()).isEqualTo("student");
        assertThat(result.getSection()).isNotNull();
        assertThat(result.isEnabled()).isEqualTo(true);
    }

    @Test
    void testStudentUpdateOwnInfo() {
        // Given
        Student update = new Student("john", "John (updated)", "Smith (updated)", "j.smith@abc.edu (updated)", "", false, "admin student");
        given(this.studentRepository.findById(1)).willReturn(Optional.of(this.students.get(0)));
        given(this.studentRepository.save(any(Student.class))).willReturn(this.students.get(0));

        Instructor instructor = new Instructor();
        instructor.setRoles("student");
        MyUserPrincipal myUserPrincipal = new MyUserPrincipal(instructor);

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(myUserPrincipal, null, myUserPrincipal.getAuthorities()));
        SecurityContextHolder.setContext(securityContext);

        // When
        Student result = this.studentService.updateStudent(1, update);

        // Then
        assertThat(result.getUsername()).isEqualTo(update.getUsername());
        assertThat(result.getFirstName()).isEqualTo(update.getFirstName());
        assertThat(result.getLastName()).isEqualTo(update.getLastName());
        assertThat(result.getEmail()).isEqualTo(update.getEmail());
        assertThat(result.getRoles()).isNotEqualTo(update.getRoles());
        assertThat(result.isEnabled()).isNotEqualTo(update.isEnabled());
    }

    private void signInWithRoles(String roles) {
        Instructor instructor = new Instructor();
        instructor.setRoles(roles);
        MyUserPrincipal myUserPrincipal = new MyUserPrincipal(instructor);
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(myUserPrincipal, null, myUserPrincipal.getAuthorities()));
        SecurityContextHolder.setContext(securityContext);
    }

    @Test
    void testInstructorUpdatesStudent() {
        // Given
        // An active-to-active profile edit. Deactivating through this route is refused (see the test below); it used
        // to be what this test did, before deactivation became its own confirmed action (UC-STU-deactivate-student).
        Student update = new Student("john", "John (updated)", "Smith (updated)", "j.smith@abc.edu (updated)", "", true, "admin student");
        given(this.studentRepository.findById(1)).willReturn(Optional.of(this.students.get(0)));
        given(this.studentRepository.save(any(Student.class))).willReturn(this.students.get(0));

        Instructor instructor = new Instructor();
        instructor.setRoles("instructor");
        MyUserPrincipal myUserPrincipal = new MyUserPrincipal(instructor);

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(myUserPrincipal, null, myUserPrincipal.getAuthorities()));
        SecurityContextHolder.setContext(securityContext);

        // When
        Student result = this.studentService.updateStudent(1, update);

        // Then
        assertThat(result.getUsername()).isEqualTo(update.getUsername());
        assertThat(result.getFirstName()).isEqualTo(update.getFirstName());
        assertThat(result.getLastName()).isEqualTo(update.getLastName());
        assertThat(result.getEmail()).isEqualTo(update.getEmail());
//        assertThat(result.getRoles()).isEqualTo(update.getRoles());
        assertThat(result.isEnabled()).isEqualTo(update.isEnabled());
    }

    @Test
    void testDeleteStudent() {
        // Given
        given(this.studentRepository.findById(1)).willReturn(Optional.of(this.students.get(0)));
        doNothing().when(this.studentRepository).deleteById(1);

        // When
        this.studentService.deleteStudent(1);

        // Then
        verify(this.studentRepository, times(1)).deleteById(1);
    }


    @Test
    void testInstructorAndCourseAdminCannotDeactivateThroughProfileUpdate() {
        for (String roles : List.of("instructor", "admin instructor")) {
            // Given
            Student john = new Student("john", "John", "Smith", "j.smith@abc.edu", "123456", true, "student");
            Student update = new Student("john (updated)", "John (updated)", "Smith (updated)", "j.smith@abc.edu (updated)", "", false, "student");
            given(this.studentRepository.findById(1)).willReturn(Optional.of(john));
            signInWithRoles(roles);

            // When
            Throwable throwable = catchThrowable(() -> this.studentService.updateStudent(1, update));

            // Then
            assertThat(throwable).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Use the Deactivate action to deactivate a student.");
            // Refused before any profile field changed
            assertThat(john.getUsername()).isEqualTo("john");
            assertThat(john.getFirstName()).isEqualTo("John");
            assertThat(john.getEmail()).isEqualTo("j.smith@abc.edu");
            assertThat(john.isEnabled()).isTrue();
        }
        verify(this.studentRepository, never()).save(any(Student.class));
    }

    @Test
    void testInstructorEditsInactiveStudentAndLegacyReactivationStillWork() {
        // Given
        Student inactive = new Student("john", "John", "Smith", "j.smith@abc.edu", "123456", false, "student");
        given(this.studentRepository.findById(1)).willReturn(Optional.of(inactive));
        given(this.studentRepository.save(any(Student.class))).willAnswer(invocation -> invocation.getArgument(0));
        signInWithRoles("instructor");

        // When: inactive to inactive, then the existing inactive-to-active path
        Student stillInactive = this.studentService.updateStudent(1, new Student("john", "Johnny", "Smith", "j.smith@abc.edu", "", false, "student"));
        boolean enabledAfterFirstEdit = stillInactive.isEnabled();
        Student reactivated = this.studentService.updateStudent(1, new Student("john", "Johnny", "Smith", "j.smith@abc.edu", "", true, "student"));

        // Then
        assertThat(enabledAfterFirstEdit).isFalse();
        assertThat(stillInactive.getFirstName()).isEqualTo("Johnny");
        assertThat(reactivated.isEnabled()).isTrue();
    }

    @Test
    void testStudentOmittingEnabledKeepsHerAccountActive() {
        // Given: a student's own DTO omits the primitive enabled, so it converts to false; that has always been ignored
        Student john = this.students.get(0);
        given(this.studentRepository.findById(1)).willReturn(Optional.of(john));
        given(this.studentRepository.save(any(Student.class))).willReturn(john);
        signInWithRoles("student");

        // When
        Student result = this.studentService.updateStudent(1, new Student("john", "John", "Smith", "j.smith@abc.edu", "", false, null));

        // Then
        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getRoles()).isEqualTo("student");
    }

    private Student studentOnTeam(boolean enabled) {
        Section section = new Section();
        section.setSectionId(2);
        Team team = new Team("Team1", "Team 1 description", "https://www.team1.com");
        team.setTeamId(1);
        Student student = new Student("john", "John", "Smith", "j.smith@abc.edu", "123456", enabled, "student");
        student.setId(4);
        section.addStudent(student);
        team.addStudent(student);
        return student;
    }

    @Test
    void testDeactivateStudentSuccess() {
        // Given
        Student john = studentOnTeam(true);
        given(this.userUtils.getUserId()).willReturn(2); // An instructor assigned to John's course section
        given(this.studentRepository.findOne(any(Specification.class))).willReturn(Optional.of(john));
        given(this.studentRepository.countInLifecycleScopeForShare(4, 2)).willReturn(1L);

        // When
        DeactivationOutcome outcome = this.studentService.deactivateStudent(4);

        // Then
        assertThat(outcome).isEqualTo(DeactivationOutcome.DEACTIVATED);
        assertThat(john.isEnabled()).isFalse();
        // Same account, course section and team (BR-student-lifecycle, BR-student-deactivation-team)
        assertThat(john.getUsername()).isEqualTo("john");
        assertThat(john.getSection().getSectionId()).isEqualTo(2);
        assertThat(john.getTeam().getTeamId()).isEqualTo(1);
        // Locked and refreshed, then the scope rechecked, before the write
        InOrder order = inOrder(this.entityManager, this.studentRepository);
        order.verify(this.entityManager).refresh(john, LockModeType.PESSIMISTIC_WRITE);
        order.verify(this.studentRepository).countInLifecycleScopeForShare(4, 2);
        order.verify(this.studentRepository).save(john);
    }

    @Test
    void testDeactivateStudentWithNoTeam() {
        // Given
        Student tracy = new Student("tracy", "Tracy", "Nicholson", "t.nicholson@abc.edu", "123456", true, "student");
        tracy.setId(16);
        given(this.userUtils.getUserId()).willReturn(1);
        given(this.studentRepository.findOne(any(Specification.class))).willReturn(Optional.of(tracy));
        given(this.studentRepository.countInLifecycleScopeForShare(16, 1)).willReturn(1L);

        // When
        DeactivationOutcome outcome = this.studentService.deactivateStudent(16);

        // Then
        assertThat(outcome).isEqualTo(DeactivationOutcome.DEACTIVATED);
        assertThat(tracy.isEnabled()).isFalse();
        assertThat(tracy.getTeam()).isNull();
    }

    @Test
    void testDeactivateStudentAlreadyDeactivated() {
        // Given
        Student john = studentOnTeam(false);
        given(this.userUtils.getUserId()).willReturn(2);
        given(this.studentRepository.findOne(any(Specification.class))).willReturn(Optional.of(john));
        given(this.studentRepository.countInLifecycleScopeForShare(4, 2)).willReturn(1L);

        // When
        DeactivationOutcome outcome = this.studentService.deactivateStudent(4);

        // Then
        assertThat(outcome).isEqualTo(DeactivationOutcome.ALREADY_DEACTIVATED);
        verify(this.studentRepository, never()).save(any(Student.class));
    }

    @Test
    void testDeactivateStudentDeactivatedWhileWaitingOnTheLock() {
        // Given: John is active when found; a concurrent deactivation commits before this request gets his row lock
        Student john = studentOnTeam(true);
        given(this.userUtils.getUserId()).willReturn(2);
        given(this.studentRepository.findOne(any(Specification.class))).willReturn(Optional.of(john));
        willAnswer(invocation -> {
            john.setEnabled(false);
            return null;
        }).given(this.entityManager).refresh(john, LockModeType.PESSIMISTIC_WRITE);
        given(this.studentRepository.countInLifecycleScopeForShare(4, 2)).willReturn(1L);

        // When
        DeactivationOutcome outcome = this.studentService.deactivateStudent(4);

        // Then
        assertThat(outcome).isEqualTo(DeactivationOutcome.ALREADY_DEACTIVATED);
        verify(this.studentRepository, never()).save(any(Student.class));
    }

    @Test
    void testDeactivateStudentOutOfScope() {
        // Given: no student with this id in the caller's lifecycle scope, whether it exists or not
        given(this.userUtils.getUserId()).willReturn(3);
        given(this.studentRepository.findOne(any(Specification.class))).willReturn(Optional.empty());

        // When
        Throwable throwable = catchThrowable(() -> this.studentService.deactivateStudent(4));

        // Then
        assertThat(throwable).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(this.entityManager);
        verify(this.studentRepository, never()).save(any(Student.class));
    }

    @Test
    void testDeactivateStudentScopeLostWhileWaitingOnTheLock() {
        for (boolean enabled : List.of(true, false)) {
            // Given: in scope when found, out of scope by the locking recheck, so neither outcome is disclosed
            Student john = studentOnTeam(enabled);
            given(this.userUtils.getUserId()).willReturn(2);
            given(this.studentRepository.findOne(any(Specification.class))).willReturn(Optional.of(john));
            given(this.studentRepository.countInLifecycleScopeForShare(4, 2)).willReturn(0L);

            // When
            Throwable throwable = catchThrowable(() -> this.studentService.deactivateStudent(4));

            // Then
            assertThat(throwable).isInstanceOf(AccessDeniedException.class);
            assertThat(john.isEnabled()).isEqualTo(enabled);
        }
        verify(this.studentRepository, never()).save(any(Student.class));
    }

    @Test
    void testDeactivateStudentWithoutCallerIdentity() {
        // Given
        given(this.userUtils.getUserId()).willReturn(null);

        // When
        Throwable throwable = catchThrowable(() -> this.studentService.deactivateStudent(4));

        // Then
        assertThat(throwable).isInstanceOf(AccessDeniedException.class);
        verify(this.studentRepository, never()).findOne(any(Specification.class));
    }

    @Test
    void testFindStudentInCallerScope() {
        // Given
        Student john = studentOnTeam(true);
        given(this.userUtils.getUserId()).willReturn(4, 2, 3);
        given(this.studentRepository.findById(4)).willReturn(Optional.of(john));
        given(this.studentRepository.findOne(any(Specification.class))).willReturn(Optional.of(john), Optional.empty());

        // When / Then: John himself, an instructor in scope, then a caller out of scope
        assertThat(this.studentService.findStudentInCallerScope(4)).isSameAs(john);
        assertThat(this.studentService.findStudentInCallerScope(4)).isSameAs(john);
        assertThat(catchThrowable(() -> this.studentService.findStudentInCallerScope(4))).isInstanceOf(ObjectNotFoundException.class);
    }

}

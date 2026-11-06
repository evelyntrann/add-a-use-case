package team.projectpulse.student;

import team.projectpulse.section.Section;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.system.UserUtils;
import team.projectpulse.system.exception.ObjectNotFoundException;
import team.projectpulse.user.userinvitation.UserInvitationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional
public class StudentService {

    private final StudentRepository studentRepository;
    private final SectionRepository sectionRepository;
    private final UserInvitationService userInvitationService;
    private final PasswordEncoder passwordEncoder;
    private final UserUtils userUtils;
    private final EntityManager entityManager;


    public StudentService(StudentRepository studentRepository, SectionRepository sectionRepository, UserInvitationService userInvitationService, PasswordEncoder passwordEncoder, UserUtils userUtils, EntityManager entityManager) {
        this.studentRepository = studentRepository;
        this.sectionRepository = sectionRepository;
        this.userInvitationService = userInvitationService;
        this.passwordEncoder = passwordEncoder;
        this.userUtils = userUtils;
        this.entityManager = entityManager;
    }

    public Page<Student> findByCriteria(Map<String, String> searchCriteria, Pageable pageable) {
        Integer sectionId = this.userUtils.getUserSectionId();

        Specification<Student> spec = Specification.unrestricted(); // Start with an unrestricted specification, matching all objects.

        if (StringUtils.hasLength(searchCriteria.get("firstName"))) {
            spec = spec.and(StudentSpecs.containsFirstName(searchCriteria.get("firstName")));
        }

        if (StringUtils.hasLength(searchCriteria.get("lastName"))) {
            spec = spec.and(StudentSpecs.containsLastName(searchCriteria.get("lastName")));
        }

        if (StringUtils.hasLength(searchCriteria.get("email"))) {
            spec = spec.and(StudentSpecs.hasEmail(searchCriteria.get("email")));
        }

        if (StringUtils.hasLength(searchCriteria.get("teamName"))) {
            spec = spec.and(StudentSpecs.hasTeamName(searchCriteria.get("teamName")));
        }

        if (StringUtils.hasLength(searchCriteria.get("teamId"))) {
            spec = spec.and(StudentSpecs.hasTeamId(searchCriteria.get("teamId")));
        }

        if (StringUtils.hasLength(searchCriteria.get("sectionName"))) {
            spec = spec.and(StudentSpecs.hasSectionName(searchCriteria.get("sectionName")));
        }

        spec = spec.and(StudentSpecs.belongsToSection(sectionId)); // Only show students that belong to the default section of the instructor

        return this.studentRepository.findAll(spec, pageable);
    }

    public Student findStudentById(Integer studentId) {
        return this.studentRepository.findById(studentId)
                .orElseThrow(() -> new ObjectNotFoundException("student", studentId));
    }

    /**
     * The student a caller asks to read by id, scoped to the caller (BR-student-lifecycle, BR-section-scoped-access):
     * the student herself, an instructor assigned to her course section, or the course admin who owns its course.
     * Anything else, including an id that names no student, is not found.
     */
    public Student findStudentInCallerScope(Integer studentId) {
        Integer callerId = this.userUtils.getUserId();
        if (callerId != null && callerId.equals(studentId)) {
            return findStudentById(studentId);
        }
        return findInLifecycleScope(studentId, callerId)
                .orElseThrow(() -> new ObjectNotFoundException("student", studentId));
    }

    /**
     * Deactivates a student's account (UC-STU-deactivate-student step 6), or reports that it already is (extension
     * 6b, BR-student-deactivation-idempotent). The account, course section, team and submitted work are otherwise
     * left exactly as they are (BR-student-lifecycle, BR-student-deactivation-team).
     *
     * <p><strong>Order of checks.</strong> The target is first found within the caller's lifecycle scope. Its row is
     * then refreshed under a write lock, so a deactivation or evaluation write already in flight finishes first and
     * this one sees its result rather than the earlier state. The scope is checked once more with a locking read, so
     * an assignment or ownership removed while this request waited is seen (Associated Information). Only then does
     * the account state decide the outcome, which keeps an unauthorized caller from learning whether the student is
     * already deactivated.
     *
     * <p>A refusal at either check is an {@link AccessDeniedException}, which {@code ExceptionHandlerAdvice} maps to
     * the same {@code 403} "No permission." the route rule returns, so a missing student and an out-of-scope one are
     * indistinguishable (extension 1a).
     */
    public DeactivationOutcome deactivateStudent(Integer studentId) {
        Integer callerId = this.userUtils.getUserId();
        Student student = findInLifecycleScope(studentId, callerId)
                .orElseThrow(() -> new AccessDeniedException("Not permitted to deactivate this student."));

        this.entityManager.refresh(student, LockModeType.PESSIMISTIC_WRITE);
        if (this.studentRepository.countInLifecycleScopeForShare(studentId, callerId) == 0) {
            throw new AccessDeniedException("Not permitted to deactivate this student.");
        }

        if (!student.isEnabled()) {
            return DeactivationOutcome.ALREADY_DEACTIVATED; // Nothing written, so nothing audited
        }
        student.setEnabled(false);
        this.studentRepository.save(student);
        return DeactivationOutcome.DEACTIVATED;
    }

    private Optional<Student> findInLifecycleScope(Integer studentId, Integer callerId) {
        if (studentId == null || callerId == null) {
            return Optional.empty();
        }
        return this.studentRepository.findOne(StudentSpecs.hasId(studentId).and(StudentSpecs.inLifecycleScopeOf(callerId)));
    }

    public List<Student> findStudentsByTeamId(Integer teamId) {
        return this.studentRepository.findByTeamTeamId(teamId);
    }

    public Student saveStudent(Student newStudent, Integer courseId, Integer sectionId, String registrationToken, String role) {
        // Validate user invitation
        this.userInvitationService.validateUserInvitation(newStudent.getEmail(), registrationToken, courseId, sectionId, role);

        // Password rule: At least 6 characters, contains at least one letter and one number.
        if (!newStudent.getPassword().matches("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{6,}$")) {
            throw new IllegalArgumentException("Password must contain at least one letter and one number, and at least 6 characters.");
        }

        // Add the student to the section
        Section section = this.sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ObjectNotFoundException("section", sectionId));
        section.addStudent(newStudent);

        newStudent.setEnabled(true);
        newStudent.setRoles("student");
        newStudent.setPassword(this.passwordEncoder.encode(newStudent.getPassword()));

        Student savedStudent = this.studentRepository.save(newStudent);
        // Delete the user invitation after the student is saved.
        this.userInvitationService.deleteUserInvitation(newStudent.getEmail());
        return savedStudent;
    }

    public Student updateStudent(Integer studentId, Student update) {
        Student oldStudent = this.studentRepository.findById(studentId)
                .orElseThrow(() -> new ObjectNotFoundException("student", studentId));

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean callerMayChangeEnabled = authentication.getAuthorities().stream()
                .anyMatch(grantedAuthority -> grantedAuthority.getAuthority().equals("ROLE_instructor"));

        // Deactivation goes through its own action, which warns about the consequences and confirms
        // (UC-STU-deactivate-student). Refuse it here before any profile field changes, but only for a caller whose
        // requested enabled value this method would apply: a student's own profile DTO may carry false or omit the
        // field, and that has always been ignored below. Active-to-active, inactive-to-inactive and the legacy
        // inactive-to-active path are unaffected.
        if (callerMayChangeEnabled && oldStudent.isEnabled() && !update.isEnabled()) {
            throw new IllegalArgumentException("Use the Deactivate action to deactivate a student.");
        }

        // If the user is not an admin, then the user can only update her username, first rubricName, last rubricName, and email
        oldStudent.setUsername(update.getUsername());
        oldStudent.setFirstName(update.getFirstName());
        oldStudent.setLastName(update.getLastName());
        oldStudent.setEmail(update.getEmail());

        // If the user is an admin, then the user can update all the fields
        if (callerMayChangeEnabled) {
            oldStudent.setEnabled(update.isEnabled());
//            oldStudent.setRoles(update.getRoles());
        }

        return this.studentRepository.save(oldStudent);
    }

    public void deleteStudent(Integer studentId) {
        this.studentRepository.findById(studentId)
                .orElseThrow(() -> new ObjectNotFoundException("student", studentId));
        this.studentRepository.deleteById(studentId);
    }

}

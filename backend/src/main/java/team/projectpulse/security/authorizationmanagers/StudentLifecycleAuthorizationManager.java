package team.projectpulse.security.authorizationmanagers;

import org.jspecify.annotations.Nullable;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import team.projectpulse.student.StudentSecurityService;

import java.util.function.Supplier;

/**
 * Guards acting on one student's account lifecycle (UC-STU-deactivate-student), and, as an alternative grant, the
 * reads that lead to it: admits the course admin who owns the student's course and any instructor assigned to the
 * student's course section (BR-student-lifecycle). The student herself is not admitted.
 *
 * <p>Reads {@code studentId} and delegates to {@link StudentSecurityService#canManageStudentLifecycle}. The service
 * method the route reaches checks the same scope again, after taking the student's row lock.
 */
@Component
public class StudentLifecycleAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final StudentSecurityService studentSecurityService;


    public StudentLifecycleAuthorizationManager(StudentSecurityService studentSecurityService) {
        this.studentSecurityService = studentSecurityService;
    }

    @Override
    public @Nullable AuthorizationResult authorize(Supplier<? extends @Nullable Authentication> authentication, RequestAuthorizationContext context) {
        Integer studentId = PathVariables.readId(context, "studentId");
        if (studentId == null) {
            return new AuthorizationDecision(false);
        }
        return new AuthorizationDecision(this.studentSecurityService.canManageStudentLifecycle(studentId));
    }

}

package team.projectpulse.student;

import org.springframework.data.jpa.domain.Specification;

public class StudentSpecs {

    public static Specification<Student> containsFirstName(String providedFirstName) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.like(criteriaBuilder.lower(root.get("firstName")), "%" + providedFirstName.toLowerCase() + "%");
    }

    public static Specification<Student> containsLastName(String providedLastName) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.like(criteriaBuilder.lower(root.get("lastName")), "%" + providedLastName.toLowerCase() + "%");
    }

    public static Specification<Student> hasEmail(String providedEmail) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(criteriaBuilder.lower(root.get("email")), providedEmail.toLowerCase());
    }

    public static Specification<Student> hasTeamName(String providedTeamName) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(criteriaBuilder.lower(root.get("team").get("teamName")), providedTeamName.toLowerCase());
    }

    public static Specification<Student> hasTeamId(String providedTeamId) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("team").get("teamId"), providedTeamId);
    }

    public static Specification<Student> hasSectionName(String providedSectionName) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(criteriaBuilder.lower(root.get("section").get("sectionName")), providedSectionName.toLowerCase());
    }

    public static Specification<Student> hasSectionId(String providedSectionId) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("section").get("sectionId"), providedSectionId);
    }

    public static Specification<Student> belongsToSection(Integer providedSectionId) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("section").get("sectionId"), providedSectionId);
    }

    public static Specification<Student> hasId(Integer studentId) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("id"), studentId);
    }

    /**
     * Students whose course section the caller may manage under BR-student-lifecycle: the caller is the course admin
     * who owns the course section's course, or an instructor assigned to the course section. The two are alternative
     * grants, so an admin of another course who is assigned here qualifies through the assignment.
     *
     * <p>The instructor join is a left join, so a course section with no assigned instructors still matches for its
     * owning course admin. A student herself never matches: this is the predicate for acting on a student, not for
     * reading one's own profile.
     */
    public static Specification<Student> inLifecycleScopeOf(Integer callerId) {
        return (root, query, criteriaBuilder) -> {
            query.distinct(true); // One row per student even when the caller matches both grants
            var section = root.join("section");
            var instructor = section.join("instructors", jakarta.persistence.criteria.JoinType.LEFT);
            return criteriaBuilder.or(
                    criteriaBuilder.equal(section.get("course").get("courseAdmin").get("id"), callerId),
                    criteriaBuilder.equal(instructor.get("id"), callerId));
        };
    }

}

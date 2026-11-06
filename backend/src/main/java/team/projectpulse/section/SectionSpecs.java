package team.projectpulse.section;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

public class SectionSpecs {

    public static Specification<Section> containsSectionName(String providedSectionName) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.like(criteriaBuilder.lower(root.get("sectionName")), "%" + providedSectionName.toLowerCase() + "%");
    }

    public static Specification<Section> belongsToCourse(Integer courseId) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("course").get("courseId"), courseId);
    }

    public static Specification<Section> hasActiveStatus(Boolean isActive) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("isActive"), isActive);
    }

    public static Specification<Section> hasId(Integer sectionId) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("sectionId"), sectionId);
    }

    /**
     * Course sections the caller instructs or owns (BR-section-scoped-access): she is assigned to the course section
     * as an instructor, or she is the course admin of its course. A left join, so a course section with no assigned
     * instructors still matches for its course admin.
     */
    public static Specification<Section> instructedOrOwnedBy(Integer callerId) {
        return (root, query, criteriaBuilder) -> {
            query.distinct(true);
            Join<Object, Object> instructor = root.join("instructors", JoinType.LEFT);
            return criteriaBuilder.or(
                    criteriaBuilder.equal(root.get("course").get("courseAdmin").get("id"), callerId),
                    criteriaBuilder.equal(instructor.get("id"), callerId));
        };
    }

    /**
     * Course sections the caller may read: those she instructs or owns, plus the one she is enrolled in as a student.
     */
    public static Specification<Section> readableBy(Integer callerId) {
        return (root, query, criteriaBuilder) -> {
            query.distinct(true);
            Join<Object, Object> instructor = root.join("instructors", JoinType.LEFT);
            Join<Object, Object> student = root.join("students", JoinType.LEFT);
            return criteriaBuilder.or(
                    criteriaBuilder.equal(root.get("course").get("courseAdmin").get("id"), callerId),
                    criteriaBuilder.equal(instructor.get("id"), callerId),
                    criteriaBuilder.equal(student.get("id"), callerId));
        };
    }

}

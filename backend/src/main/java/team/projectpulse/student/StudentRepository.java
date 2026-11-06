package team.projectpulse.student;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StudentRepository extends JpaRepository<Student, Integer>, JpaSpecificationExecutor<Student> {

    // Find all students in a section
    List<Student> findBySectionSectionId(Integer sectionId);

    List<Student> findByTeamTeamId(Integer teamId);

    /**
     * The lifecycle scope check of {@link StudentSpecs#inLifecycleScopeOf}, as a MySQL locking read.
     *
     * <p>Run after the caller already holds the student's row lock, as the final authorization before a mutation or a
     * no-op answer. Under REPEATABLE READ a plain read here would return the snapshot this transaction took at its
     * first read, so an assignment or ownership change committed while the request waited on the lock would go
     * unseen. A locking read always reads the latest committed rows. It is native because the guarantee depends on a
     * bare {@code FOR SHARE}: a {@code FOR SHARE OF <alias>} form locks, and so freshly reads, only the named table,
     * and the course-section, course and assignment rows would again come from the snapshot.
     */
    @Query(value = """
            select count(*)
            from peer_evaluation_user s
            join section sec on sec.section_id = s.section_section_id
            join course c on c.course_id = sec.course_course_id
            left join section_instructor si on si.section_id = sec.section_id
            where s.id = :studentId
              and (c.course_admin_id = :callerId or si.instructor_id = :callerId)
            for share
            """, nativeQuery = true)
    long countInLifecycleScopeForShare(@Param("studentId") Integer studentId, @Param("callerId") Integer callerId);

}

package team.projectpulse.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<PeerEvaluationUser, Integer> {

    Optional<PeerEvaluationUser> findByUsername(String username);

    Optional<PeerEvaluationUser> findByEmail(String email);

    List<PeerEvaluationUser> findByEmailIn(Collection<String> emails);

    /**
     * Whether an account with this id exists and is active, read from the database on every call. A derived
     * {@code exists} query, so no entity is loaded and nothing cached in a persistence context answers it.
     */
    boolean existsByIdAndEnabledTrue(Integer id);

}

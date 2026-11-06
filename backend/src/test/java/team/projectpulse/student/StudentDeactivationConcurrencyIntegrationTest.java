package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.evaluation.EvaluationService;
import team.projectpulse.evaluation.PeerEvaluation;
import team.projectpulse.evaluation.converter.PeerEvaluationDtoToPeerEvaluationConverter;
import team.projectpulse.evaluation.dto.PeerEvaluationDto;
import team.projectpulse.evaluation.dto.RatingDto;
import team.projectpulse.system.exception.PeerEvaluationIllegalArgumentException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ordering guarantees of docs/design/stu.md, in separate committed transactions against MySQL: a request that
 * arrives while another holds a student's row lock waits for it, then decides on the committed state, both for the
 * account (BR-student-deactivation-idempotent, BR-evaluation-active-evaluatee) and for the relationships that grant
 * scope or team eligibility (UC-STU-deactivate-student Associated Information, BR-team-assignment-required).
 *
 * <p>These tests commit, so they opt out of the base class's rolled-back transaction and restore every row they touch
 * in {@link #tearDown()}. The dev clock is fixed at 2023-08-20, so 2023-W32 is the week open for evaluations,
 * and Team2 (Woody, Amanda, Cora, Agustin) has no evaluations for it in the seed.
 */
@DisplayName("Integration tests for deactivation ordering across transactions")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StudentDeactivationConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final String WEEK = "2023-W32";
    private static final long BLOCKED_FOR_MS = 700;

    @Autowired
    StudentService studentService;
    @Autowired
    EvaluationService evaluationService;
    @Autowired
    PeerEvaluationDtoToPeerEvaluationConverter converter;
    @Autowired
    EntityManager entityManager;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactionManager;

    TransactionTemplate tx;
    ExecutorService pool;
    Integer maxEvaluationId;
    Integer wei, bill, john, eric, woody, amanda, amandaTeam;

    @BeforeEach
    void setUp() {
        this.tx = new TransactionTemplate(this.transactionManager);
        this.pool = Executors.newFixedThreadPool(2);
        this.maxEvaluationId = this.jdbc.queryForObject("select coalesce(max(peer_evaluation_id), 0) from peer_evaluation", Integer.class);
        this.wei = idOf("b.wei@abc.edu");
        this.bill = idOf("b.gates@abc.edu");
        this.john = idOf("j.smith@abc.edu");
        this.eric = idOf("e.hudson@abc.edu");
        this.woody = idOf("w.allen@abc.edu");
        this.amanda = idOf("a.wagner@abc.edu");
        this.amandaTeam = this.jdbc.queryForObject("select team_team_id from peer_evaluation_user where id = ?", Integer.class, this.amanda);
    }

    @AfterEach
    void tearDown() {
        this.pool.shutdownNow();
        restoreSeedData();
    }

    private void restoreSeedData() {
        this.jdbc.update("delete from rating where peer_evaluation_peer_evaluation_id > ?", this.maxEvaluationId);
        this.jdbc.update("delete from peer_evaluation where peer_evaluation_id > ?", this.maxEvaluationId);
        this.jdbc.update("update peer_evaluation_user set enabled = 1 where dtype = 'Student'"); // Every seeded student is active
        this.jdbc.update("update peer_evaluation_user set team_team_id = ? where id = ?", this.amandaTeam, this.amanda);
        if (this.jdbc.queryForObject("select count(*) from section_instructor where section_id = 2 and instructor_id = ?", Integer.class, this.bill) == 0) {
            this.jdbc.update("insert into section_instructor (section_id, instructor_id) values (2, ?)", this.bill);
        }
    }

    private Integer idOf(String email) {
        return this.jdbc.queryForObject("select id from peer_evaluation_user where email = ?", Integer.class, email);
    }

    private boolean enabled(Integer userId) {
        return Boolean.TRUE.equals(this.jdbc.queryForObject("select enabled from peer_evaluation_user where id = ?", Boolean.class, userId));
    }

    /** Runs the work on a pool thread signed in as the user, in a transaction of its own that commits when it returns. */
    private <T> Future<T> inTransactionAs(Integer userId, Callable<T> work) {
        return this.pool.submit(() -> {
            Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("userId", userId).build();
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                    List.of(new SimpleGrantedAuthority("ROLE_instructor"), new SimpleGrantedAuthority("ROLE_student"))));
            try {
                return this.tx.execute(status -> {
                    try {
                        return work.call();
                    } catch (RuntimeException ex) {
                        throw ex;
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                });
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).as("latch released").isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /** The request is still waiting, which here can only be on the student row lock the holder has. */
    private static void assertBlocked(Future<?> request) {
        assertThatThrownBy(() -> request.get(BLOCKED_FOR_MS, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
    }

    private static Throwable failureOf(Future<?> request) throws InterruptedException, TimeoutException {
        try {
            request.get(20, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException ex) {
            return ex.getCause();
        }
    }

    private PeerEvaluationDto evaluationOf(Integer evaluatorId, Integer evaluateeId) {
        List<RatingDto> ratings = List.of(new RatingDto(null, 1, 8.0), new RatingDto(null, 2, 8.0), new RatingDto(null, 3, 8.0),
                new RatingDto(null, 4, 8.0), new RatingDto(null, 5, 8.0), new RatingDto(null, 6, 8.0));
        return new PeerEvaluationDto(null, WEEK, evaluatorId, null, evaluateeId, null, ratings, null, "public", "private", null, null);
    }

    private long evaluationsOf(Integer evaluatorId, Integer evaluateeId) {
        return this.jdbc.queryForObject("select count(*) from peer_evaluation where week = ? and evaluator_id = ? and evaluatee_id = ?",
                Long.class, WEEK, evaluatorId, evaluateeId);
    }

    /** A deactivation that, once it holds the student's lock and has written, waits for the test before committing. */
    private Future<DeactivationOutcome> heldDeactivation(Integer studentId, CountDownLatch locked, CountDownLatch release) {
        return inTransactionAs(this.wei, () -> {
            DeactivationOutcome outcome = this.studentService.deactivateStudent(studentId);
            locked.countDown();
            await(release);
            return outcome;
        });
    }

    @Test
    void testConcurrentRepeatWaitsThenReturnsUnchanged() throws Exception {
        // Given: Wei's deactivation of Eric holds his row lock, uncommitted
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<DeactivationOutcome> first = heldDeactivation(this.eric, locked, release);
        await(locked);

        // When: Bill confirms too; he reads Eric as active, then waits on the lock
        Future<DeactivationOutcome> second = inTransactionAs(this.bill, () -> this.studentService.deactivateStudent(this.eric));
        assertBlocked(second);
        release.countDown();

        // Then: after the first commits, the second refreshes to inactive and is the successful no-op (STU 6b)
        assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(DeactivationOutcome.DEACTIVATED);
        assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(DeactivationOutcome.ALREADY_DEACTIVATED);
        assertThat(enabled(this.eric)).isFalse();
    }

    @Test
    void testAssignmentRemovedWhileWaitingIsRefusedForActiveAndInactiveTargets() throws Exception {
        for (boolean initiallyEnabled : List.of(true, false)) {
            // Given: the holder locks Eric's row and removes Bill's assignment to course section 2, uncommitted
            this.jdbc.update("update peer_evaluation_user set enabled = ? where id = ?", initiallyEnabled, this.eric);
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<Void> holder = inTransactionAs(this.wei, () -> {
                this.entityManager.find(Student.class, this.eric, LockModeType.PESSIMISTIC_WRITE);
                this.jdbc.update("delete from section_instructor where section_id = 2 and instructor_id = ?", this.bill);
                locked.countDown();
                await(release);
                return null;
            });
            await(locked);

            // When: Bill, still assigned in the committed state he first reads, confirms and waits on the lock
            Future<DeactivationOutcome> request = inTransactionAs(this.bill, () -> this.studentService.deactivateStudent(this.eric));
            assertBlocked(request);
            release.countDown();
            holder.get(20, TimeUnit.SECONDS);

            // Then: the locking recheck sees the removal: refused, neither changed nor reported as already deactivated
            assertThat(failureOf(request)).as("initially enabled %s", initiallyEnabled).isInstanceOf(AccessDeniedException.class);
            assertThat(enabled(this.eric)).isEqualTo(initiallyEnabled);
            restoreSeedData();
        }
    }

    @Test
    void testCreateWaitingOnADeactivationIsRefusedAfterItCommits() throws Exception {
        // Given: Woody's request has already loaded Amanda, active, when Wei's deactivation of her takes the lock
        CountDownLatch converted = new CountDownLatch(1);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<PeerEvaluation> create = inTransactionAs(this.woody, () -> {
            PeerEvaluation evaluation = this.converter.convert(evaluationOf(this.woody, this.amanda));
            assertThat(evaluation.getEvaluatee().isEnabled()).isTrue(); // The managed instance's earlier state
            converted.countDown();
            await(locked);
            return this.evaluationService.addPeerEvaluation(evaluation);
        });
        await(converted);
        Future<DeactivationOutcome> deactivation = heldDeactivation(this.amanda, locked, release);
        await(locked);

        // When
        assertBlocked(create);
        release.countDown();

        // Then
        assertThat(deactivation.get(20, TimeUnit.SECONDS)).isEqualTo(DeactivationOutcome.DEACTIVATED);
        assertThat(failureOf(create)).isInstanceOf(PeerEvaluationIllegalArgumentException.class)
                .hasMessage("The selected evaluatee is deactivated.");
        assertThat(evaluationsOf(this.woody, this.amanda)).isZero();
    }

    @Test
    void testEditWaitingOnADeactivationIsRefusedAfterItCommits() throws Exception {
        // Given: John's saved evaluation of Eric for the open week, with its stored scores and comments
        Integer evaluationId = this.jdbc.queryForObject("select peer_evaluation_id from peer_evaluation where week = ? and evaluator_id = ? and evaluatee_id = ?",
                Integer.class, WEEK, this.john, this.eric);
        Map<String, Object> stored = this.jdbc.queryForMap("select total_score, public_comment, private_comment from peer_evaluation where peer_evaluation_id = ?", evaluationId);
        List<Double> storedScores = this.jdbc.queryForList("select actual_score from rating where peer_evaluation_peer_evaluation_id = ? order by rating_id", Double.class, evaluationId);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<PeerEvaluation> edit = inTransactionAs(this.john, () -> {
            // The body names Jerry, who stays active; the stored evaluatee is what counts
            PeerEvaluation update = this.converter.convert(evaluationOf(this.john, idOf("j.moon@abc.edu")));
            assertThat(this.entityManager.find(PeerEvaluation.class, evaluationId).getEvaluatee().isEnabled()).isTrue();
            loaded.countDown();
            await(locked);
            return this.evaluationService.updatePeerEvaluation(evaluationId, update);
        });
        await(loaded);
        Future<DeactivationOutcome> deactivation = heldDeactivation(this.eric, locked, release);
        await(locked);

        // When
        assertBlocked(edit);
        release.countDown();

        // Then
        assertThat(deactivation.get(20, TimeUnit.SECONDS)).isEqualTo(DeactivationOutcome.DEACTIVATED);
        assertThat(failureOf(edit)).isInstanceOf(PeerEvaluationIllegalArgumentException.class)
                .hasMessage("The selected evaluatee is deactivated.");
        assertThat(this.jdbc.queryForMap("select total_score, public_comment, private_comment from peer_evaluation where peer_evaluation_id = ?", evaluationId))
                .isEqualTo(stored);
        assertThat(this.jdbc.queryForList("select actual_score from rating where peer_evaluation_peer_evaluation_id = ? order by rating_id", Double.class, evaluationId))
                .isEqualTo(storedScores);
    }

    @Test
    void testAnEvaluationCommittedFirstRemainsIntact() throws Exception {
        // Given: Woody's evaluation of Amanda commits before her deactivation starts
        Integer evaluationId = inTransactionAs(this.woody, () -> this.evaluationService
                .addPeerEvaluation(this.converter.convert(evaluationOf(this.woody, this.amanda))).getPeerEvaluationId())
                .get(20, TimeUnit.SECONDS);

        // When
        assertThat(inTransactionAs(this.wei, () -> this.studentService.deactivateStudent(this.amanda)).get(20, TimeUnit.SECONDS))
                .isEqualTo(DeactivationOutcome.DEACTIVATED);

        // Then
        assertThat(this.jdbc.queryForObject("select public_comment from peer_evaluation where peer_evaluation_id = ?", String.class, evaluationId))
                .isEqualTo("public");
        assertThat(this.jdbc.queryForObject("select count(*) from rating where peer_evaluation_peer_evaluation_id = ?", Integer.class, evaluationId))
                .isEqualTo(6);
    }

    @Test
    void testTeamChangeWhileCreateWaitsIsRefused() throws Exception {
        // Given: Woody's request has loaded Amanda on his team when the holder locks her and removes her from it
        CountDownLatch converted = new CountDownLatch(1);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<PeerEvaluation> create = inTransactionAs(this.woody, () -> {
            PeerEvaluation evaluation = this.converter.convert(evaluationOf(this.woody, this.amanda));
            assertThat(evaluation.getEvaluatee().getTeam()).isNotNull();
            converted.countDown();
            await(locked);
            return this.evaluationService.addPeerEvaluation(evaluation);
        });
        await(converted);
        Future<Void> holder = inTransactionAs(this.wei, () -> {
            this.entityManager.find(Student.class, this.amanda, LockModeType.PESSIMISTIC_WRITE);
            this.jdbc.update("update peer_evaluation_user set team_team_id = null where id = ?", this.amanda);
            locked.countDown();
            await(release);
            return null;
        });
        await(locked);

        // When
        assertBlocked(create);
        release.countDown();
        holder.get(20, TimeUnit.SECONDS);

        // Then: a 400 naming the unmet team condition, not a 403
        assertThat(failureOf(create)).isInstanceOf(PeerEvaluationIllegalArgumentException.class)
                .hasMessage("The evaluator and evaluatee must be on the same team.");
        assertThat(evaluationsOf(this.woody, this.amanda)).isZero();
    }

}

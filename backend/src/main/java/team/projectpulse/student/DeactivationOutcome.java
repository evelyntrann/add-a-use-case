package team.projectpulse.student;

/**
 * What {@link StudentService#deactivateStudent} did. Both are successes (UC-STU-deactivate-student step 6 and
 * extension 6b); the controller picks the message from this, so the service never hands an entity back.
 */
public enum DeactivationOutcome {

    /** The account was active and is now deactivated. */
    DEACTIVATED,

    /** The account was already deactivated, so nothing was written (BR-student-deactivation-idempotent). */
    ALREADY_DEACTIVATED

}

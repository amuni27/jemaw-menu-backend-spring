package com.agafari.com.architecture.fixtures;

import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.service.MonetizationLocks;
import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** AGF SUB-R2-05: deliberately wrong code, compiled but never run, that proves the boundary scanner detects each bypass. */
@SuppressWarnings("unused")
public final class BoundaryViolations {

    private BoundaryViolations() {
    }

    public static class CreatesWithConstructor {
        BusinessSubscription bypass() { return new BusinessSubscription(); }
    }

    public static class CreatesWithManualFactory {
        BusinessSubscription bypass() { return BusinessSubscription.manual("b", "MODEL_2", 1, null, Instant.EPOCH, "a"); }
    }

    public static class CreatesWithOwnerFactory {
        BusinessSubscription bypass() { return BusinessSubscription.owner("b", "MODEL_2", 1, Instant.EPOCH, "a"); }
    }

    public static class CreatesWithTrialFactory {
        BusinessSubscription bypass() { return BusinessSubscription.trial("b", 1, Instant.EPOCH, Instant.EPOCH, "a"); }
    }

    public static class EndsDirectly {
        void bypass(BusinessSubscription s) { s.end(Instant.EPOCH, "a"); }
    }

    public static class EndsTrialDirectly {
        void bypass(BusinessSubscription s) { s.endTrial(Instant.EPOCH, Instant.EPOCH, "a"); }
    }

    public static class ChangesPeriodEnd {
        void bypass(BusinessSubscription s) { s.changePeriodEnd(Instant.EPOCH, Instant.EPOCH, "a"); }
    }

    public static class ChangesTransitionDate {
        void bypass(BusinessSubscription s) { s.changeTransitionDate(Instant.EPOCH, Instant.EPOCH, "a"); }
    }

    public static class EndsThroughAMethodReference {
        void bypass(Optional<BusinessSubscription> s) { s.ifPresent(x -> { }); java.util.function.BiConsumer<Instant, String> f = s.orElseThrow()::end; }
    }

    public static class EndsInALambda {
        void bypass(List<BusinessSubscription> all) { all.forEach(s -> s.end(Instant.EPOCH, "a")); }
    }

    public static class SavesThroughTheRepository {
        void bypass(BusinessSubscriptionRepository r, BusinessSubscription s) { r.saveAndFlush(s); }
    }

    public static class ExpiresThroughTheRepository {
        void bypass(BusinessSubscriptionRepository r) { r.expireIfDue("id", Instant.EPOCH); }
    }

    public static class EndsThroughTheRepository {
        void bypass(BusinessSubscriptionRepository r) { r.endGrandfathered("id", Instant.EPOCH, Instant.EPOCH, 0); }
    }

    public static class UpdatesStatusWithJdbc {
        int bypass(JdbcTemplate jdbc) { return jdbc.update("update business_subscription set status = 'EXPIRED' where id = ?", "x"); }
    }

    public static class InsertsWithJdbc {
        int bypass(JdbcTemplate jdbc) { return jdbc.update("INSERT INTO business_subscription (id) values (?)", "x"); }
    }

    public static class DeletesWithJdbc {
        int bypass(JdbcTemplate jdbc) { return jdbc.update("delete from business_subscription where id = ?", "x"); }
    }

    public static class WritesThePeriodTableWithJdbc {
        int bypass(JdbcTemplate jdbc) { return jdbc.update("insert into subscription_period (id) values (?)", "x"); }
    }

    public static class BuildsUpdateByConcatenation {
        int bypass(JdbcTemplate jdbc, String column) { return jdbc.update("update business_subscription set " + column + " = 1"); }
    }

    public interface OtherRepositoryWithNativeUpdate extends Repository<BusinessSubscription, String> {
        @Modifying
        @Query(value = "update business_subscription set period_end = null", nativeQuery = true)
        int bypass();
    }

    public interface OtherRepositoryWithJpqlUpdate extends Repository<BusinessSubscription, String> {
        @Modifying
        @Query("update BusinessSubscription s set s.status = 'EXPIRED'")
        int bypass();
    }

    public static class PersistsThroughTheEntityManager {
        void bypass(EntityManager em, BusinessSubscription s) { em.persist(s); }
    }

    public static class MergesThroughTheEntityManager {
        BusinessSubscription bypass(EntityManager em, BusinessSubscription s) { return em.merge(s); }
    }

    public static class WritesAFieldByReflection {
        void bypass(BusinessSubscription s, Field f) throws IllegalAccessException { f.set(s, "EXPIRED"); }
    }

    public static class ConstructsVerifiedPaymentRef {
        Object bypass() { return new com.agafari.com.architecture.fixtures.VerifiedPaymentRef(); }
    }

    /** Everything here is allowed: reading is not writing. */
    public static class OnlyReads {
        String reads(BusinessSubscription s, BusinessSubscriptionRepository r, JdbcTemplate jdbc, Instant now) {
            r.findCurrentByBusinessId("b");
            r.findById("x");
            r.existsByBusinessIdAndSource("b", "TRIAL");
            jdbc.queryForList("select id from business_subscription where business_id = ?", String.class, "b");
            jdbc.update("update plan_request set status = 'WITHDRAWN' where id = ?", "x");
            return s.getStatus() + s.getPeriodEnd() + s.effectiveStatus(now) + s.isCurrent() + s.isTrial();
        }
    }

    /** An entity manager or reflection in a class unrelated to subscriptions is not this rule's concern. */
    public static class UnrelatedPersistence {
        void persists(EntityManager em, Object other) { em.persist(other); }
    }

    // ---- lifecycle-like classes for the lock rule

    public static class WritesWithoutALock {
        private BusinessSubscriptionRepository r;
        void write(BusinessSubscription s) { r.saveAndFlush(s); }
    }

    public static class WritesBeforeTheLock {
        private BusinessSubscriptionRepository r;
        private MonetizationLocks locks;
        void write(BusinessSubscription s) { r.saveAndFlush(s); locks.assertHeld("b"); }
    }

    public static class RestaurantOnlyOutsideTheException {
        private BusinessSubscriptionRepository r;
        private MonetizationLocks locks;
        void write(BusinessSubscription s) { locks.assertRestaurantHeld("b"); r.saveAndFlush(s); }
    }

    public static class ApprovedLifecycleShape {
        private BusinessSubscriptionRepository r;
        private MonetizationLocks locks;
        void write(BusinessSubscription s) { locks.assertHeld("b"); s.end(Instant.EPOCH, "a"); r.saveAndFlush(s); }
        boolean expireTrialIfDue(String id) { locks.assertRestaurantHeld("b"); return r.expireIfDue(id, Instant.EPOCH) == 1; }
        boolean reads(BusinessSubscription s) { return s.isCurrent(); }
    }
}

package org.lfdecentralizedtrust.splice.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext, SynchronizerAlias}
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules_MiningRound_Archive,
  AppTransferContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.cometbft.CometBftConfigLimits
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.MemberTraffic
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.{
  DsoDecentralizedSynchronizerConfig,
  SynchronizerNodeConfigLimits,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.{
  ARC_AmuletRules,
  ARC_AnsEntryContext,
  ARC_DsoRules,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_MiningRound_Archive
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.ansentrycontext_actionrequiringconfirmation.{
  ANSRARC_CollectInitialEntryPayment,
  ANSRARC_RejectEntryInitialPayment,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.{
  SRARC_AddSv,
  SRARC_OffboardSv,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.svonboarding.{
  SvOnboardingConfirmed,
  SvOnboardingRequest,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment.{PaymentAmount, Unit}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions.*
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.store.{Limit, MiningRoundsStore, PageLimit, StoreTest}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore.{IdleAnsSubscription, RoundBatch}
import org.lfdecentralizedtrust.splice.sv.store.db.DbSvDsoStore
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvStore}
import org.lfdecentralizedtrust.splice.sv.util.SvUtil
import org.lfdecentralizedtrust.splice.util.{
  AssignedContract,
  Contract,
  ResourceTemplateDecoder,
  TemplateJsonDecoder,
}

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.{Collections, Optional}
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.util.Random

abstract class SvDsoStoreTest extends StoreTest with HasExecutionContext {

  "SvDsoStore" should {

    def offsetFreeLookupTest[TCid <: ContractId[T], T, C <: Contract.Has[TCid, T]](
        create: => Contract[TCid, T],
        noise: => Seq[Contract[TCid, T]],
    )(
        fetch: SvDsoStore => Future[Option[C]]
    ) = "return the entry if found" in {
      val created = create
      for {
        store <- mkStore()
        _ <- dummyDomain.create(created)(store.multiDomainAcsStore)
        _ <- MonadUtil.sequentialTraverse(noise)(dummyDomain.create(_)(store.multiDomainAcsStore))
        result <- fetch(store)
      } yield result.map(_.contract) should be(Some(created))
    }

    def lookupTests[TCid <: ContractId[T], T, C <: Contract.Has[TCid, T]](
        name: String
    )(create: => Contract[TCid, T], noise: => Seq[Contract[TCid, T]] = Seq.empty)(
        fetch: SvDsoStore => Future[QueryResult[Option[C]]]
    ) = {
      s"$name" should {

        "return only the offset if there's none" in {
          for {
            store <- mkStore()
            result <- fetch(store)
          } yield result should be(QueryResult(acsOffset, None))
        }

        offsetFreeLookupTest(create, noise)(fetch andThen (_ map (_.value)))
      }
    }

    lookupTests("lookupDsoRulesWithOffset")(dsoRules())(_.lookupDsoRulesWithStateWithOffset())
    lookupTests("lookupAmuletRulesWithOffset")(amuletRules())(_.lookupAmuletRulesWithOffset())
    lookupTests("lookupAnsRulesWithOffset")(ansRules())(
      _.lookupAnsRulesWithOffset()
    )
    lookupTests("lookupConfirmationByActionWithOffset")(
      create = confirmation(1, addUser666Action),
      noise = Seq(
        confirmation(2, addUser667Action),
        confirmation(3, removeUserAction),
      ),
    )(_.lookupConfirmationByActionWithOffset(storeSvParty, addUser666Action))
    lookupTests("lookupSvOnboardingRequestByTokenWithOffset")(
      create = svOnboardingRequest("good", userParty(1), "good-pid", "good"),
      noise = Seq(
        svOnboardingRequest("bad", userParty(2), "bad-pid", "bad")
      ),
    )(_.lookupSvOnboardingRequestByTokenWithOffset("good"))
    lookupTests("lookupSvOnboardingRequestByCandidateParty")(
      create = svOnboardingRequest("good", userParty(1), "good-pid", "good"),
      noise = Seq(
        svOnboardingRequest("bad", userParty(2), "bad-pid", "bad")
      ),
    )(
      _.lookupSvOnboardingRequestByCandidateParty(userParty(1)).map(
        QueryResult(acsOffset, _)
      )
    )
    lookupTests("lookupSvOnboardingRequestByCandidateName")(
      create = svOnboardingRequest("good", userParty(1), "good-pid", "good"),
      noise = Seq(
        svOnboardingRequest("bad", userParty(2), "bad-pid", "bad")
      ),
    )(
      _.lookupSvOnboardingRequestByCandidateName("good").map(
        QueryResult(acsOffset, _)
      )
    )
    "lookupSvOnboardingConfirmedByParty" should {
      offsetFreeLookupTest(
        create = svOnboardingConfirmed("good", userParty(1), "good-pid"),
        noise = Seq(svOnboardingConfirmed("bad", userParty(2), "bad-pid")),
      )(_.lookupSvOnboardingConfirmedByParty(userParty(1)))
    }
    lookupTests("lookupSvOnboardingConfirmedByNameWithOffset")(
      create = svOnboardingConfirmed("good", userParty(1), "good-pid"),
      noise = Seq(svOnboardingConfirmed("bad", userParty(2), "bad-pid")),
    )(
      _.lookupSvOnboardingConfirmedByNameWithOffset("good")
    )
    val now = Instant.now()
    val timeInThePast = now.truncatedTo(ChronoUnit.MICROS).minusSeconds(3600)
    lookupTests("lookupAnsEntryByNameWithOffset")(
      create = ansEntry(userParty(1), "good"),
      noise = Seq(ansEntry(userParty(2), "bad"), ansEntry(userParty(3), "expired", timeInThePast)),
    )(
      _.lookupAnsEntryByNameWithOffset("good", CantonTimestamp.assertFromInstant(now))
    )
    def paymentId(n: Int) = new SubscriptionInitialPayment.ContractId(validContractId(n))
    def newReferenceId = new SubscriptionRequest.ContractId(nextCid())
    lookupTests("lookupSubscriptionInitialPaymentWithOffset")(
      create = subscriptionInitialPayment(
        newReferenceId,
        paymentId(1),
        userParty(1),
        dsoParty,
        BigDecimal(1.0),
      ),
      noise = Seq(
        subscriptionInitialPayment(
          newReferenceId,
          paymentId(2),
          userParty(2),
          dsoParty,
          BigDecimal(2.0),
        ),
        subscriptionInitialPayment(
          newReferenceId,
          paymentId(3),
          userParty(3),
          dsoParty,
          BigDecimal(3.0),
        ),
      ),
    )(
      _.lookupSubscriptionInitialPaymentWithOffset(paymentId(1))
    )
    lookupTests("lookupFeaturedAppRightWithOffset")(
      create = featuredAppRight(userParty(1)),
      noise = (2 to 4).map(n => featuredAppRight(userParty(n))),
    )(
      _.lookupFeaturedAppRightWithOffset(userParty(1))
    )

    "getOpenMiningRoundTriple" should {

      "return the oldest, middle, newest mining rounds" in {
        for {
          store <- mkStore()
          (oldest, middle, newest) <- createMiningRoundsTriple(store)
          result <- store.getOpenMiningRoundTriple()
        } yield result should be(
          MiningRoundsStore.OpenMiningRoundTriple(
            oldest = oldest,
            middle = middle,
            newest = newest,
            dummyDomain,
          )
        )
      }

    }

    "listExpiredAmulets" should {

      "return all the amulets that are expired as of the latest open mining round" in {
        val expiresAtRound2 = amulet(storeSvParty, 1.0, 1, 1.0)
        val expiresAtRound3 = amulet(storeSvParty, 1.0, 2, 1.0)
        val expiresAtRound4 = amulet(storeSvParty, 3.0, 1, 1.0)
        val wontExpireAnyTimeSoon = amulet(storeSvParty, 10.0, 2, 0.0001)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(dsoRules())(store.multiDomainAcsStore)
          _ <- createMiningRoundsTriple(store, startRound = 3L) // oldest is round 3, newest is 5
          _ <- MonadUtil.sequentialTraverse(
            Seq(expiresAtRound2, expiresAtRound3, expiresAtRound4, wontExpireAnyTimeSoon)
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listExpiredAmulets(Set.empty)(
            CantonTimestamp.now(),
            PageLimit.tryCreate(100),
          )(
            traceContext
          )
        } yield {
          val contracts = result.map(_.contract)
          contracts should contain theSameElementsAs Seq(expiresAtRound2, expiresAtRound3)
        }
      }

      "do not return expired amulets from other domains" in {
        val expiresAtRound2 = amulet(storeSvParty, 1.0, 1, 1.0)
        val expiresAtRound3 = amulet(storeSvParty, 1.0, 2, 1.0)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(dsoRules())(store.multiDomainAcsStore)
          _ <- createMiningRoundsTriple(store, startRound = 3L) // oldest is round 3, newest is 5
          _ <- dummy2Domain.create(expiresAtRound2)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(expiresAtRound3)(store.multiDomainAcsStore)
          result <- store.listExpiredAmulets(Set.empty)(
            CantonTimestamp.now(),
            PageLimit.tryCreate(100),
          )(
            traceContext
          )
        } yield {
          val contracts = result.map(_.contract)
          contracts should contain theSameElementsAs Seq(expiresAtRound3)
        }
      }

      "respects ignored parties" in {
        val party1 = userParty(1)
        val party2 = userParty(2)
        val party3 = userParty(3)
        val expiresAtRound2 = amulet(party1, 1.0, 1, 1.0)
        val expiresAtRound3 = amulet(party2, 1.0, 2, 1.0)
        val expiresAtRound4 = amulet(party3, 3.0, 1, 1.0)
        val wontExpireAnyTimeSoon = amulet(party1, 10.0, 2, 0.0001)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(dsoRules())(store.multiDomainAcsStore)
          _ <- createMiningRoundsTriple(store, startRound = 4L) // oldest is round 4, newest is 6
          _ <- MonadUtil.sequentialTraverse(
            Seq(expiresAtRound2, expiresAtRound3, expiresAtRound4, wontExpireAnyTimeSoon)
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listExpiredAmulets(Set.empty)(
            CantonTimestamp.now(),
            PageLimit.tryCreate(100),
          )(
            traceContext
          )
          resultNoParty1 <- store.listExpiredAmulets(Set(party1))(
            CantonTimestamp.now(),
            PageLimit.tryCreate(100),
          )(
            traceContext
          )
          resultNoParty2 <- store.listExpiredAmulets(Set(party2))(
            CantonTimestamp.now(),
            PageLimit.tryCreate(100),
          )(
            traceContext
          )
          resultNoParty1And2 <- store.listExpiredAmulets(Set(party1, party2))(
            CantonTimestamp.now(),
            PageLimit.tryCreate(100),
          )(
            traceContext
          )
        } yield {
          result.map(_.contract) should contain theSameElementsAs Seq(
            expiresAtRound2,
            expiresAtRound3,
            expiresAtRound4,
          )
          resultNoParty1.map(_.contract) should contain theSameElementsAs Seq(
            expiresAtRound3,
            expiresAtRound4,
          )
          resultNoParty2.map(_.contract) should contain theSameElementsAs Seq(
            expiresAtRound2,
            expiresAtRound4,
          )
          resultNoParty1And2.map(_.contract) should contain theSameElementsAs Seq(expiresAtRound4)
        }
      }

    }

    "listLockedExpiredAmulets" should {

      "return all the locked amulets that are expired as of the latest open mining round" in {
        val expiresAtRound2 = lockedAmulet(storeSvParty, 1.0, 1, 1.0)
        val expiresAtRound3 = lockedAmulet(storeSvParty, 1.0, 2, 1.0)
        val expiresAtRound4 = lockedAmulet(storeSvParty, 3.0, 1, 1.0)
        val wontExpireAnyTimeSoon = lockedAmulet(storeSvParty, 10.0, 2, 0.0001)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(dsoRules())(store.multiDomainAcsStore)
          _ <- createMiningRoundsTriple(store, startRound = 3L) // oldest is round 3, newest is 5
          _ <- MonadUtil.sequentialTraverse(
            Seq(expiresAtRound2, expiresAtRound3, expiresAtRound4, wontExpireAnyTimeSoon)
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listLockedExpiredAmulets(Set.empty)(
            CantonTimestamp.now(),
            PageLimit.tryCreate(100),
          )(
            traceContext
          )
        } yield {
          val contracts = result.map(_.contract)
          contracts should contain theSameElementsAs Seq(expiresAtRound2, expiresAtRound3)
        }
      }

      "do not return expired locked amulets from other domains" in {
        val expiresAtRound2 = lockedAmulet(storeSvParty, 1.0, 1, 1.0)
        val expiresAtRound3 = lockedAmulet(storeSvParty, 1.0, 2, 1.0)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(dsoRules())(store.multiDomainAcsStore)
          _ <- createMiningRoundsTriple(store, startRound = 3L) // oldest is round 3, newest is 5
          _ <- dummy2Domain.create(expiresAtRound2)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(expiresAtRound3)(store.multiDomainAcsStore)
          result <- store.listLockedExpiredAmulets(Set.empty)(
            CantonTimestamp.now(),
            PageLimit.tryCreate(100),
          )(
            traceContext
          )
        } yield {
          val contracts = result.map(_.contract)
          contracts should contain theSameElementsAs Seq(expiresAtRound3)
        }
      }

      "respects ignored parties" in {
        val party1 = userParty(1)
        val party2 = userParty(2)
        val party3 = userParty(3)
        val expiresAtRound2 = lockedAmulet(party1, 1.0, 1, 1.0, holders = Seq(party1))
        val expiresAtRound3 = lockedAmulet(party2, 1.0, 2, 1.0, holders = Seq(party2, party3))
        val expiresAtRound4 = lockedAmulet(party3, 3.0, 1, 1.0, holders = Seq.empty)
        val wontExpireAnyTimeSoon = lockedAmulet(party1, 10.0, 2, 0.0001, holders = Seq(party1))
        for {
          store <- mkStore()
          _ <- dummyDomain.create(dsoRules())(store.multiDomainAcsStore)
          _ <- createMiningRoundsTriple(store, startRound = 4L) // oldest is round 4, newest is 6
          _ <- MonadUtil.sequentialTraverse(
            Seq(expiresAtRound2, expiresAtRound3, expiresAtRound4, wontExpireAnyTimeSoon)
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listLockedExpiredAmulets(Set.empty)(
            CantonTimestamp.now(),
            PageLimit.tryCreate(100),
          )(
            traceContext
          )
          resultNoParty1 <- store.listLockedExpiredAmulets(Set(party1))(
            CantonTimestamp.now(),
            PageLimit.tryCreate(100),
          )(
            traceContext
          )
          resultNoParty2 <- store.listLockedExpiredAmulets(Set(party2))(
            CantonTimestamp.now(),
            PageLimit.tryCreate(100),
          )(
            traceContext
          )
          resultNoParty1And2 <- store.listLockedExpiredAmulets(Set(party1, party2))(
            CantonTimestamp.now(),
            PageLimit.tryCreate(100),
          )(
            traceContext
          )
          resultNoParty3 <- store.listLockedExpiredAmulets(Set(party3))(
            CantonTimestamp.now(),
            PageLimit.tryCreate(100),
          )(
            traceContext
          )
        } yield {
          result.map(_.contract) should contain theSameElementsAs Seq(
            expiresAtRound2,
            expiresAtRound3,
            expiresAtRound4,
          )
          resultNoParty1.map(_.contract) should contain theSameElementsAs Seq(
            expiresAtRound3,
            expiresAtRound4,
          )
          resultNoParty2.map(_.contract) should contain theSameElementsAs Seq(
            expiresAtRound2,
            expiresAtRound4,
          )
          resultNoParty3.map(_.contract) should contain theSameElementsAs Seq(expiresAtRound2)
          resultNoParty1And2.map(_.contract) should contain theSameElementsAs Seq(expiresAtRound4)
        }
      }
    }

    "listConfirmations" should {

      "list all confirmations with a matching action" in {
        val goodAction = addUser666Action
        val badActionSameType = addUser667Action
        val badActionDifferentType = removeUserAction
        val goodConfirmations = (1 to 3).map(n => confirmation(n, goodAction))
        val badConfirmation1 = confirmation(4, badActionSameType)
        val badConfirmation2 = confirmation(5, badActionDifferentType)
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(
            goodConfirmations :+ badConfirmation1 :+ badConfirmation2
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listConfirmations(goodAction)
        } yield {
          result should contain theSameElementsAs goodConfirmations
        }
      }

    }

    "list & sum AppRewardCouponsOnDomain" should {

      "list all the app reward coupons on the domain" in {
        val inRound =
          (1 to 3).map(n =>
            appRewardCoupon(round = 3, userParty(n), amount = numeric(n), featured = n % 2 == 0)
          )
        val outOfRound = (1 to 3).map(n => appRewardCoupon(round = 2, userParty(n)))
        val inRoundOtherDomain = (1 to 3).map(n => appRewardCoupon(round = 3, userParty(n)))
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(inRound ++ outOfRound)(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          _ <- MonadUtil.sequentialTraverse(inRoundOtherDomain)(
            dummy2Domain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listAppRewardCouponsOnDomain(round = 3, dummyDomain, Limit.DefaultLimit)
          sumResult <- store.sumAppRewardCouponsOnDomain(round = 3, dummyDomain)
        } yield {
          result should contain theSameElementsAs inRound
          sumResult.featured should be(
            inRound.filter(_.payload.featured).map(_.payload.amount).map(BigDecimal(_)).sum
          )
          sumResult.unfeatured should be(
            inRound.filter(!_.payload.featured).map(_.payload.amount).map(BigDecimal(_)).sum
          )
        }
      }

    }

    "list & sum ValidatorRewardCouponsOnDomain" should {

      "list all the validator reward coupons on the domain" in {
        val inRound =
          (1 to 3).map(n => validatorRewardCoupon(round = 3, userParty(n), amount = numeric(n)))
        val outOfRound = (1 to 3).map(n => validatorRewardCoupon(round = 2, userParty(n)))
        val inRoundOtherDomain = (1 to 3).map(n => validatorRewardCoupon(round = 3, userParty(n)))
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(inRound ++ outOfRound)(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          _ <- MonadUtil.sequentialTraverse(inRoundOtherDomain)(
            dummy2Domain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listValidatorRewardCouponsOnDomain(
            round = 3,
            dummyDomain,
            Limit.DefaultLimit,
          )
          sumResult <- store.sumValidatorRewardCouponsOnDomain(
            round = 3,
            dummyDomain,
          )
        } yield {
          result should contain theSameElementsAs inRound
          sumResult should be(inRound.map(_.payload.amount).map(BigDecimal(_)).sum)
        }
      }

    }

    "list & sum ValidatorFaucetCouponsOnDomain" should {

      "list all the validator faucet coupons on the domain" in {
        val inRound = (1 to 5).map(n => validatorFaucetCoupon(userParty(n), round = 3))
        val outOfRound = (1 to 3).map(n => validatorFaucetCoupon(userParty(n), round = 2))
        val inRoundOtherDomain = (1 to 3).map(n => validatorFaucetCoupon(userParty(n), round = 3))
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(inRound ++ outOfRound)(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          _ <- MonadUtil.sequentialTraverse(inRoundOtherDomain)(
            dummy2Domain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listValidatorFaucetCouponsOnDomain(
            round = 3,
            dummyDomain,
            Limit.DefaultLimit,
          )
          countResult <- store.countValidatorFaucetCouponsOnDomain(
            round = 3,
            dummyDomain,
          )
        } yield {
          result should contain theSameElementsAs inRound
          countResult should be(inRound.size.toLong)
        }
      }

    }

    "list & sum ValidatorLivenessActivityRecordsOnDomain" should {

      "list all the validator liveness activity records on the domain" in {
        val inRound = (1 to 5).map(n => validatorLivenessActivityRecord(userParty(n), round = 3))
        val outOfRound = (1 to 3).map(n => validatorLivenessActivityRecord(userParty(n), round = 2))
        val inRoundOtherDomain =
          (1 to 3).map(n => validatorLivenessActivityRecord(userParty(n), round = 3))
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(inRound ++ outOfRound)(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          _ <- MonadUtil.sequentialTraverse(inRoundOtherDomain)(
            dummy2Domain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listValidatorLivenessActivityRecordsOnDomain(
            round = 3,
            dummyDomain,
            Limit.DefaultLimit,
          )
          countResult <- store.countValidatorLivenessActivityRecordsOnDomain(
            round = 3,
            dummyDomain,
          )
        } yield {
          result should contain theSameElementsAs inRound
          countResult should be(inRound.size.toLong)
        }
      }

    }

    "listAppRewardCouponsGroupedByRound" should {

      "return all app reward coupons in a round grouped by round" in {
        val provider1InRound = (1 to 3).map(_ => appRewardCoupon(round = 3, userParty(1)))
        val provider2InRound = (1 to 3).map(_ => appRewardCoupon(round = 3, userParty(2)))
        val provider1OutOfRound = (1 to 3).map(_ => appRewardCoupon(round = 2, userParty(1)))
        val provider2OutOfRound = (1 to 3).map(_ => appRewardCoupon(round = 2, userParty(2)))
        val provider1OtherDomain = (1 to 3).map(_ => appRewardCoupon(round = 3, userParty(1)))
        val provider2OtherDomain = (1 to 3).map(_ => appRewardCoupon(round = 3, userParty(2)))
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(
            provider1InRound ++ provider2InRound ++ provider1OutOfRound ++ provider2OutOfRound
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          _ <- MonadUtil.sequentialTraverse(
            provider1OtherDomain ++ provider2OtherDomain
          )(
            dummy2Domain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listAppRewardCouponsGroupedByRound(
            domain = dummyDomain,
            totalCouponsLimit = PageLimit.tryCreate(1000),
            ignoredParties = Set.empty,
          )
        } yield {
          result should have size 2
          forExactly(1, result) { case RoundBatch(round, cids) =>
            round shouldBe 3
            cids.toSet shouldBe provider1InRound.map(_.contractId).toSet ++ provider2InRound
              .map(_.contractId)
              .toSet
          }
          forExactly(1, result) { case RoundBatch(round, cids) =>
            round shouldBe 2
            cids.toSet shouldBe provider2OutOfRound.map(_.contractId).toSet ++ provider1OutOfRound
              .map(_.contractId)
              .toSet
          }
        }
      }

    }

    "listValidatorRewardCouponsGroupedByRound" should {

      "return all validator reward coupons in a round grouped by round" in {
        val provider1InRound = (1 to 3).map(_ => validatorRewardCoupon(round = 3, userParty(1)))
        val provider2InRound = (1 to 3).map(_ => validatorRewardCoupon(round = 3, userParty(2)))
        val provider1OutOfRound = (1 to 3).map(_ => validatorRewardCoupon(round = 2, userParty(1)))
        val provider2OutOfRound = (1 to 3).map(_ => validatorRewardCoupon(round = 2, userParty(2)))
        val provider1OtherDomain = (1 to 3).map(_ => validatorRewardCoupon(round = 3, userParty(1)))
        val provider2OtherDomain = (1 to 3).map(_ => validatorRewardCoupon(round = 3, userParty(2)))
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(
            provider1InRound ++ provider2InRound ++ provider1OutOfRound ++ provider2OutOfRound
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          _ <- MonadUtil.sequentialTraverse(
            provider1OtherDomain ++ provider2OtherDomain
          )(
            dummy2Domain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listValidatorRewardCouponsGroupedByRound(
            domain = dummyDomain,
            totalCouponsLimit = PageLimit.tryCreate(1000),
            ignoredParties = Set.empty,
          )
        } yield {
          result should have size 2
          forExactly(1, result) { case RoundBatch(round, cids) =>
            round shouldBe 3
            cids.toSet shouldBe provider1InRound.map(_.contractId).toSet ++ provider2InRound
              .map(_.contractId)
              .toSet
          }
          forExactly(1, result) { case RoundBatch(round, cids) =>
            round shouldBe 2
            cids.toSet shouldBe provider1OutOfRound.map(_.contractId).toSet ++ provider2OutOfRound
              .map(_.contractId)
              .toSet
          }
        }
      }

    }

    "listValidatorFaucetCouponsGroupedByRound" should {

      "return all validator faucet coupons in a round grouped by round" in {
        val validator1InRound = (1 to 3).map(_ => validatorFaucetCoupon(userParty(1), round = 3))
        val validator2InRound = (1 to 3).map(_ => validatorFaucetCoupon(userParty(2), round = 3))
        val validator1OutOfRound = (1 to 3).map(_ => validatorFaucetCoupon(userParty(1), round = 2))
        val validator2OutOfRound = (1 to 3).map(_ => validatorFaucetCoupon(userParty(2), round = 2))
        val validator1OtherDomain =
          (1 to 3).map(_ => validatorFaucetCoupon(userParty(1), round = 3))
        val validator2OtherDomain =
          (1 to 3).map(_ => validatorFaucetCoupon(userParty(2), round = 3))
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(
            validator1InRound ++ validator2InRound ++ validator1OutOfRound ++ validator2OutOfRound
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          _ <- MonadUtil.sequentialTraverse(
            validator1OtherDomain ++ validator2OtherDomain
          )(
            dummy2Domain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listValidatorFaucetCouponsGroupedByRound(
            domain = dummyDomain,
            totalCouponsLimit = PageLimit.tryCreate(1000),
            ignoredParties = Set.empty,
          )
        } yield {
          result should have size 2
          forExactly(1, result) { case RoundBatch(round, cids) =>
            round shouldBe 2
            cids.toSet shouldBe validator1OutOfRound.map(_.contractId).toSet ++ validator2OutOfRound
              .map(_.contractId)
              .toSet
          }
          forExactly(1, result) { case RoundBatch(round, cids) =>
            round shouldBe 3
            cids.toSet shouldBe validator2InRound.map(_.contractId).toSet ++ validator1InRound
              .map(_.contractId)
              .toSet
          }
        }
      }
    }

    "listValidatorLivenessActivityRecordsGroupedByRound" should {

      "return all validator liveness activity records in a round grouped by round" in {
        val validator1InRound =
          (1 to 3).map(_ => validatorLivenessActivityRecord(userParty(1), round = 3))
        val validator2InRound =
          (1 to 3).map(_ => validatorLivenessActivityRecord(userParty(2), round = 3))
        val validator1OutOfRound =
          (1 to 3).map(_ => validatorLivenessActivityRecord(userParty(1), round = 2))
        val validator2OutOfRound =
          (1 to 3).map(_ => validatorLivenessActivityRecord(userParty(2), round = 2))
        val validator1OtherDomain =
          (1 to 3).map(_ => validatorLivenessActivityRecord(userParty(1), round = 3))
        val validator2OtherDomain =
          (1 to 3).map(_ => validatorLivenessActivityRecord(userParty(2), round = 3))
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(
            validator1InRound ++ validator2InRound ++ validator1OutOfRound ++ validator2OutOfRound
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          _ <- MonadUtil.sequentialTraverse(
            validator1OtherDomain ++ validator2OtherDomain
          )(
            dummy2Domain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listValidatorLivenessActivityRecordsGroupedByRound(
            domain = dummyDomain,
            totalCouponsLimit = PageLimit.tryCreate(1000),
            ignoredParties = Set.empty,
          )
        } yield {
          result should have size 2
          forExactly(1, result) { case RoundBatch(round, cids) =>
            round shouldBe 2
            cids.toSet shouldBe validator1OutOfRound.map(_.contractId).toSet ++ validator2OutOfRound
              .map(_.contractId)
              .toSet
          }
          forExactly(1, result) { case RoundBatch(round, cids) =>
            round shouldBe 3
            cids.toSet shouldBe validator2InRound.map(_.contractId).toSet ++ validator1InRound
              .map(_.contractId)
              .toSet
          }
        }
      }
    }

    "getExpiredRewards" in {
      val closedRound = closedMiningRound(dsoParty, 2)
      val validatorFaucet1NotClosed =
        (1 to 3).map(_ => validatorFaucetCoupon(userParty(1), round = 3))
      val validatorFaucet2NotClosed =
        (1 to 3).map(_ => validatorFaucetCoupon(userParty(2), round = 3))
      val validatorFaucet1Closed = (1 to 3).map(_ => validatorFaucetCoupon(userParty(1), round = 2))
      val validatorFaucet2Closed = (1 to 3).map(_ => validatorFaucetCoupon(userParty(2), round = 2))
      val validatorFaucet3Closed = (1 to 3).map(_ => validatorFaucetCoupon(userParty(3), round = 2))

      val validator1NotClosed = (1 to 3).map(_ => validatorRewardCoupon(round = 3, userParty(1)))
      val validator2NotClosed = (1 to 3).map(_ => validatorRewardCoupon(round = 3, userParty(2)))
      val validator1Closed = (1 to 3).map(_ => validatorRewardCoupon(round = 2, userParty(1)))
      val validator2Closed = (1 to 3).map(_ => validatorRewardCoupon(round = 2, userParty(2)))

      val app1NotClosed = (1 to 3).map(_ => appRewardCoupon(round = 3, userParty(1)))
      val app2NotClosed = (1 to 3).map(_ => appRewardCoupon(round = 3, userParty(2)))
      val app1Closed = (1 to 3).map(_ => appRewardCoupon(round = 2, userParty(1)))
      val app2Closed = (1 to 3).map(_ => appRewardCoupon(round = 2, userParty(2)))

      val sv1NotClosed =
        (1 to 3).map(_ => svRewardCoupon(round = 3, userParty(4), userParty(1), 1000))
      val sv2NotClosed =
        (1 to 3).map(_ => svRewardCoupon(round = 3, userParty(4), userParty(2), 1000))
      val sv3NotClosed =
        (1 to 3).map(_ => svRewardCoupon(round = 3, userParty(1), userParty(4), 1000))
      val sv4NotClosed =
        (1 to 3).map(_ => svRewardCoupon(round = 3, userParty(1), userParty(1), 1000))
      val sv1Closed = (1 to 3).map(_ => svRewardCoupon(round = 2, userParty(4), userParty(1), 1000))
      val sv2Closed = (1 to 3).map(_ => svRewardCoupon(round = 2, userParty(4), userParty(2), 1000))
      val sv3Closed = (1 to 3).map(_ => svRewardCoupon(round = 2, userParty(1), userParty(4), 1000))
      val sv4Closed = (1 to 3).map(_ => svRewardCoupon(round = 2, userParty(1), userParty(2), 1000))
      for {
        store <- mkStore()
        _ <- dummyDomain.create(closedRound)(store.multiDomainAcsStore)
        _ <- MonadUtil.sequentialTraverse(
          validatorFaucet1NotClosed ++ validatorFaucet2NotClosed ++ validatorFaucet1Closed ++ validatorFaucet2Closed ++ validatorFaucet3Closed
        )(
          dummyDomain.create(_)(store.multiDomainAcsStore)
        )
        _ <- MonadUtil.sequentialTraverse(
          validator1NotClosed ++ validator2NotClosed ++ validator1Closed ++ validator2Closed
        )(
          dummyDomain.create(_)(store.multiDomainAcsStore)
        )
        _ <- MonadUtil.sequentialTraverse(
          app1NotClosed ++ app2NotClosed ++ app1Closed ++ app2Closed
        )(
          dummyDomain.create(_)(store.multiDomainAcsStore)
        )
        _ <- MonadUtil.sequentialTraverse(
          sv1NotClosed ++ sv2NotClosed ++ sv3NotClosed ++ sv4NotClosed ++ sv1Closed ++ sv2Closed ++ sv3Closed ++ sv4Closed
        )(
          dummyDomain.create(_)(store.multiDomainAcsStore)
        )
        result <- store.getExpiredCouponsInBatchesPerRoundAndCouponType(
          domain = dummyDomain,
          enableExpireValidatorFaucet = true,
          totalCouponsLimit = PageLimit.tryCreate(1000),
          ignoredExpiredRewardsPartyIds = Set.empty,
        )
        resultWithIgnoredUserParty <- store.getExpiredCouponsInBatchesPerRoundAndCouponType(
          domain = dummyDomain,
          enableExpireValidatorFaucet = true,
          totalCouponsLimit = PageLimit.tryCreate(1000),
          ignoredExpiredRewardsPartyIds = Set(userParty(1), userParty(3)),
        )
        resultWithoutFaucet <- store.getExpiredCouponsInBatchesPerRoundAndCouponType(
          domain = dummyDomain,
          enableExpireValidatorFaucet = false,
          totalCouponsLimit = PageLimit.tryCreate(1000),
          ignoredExpiredRewardsPartyIds = Set.empty,
        )
      } yield {
        result should have size 4
        forAll(result)(_.closedRoundNumber shouldBe 2)
        forExactly(1, result) { batch =>
          batch.validatorCoupons should have size 6
          batch.appCoupons should have size 0
          batch.svRewardCoupons should have size 0
          batch.validatorFaucets should have size 0
        }
        forExactly(1, result) { batch =>
          batch.validatorCoupons should have size 0
          batch.appCoupons should have size 6
          batch.svRewardCoupons should have size 0
          batch.validatorFaucets should have size 0
        }
        forExactly(1, result) { batch =>
          batch.validatorCoupons should have size 0
          batch.appCoupons should have size 0
          batch.svRewardCoupons should have size 12
          batch.validatorFaucets should have size 0
        }
        forExactly(1, result) { batch =>
          batch.validatorCoupons should have size 0
          batch.appCoupons should have size 0
          batch.svRewardCoupons should have size 0
          batch.validatorFaucets should have size 9
        }
        resultWithIgnoredUserParty should have size 4
        forAll(resultWithIgnoredUserParty)(_.closedRoundNumber shouldBe 2)
        forExactly(1, resultWithIgnoredUserParty) { batch =>
          batch.validatorCoupons should have size 3
          batch.appCoupons should have size 0
          batch.svRewardCoupons should have size 0
          batch.validatorFaucets should have size 0
        }
        forExactly(1, resultWithIgnoredUserParty) { batch =>
          batch.validatorCoupons should have size 0
          batch.appCoupons should have size 3
          batch.svRewardCoupons should have size 0
          batch.validatorFaucets should have size 0
        }
        forExactly(1, resultWithIgnoredUserParty) { batch =>
          batch.validatorCoupons should have size 0
          batch.appCoupons should have size 0
          batch.svRewardCoupons should have size 3
          batch.validatorFaucets should have size 0
        }
        forExactly(1, resultWithIgnoredUserParty) { batch =>
          batch.validatorCoupons should have size 0
          batch.appCoupons should have size 0
          batch.svRewardCoupons should have size 0
          batch.validatorFaucets should have size 3
        }
        resultWithoutFaucet should have size 3
        forAll(resultWithoutFaucet)(_.validatorFaucets should have size 0)
      }
    }

    "listArchivableClosedMiningRounds" should {

      "return all ClosedMiningRounds without left-over reward coupons and with no ready-to-be-archived confirmation" in {
        val goodClosed = (1 to 3).map(n => closedMiningRound(dsoParty, n.toLong))
        val hasValidatorCoupon = closedMiningRound(dsoParty, round = 4)
        val hasAppCoupon = closedMiningRound(dsoParty, round = 5)
        val hasConfirmation = closedMiningRound(dsoParty, round = 6)
        val hasValidatorLivenessActivityRecord = closedMiningRound(dsoParty, round = 7)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(dsoRules())(store.multiDomainAcsStore)
          _ <- MonadUtil.sequentialTraverse(
            goodClosed :+ hasValidatorCoupon :+ hasAppCoupon :+ hasConfirmation :+ hasValidatorLivenessActivityRecord
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          _ <- dummyDomain.create(validatorFaucetCoupon(userParty(1), round = 3))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(validatorRewardCoupon(round = 4, userParty(1)))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(appRewardCoupon(round = 5, userParty(1)))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(
            confirmation(
              n = 6,
              action = new ARC_AmuletRules(
                new CRARC_MiningRound_Archive(
                  new AmuletRules_MiningRound_Archive(
                    hasConfirmation.contractId
                  )
                )
              ),
            )
          )(store.multiDomainAcsStore)
          _ <- dummyDomain.create(validatorLivenessActivityRecord(userParty(1), round = 7))(
            store.multiDomainAcsStore
          )
          result <- store.listArchivableClosedMiningRounds()
        } yield {
          result.map(_.value) should contain theSameElementsAs goodClosed.map(
            AssignedContract(_, dummyDomain)
          )
        }
      }

    }

    "lookupAnsInitialPaymentConfirmationByPaymentIdWithOffset" should {

      "find the confirmation by the initial payment id" in {
        val acceptedConfirmations =
          (1 to 2).map(n =>
            confirmation(
              n,
              ansEntryContextPaymentAction(
                ansEntryContext(n, s"name$n").contractId,
                isAccepted = true,
                new SubscriptionInitialPayment.ContractId(validContractId(n)),
              ),
            )
          )

        val rejectedConfirmations =
          (3 to 4).map(n =>
            confirmation(
              n,
              ansEntryContextPaymentAction(
                ansEntryContext(n, s"name$n").contractId,
                isAccepted = false,
                new SubscriptionInitialPayment.ContractId(validContractId(n)),
              ),
            )
          )
        val unrelatedConfirmation = confirmation(10, addUser666Action)

        def lookupConfirmations(
            lookup: SubscriptionInitialPayment.ContractId => Future[
              QueryResult[Option[Contract[Confirmation.ContractId, Confirmation]]]
            ]
        ) = Future.sequence(
          (1 to 4).map(n =>
            lookup(new SubscriptionInitialPayment.ContractId(validContractId(n))).map(_.value)
          )
        )

        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(
            acceptedConfirmations ++ rejectedConfirmations ++ Seq(unrelatedConfirmation)
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          results <- lookupConfirmations(
            store.lookupAnsInitialPaymentConfirmationByPaymentIdWithOffset(storeSvParty, _)
          )
          acceptedResults <- lookupConfirmations(
            store.lookupAnsAcceptedInitialPaymentConfirmationByPaymentIdWithOffset(storeSvParty, _)
          )
          rejectedResults <- lookupConfirmations(
            store.lookupAnsRejectedInitialPaymentConfirmationByPaymentIdWithOffset(storeSvParty, _)
          )
        } yield {
          results should be((acceptedConfirmations ++ rejectedConfirmations).map(Some(_)))
          acceptedResults should be(acceptedConfirmations.map(Some(_)) ++ Seq(None, None))
          rejectedResults should be(Seq(None, None) ++ rejectedConfirmations.map(Some(_)))
        }
      }
    }

    "listExpiredSvOnboardingRequests" should {

      "return all expired SV onboarding requests" in {
        val expired = (1 to 3).map(n =>
          svOnboardingRequest(
            n.toString,
            userParty(n),
            s"participant-id-${n.toString}",
            n.toString,
            Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(n.toLong * 3600),
          )
        )
        val notExpired =
          (4 to 6).map(n =>
            svOnboardingRequest(
              n.toString,
              userParty(n),
              s"PAR::sv${n.toString}::12345",
              n.toString,
            )
          )
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(expired ++ notExpired)(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listExpiredSvOnboardingRequests(
            CantonTimestamp.now(),
            PageLimit.tryCreate(100),
          )(
            traceContext
          )
        } yield {
          val contracts = result.map(_.contract)
          contracts should contain theSameElementsAs expired
        }
      }

    }

    "listExpiredSvOnboardingConfirmed" should {

      "return all expired SV onboarding confirmations" in {
        val expired = (1 to 3).map(n =>
          svOnboardingConfirmed(
            n.toString,
            userParty(n),
            s"PAR::sv${n.toString}::12345",
            Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(n.toLong * 3600),
          )
        )
        val notExpired =
          (4 to 6).map(n =>
            svOnboardingConfirmed(n.toString, userParty(n), s"PAR::sv${n.toString}::12345")
          )
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(expired ++ notExpired)(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listExpiredSvOnboardingConfirmed(
            CantonTimestamp.now(),
            PageLimit.tryCreate(100),
          )(
            traceContext
          )
        } yield {
          val contracts = result.map(_.contract)
          contracts should contain theSameElementsAs expired
        }
      }

    }

    "listExpiredAnsSubscriptions" should {

      "return all entries where subscription_next_payment_due_at < now" in {
        for {
          store <- mkStore()
          // 1 to 3 are expired, 4 to 6 are not
          data = ((1 to 3).map(n =>
            n -> Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(n * 1000L)
          ) ++ (4 to 6)
            .map(n => n -> Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(n * 1000L)))
            .map { case (n, nextPaymentDueAt) =>
              val contextContract =
                ansEntryContext(n, n.toString)
              val idleStateContract =
                subscriptionIdleState(
                  n,
                  nextPaymentDueAt,
                )

              (contextContract, idleStateContract)
            }
          _ <- MonadUtil.sequentialTraverse(data) { case (contextContract, idleContract) =>
            for {
              _ <- dummyDomain.create(contextContract, createdEventSignatories = Seq(dsoParty))(
                store.multiDomainAcsStore
              )
              _ <- dummyDomain.create(idleContract, createdEventSignatories = Seq(dsoParty))(
                store.multiDomainAcsStore
              )
            } yield ()
          }
        } yield {
          val expected = data
            .take(3)
            .map { case (ctxContract, idleContract) =>
              IdleAnsSubscription(idleContract, ctxContract)
            }
            .reverse
          store
            .listExpiredAnsSubscriptions(CantonTimestamp.now(), limit = PageLimit.tryCreate(3))
            .futureValue should be(expected)
        }
      }

    }

    "listInitialPaymentConfirmationByAnsName" should {

      "find the confirmation by the ans name" in {
        val goodAnsName = ansEntryContext(1, "good")
        val goodConfirmations =
          (1 to 3).map(n => confirmation(n, ansEntryContextPaymentAction(goodAnsName.contractId)))
        val badAnsName = ansEntryContext(2, "bad")
        val badConfirmations =
          (4 to 6).map(n => confirmation(n, ansEntryContextPaymentAction(badAnsName.contractId)))
        val unrelatedConfirmations = (7 to 9).map(n => confirmation(n, addUser666Action))

        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(
            Seq(goodAnsName, badAnsName)
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          _ <- MonadUtil.sequentialTraverse(
            goodConfirmations ++ badConfirmations ++ unrelatedConfirmations
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listInitialPaymentConfirmationByAnsName(
            storeSvParty,
            "good",
          )
        } yield result should contain theSameElementsAs (goodConfirmations)
      }

    }

    "listMemberTrafficContracts" should {

      "list all MemberTraffic contracts of a member" in {
        val namespace = Namespace(Fingerprint.tryFromString(s"dummy"))
        val goodMember = ParticipantId("good", namespace)
        val badMember = MediatorId(UniqueIdentifier.tryCreate("bad", namespace))
        val goodContracts = (1 to 3).map(n => memberTraffic(goodMember, dummyDomain, n.toLong))
        val badContracts = (4 to 6).map(n => memberTraffic(badMember, dummyDomain, n.toLong)) ++
          (7 to 9).map(n => memberTraffic(goodMember, dummy2Domain, n.toLong))
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(
            goodContracts ++ badContracts
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listMemberTrafficContracts(
            goodMember,
            dummyDomain,
            PageLimit.tryCreate(100),
          )
        } yield result should contain theSameElementsAs goodContracts
      }

    }

    "getTotalPurchasedMemberTraffic" should {

      "return the sum over all traffic contracts for the member" in {
        val namespace = Namespace(Fingerprint.tryFromString(s"dummy"))
        val goodMember = ParticipantId("good", namespace)
        val badMember = MediatorId(UniqueIdentifier.tryCreate("bad", namespace))
        val goodContracts = (1 to 3).map(n => memberTraffic(goodMember, dummyDomain, n.toLong))
        val badContracts = (4 to 6).map(n => memberTraffic(badMember, dummyDomain, n.toLong)) ++
          (7 to 9).map(n => memberTraffic(goodMember, dummy2Domain, n.toLong))
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(
            goodContracts ++ badContracts
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.getTotalPurchasedMemberTraffic(
            goodMember,
            dummyDomain,
          )
        } yield result shouldBe (1 to 3).sum.toLong
      }

    }

    "listSvRewardState" should {
      "list SvRewardState" in {

        val sv1RewardState1 = svRewardState("sv1")
        val sv1RewardState2 = svRewardState("sv1")
        val sv2RewardState = svRewardState("sv2")
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(Seq(sv1RewardState1, sv1RewardState2, sv2RewardState))(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          sv1RewardStates <- store.listSvRewardStates("sv1", Limit.DefaultLimit)
          sv2RewardStates <- store.listSvRewardStates("sv2", Limit.DefaultLimit)
        } yield {
          sv1RewardStates.map(_.contractId).toSet shouldBe Set(
            sv1RewardState1.contractId,
            sv1RewardState2.contractId,
          )
          sv2RewardStates.map(_.contractId).toSet shouldBe Set(sv2RewardState.contractId)
        }
      }
    }

    "listExpiredTransferPreapprovals" should {

      "return all expired transfer pre-approvals" in {
        val expired = (1 to 3).map(n =>
          transferPreapproval(
            userParty(n),
            providerParty(n),
            time(0),
            expiresAt = time(n.toLong),
          )
        )
        val notExpired =
          (4 to 6).map(n =>
            transferPreapproval(
              userParty(n),
              providerParty(n),
              time(0),
              expiresAt = time(n.toLong),
            )
          )
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(expired ++ notExpired)(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listExpiredTransferPreapprovals(
            time(4),
            PageLimit.tryCreate(100),
          )(
            traceContext
          )
        } yield {
          val contracts = result.map(_.contract)
          contracts should contain theSameElementsAs expired
        }
      }

    }
  }

  lazy val addUser667Action = new ARC_DsoRules(
    new SRARC_AddSv(
      new DsoRules_AddSv(
        userParty(667).toProtoPrimitive,
        "user667",
        SvUtil.DefaultSV1Weight,
        "user667ParticipantId",
        new Round(1L),
      )
    )
  )

  lazy val removeUserAction = new ARC_DsoRules(
    new SRARC_OffboardSv(new DsoRules_OffboardSv(userParty(666).toProtoPrimitive))
  )

  private def ansEntryContextPaymentAction(
      contextCid: AnsEntryContext.ContractId,
      isAccepted: Boolean = true,
      paymentId: SubscriptionInitialPayment.ContractId =
        new SubscriptionInitialPayment.ContractId(validContractId(1)),
  ): ActionRequiringConfirmation = {
    val appTransferContext = new AppTransferContext(
      new splice.amuletrules.AmuletRules.ContractId(validContractId(1)),
      new splice.round.OpenMiningRound.ContractId(validContractId(1)),
      Optional.empty(),
    )
    new ARC_AnsEntryContext(
      contextCid,
      if (isAccepted)
        new ANSRARC_CollectInitialEntryPayment(
          new AnsEntryContext_CollectInitialEntryPayment(
            paymentId,
            appTransferContext,
            new AnsRules.ContractId(validContractId(1)),
          )
        )
      else
        new ANSRARC_RejectEntryInitialPayment(
          new AnsEntryContext_RejectEntryInitialPayment(
            paymentId,
            appTransferContext,
            new AnsRules.ContractId(validContractId(1)),
          )
        ),
    )
  }

  private def createMiningRoundsTriple(store: SvDsoStore, startRound: Long = 1L): Future[
    (
        Contract[OpenMiningRound.ContractId, OpenMiningRound],
        Contract[OpenMiningRound.ContractId, OpenMiningRound],
        Contract[OpenMiningRound.ContractId, OpenMiningRound],
    )
  ] = {
    val oldest = openMiningRound(dsoParty, startRound, 1.0)
    val middle = openMiningRound(dsoParty, startRound + 1, 2.0)
    val newest = openMiningRound(dsoParty, startRound + 2, 3.0)
    val all = Random.shuffle(Seq(oldest, middle, newest))
    MonadUtil
      .sequentialTraverse(all)(dummyDomain.create(_)(store.multiDomainAcsStore))
      .map(_ => (oldest, middle, newest))
  }

  private def confirmation(n: Int, action: ActionRequiringConfirmation) = {
    val template = new Confirmation(
      dsoParty.toProtoPrimitive,
      storeSvParty.toProtoPrimitive,
      action,
      Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(3600),
    )
    contract(
      Confirmation.TEMPLATE_ID_WITH_PACKAGE_ID,
      new Confirmation.ContractId(validContractId(n)),
      template,
    )
  }

  def dsoRules(
      svs: java.util.Map[String, SvInfo] = Collections.emptyMap(),
      epoch: Long = 123,
  ) = {
    val newSynchronizerId = "new-domain-id"
    val template = new DsoRules(
      dsoParty.toProtoPrimitive,
      epoch,
      svs,
      Collections.emptyMap(),
      storeSvParty.toProtoPrimitive,
      new DsoRulesConfig(
        1,
        1,
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new SynchronizerNodeConfigLimits(new CometBftConfigLimits(1, 1, 1, 1, 1)),
        1,
        new DsoDecentralizedSynchronizerConfig(
          Collections.emptyMap(),
          newSynchronizerId,
          newSynchronizerId,
        ),
        Optional.empty(),
        Optional.empty(), // voteCooldownTime`
      ),
      Collections.emptyMap(),
      true,
    )

    contract(
      DsoRules.TEMPLATE_ID_WITH_PACKAGE_ID,
      new DsoRules.ContractId(validContractId(1)),
      template,
    )
  }

  private def memberTraffic(
      member: Member,
      synchronizerId: SynchronizerId,
      totalPurchased: Long,
  ) = {
    val template = new MemberTraffic(
      dsoParty.toProtoPrimitive,
      member.toProtoPrimitive,
      synchronizerId.toProtoPrimitive,
      domainMigrationId,
      totalPurchased,
      1,
      numeric(1.0),
      numeric(1.0),
    )

    contract(
      MemberTraffic.TEMPLATE_ID_WITH_PACKAGE_ID,
      new MemberTraffic.ContractId(nextCid()),
      template,
    )
  }

  private def ansEntry(
      user: PartyId,
      name: String,
      expiresAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(3600),
  ) = {
    val template = new AnsEntry(
      user.toProtoPrimitive,
      dsoParty.toProtoPrimitive,
      name,
      s"https://example.com/$name",
      s"Test with $name",
      expiresAt,
    )

    contract(
      AnsEntry.TEMPLATE_ID_WITH_PACKAGE_ID,
      new AnsEntry.ContractId(nextCid()),
      template,
    )
  }

  private def ansEntryContext(n: Int, name: String) = {
    val template = new AnsEntryContext(
      dsoParty.toProtoPrimitive,
      userParty(n).toProtoPrimitive,
      name,
      s"https://example.com/$name",
      s"Test with $name",
      new SubscriptionRequest.ContractId(validContractId(n, "ab")),
    )

    contract(
      AnsEntryContext.TEMPLATE_ID_WITH_PACKAGE_ID,
      new AnsEntryContext.ContractId(validContractId(n, "cc")),
      template,
    )
  }

  private def subscriptionIdleState(
      n: Int,
      nextPaymentDueAt: Instant,
      entryDescription: String = "Sample fake description",
  ) = {
    val templateId = SubscriptionIdleState.TEMPLATE_ID_WITH_PACKAGE_ID
    val template = new SubscriptionIdleState(
      new Subscription.ContractId(validContractId(n, "aa")),
      new SubscriptionData(
        userParty(n).toProtoPrimitive,
        dsoParty.toProtoPrimitive,
        dsoParty.toProtoPrimitive,
        dsoParty.toProtoPrimitive,
        entryDescription,
      ),
      new SubscriptionPayData(
        new PaymentAmount(numeric(BigDecimal("1")), Unit.AMULETUNIT),
        new RelTime(1_000_000_000L),
        new RelTime(1_000_000L),
      ),
      nextPaymentDueAt,
      new SubscriptionRequest.ContractId(validContractId(n, "ab")),
    )
    contract(
      identifier = templateId,
      contractId = new SubscriptionIdleState.ContractId(s"$domain#$n"),
      payload = template,
    )
  }

  private def svOnboardingRequest(
      name: String,
      candidate: PartyId,
      participantId: String,
      token: String,
      expiry: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(3600),
  ) = {
    val template = new SvOnboardingRequest(
      name,
      candidate.toProtoPrimitive,
      participantId,
      token,
      storeSvParty.toProtoPrimitive,
      dsoParty.toProtoPrimitive,
      expiry,
    )

    contract(
      SvOnboardingRequest.TEMPLATE_ID_WITH_PACKAGE_ID,
      new SvOnboardingRequest.ContractId(nextCid()),
      template,
    )
  }

  private def svOnboardingConfirmed(
      name: String,
      candidate: PartyId,
      participantId: String,
      expiry: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(3600),
  ) = {
    val template = new SvOnboardingConfirmed(
      candidate.toProtoPrimitive,
      name,
      SvUtil.DefaultSV1Weight,
      participantId,
      "reason",
      dsoParty.toProtoPrimitive,
      expiry,
    )

    contract(
      SvOnboardingConfirmed.TEMPLATE_ID_WITH_PACKAGE_ID,
      new SvOnboardingConfirmed.ContractId(nextCid()),
      template,
    )
  }

  protected def mkStore(): Future[SvDsoStore]

  lazy val acsOffset = nextOffset()
  lazy val domain = dummyDomain.toProtoPrimitive
  lazy val storeSvParty = providerParty(42)
}

class DbSvDsoStoreTest
    extends SvDsoStoreTest
    with HasActorSystem
    with SplicePostgresTest
    with AcsJdbcTypes
    with AcsTables {

  override protected def mkStore(): Future[DbSvDsoStore] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all ++
          DarResources.validatorLifecycle.all ++
          DarResources.dsoGovernance.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val store = new DbSvDsoStore(
      SvStore.Key(storeSvParty, dsoParty),
      storage,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      DomainMigrationInfo(
        domainMigrationId,
        None,
      ),
      participantId = mkParticipantId("SvDsoStoreTest"),
    )(parallelExecutionContext, implicitly, implicitly)
    for {
      _ <- store.multiDomainAcsStore.testIngestionSink.initialize()
      _ <- store.multiDomainAcsStore.testIngestionSink
        .ingestAcs(acsOffset, Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(SynchronizerAlias.tryCreate(domain) -> dummyDomain)
      )
    } yield store
  }

  "listVoteRequestsReadyToBeClosed" should {

    val votesAccept =
      (1 to 4).map(n =>
        new Vote(userParty(n).toProtoPrimitive, true, new Reason("", ""), Optional.empty())
      )
    val votesRefuse =
      (1 to 4).map(n =>
        new Vote(userParty(n).toProtoPrimitive, false, new Reason("", ""), Optional.empty())
      )
    val nowMinus2Hours = Instant.now.truncatedTo(ChronoUnit.MICROS).minusSeconds(7200)
    val nowMinus1Hour = Instant.now.truncatedTo(ChronoUnit.MICROS).minusSeconds(3600)
    val nowPlus1Hour = Instant.now.truncatedTo(ChronoUnit.MICROS).plusSeconds(3600)
    val nowPlus2Hours = Instant.now.truncatedTo(ChronoUnit.MICROS).plusSeconds(7200)

    "list only vote requests without `targetEffectiveAt` that are ready to be closed" in {
      val readyToBeClosed = Seq(
        // expiry reached
        voteRequest(requester = userParty(1), votes = votesAccept, expiry = nowMinus1Hour),
        voteRequest(requester = userParty(1), votes = votesRefuse, expiry = nowMinus1Hour),
        voteRequest(requester = userParty(2), votes = Seq.empty, expiry = nowMinus1Hour),
        // expiry not reached, but early closing
        voteRequest(requester = userParty(3), votes = votesAccept, expiry = nowPlus1Hour),
        voteRequest(requester = userParty(3), votes = votesRefuse, expiry = nowPlus1Hour),
      )
      val notReadyToBeClosed = Seq(
        voteRequest(requester = userParty(1), votes = Seq.empty, expiry = nowPlus1Hour)
      )
      val svs = Map(
        "sv1" -> new SvInfo("sv1", new Round(0L), 1L, "df"),
        "sv2" -> new SvInfo("sv2", new Round(0L), 1L, "df"),
        "sv3" -> new SvInfo("sv3", new Round(0L), 1L, "df"),
        "sv4" -> new SvInfo("sv4", new Round(0L), 1L, "df"),
      ).asJava
      for {
        store <- mkStore()
        _ <- dummyDomain.create(dsoRules(svs))(store.multiDomainAcsStore)
        _ <- dummyDomain.create(amuletRules())(store.multiDomainAcsStore)
        _ <- MonadUtil.sequentialTraverse(readyToBeClosed ++ notReadyToBeClosed)(
          dummyDomain.create(_)(store.multiDomainAcsStore)
        )
        result <- store.listVoteRequestsReadyToBeClosed(
          CantonTimestamp.now(),
          PageLimit.tryCreate(100),
        )(traceContext)
      } yield {
        val contracts = result.map(_.contract)
        contracts should contain theSameElementsAs readyToBeClosed
      }
    }

    "list only vote requests with `targetEffectiveAt` that are ready to be closed" in {
      val readyToBeClosed = Seq(
        // effectiveAt reached
        voteRequest(
          requester = userParty(1),
          votes = votesAccept,
          expiry = nowMinus2Hours,
          effectiveAt = Optional.of(nowMinus1Hour),
        ),
        voteRequest(
          requester = userParty(1),
          votes = votesRefuse,
          expiry = nowMinus2Hours,
          effectiveAt = Optional.of(nowMinus1Hour),
        ),
        voteRequest(
          requester = userParty(2),
          votes = Seq.empty,
          expiry = nowMinus2Hours,
          effectiveAt = Optional.of(nowMinus1Hour),
        ),
        // between expiration and effectiveAt
        voteRequest(
          requester = userParty(2),
          votes = Seq.empty,
          expiry = nowMinus1Hour,
          effectiveAt = Optional.of(nowPlus1Hour),
        ),
        // early closing only possible if super-majority refuse
        voteRequest(
          requester = userParty(2),
          votes = votesRefuse,
          expiry = nowMinus1Hour,
          effectiveAt = Optional.of(nowPlus1Hour),
        ),
        voteRequest(
          requester = userParty(1),
          votes = votesRefuse,
          expiry = nowPlus1Hour,
          effectiveAt = Optional.of(nowPlus2Hours),
        ),
      )
      val notReadyToBeClosed = Seq(
        // effectiveAt not reached
        voteRequest(
          requester = userParty(1),
          votes = votesAccept,
          expiry = nowPlus1Hour,
          effectiveAt = Optional.of(nowPlus2Hours),
        ),
        voteRequest(
          requester = userParty(2),
          votes = Seq.empty,
          expiry = nowPlus1Hour,
          effectiveAt = Optional.of(nowPlus2Hours),
        ),
        // between expiration and effectiveAt
        voteRequest(
          requester = userParty(2),
          votes = votesAccept,
          expiry = nowMinus1Hour,
          effectiveAt = Optional.of(nowPlus1Hour),
        ),
      )
      val svs = Map(
        "sv1" -> new SvInfo("sv1", new Round(0L), 1L, "df"),
        "sv2" -> new SvInfo("sv2", new Round(0L), 1L, "df"),
        "sv3" -> new SvInfo("sv3", new Round(0L), 1L, "df"),
        "sv4" -> new SvInfo("sv4", new Round(0L), 1L, "df"),
      ).asJava
      for {
        store <- mkStore()
        _ <- dummyDomain.create(dsoRules(svs))(store.multiDomainAcsStore)
        _ <- dummyDomain.create(amuletRules())(store.multiDomainAcsStore)
        _ <- MonadUtil.sequentialTraverse(readyToBeClosed ++ notReadyToBeClosed)(
          dummyDomain.create(_)(store.multiDomainAcsStore)
        )
        result <- store.listVoteRequestsReadyToBeClosed(
          CantonTimestamp.now(),
          PageLimit.tryCreate(100),
        )(traceContext)
      } yield {
        val contracts = result.map(_.contract)
        contracts should contain theSameElementsAs readyToBeClosed
      }
    }

  }

  "listExpiredVoteRequests" should {

    "return all vote requests that are expired as of now" in {
      val expired = (1 to 3).map(n =>
        voteRequest(
          requester = userParty(n),
          votes = Seq.empty,
          expiry = Instant.now.truncatedTo(ChronoUnit.MICROS).minusSeconds(n.toLong * 3600),
        )
      )
      val notExpired =
        (4 to 6).map(n => voteRequest(requester = userParty(n), votes = Seq.empty))
      for {
        store <- mkStore()
        _ <- MonadUtil.sequentialTraverse(expired ++ notExpired)(
          dummyDomain.create(_)(store.multiDomainAcsStore)
        )
        result <- store.listExpiredVoteRequests()(
          CantonTimestamp.now(),
          PageLimit.tryCreate(100),
        )(traceContext)
      } yield {
        val contracts = result.map(_.contract)
        contracts should contain theSameElementsAs expired
      }
    }
  }

  "listVotesByVoteRequests" should {

    "return all votes by their VoteRequest contract ids" in {
      val goodVotes = (1 to 3).map(n =>
        Seq(n, n + 3)
          .map(i =>
            new Vote(userParty(i).toProtoPrimitive, true, new Reason("", ""), Optional.empty())
          )
      )
      val badVotes = (1 to 3).map(n =>
        Seq(n)
          .map(i =>
            new Vote(userParty(i).toProtoPrimitive, true, new Reason("", ""), Optional.empty())
          )
      )
      val goodVoteRequests =
        (1 to 3).map(n =>
          voteRequest(
            requester = userParty(n),
            votes = goodVotes(n - 1),
          )
        )
      val badVoteRequests =
        (4 to 6).map(n => voteRequest(requester = userParty(n), votes = badVotes(n - 4)))
      for {
        store <- mkStore()
        _ <- MonadUtil.sequentialTraverse(goodVoteRequests ++ badVoteRequests)(
          dummyDomain.create(_)(store.multiDomainAcsStore)
        )
        result <- store.listVoteRequestsByTrackingCid(goodVoteRequests.map(_.contractId))
        votes = result.flatMap(_.payload.votes.values().asScala)
      } yield {
        votes should contain theSameElementsAs (goodVotes.flatten)
      }
    }
  }

  "lookupVoteRequestByThisSvAndActionWithOffset" should {

    "find the vote request done by this SV and with the passed action" in {
      val goodAction = addUser666Action
      val goodVoteRequest =
        voteRequest(
          action = goodAction,
          requester = storeSvParty,
          votes = Seq.empty,
        )
      val doneByAnotherSV =
        voteRequest(
          action = goodAction,
          requester = providerParty(1234),
          votes = Seq.empty,
        )
      val differentAction =
        voteRequest(
          action = addUser667Action,
          requester = storeSvParty,
          votes = Seq.empty,
        )
      for {
        store <- mkStore()
        _ <- dummyDomain.create(doneByAnotherSV)(store.multiDomainAcsStore)
        _ <- dummyDomain.create(differentAction)(store.multiDomainAcsStore)
        _ <- dummyDomain.create(goodVoteRequest)(store.multiDomainAcsStore)
        result <- store.lookupVoteRequestByThisSvAndActionWithOffset(goodAction)
      } yield {
        result.value should be(Some(goodVoteRequest))
      }
    }

  }

  "lookupVoteByThisSvAndVoteRequestWithOffset" should {

    "find the vote by vote request done by this SV" in {
      val goodVote =
        new Vote(storeSvParty.toProtoPrimitive, true, new Reason("", ""), Optional.empty())
      val goodRequest = voteRequest(
        requester = storeSvParty,
        votes = Seq(goodVote),
      )
      val badRequest = voteRequest(
        requester = providerParty(1234),
        votes = Seq(goodVote),
      )
      for {
        store <- mkStore()
        _ <- dummyDomain.create(goodRequest)(store.multiDomainAcsStore)
        _ <- dummyDomain.create(badRequest)(store.multiDomainAcsStore)
        result <- store.lookupVoteByThisSvAndVoteRequestWithOffset(goodRequest.contractId)
      } yield {
        result.value should be(Some(goodVote))
      }
    }
  }

  "lookupAmuletConversionRateFeed" in {
    val publisher1 = providerParty(1)
    val publisher2 = providerParty(2)
    def conversionRateFeed(publisher: PartyId, rate: BigDecimal) = {
      val payload =
        new amuletconversionratefeed.AmuletConversionRateFeed(
          publisher.toProtoPrimitive,
          dsoParty.toProtoPrimitive,
          java.util.Optional.empty(),
          rate.bigDecimal.setScale(10),
        )
      contract(
        amuletconversionratefeed.AmuletConversionRateFeed.TEMPLATE_ID_WITH_PACKAGE_ID,
        new amuletconversionratefeed.AmuletConversionRateFeed.ContractId(nextCid()),
        payload,
      )
    }
    val publisher1Feed1 = conversionRateFeed(publisher1, BigDecimal(42.0))
    val publisher2Feed1 = conversionRateFeed(publisher2, BigDecimal(23.0))
    val publisher1Feed2 = conversionRateFeed(publisher1, BigDecimal(23.0))
    for {
      store <- mkStore()
      _ <- dummyDomain.create(publisher1Feed1)(
        store.multiDomainAcsStore
      )
      result <- store.lookupAmuletConversionRateFeed(publisher1)
      _ = result shouldBe Some(publisher1Feed1)
      result <- store.lookupAmuletConversionRateFeed(publisher2)
      _ = result shouldBe None
      _ <- dummyDomain.create(publisher2Feed1)(
        store.multiDomainAcsStore
      )
      result <- store.lookupAmuletConversionRateFeed(publisher2)
      _ = result shouldBe Some(publisher2Feed1)
      _ <- dummyDomain.create(publisher1Feed2)(
        store.multiDomainAcsStore
      )
      result <- store.lookupAmuletConversionRateFeed(publisher1)
      _ = result shouldBe Some(publisher1Feed2)
    } yield succeed
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = resetAllAppTables(storage)
}

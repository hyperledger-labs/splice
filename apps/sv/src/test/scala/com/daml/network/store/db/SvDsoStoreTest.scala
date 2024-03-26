package com.daml.network.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.DamlRecord
import com.daml.network.codegen.java.splice
import com.daml.network.codegen.java.splice.amuletrules.{
  AppTransferContext,
  AmuletRules_MiningRound_Archive,
}
import com.daml.network.codegen.java.splice.globaldomain.MemberTraffic
import com.daml.network.codegen.java.splice.round.OpenMiningRound
import com.daml.network.codegen.java.splice.types.Round
import com.daml.network.codegen.java.cn.ans.*
import com.daml.network.codegen.java.cn.cometbft.CometBftConfigLimits
import com.daml.network.codegen.java.cn.dso.globaldomain.{
  DomainNodeConfigLimits,
  DsoGlobalDomainConfig,
}
import com.daml.network.codegen.java.cn.dsorules.*
import com.daml.network.codegen.java.cn.dsorules.actionrequiringconfirmation.{
  ARC_AnsEntryContext,
  ARC_AmuletRules,
  ARC_DsoRules,
}
import com.daml.network.codegen.java.cn.dsorules.ansentrycontext_actionrequiringconfirmation.{
  ANSRARC_CollectInitialEntryPayment,
  ANSRARC_RejectEntryInitialPayment,
}
import com.daml.network.codegen.java.cn.dsorules.amuletrules_actionrequiringconfirmation.CRARC_MiningRound_Archive
import com.daml.network.codegen.java.cn.dsorules.electionrequestreason.ERR_OtherReason
import com.daml.network.codegen.java.cn.dsorules.dsorules_actionrequiringconfirmation.{
  SRARC_AddMember,
  SRARC_OffboardMember,
}
import com.daml.network.codegen.java.cn.dsorules.voterequestoutcome.VRO_Accepted
import com.daml.network.codegen.java.cn.svonboarding.{SvOnboardingConfirmed, SvOnboardingRequest}
import com.daml.network.codegen.java.cn.wallet.payment.{Currency, PaymentAmount}
import com.daml.network.codegen.java.cn.wallet.subscriptions.{
  Subscription,
  SubscriptionData,
  SubscriptionIdleState,
  SubscriptionInitialPayment,
  SubscriptionPayData,
  SubscriptionRequest,
}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.environment.{DarResources, RetryProvider}
import com.daml.network.store.{Limit, PageLimit, StoreTest}
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.config.{SvDomainConfig, SvGlobalDomainConfig}
import com.daml.network.sv.history.DsoRulesCloseVoteRequest
import com.daml.network.sv.store.db.DbSvDsoStore
import com.daml.network.sv.store.SvDsoStore.IdleAnsSubscription
import com.daml.network.sv.store.{SvStore, SvDsoStore}
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.{
  AssignedContract,
  Contract,
  ResourceTemplateDecoder,
  TemplateJsonDecoder,
}
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory.NoOpMetricsFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.util.MonadUtil

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util
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

    lookupTests("lookupDsoRulesWithOffset")(dsoRules())(_.lookupDsoRulesWithOffset())
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
    lookupTests("lookupElectionRequestByRequesterWithOffset")(
      create = electionRequest(userParty(1), epoch = 1),
      noise = Seq(
        electionRequest(userParty(2), epoch = 2),
        electionRequest(userParty(1), epoch = 2),
        electionRequest(userParty(2), epoch = 1),
      ),
    )(
      _.lookupElectionRequestByRequesterWithOffset(userParty(1), epoch = 1)
    )
    lookupTests("lookupAnsEntryByNameWithOffset")(
      create = ansEntry(userParty(1), "good"),
      noise = Seq(ansEntry(userParty(2), "bad")),
    )(
      _.lookupAnsEntryByNameWithOffset("good")
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
          SvDsoStore.OpenMiningRoundTriple(
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
          result <- store.listExpiredAmulets(CantonTimestamp.now(), PageLimit.tryCreate(100))(
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
          result <- store.listExpiredAmulets(CantonTimestamp.now(), PageLimit.tryCreate(100))(
            traceContext
          )
        } yield {
          val contracts = result.map(_.contract)
          contracts should contain theSameElementsAs Seq(expiresAtRound3)
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
          result <- store.listLockedExpiredAmulets(CantonTimestamp.now(), PageLimit.tryCreate(100))(
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
          result <- store.listLockedExpiredAmulets(CantonTimestamp.now(), PageLimit.tryCreate(100))(
            traceContext
          )
        } yield {
          val contracts = result.map(_.contract)
          contracts should contain theSameElementsAs Seq(expiresAtRound3)
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

    "listAppRewardCouponsGroupedByCounterparty" should {

      "return all app reward coupons in a round grouped by counterparty" in {
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
          result <- store.listAppRewardCouponsGroupedByCounterparty(
            roundNumber = 3,
            roundDomain = dummyDomain,
            totalCouponsLimit = PageLimit.tryCreate(1000),
          )
        } yield {
          result.map(_.toSet).toSet should be(
            Set(
              provider1InRound.map(_.contractId).toSet,
              provider2InRound.map(_.contractId).toSet,
            )
          )
        }
      }

    }

    "listValidatorRewardCouponsGroupedByCounterparty" should {

      "return all validator reward coupons in a round grouped by counterparty" in {
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
          result <- store.listValidatorRewardCouponsGroupedByCounterparty(
            roundNumber = 3,
            roundDomain = dummyDomain,
            totalCouponsLimit = PageLimit.tryCreate(1000),
          )
        } yield {
          result.map(_.toSet).toSet should be(
            Set(
              provider1InRound.map(_.contractId).toSet,
              provider2InRound.map(_.contractId).toSet,
            )
          )
        }
      }

    }

    "listValidatorFaucetCouponsGroupedByCounterparty" should {

      "return all validator faucet coupons in a round grouped by counterparty" in {
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
          result <- store.listValidatorFaucetCouponsGroupedByCounterparty(
            roundNumber = 3,
            roundDomain = dummyDomain,
            totalCouponsLimit = PageLimit.tryCreate(1000),
          )
        } yield {
          result.map(_.toSet).toSet should be(
            Set(
              validator1InRound.map(_.contractId).toSet,
              validator2InRound.map(_.contractId).toSet,
            )
          )
        }
      }

    }

    "listArchivableClosedMiningRounds" should {

      "return all ClosedMiningRounds without left-over reward coupons and with no ready-to-be-archived confirmation" in {
        val goodClosed = (1 to 3).map(n => closedMiningRound(dsoParty, n.toLong))
        val hasValidatorCoupon = closedMiningRound(dsoParty, round = 4)
        val hasAppCoupon = closedMiningRound(dsoParty, round = 5)
        val hasConfirmation = closedMiningRound(dsoParty, round = 6)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(dsoRules())(store.multiDomainAcsStore)
          _ <- MonadUtil.sequentialTraverse(
            goodClosed :+ hasValidatorCoupon :+ hasAppCoupon :+ hasConfirmation
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
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

    "listElectionRequests" should {

      "return all election requests for the given dsoRules" in {
        import scala.jdk.CollectionConverters.*
        val goodDsoRules = dsoRules(
          members = (1 to 3)
            .map { n =>
              userParty(n).toProtoPrimitive -> memberInfo(n.toString)
            }
            .toMap
            .asJava,
          epoch = 1,
        )
        val goodElectionRequests =
          (1 to 3).map(n => electionRequest(userParty(n) /*member of good dsorules*/, epoch = 1))
        val electionRequestOtherMember = electionRequest(userParty(666), epoch = 1)
        val electionRequestOtherEpoch = electionRequest(userParty(1), epoch = 2)

        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(
            goodElectionRequests :+ electionRequestOtherMember :+ electionRequestOtherEpoch
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listElectionRequests(AssignedContract(goodDsoRules, dummyDomain))(
            traceContext
          )
        } yield {
          result should contain theSameElementsAs goodElectionRequests
        }
      }

    }

    "listExpiredElectionRequests" should {

      "return all election requests with a smaller epoch than provided" in {
        val electionRequests = (1 to 5).map(n => electionRequest(userParty(n), epoch = n.toLong))

        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(electionRequests)(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listExpiredElectionRequests(epoch = 4)(traceContext)
        } yield {
          result should contain theSameElementsAs electionRequests.take(3)
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

    "listLaggingDsoRulesFollowers" should {
      "list followers" in {

        val leaderContract = dsoRules()
        val followerContract1 = amuletRules()
        val followerContract2 = memberRewardState("sv1")
        val alreadyReassigned = memberRewardState("sv2")
        for {
          store <- mkStore()
          _ <- dummyDomain.create(leaderContract)(store.multiDomainAcsStore)
          _ <- dummy2Domain.create(followerContract1)(store.multiDomainAcsStore)
          _ <- dummy2Domain.create(followerContract2)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(alreadyReassigned)(store.multiDomainAcsStore)
          result <- store.listDsoRulesTransferFollowers()
        } yield result.map(x =>
          x.leader.contractId -> x.follower.contractId
        ) should contain theSameElementsAs Seq(
          leaderContract.contractId -> followerContract1.contractId,
          leaderContract.contractId -> followerContract2.contractId,
        )
      }
    }

    "listAmuletRulesTransferFollowers" should {
      "list followers" in {

        val leaderContract = amuletRules()
        val followerContract1 = openMiningRound(dsoParty, 3, 1.0)
        val followerContract2 = closedMiningRound(dsoParty, 1)
        val alreadyReassigned = closedMiningRound(dsoParty, 2)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(leaderContract)(store.multiDomainAcsStore)
          _ <- dummy2Domain.create(followerContract1)(store.multiDomainAcsStore)
          _ <- dummy2Domain.create(followerContract2)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(alreadyReassigned)(store.multiDomainAcsStore)
          result <- store.listAmuletRulesTransferFollowers()
        } yield result.map(x =>
          x.leader.contractId -> x.follower.contractId
        ) should contain theSameElementsAs Seq(
          leaderContract.contractId -> followerContract1.contractId,
          leaderContract.contractId -> followerContract2.contractId,
        )
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
        val namespace = Namespace(Fingerprint.tryCreate(s"dummy"))
        val goodMember = ParticipantId(Identifier.tryCreate("good"), namespace)
        val badMember = MediatorId(Identifier.tryCreate("bad"), namespace)
        val goodContracts = (1 to 3).map(n => memberTraffic(goodMember, n.toLong))
        val badContracts = (4 to 6).map(n => memberTraffic(badMember, n.toLong))
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
        val namespace = Namespace(Fingerprint.tryCreate(s"dummy"))
        val goodMember = ParticipantId(Identifier.tryCreate("good"), namespace)
        val badMember = MediatorId(Identifier.tryCreate("bad"), namespace)
        val goodContracts = (1 to 3).map(n => memberTraffic(goodMember, n.toLong))
        val badContracts = (4 to 6).map(n => memberTraffic(badMember, n.toLong))
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

  }

  lazy val addUser666Action = new ARC_DsoRules(
    new SRARC_AddMember(
      new DsoRules_AddMember(
        userParty(666).toProtoPrimitive,
        "user666",
        SvUtil.DefaultFoundingNodeWeight,
        "user666ParticipantId",
        new Round(1L),
      )
    )
  )

  lazy val addUser667Action = new ARC_DsoRules(
    new SRARC_AddMember(
      new DsoRules_AddMember(
        userParty(667).toProtoPrimitive,
        "user667",
        SvUtil.DefaultFoundingNodeWeight,
        "user667ParticipantId",
        new Round(1L),
      )
    )
  )

  lazy val removeUserAction = new ARC_DsoRules(
    new SRARC_OffboardMember(new DsoRules_OffboardMember(userParty(666).toProtoPrimitive))
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
      Confirmation.TEMPLATE_ID,
      new Confirmation.ContractId(validContractId(n)),
      template,
    )
  }

  def dsoRules(
      members: java.util.Map[String, MemberInfo] = Collections.emptyMap(),
      epoch: Long = 123,
  ) = {
    val newDomainId = "new-domain-id"
    val template = new DsoRules(
      dsoParty.toProtoPrimitive,
      epoch,
      members,
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
        new DomainNodeConfigLimits(new CometBftConfigLimits(1, 1, 1, 1, 1)),
        1,
        new DsoGlobalDomainConfig(Collections.emptyMap(), newDomainId, newDomainId),
        Optional.empty(),
      ),
      Collections.emptyMap(),
      true,
    )

    contract(
      DsoRules.TEMPLATE_ID,
      new DsoRules.ContractId(validContractId(1)),
      template,
    )
  }

  private def memberInfo(name: String) = {
    new MemberInfo(
      name,
      new Round(1L),
      789L,
      s"PAR::${name}::12345",
    )
  }

  private def memberTraffic(member: Member, totalPurchased: Long) = {
    val template = new MemberTraffic(
      dsoParty.toProtoPrimitive,
      member.toProtoPrimitive,
      dummyDomain.toProtoPrimitive,
      domainMigrationId,
      totalPurchased,
      1,
      numeric(1.0),
      numeric(1.0),
    )

    contract(
      MemberTraffic.TEMPLATE_ID,
      new MemberTraffic.ContractId(nextCid()),
      template,
    )
  }

  private def electionRequest(requester: PartyId, epoch: Long) = {
    val template = new ElectionRequest(
      dsoParty.toProtoPrimitive,
      requester.toProtoPrimitive,
      epoch,
      new ERR_OtherReason("test"),
      Collections.emptyList(),
    )

    contract(
      ElectionRequest.TEMPLATE_ID,
      new ElectionRequest.ContractId(nextCid()),
      template,
    )
  }

  private def ansEntry(user: PartyId, name: String) = {
    val template = new AnsEntry(
      user.toProtoPrimitive,
      dsoParty.toProtoPrimitive,
      name,
      s"https://example.com/$name",
      s"Test with $name",
      Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(3600),
    )

    contract(
      AnsEntry.TEMPLATE_ID,
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
      AnsEntryContext.TEMPLATE_ID,
      new AnsEntryContext.ContractId(validContractId(n, "cc")),
      template,
    )
  }

  private def subscriptionIdleState(
      n: Int,
      nextPaymentDueAt: Instant,
      entryDescription: String = "Sample fake description",
  ) = {
    val templateId = SubscriptionIdleState.TEMPLATE_ID
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
        new PaymentAmount(numeric(BigDecimal("1")), Currency.CC),
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
      SvOnboardingRequest.TEMPLATE_ID,
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
      SvUtil.DefaultFoundingNodeWeight,
      participantId,
      "reason",
      dsoParty.toProtoPrimitive,
      expiry,
    )

    contract(
      SvOnboardingConfirmed.TEMPLATE_ID,
      new SvOnboardingConfirmed.ContractId(nextCid()),
      template,
    )
  }

  protected def mkStore(): Future[SvDsoStore]

  lazy val acsOffset = nextOffset()
  lazy val domain = dummyDomain.toProtoPrimitive
  lazy val storeSvParty = providerParty(42)
  lazy val svDomainConfig = SvDomainConfig(
    SvGlobalDomainConfig(DomainAlias.tryCreate(domain), "https://example.com")
  )
}

class DbSvDsoStoreTest
    extends SvDsoStoreTest
    with HasActorSystem
    with CNPostgresTest
    with AcsJdbcTypes
    with AcsTables {

  override protected def mkStore(): Future[DbSvDsoStore] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.cantonAmulet.all ++
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
      domainMigrationId,
      participantId = mkParticipantId("SvDsoStoreTest"),
    )(parallelExecutionContext, implicitly, implicitly)
    for {
      _ <- store.multiDomainAcsStore.testIngestionSink.initialize()
      _ <- store.multiDomainAcsStore.testIngestionSink
        .ingestAcs(acsOffset, Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(DomainAlias.tryCreate(domain) -> dummyDomain)
      )
    } yield store
  }

  "listVoteRequestResults" should {

    "list all past VoteRequestResult" in {
      for {
        store <- mkStore()
        voteRequestContract = voteRequest(
          requester = userParty(1),
          votes = (1 to 4)
            .map(n => new Vote(userParty(n).toProtoPrimitive, true, new Reason("", ""))),
        )
        _ <- dummyDomain.create(voteRequestContract)(store.multiDomainAcsStore)
        result = mkVoteRequestResult(voteRequestContract)
        _ <- dummyDomain.exercise(
          contract = dsoRules(),
          interfaceId = Some(DsoRules.TEMPLATE_ID),
          choiceName = DsoRulesCloseVoteRequest.choice.name,
          mkCloseVoteRequest(
            voteRequestContract.contractId
          ),
          result.toValue,
        )(
          store.multiDomainAcsStore
        )
      } yield {
        store
          .listVoteRequestResults(
            Some("AddMember"),
            Some(true),
            None,
            None,
            None,
            PageLimit.tryCreate(1),
          )
          .futureValue
          .toList
          .loneElement shouldBe result
        store
          .listVoteRequestResults(
            Some("SRARC_AddMember"),
            Some(false),
            None,
            None,
            None,
            PageLimit.tryCreate(1),
          )
          .futureValue
          .toList
          .size shouldBe (0)
        store
          .listVoteRequestResults(
            None,
            None,
            None,
            None,
            None,
            PageLimit.tryCreate(1),
          )
          .futureValue
          .toList
          .size shouldBe (1)
        store
          .listVoteRequestResults(
            None,
            None,
            None,
            Some(Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(3600).toString),
            None,
            PageLimit.tryCreate(1),
          )
          .futureValue
          .toList
          .size shouldBe (0)
        store
          .listVoteRequestResults(
            None,
            None,
            None,
            Some(Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(3600).toString),
            None,
            PageLimit.tryCreate(1),
          )
          .futureValue
          .toList
          .size shouldBe (1)
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
          .map(i => new Vote(userParty(i).toProtoPrimitive, true, new Reason("", "")))
      )
      val badVotes = (1 to 3).map(n =>
        Seq(n)
          .map(i => new Vote(userParty(i).toProtoPrimitive, true, new Reason("", "")))
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
        new Vote(storeSvParty.toProtoPrimitive, true, new Reason("", ""))
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

  private def mkCloseVoteRequest(
      requestId: VoteRequest.ContractId
  ): DamlRecord = {
    new DsoRules_CloseVoteRequest(
      requestId,
      Optional.empty(),
    ).toValue
  }

  private def mkVoteRequestResult(
      voteRequestContract: Contract[VoteRequest.ContractId, VoteRequest]
  ): VoteRequestResult = new VoteRequestResult(
    voteRequestContract.payload,
    Instant.now().truncatedTo(ChronoUnit.MICROS),
    util.List.of(),
    util.List.of(),
    new VRO_Accepted(Instant.now().truncatedTo(ChronoUnit.MICROS)),
  )

  private def voteRequest(
      requester: PartyId,
      votes: Seq[Vote],
      expiry: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(3600L),
      action: ActionRequiringConfirmation = addUser666Action,
  ) = {
    val cid = new VoteRequest.ContractId(nextCid())
    val template = new VoteRequest(
      dsoParty.toProtoPrimitive,
      requester.toProtoPrimitive,
      action,
      new Reason("https://www.example.com", ""),
      expiry,
      votes.map(e => (e.sv, e)).toMap.asJava,
      Optional.of(cid),
    )

    contract(
      VoteRequest.TEMPLATE_ID,
      cid,
      template,
    )
  }

  override protected def cleanDb(storage: DbStorage): Future[?] =
    for {
      _ <- resetAllCnAppTables(storage)
    } yield ()
}

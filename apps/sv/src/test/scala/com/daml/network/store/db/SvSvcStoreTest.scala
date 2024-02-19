package com.daml.network.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.DamlRecord
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coinrules.AppTransferContext
import com.daml.network.codegen.java.cc.coinrules.CoinRules_MiningRound_Archive
import com.daml.network.codegen.java.cc.types.Round
import com.daml.network.codegen.java.cc.coinimport.importpayload.{IP_Coin, IP_ValidatorLicense}
import com.daml.network.codegen.java.cc.coinimport.{ImportCrate, ImportPayload}
import com.daml.network.codegen.java.cc.globaldomain.MemberTraffic
import com.daml.network.codegen.java.cc.round.OpenMiningRound
import com.daml.network.codegen.java.cc.validatorlicense.ValidatorLicense
import com.daml.network.codegen.java.cn.cns.*
import com.daml.network.codegen.java.cn.cometbft.CometBftConfigLimits
import com.daml.network.codegen.java.cn.svc.globaldomain.{
  DomainNodeConfigLimits,
  SvcGlobalDomainConfig,
}
import com.daml.network.sv.store.SvSvcStore.IdleCnsSubscription
import com.daml.network.codegen.java.cn.svcrules.*
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.{
  ARC_CnsEntryContext,
  ARC_CoinRules,
  ARC_SvcRules,
}
import com.daml.network.codegen.java.cn.svcrules.cnsentrycontext_actionrequiringconfirmation.{
  CNSRARC_CollectInitialEntryPayment,
  CNSRARC_RejectEntryInitialPayment,
}
import com.daml.network.codegen.java.cn.svcrules.coinrules_actionrequiringconfirmation.CRARC_MiningRound_Archive
import com.daml.network.codegen.java.cn.svcrules.electionrequestreason.ERR_OtherReason
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.{
  SRARC_AddMember,
  SRARC_RemoveMember,
}
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
import com.daml.network.environment.ParticipantAdminConnection.HasParticipantId
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.store.{Limit, PageLimit, StoreTest}
import com.daml.network.sv.config.{SvDomainConfig, SvGlobalDomainConfig}
import com.daml.network.sv.history.SvcRulesExecuteDefiniteVote
import com.daml.network.sv.store.db.DbSvSvcStore
import com.daml.network.sv.store.memory.InMemorySvSvcStore
import com.daml.network.sv.store.{SvStore, SvSvcStore}
import com.daml.network.sv.util.SvUtil.dummySvRewardWeight
import com.daml.network.util.{
  AssignedContract,
  Contract,
  ResourceTemplateDecoder,
  TemplateJsonDecoder,
}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory.NoOpMetricsFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}

import java.time.Instant
import java.util
import java.util.{Collections, Optional}
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.util.Random

abstract class SvSvcStoreTest extends StoreTest with HasExecutionContext {

  "SvSvcStore" should {

    def offsetFreeLookupTest[TCid <: ContractId[T], T, C <: Contract.Has[TCid, T]](
        create: => Contract[TCid, T],
        noise: => Seq[Contract[TCid, T]],
    )(
        fetch: SvSvcStore => Future[Option[C]]
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
        fetch: SvSvcStore => Future[QueryResult[Option[C]]]
    ) = {
      s"$name" should {

        "return only the offset if there's none" in {
          for {
            store <- mkStore()
            result <- fetch(store)
          } yield result should be(QueryResult(acsOffset.toHexString, None))
        }

        offsetFreeLookupTest(create, noise)(fetch andThen (_ map (_.value)))
      }
    }

    lookupTests("lookupSvcRulesWithOffset")(svcRules())(_.lookupSvcRulesWithOffset())
    lookupTests("lookupCoinRulesWithOffset")(coinRules())(_.lookupCoinRulesWithOffset())
    lookupTests("lookupCnsRulesWithOffset")(cnsRules())(
      _.lookupCnsRulesWithOffset()
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
        QueryResult(acsOffset.toHexString, _)
      )
    )
    lookupTests("lookupSvOnboardingRequestByCandidateName")(
      create = svOnboardingRequest("good", userParty(1), "good-pid", "good"),
      noise = Seq(
        svOnboardingRequest("bad", userParty(2), "bad-pid", "bad")
      ),
    )(
      _.lookupSvOnboardingRequestByCandidateName("good").map(
        QueryResult(acsOffset.toHexString, _)
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
    lookupTests("lookupCnsEntryByNameWithOffset")(
      create = cnsEntry(userParty(1), "good"),
      noise = Seq(cnsEntry(userParty(2), "bad")),
    )(
      _.lookupCnsEntryByNameWithOffset("good")
    )
    def paymentId(n: Int) = new SubscriptionInitialPayment.ContractId(validContractId(n))
    def newReferenceId = new SubscriptionRequest.ContractId(nextCid())
    lookupTests("lookupSubscriptionInitialPaymentWithOffset")(
      create = subscriptionInitialPayment(
        newReferenceId,
        paymentId(1),
        userParty(1),
        svcParty,
        BigDecimal(1.0),
      ),
      noise = Seq(
        subscriptionInitialPayment(
          newReferenceId,
          paymentId(2),
          userParty(2),
          svcParty,
          BigDecimal(2.0),
        ),
        subscriptionInitialPayment(
          newReferenceId,
          paymentId(3),
          userParty(3),
          svcParty,
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
          SvSvcStore.OpenMiningRoundTriple(
            oldest = oldest,
            middle = middle,
            newest = newest,
            dummyDomain,
          )
        )
      }

    }

    "listExpiredCoins" should {

      "return all the coins that are expired as of the latest open mining round" in {
        val expiresAtRound2 = coin(storeSvParty, 1.0, 1, 1.0)
        val expiresAtRound3 = coin(storeSvParty, 1.0, 2, 1.0)
        val expiresAtRound4 = coin(storeSvParty, 3.0, 1, 1.0)
        val wontExpireAnyTimeSoon = coin(storeSvParty, 10.0, 2, 0.0001)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(svcRules())(store.multiDomainAcsStore)
          _ <- createMiningRoundsTriple(store, startRound = 3L) // oldest is round 3, newest is 5
          _ <- MonadUtil.sequentialTraverse(
            Seq(expiresAtRound2, expiresAtRound3, expiresAtRound4, wontExpireAnyTimeSoon)
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listExpiredCoins(CantonTimestamp.now(), PageLimit.tryCreate(100))(
            traceContext
          )
        } yield {
          val contracts = result.map(_.contract)
          contracts should contain theSameElementsAs Seq(expiresAtRound2, expiresAtRound3)
        }
      }

      "do not return expired coins from other domains" in {
        val expiresAtRound2 = coin(storeSvParty, 1.0, 1, 1.0)
        val expiresAtRound3 = coin(storeSvParty, 1.0, 2, 1.0)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(svcRules())(store.multiDomainAcsStore)
          _ <- createMiningRoundsTriple(store, startRound = 3L) // oldest is round 3, newest is 5
          _ <- dummy2Domain.create(expiresAtRound2)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(expiresAtRound3)(store.multiDomainAcsStore)
          result <- store.listExpiredCoins(CantonTimestamp.now(), PageLimit.tryCreate(100))(
            traceContext
          )
        } yield {
          val contracts = result.map(_.contract)
          contracts should contain theSameElementsAs Seq(expiresAtRound3)
        }
      }

    }

    "listLockedExpiredCoins" should {

      "return all the locked coins that are expired as of the latest open mining round" in {
        val expiresAtRound2 = lockedCoin(storeSvParty, 1.0, 1, 1.0)
        val expiresAtRound3 = lockedCoin(storeSvParty, 1.0, 2, 1.0)
        val expiresAtRound4 = lockedCoin(storeSvParty, 3.0, 1, 1.0)
        val wontExpireAnyTimeSoon = lockedCoin(storeSvParty, 10.0, 2, 0.0001)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(svcRules())(store.multiDomainAcsStore)
          _ <- createMiningRoundsTriple(store, startRound = 3L) // oldest is round 3, newest is 5
          _ <- MonadUtil.sequentialTraverse(
            Seq(expiresAtRound2, expiresAtRound3, expiresAtRound4, wontExpireAnyTimeSoon)
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listLockedExpiredCoins(CantonTimestamp.now(), PageLimit.tryCreate(100))(
            traceContext
          )
        } yield {
          val contracts = result.map(_.contract)
          contracts should contain theSameElementsAs Seq(expiresAtRound2, expiresAtRound3)
        }
      }

      "do not return expired locked coins from other domains" in {
        val expiresAtRound2 = lockedCoin(storeSvParty, 1.0, 1, 1.0)
        val expiresAtRound3 = lockedCoin(storeSvParty, 1.0, 2, 1.0)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(svcRules())(store.multiDomainAcsStore)
          _ <- createMiningRoundsTriple(store, startRound = 3L) // oldest is round 3, newest is 5
          _ <- dummy2Domain.create(expiresAtRound2)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(expiresAtRound3)(store.multiDomainAcsStore)
          result <- store.listLockedExpiredCoins(CantonTimestamp.now(), PageLimit.tryCreate(100))(
            traceContext
          )
        } yield {
          val contracts = result.map(_.contract)
          contracts should contain theSameElementsAs Seq(expiresAtRound3)
        }
      }

    }

    "listExpiredVoteRequests" should {

      "return all vote requests that are expired as of now" in {
        val expired = (1 to 3).map(n =>
          voteRequest(requester = userParty(n), expiry = Instant.now.minusSeconds(n.toLong * 3600))
        )
        val notExpired = (4 to 6).map(n => voteRequest(requester = userParty(n)))
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
        val goodClosed = (1 to 3).map(n => closedMiningRound(svcParty, n.toLong))
        val hasValidatorCoupon = closedMiningRound(svcParty, round = 4)
        val hasAppCoupon = closedMiningRound(svcParty, round = 5)
        val hasConfirmation = closedMiningRound(svcParty, round = 6)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(svcRules())(store.multiDomainAcsStore)
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
              action = new ARC_CoinRules(
                new CRARC_MiningRound_Archive(
                  new CoinRules_MiningRound_Archive(
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

    "lookupCnsInitialPaymentConfirmationByPaymentIdWithOffset" should {

      "find the confirmation by the initial payment id" in {
        val acceptedConfirmations =
          (1 to 2).map(n =>
            confirmation(
              n,
              cnsEntryContextPaymentAction(
                cnsEntryContext(n, s"name$n").contractId,
                isAccepted = true,
                new SubscriptionInitialPayment.ContractId(validContractId(n)),
              ),
            )
          )

        val rejectedConfirmations =
          (3 to 4).map(n =>
            confirmation(
              n,
              cnsEntryContextPaymentAction(
                cnsEntryContext(n, s"name$n").contractId,
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
            store.lookupCnsInitialPaymentConfirmationByPaymentIdWithOffset(storeSvParty, _)
          )
          acceptedResults <- lookupConfirmations(
            store.lookupCnsAcceptedInitialPaymentConfirmationByPaymentIdWithOffset(storeSvParty, _)
          )
          rejectedResults <- lookupConfirmations(
            store.lookupCnsRejectedInitialPaymentConfirmationByPaymentIdWithOffset(storeSvParty, _)
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
            Instant.now().minusSeconds(n.toLong * 3600),
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
            Instant.now().minusSeconds(n.toLong * 3600),
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

      "return all election requests for the given svcRules" in {
        import scala.jdk.CollectionConverters.*
        val goodSvcRules = svcRules(
          members = (1 to 3)
            .map { n =>
              userParty(n).toProtoPrimitive -> memberInfo(n.toString)
            }
            .toMap
            .asJava,
          epoch = 1,
        )
        val goodElectionRequests =
          (1 to 3).map(n => electionRequest(userParty(n) /*member of good svcrules*/, epoch = 1))
        val electionRequestOtherMember = electionRequest(userParty(666), epoch = 1)
        val electionRequestOtherEpoch = electionRequest(userParty(1), epoch = 2)

        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(
            goodElectionRequests :+ electionRequestOtherMember :+ electionRequestOtherEpoch
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listElectionRequests(AssignedContract(goodSvcRules, dummyDomain))(
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

    "getImportShipmentFor" should {

      "return the ImportShipment for the receiver" in {
        val receiver = userParty(1)
        val receiverCoins = (1 to 3).map(n =>
          importCrate(
            receiver,
            new IP_Coin(coin(receiver, n.toDouble, n.toLong, n.toDouble).payload),
          )
        )
        val receiverLicenses = (1 to 3).map(_ =>
          importCrate(
            receiver,
            new IP_ValidatorLicense(
              new ValidatorLicense(
                userParty(1).toProtoPrimitive,
                storeSvParty.toProtoPrimitive,
                svcParty.toProtoPrimitive,
                Optional.empty(),
              )
            ),
          )
        )
        val coinsOfOthers = (4 to 6).map(n =>
          importCrate(
            userParty(n),
            new IP_Coin(coin(userParty(n), n.toDouble, n.toLong, n.toDouble).payload),
          )
        )
        val validatorLicensesOfOthers = (4 to 6).map(n =>
          importCrate(
            userParty(n),
            new IP_ValidatorLicense(
              new ValidatorLicense(
                userParty(n).toProtoPrimitive,
                storeSvParty.toProtoPrimitive,
                svcParty.toProtoPrimitive,
                Optional.empty(),
              )
            ),
          )
        )

        for {
          store <- mkStore()
          (_, _, newest) <- createMiningRoundsTriple(store, startRound = 500L)
          _ <- MonadUtil.sequentialTraverse(
            receiverCoins ++ coinsOfOthers ++ receiverLicenses ++ validatorLicensesOfOthers
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.getImportShipmentFor(receiver)(traceContext)
        } yield {
          result.openRound.contract should be(newest)
          result.crates.map(
            _.contract
          ) should contain theSameElementsAs (receiverCoins ++ receiverLicenses)
        }
      }

    }

    "listExpiredCnsSubscriptions" should {

      "return all entries where subscription_next_payment_due_at < now" in {
        for {
          store <- mkStore()
          // 1 to 3 are expired, 4 to 6 are not
          data = ((1 to 3).map(n => n -> Instant.now().minusSeconds(n * 1000L)) ++ (4 to 6)
            .map(n => n -> Instant.now().plusSeconds(n * 1000L)))
            .map { case (n, nextPaymentDueAt) =>
              val contextContract =
                cnsEntryContext(n, n.toString)
              val idleStateContract =
                subscriptionIdleState(
                  n,
                  nextPaymentDueAt,
                )

              (contextContract, idleStateContract)
            }
          _ <- MonadUtil.sequentialTraverse(data) { case (contextContract, idleContract) =>
            for {
              _ <- dummyDomain.create(contextContract, createdEventSignatories = Seq(svcParty))(
                store.multiDomainAcsStore
              )
              _ <- dummyDomain.create(idleContract, createdEventSignatories = Seq(svcParty))(
                store.multiDomainAcsStore
              )
            } yield ()
          }
        } yield {
          val expected = data
            .take(3)
            .map { case (ctxContract, idleContract) =>
              IdleCnsSubscription(idleContract, ctxContract)
            }
            .reverse
          store
            .listExpiredCnsSubscriptions(CantonTimestamp.now(), limit = PageLimit.tryCreate(3))
            .futureValue should be(expected)
        }
      }

    }

    "listLaggingSvcRulesFollowers" should {
      "list followers" in {

        val leaderContract = svcRules()
        val followerContract1 = coinRules()
        val followerContract2 = svReward(storeSvParty, 1)
        val alreadyReassigned = svReward(storeSvParty, 2)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(leaderContract)(store.multiDomainAcsStore)
          _ <- dummy2Domain.create(followerContract1)(store.multiDomainAcsStore)
          _ <- dummy2Domain.create(followerContract2)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(alreadyReassigned)(store.multiDomainAcsStore)
          result <- store.listSvcRulesTransferFollowers(HasParticipantId.ForTesting)
        } yield result.map(x =>
          x.leader.contractId -> x.follower.contractId
        ) should contain theSameElementsAs Seq(
          leaderContract.contractId -> followerContract1.contractId,
          leaderContract.contractId -> followerContract2.contractId,
        )
      }
    }

    "listCoinRulesTransferFollowers" should {
      "list followers" in {

        val leaderContract = coinRules()
        val followerContract1 = openMiningRound(svcParty, 1, 1.0)
        val followerContract2 = svcReward(1)
        val alreadyReassigned = svcReward(2)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(leaderContract)(store.multiDomainAcsStore)
          _ <- dummy2Domain.create(followerContract1)(store.multiDomainAcsStore)
          _ <- dummy2Domain.create(followerContract2)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(alreadyReassigned)(store.multiDomainAcsStore)
          result <- store.listCoinRulesTransferFollowers(HasParticipantId.ForTesting)
        } yield result.map(x =>
          x.leader.contractId -> x.follower.contractId
        ) should contain theSameElementsAs Seq(
          leaderContract.contractId -> followerContract1.contractId,
          leaderContract.contractId -> followerContract2.contractId,
        )
      }
    }

    "listInitialPaymentConfirmationByCnsName" should {

      "find the confirmation by the cns name" in {
        val goodCnsName = cnsEntryContext(1, "good")
        val goodConfirmations =
          (1 to 3).map(n => confirmation(n, cnsEntryContextPaymentAction(goodCnsName.contractId)))
        val badCnsName = cnsEntryContext(2, "bad")
        val badConfirmations =
          (4 to 6).map(n => confirmation(n, cnsEntryContextPaymentAction(badCnsName.contractId)))
        val unrelatedConfirmations = (7 to 9).map(n => confirmation(n, addUser666Action))

        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(
            Seq(goodCnsName, badCnsName)
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          _ <- MonadUtil.sequentialTraverse(
            goodConfirmations ++ badConfirmations ++ unrelatedConfirmations
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listInitialPaymentConfirmationByCnsName(
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

    "listVoteResults" should {

      "list all past VoteResults" in {
        for {
          store <- mkStore()
          voteRequestContract = voteRequest(requester = userParty(1))
          _ <- dummyDomain.create(voteRequestContract)(store.multiDomainAcsStore)
          votes = (1 to 4).map(n => vote(userParty(n), voteRequestContract.contractId)).toList
          result = mkExecuteDefiniteVoteResult(voteRequestContract)
          _ <- MonadUtil.sequentialTraverse(votes)(dummyDomain.create(_)(store.multiDomainAcsStore))
          _ <- dummyDomain.exercise(
            contract = svcRules(),
            interfaceId = Some(SvcRules.TEMPLATE_ID),
            choiceName = SvcRulesExecuteDefiniteVote.choice.name,
            mkExecuteDefiniteVote(
              voteRequestContract.contractId,
              votes.map(v => Vote.ContractId.fromContractId(v.contractId)).asJava,
            ),
            result.toValue,
          )(
            store.multiDomainAcsStore
          )
        } yield {
          store
            .listVoteResults(
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
            .listVoteResults(
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
            .listVoteResults(
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
            .listVoteResults(
              None,
              None,
              None,
              Some(Instant.now().plusSeconds(3600).toString),
              None,
              PageLimit.tryCreate(1),
            )
            .futureValue
            .toList
            .size shouldBe (0)
          store
            .listVoteResults(
              None,
              None,
              None,
              Some(Instant.now().minusSeconds(3600).toString),
              None,
              PageLimit.tryCreate(1),
            )
            .futureValue
            .toList
            .size shouldBe (1)
        }
      }
    }

    "listVotesByVoteRequests" should {

      "return all votes by their VoteRequest contract ids" in {
        val goodVoteRequests =
          (1 to 3).map(n => voteRequest(requester = userParty(n)))
        val badVoteRequests =
          (4 to 6).map(n => voteRequest(requester = userParty(n)))
        val goodVotes =
          (1 to 6).map(n => vote(userParty(n), goodVoteRequests((n - 1) % 3).contractId))
        val badVotes = (1 to 3).map(n => vote(userParty(n), badVoteRequests(n - 1).contractId))
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(goodVoteRequests ++ badVoteRequests)(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          _ <- MonadUtil.sequentialTraverse(goodVotes ++ badVotes)(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listVotesByVoteRequests(goodVoteRequests.map(_.contractId))
        } yield {
          result should contain theSameElementsAs (goodVotes)
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
          )
        val doneByAnotherSV =
          voteRequest(
            action = goodAction,
            requester = providerParty(1234),
          )
        val differentAction =
          voteRequest(
            action = addUser667Action,
            requester = storeSvParty,
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
        val goodRequest = voteRequest(requester = storeSvParty)
        val badRequest = voteRequest(requester = providerParty(1234))
        val goodVote = vote(storeSvParty, goodRequest.contractId)
        val doneByAnother = vote(providerParty(1234), goodRequest.contractId)
        val anotherRequest = vote(storeSvParty, badRequest.contractId)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(goodRequest)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(badRequest)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(goodVote)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(doneByAnother)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(anotherRequest)(store.multiDomainAcsStore)
          result <- store.lookupVoteByThisSvAndVoteRequestWithOffset(goodRequest.contractId)
        } yield {
          result.value should be(Some(goodVote))
        }
      }

    }

  }

  lazy val addUser666Action = new ARC_SvcRules(
    new SRARC_AddMember(
      new SvcRules_AddMember(
        userParty(666).toProtoPrimitive,
        "user666",
        dummySvRewardWeight,
        "user666ParticipantId",
        new Round(1L),
        dummyDomain.toProtoPrimitive,
      )
    )
  )

  lazy val addUser667Action = new ARC_SvcRules(
    new SRARC_AddMember(
      new SvcRules_AddMember(
        userParty(667).toProtoPrimitive,
        "user667",
        dummySvRewardWeight,
        "user667ParticipantId",
        new Round(1L),
        dummyDomain.toProtoPrimitive,
      )
    )
  )

  lazy val removeUserAction = new ARC_SvcRules(
    new SRARC_RemoveMember(new SvcRules_RemoveMember(userParty(666).toProtoPrimitive))
  )

  private def mkExecuteDefiniteVote(
      requestId: VoteRequest.ContractId,
      voteIds: util.List[Vote.ContractId],
  ): DamlRecord = {
    new SvcRules_ExecuteDefiniteVote(
      requestId,
      Optional.empty(),
      voteIds,
    ).toValue
  }

  private def mkExecuteDefiniteVoteResult(
      voteRequestContract: Contract[VoteRequest.ContractId, VoteRequest]
  ): VoteResult = new VoteResult(
    voteRequestContract.payload.action,
    true,
    false,
    voteRequestContract.payload.requester,
    Instant.now(),
    Instant.now(),
    (1 to 4).map(n => userParty(n).toProtoPrimitive).toList.asJava,
    List.empty.asJava,
  )

  private def cnsEntryContextPaymentAction(
      contextCid: CnsEntryContext.ContractId,
      isAccepted: Boolean = true,
      paymentId: SubscriptionInitialPayment.ContractId =
        new SubscriptionInitialPayment.ContractId(validContractId(1)),
  ): ActionRequiringConfirmation = {
    val appTransferContext = new AppTransferContext(
      new cc.coinrules.CoinRules.ContractId(validContractId(1)),
      new cc.round.OpenMiningRound.ContractId(validContractId(1)),
      Optional.empty(),
    )
    new ARC_CnsEntryContext(
      contextCid,
      if (isAccepted)
        new CNSRARC_CollectInitialEntryPayment(
          new CnsEntryContext_CollectInitialEntryPayment(
            paymentId,
            appTransferContext,
            new CnsRules.ContractId(validContractId(1)),
          )
        )
      else
        new CNSRARC_RejectEntryInitialPayment(
          new CnsEntryContext_RejectEntryInitialPayment(
            paymentId,
            appTransferContext,
            new CnsRules.ContractId(validContractId(1)),
          )
        ),
    )
  }

  private def createMiningRoundsTriple(store: SvSvcStore, startRound: Long = 1L): Future[
    (
        Contract[OpenMiningRound.ContractId, OpenMiningRound],
        Contract[OpenMiningRound.ContractId, OpenMiningRound],
        Contract[OpenMiningRound.ContractId, OpenMiningRound],
    )
  ] = {
    val oldest = openMiningRound(svcParty, startRound, 1.0)
    val middle = openMiningRound(svcParty, startRound + 1, 2.0)
    val newest = openMiningRound(svcParty, startRound + 2, 3.0)
    val all = Random.shuffle(Seq(oldest, middle, newest))
    MonadUtil
      .sequentialTraverse(all)(dummyDomain.create(_)(store.multiDomainAcsStore))
      .map(_ => (oldest, middle, newest))
  }

  private def voteRequest(
      requester: PartyId,
      expiry: Instant = Instant.now().plusSeconds(3600L),
      action: ActionRequiringConfirmation = addUser666Action,
  ) = {
    val template = new VoteRequest(
      svcParty.toProtoPrimitive,
      requester.toProtoPrimitive,
      action,
      new Reason("https://www.example.com", ""),
      expiry,
    )

    contract(
      VoteRequest.TEMPLATE_ID,
      new VoteRequest.ContractId(nextCid()),
      template,
    )
  }

  private def vote(
      requester: PartyId,
      voteRequestCid: VoteRequest.ContractId,
  ): Contract[Vote.ContractId, Vote] = {
    val template = new Vote(
      svcParty.toProtoPrimitive,
      voteRequestCid,
      requester.toProtoPrimitive,
      true,
      new Reason("url", "summary"),
      Instant.now().plusSeconds(3600),
    )

    contract(
      Vote.TEMPLATE_ID,
      new Vote.ContractId(nextCid()),
      template,
    )
  }

  private def confirmation(n: Int, action: ActionRequiringConfirmation) = {
    val template = new Confirmation(
      svcParty.toProtoPrimitive,
      storeSvParty.toProtoPrimitive,
      action,
      Instant.now().plusSeconds(3600),
    )
    contract(
      Confirmation.TEMPLATE_ID,
      new Confirmation.ContractId(validContractId(n)),
      template,
    )
  }

  private def svcRules(
      members: java.util.Map[String, MemberInfo] = Collections.emptyMap(),
      epoch: Long = 123,
  ) = {
    val newDomainId = "new-domain-id"
    val template = new SvcRules(
      svcParty.toProtoPrimitive,
      epoch,
      members,
      Collections.emptyMap(),
      storeSvParty.toProtoPrimitive,
      new SvcRulesConfig(
        1,
        1,
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new DomainNodeConfigLimits(new CometBftConfigLimits(1, 1, 1, 1, 1)),
        1,
        1,
        new SvcGlobalDomainConfig(Collections.emptyMap(), newDomainId, newDomainId),
        Optional.empty(),
      ),
      Collections.emptyMap(),
      true,
    )

    contract(
      SvcRules.TEMPLATE_ID,
      new SvcRules.ContractId(validContractId(1)),
      template,
    )
  }

  private def memberInfo(name: String) = {
    new MemberInfo(
      name,
      new Round(1L),
      new Round(123L),
      456L,
      789L,
      s"PAR::${name}::12345",
      Collections.emptyMap(),
    )
  }

  private def memberTraffic(member: Member, totalPurchased: Long) = {
    val template = new MemberTraffic(
      svcParty.toProtoPrimitive,
      member.toProtoPrimitive,
      dummyDomain.toProtoPrimitive,
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
      svcParty.toProtoPrimitive,
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

  private def cnsEntry(user: PartyId, name: String) = {
    val template = new CnsEntry(
      user.toProtoPrimitive,
      svcParty.toProtoPrimitive,
      name,
      s"https://example.com/$name",
      s"Test with $name",
      Instant.now().plusSeconds(3600),
    )

    contract(
      CnsEntry.TEMPLATE_ID,
      new CnsEntry.ContractId(nextCid()),
      template,
    )
  }

  private def cnsEntryContext(n: Int, name: String) = {
    val template = new CnsEntryContext(
      svcParty.toProtoPrimitive,
      userParty(n).toProtoPrimitive,
      name,
      s"https://example.com/$name",
      s"Test with $name",
      new SubscriptionRequest.ContractId(validContractId(n, "ab")),
    )

    contract(
      CnsEntryContext.TEMPLATE_ID,
      new CnsEntryContext.ContractId(validContractId(n, "cc")),
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
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
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
      expiry: Instant = Instant.now().plusSeconds(3600),
  ) = {
    val template = new SvOnboardingRequest(
      name,
      candidate.toProtoPrimitive,
      participantId,
      token,
      storeSvParty.toProtoPrimitive,
      svcParty.toProtoPrimitive,
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
      expiry: Instant = Instant.now().plusSeconds(3600),
  ) = {
    val template = new SvOnboardingConfirmed(
      candidate.toProtoPrimitive,
      name,
      participantId,
      dummySvRewardWeight,
      "reason",
      svcParty.toProtoPrimitive,
      expiry,
    )

    contract(
      SvOnboardingConfirmed.TEMPLATE_ID,
      new SvOnboardingConfirmed.ContractId(nextCid()),
      template,
    )
  }

  private def importCrate(receiver: PartyId, payload: ImportPayload) = {
    val template = new ImportCrate(
      svcParty.toProtoPrimitive,
      receiver.toProtoPrimitive,
      payload,
    )

    contract(
      ImportCrate.TEMPLATE_ID,
      new ImportCrate.ContractId(nextCid()),
      template,
    )
  }

  protected def mkStore(): Future[SvSvcStore]

  lazy val acsOffset = Offset.fromByteArray(Array(1, 2, 3).map(_.toByte))
  lazy val domain = dummyDomain.toProtoPrimitive
  lazy val storeSvParty = providerParty(42)
  lazy val svDomainConfig = SvDomainConfig(
    SvGlobalDomainConfig(DomainAlias.tryCreate(domain), "https://example.com")
  )
}

class InMemorySvSvcStoreTest extends SvSvcStoreTest {

  override protected def mkStore(): Future[InMemorySvSvcStore] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.cantonCoin.all ++
          DarResources.validatorLifecycle.all ++
          DarResources.svcGovernance.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)
    val store = new InMemorySvSvcStore(
      SvStore.Key(storeSvParty, svcParty),
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
    )
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(acsOffset.toHexString, Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(DomainAlias.tryCreate(domain) -> dummyDomain)
      )
    } yield store
  }
}

class DbSvSvcStoreTest
    extends SvSvcStoreTest
    with HasActorSystem
    with CNPostgresTest
    with AcsJdbcTypes
    with AcsTables {

  override protected def mkStore(): Future[DbSvSvcStore] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.cantonCoin.all ++
          DarResources.validatorLifecycle.all ++
          DarResources.svcGovernance.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val store = new DbSvSvcStore(
      SvStore.Key(storeSvParty, svcParty),
      storage,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      domainMigrationId = 0,
    )(parallelExecutionContext, implicitly, implicitly)
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(acsOffset.toHexString, Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(DomainAlias.tryCreate(domain) -> dummyDomain)
      )
    } yield store
  }

  override protected def cleanDb(storage: DbStorage): Future[?] =
    for {
      _ <- resetAllCnAppTables(storage)
    } yield ()
}

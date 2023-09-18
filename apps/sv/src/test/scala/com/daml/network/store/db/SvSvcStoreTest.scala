package com.daml.network.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.{ContractMetadata, DamlRecord}
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.api.v1.coin.{AppTransferContext, EnabledChoices}
import com.daml.network.codegen.java.cc.api.v1.round.Round
import com.daml.network.codegen.java.cc.coin.{
  CoinRules_MiningRound_Archive,
  CoinRules_SetEnabledChoices,
}
import com.daml.network.codegen.java.cc.coinimport.importpayload.{IP_Coin, IP_ValidatorLicense}
import com.daml.network.codegen.java.cc.coinimport.{ImportCrate, ImportPayload}
import com.daml.network.codegen.java.cc.globaldomain.MemberTraffic
import com.daml.network.codegen.java.cc.round.OpenMiningRound
import com.daml.network.codegen.java.cc.validatorlicense.ValidatorLicense
import com.daml.network.codegen.java.cn.cns.*
import com.daml.network.codegen.java.cn.cometbft.CometBftConfigLimits
import com.daml.network.codegen.java.cn.svc.globaldomain.{
  DomainNodeConfigLimits,
  GlobalDomainConfig,
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
import com.daml.network.codegen.java.cn.svcrules.coinrules_actionrequiringconfirmation.{
  CRARC_MiningRound_Archive,
  CRARC_SetEnabledChoices,
}
import com.daml.network.codegen.java.cn.svcrules.electionrequestreason.ERR_OtherReason
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_RemoveMember
import com.daml.network.codegen.java.cn.svonboarding.{SvOnboardingConfirmed, SvOnboardingRequest}
import com.daml.network.codegen.java.cn.wallet.payment.{Currency, PaymentAmount}
import com.daml.network.codegen.java.cn.wallet.subscriptions.{
  Subscription,
  SubscriptionContext,
  SubscriptionIdleState,
  SubscriptionInitialPayment,
  SubscriptionPayData,
}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.environment.RetryProvider
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.store.{Limit, StoreTest, TxLogStore}
import com.daml.network.sv.config.{SvDomainConfig, SvGlobalDomainConfig}
import com.daml.network.sv.history.SvcRulesExecuteDefiniteVote
import com.daml.network.sv.store.SvcTxLogParser.TxLogEntry.DefiniteVoteTxLogEntry
import com.daml.network.sv.store.SvcTxLogParser.TxLogIndexRecord.DefiniteVoteIndexRecord
import com.daml.network.sv.store.db.DbSvSvcStore
import com.daml.network.sv.store.memory.InMemorySvSvcStore
import com.daml.network.sv.store.{SvStore, SvSvcStore}
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
import com.digitalasset.canton.metrics.MetricHandle.NoOpMetricsFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}
import com.google.protobuf

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
        _ <- Future.traverse(noise)(dummyDomain.create(_)(store.multiDomainAcsStore))
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
      create = confirmation(1, enabledChoicesTrueAction),
      noise = Seq(
        confirmation(2, enabledChoicesFalseAction),
        confirmation(3, removeUserAction),
      ),
    )(_.lookupConfirmationByActionWithOffset(storeSvParty, enabledChoicesTrueAction))
    lookupTests("lookupSvOnboardingRequestByTokenWithOffset")(
      create = svOnboardingRequest("good", userParty(1), "good"),
      noise = Seq(
        svOnboardingRequest("bad", userParty(2), "bad")
      ),
    )(_.lookupSvOnboardingRequestByTokenWithOffset("good"))
    lookupTests("lookupSvOnboardingRequestByCandidateParty")(
      create = svOnboardingRequest("good", userParty(1), "good"),
      noise = Seq(
        svOnboardingRequest("bad", userParty(2), "bad")
      ),
    )(
      _.lookupSvOnboardingRequestByCandidateParty(userParty(1)).map(
        QueryResult(acsOffset.toHexString, _)
      )
    )
    lookupTests("lookupSvOnboardingRequestByCandidateName")(
      create = svOnboardingRequest("good", userParty(1), "good"),
      noise = Seq(
        svOnboardingRequest("bad", userParty(2), "bad")
      ),
    )(
      _.lookupSvOnboardingRequestByCandidateName("good").map(
        QueryResult(acsOffset.toHexString, _)
      )
    )
    "lookupSvOnboardingConfirmedByParty" should {
      offsetFreeLookupTest(
        create = svOnboardingConfirmed("good", userParty(1)),
        noise = Seq(svOnboardingConfirmed("bad", userParty(2))),
      )(_.lookupSvOnboardingConfirmedByParty(userParty(1)))
    }
    lookupTests("lookupSvOnboardingConfirmedByNameWithOffset")(
      create = svOnboardingConfirmed("good", userParty(1)),
      noise = Seq(svOnboardingConfirmed("bad", userParty(2))),
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
    lookupTests("lookupSubscriptionInitialPaymentWithOffset")(
      create = subscriptionInitialPayment(paymentId(1), userParty(1), svcParty, BigDecimal(1.0)),
      noise = Seq(
        subscriptionInitialPayment(paymentId(2), userParty(2), svcParty, BigDecimal(2.0)),
        subscriptionInitialPayment(paymentId(3), userParty(3), svcParty, BigDecimal(3.0)),
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
          _ <- Future.traverse(
            Seq(expiresAtRound2, expiresAtRound3, expiresAtRound4, wontExpireAnyTimeSoon)
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listExpiredCoins(CantonTimestamp.now(), 100)(traceContext)
        } yield {
          val contracts = result.map(_.contract)
          contracts should contain theSameElementsAs Seq(expiresAtRound2, expiresAtRound3)
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
          _ <- Future.traverse(
            Seq(expiresAtRound2, expiresAtRound3, expiresAtRound4, wontExpireAnyTimeSoon)
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listLockedExpiredCoins(CantonTimestamp.now(), 100)(traceContext)
        } yield {
          val contracts = result.map(_.contract)
          contracts should contain theSameElementsAs Seq(expiresAtRound2, expiresAtRound3)
        }
      }

    }

    "listExpiredVoteRequests" should {

      "return all vote requests that are expired as of now" in {
        val now = Instant.now()
        val expired = (1 to 3).map(n => voteRequest(n, now.minusSeconds(n.toLong * 3600)))
        val notExpired = (4 to 6).map(n => voteRequest(n, now.plusSeconds(n.toLong * 3600)))
        for {
          store <- mkStore()
          _ <- Future.traverse(expired ++ notExpired)(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listExpiredVoteRequests()(CantonTimestamp.now(), 100)(traceContext)
        } yield {
          val contracts = result.map(_.contract)
          contracts should contain theSameElementsAs expired
        }
      }

    }

    "listConfirmations" should {

      "list all confirmations with a matching action" in {
        val goodAction = enabledChoicesTrueAction
        val badActionSameType = enabledChoicesFalseAction
        val badActionDifferentType = removeUserAction
        val goodConfirmations = (1 to 3).map(n => confirmation(n, goodAction))
        val badConfirmation1 = confirmation(4, badActionSameType)
        val badConfirmation2 = confirmation(5, badActionDifferentType)
        for {
          store <- mkStore()
          _ <- Future.traverse(goodConfirmations :+ badConfirmation1 :+ badConfirmation2)(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listConfirmations(goodAction)
        } yield {
          result should contain theSameElementsAs goodConfirmations
        }
      }

    }

    "listAppRewardCouponsOnDomain" should {

      // TODO (#5314): add cases with a different domain
      "list all the app reward coupons on the domain" in {
        val inRound = (1 to 3).map(n => appRewardCoupon(round = 3, userParty(n)))
        val outOfRound = (1 to 3).map(n => appRewardCoupon(round = 2, userParty(n)))
        for {
          store <- mkStore()
          _ <- Future.traverse(inRound ++ outOfRound)(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listAppRewardCouponsOnDomain(round = 3, dummyDomain, Limit.DefaultLimit)
        } yield {
          result should contain theSameElementsAs inRound
        }
      }

    }

    "listValidatorRewardCouponsOnDomain" should {

      // TODO (#5314): add cases with a different domain
      "list all the validator reward coupons on the domain" in {
        val inRound = (1 to 3).map(n => validatorRewardCoupon(round = 3, userParty(n)))
        val outOfRound = (1 to 3).map(n => validatorRewardCoupon(round = 2, userParty(n)))
        for {
          store <- mkStore()
          _ <- Future.traverse(inRound ++ outOfRound)(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listValidatorRewardCouponsOnDomain(
            round = 3,
            dummyDomain,
            Limit.DefaultLimit,
          )
        } yield {
          result should contain theSameElementsAs inRound
        }
      }

    }

    "listAppRewardCouponsGroupedByCounterparty" should {

      "return all app reward coupons in a round grouped by counterparty" in {
        val provider1InRound = (1 to 3).map(_ => appRewardCoupon(round = 3, userParty(1)))
        val provider2InRound = (1 to 3).map(_ => appRewardCoupon(round = 3, userParty(2)))
        val provider1OutOfRound = (1 to 3).map(_ => appRewardCoupon(round = 2, userParty(1)))
        val provider2OutOfRound = (1 to 3).map(_ => appRewardCoupon(round = 2, userParty(2)))
        for {
          store <- mkStore()
          _ <- Future.traverse(
            provider1InRound ++ provider2InRound ++ provider1OutOfRound ++ provider2OutOfRound
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listAppRewardCouponsGroupedByCounterparty(
            roundNumber = 3,
            roundDomain = dummyDomain,
            totalCouponsLimit = 1000,
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

      "return all app reward coupons in a round grouped by counterparty" in {
        val provider1InRound = (1 to 3).map(_ => validatorRewardCoupon(round = 3, userParty(1)))
        val provider2InRound = (1 to 3).map(_ => validatorRewardCoupon(round = 3, userParty(2)))
        val provider1OutOfRound = (1 to 3).map(_ => validatorRewardCoupon(round = 2, userParty(1)))
        val provider2OutOfRound = (1 to 3).map(_ => validatorRewardCoupon(round = 2, userParty(2)))
        for {
          store <- mkStore()
          _ <- Future.traverse(
            provider1InRound ++ provider2InRound ++ provider1OutOfRound ++ provider2OutOfRound
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listValidatorRewardCouponsGroupedByCounterparty(
            roundNumber = 3,
            roundDomain = dummyDomain,
            totalCouponsLimit = 1000,
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

    "listArchivableClosedMiningRounds" should {

      "return all ClosedMiningRounds without left-over reward coupons and with no ready-to-be-archived confirmation" in {
        val goodClosed = (1 to 3).map(n => closedMiningRound(svcParty, n.toLong))
        val hasValidatorCoupon = closedMiningRound(svcParty, round = 4)
        val hasAppCoupon = closedMiningRound(svcParty, round = 5)
        val hasConfirmation = closedMiningRound(svcParty, round = 6)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(svcRules())(store.multiDomainAcsStore)
          _ <- Future.traverse(
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
        val unrelatedConfirmation = confirmation(10, enabledChoicesTrueAction)

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
          _ <- Future.traverse(
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
            n.toString,
            Instant.now().minusSeconds(n.toLong * 3600),
          )
        )
        val notExpired =
          (4 to 6).map(n => svOnboardingRequest(n.toString, userParty(n), n.toString))
        for {
          store <- mkStore()
          _ <- Future.traverse(expired ++ notExpired)(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listExpiredSvOnboardingRequests(CantonTimestamp.now(), 100)(
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
            Instant.now().minusSeconds(n.toLong * 3600),
          )
        )
        val notExpired = (4 to 6).map(n => svOnboardingConfirmed(n.toString, userParty(n)))
        for {
          store <- mkStore()
          _ <- Future.traverse(expired ++ notExpired)(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listExpiredSvOnboardingConfirmed(CantonTimestamp.now(), 100)(
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
          _ <- Future.traverse(
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
          _ <- Future.traverse(electionRequests)(
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
              )
            ),
          )
        )

        for {
          store <- mkStore()
          (_, _, newest) <- createMiningRoundsTriple(store, startRound = 500L)
          _ <- Future.traverse(
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
                  contextContract.contractId,
                )

              (contextContract, idleStateContract)
            }
          _ <- Future.traverse(data) { case (contextContract, idleContract) =>
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
          eventually() {
            store
              .listExpiredCnsSubscriptions(CantonTimestamp.now(), limit = 3)
              .futureValue should be(expected)
          }
        }
      }

    }

    // TODO (#5314): this is missing tests for all the usages of "NotOnDomain"

    "listInitialPaymentConfirmationByCnsName" should {

      "find the confirmation by the cns name" in {
        val goodCnsName = cnsEntryContext(1, "good")
        val goodConfirmations =
          (1 to 3).map(n => confirmation(n, cnsEntryContextPaymentAction(goodCnsName.contractId)))
        val badCnsName = cnsEntryContext(2, "bad")
        val badConfirmations =
          (4 to 6).map(n => confirmation(n, cnsEntryContextPaymentAction(badCnsName.contractId)))
        val unrelatedConfirmations = (7 to 9).map(n => confirmation(n, enabledChoicesTrueAction))

        for {
          store <- mkStore()
          _ <- Future.traverse(
            Seq(goodCnsName, badCnsName)
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          _ <- Future.traverse(
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
        val namespace = Namespace(Fingerprint.tryCreate(s"participant-identity"))
        val goodMember = ParticipantId(Identifier.tryCreate("good"), namespace)
        val badMember = SequencerId(dummyDomain)
        val goodContracts = (1 to 3).map(n => memberTraffic(goodMember, n.toLong))
        val badContracts = (4 to 6).map(n => memberTraffic(badMember, n.toLong))
        for {
          store <- mkStore()
          _ <- Future.traverse(
            goodContracts ++ badContracts
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.listMemberTrafficContracts(goodMember, dummyDomain, 100)
        } yield result should contain theSameElementsAs goodContracts
      }

    }

    "listVoteResults" should {

      "list all past VoteResults" in {
        for {
          store <- mkStore()
          voteRequestContract = voteRequest(1, Instant.now().plusSeconds(1.toLong * 3600))
          _ <- dummyDomain.create(voteRequestContract)(store.multiDomainAcsStore)
          votes = (1 to 4).map(n => vote(n, voteRequestContract.contractId)).toList
          result = mkExecuteDefiniteVoteResult(voteRequestContract)
          _ <- Future.traverse(votes)(dummyDomain.create(_)(store.multiDomainAcsStore))
          definitiveVoteTx <- dummyDomain.exercise(
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
          transactionTreeSource.addTree(definitiveVoteTx)
          store
            .listVoteResults(
              Some("CRARC_SetEnabledChoices"),
              Some(true),
              None,
              None,
              None,
              1,
            )
            .futureValue
            .toList
            .loneElement shouldBe DefiniteVoteTxLogEntry(
            new DefiniteVoteIndexRecord(
              definitiveVoteTx.getOffset,
              definitiveVoteTx.getRootEventIds.get(0),
              dummyDomain,
              "CRARC_SetEnabledChoices",
              true,
              result.requester,
              result.effectiveAt.toString,
            ),
            result.rejectedBy.asScala.toList,
            result.acceptedBy.asScala.toList,
            result.action,
          )
          store
            .listVoteResults(
              Some("CRARC_SetEnabledChoices"),
              Some(false),
              None,
              None,
              None,
              1,
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
              1,
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
              1,
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
              1,
            )
            .futureValue
            .toList
            .size shouldBe (1)
        }
      }
    }

  }

  lazy val enabledChoicesTrueAction = new ARC_CoinRules(
    new CRARC_SetEnabledChoices(
      new CoinRules_SetEnabledChoices(
        enabledChoices(true)
      )
    )
  )
  lazy val enabledChoicesFalseAction = new ARC_CoinRules(
    new CRARC_SetEnabledChoices(
      new CoinRules_SetEnabledChoices(
        enabledChoices(false)
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
    voteRequestContract.payload.requester,
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
      new v1.coin.CoinRules.ContractId(validContractId(1)),
      new v1.round.OpenMiningRound.ContractId(validContractId(1)),
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
    Future
      .traverse(all)(dummyDomain.create(_)(store.multiDomainAcsStore))
      .map(_ => (oldest, middle, newest))
  }

  private def voteRequest(n: Int, expiry: Instant) = {
    val template = new VoteRequest(
      svcParty.toProtoPrimitive,
      userParty(n).toProtoPrimitive,
      enabledChoicesTrueAction,
      new Reason("https://www.example.com", ""),
      expiry,
    )

    Contract(
      VoteRequest.TEMPLATE_ID,
      new VoteRequest.ContractId(validContractId(n)),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  private def vote(
      n: Int,
      voteRequestCid: VoteRequest.ContractId,
  ): Contract[Vote.ContractId, Vote] = {
    val template = new Vote(
      svcParty.toProtoPrimitive,
      voteRequestCid,
      userParty(n).toProtoPrimitive,
      true,
      new Reason("url", "summary"),
      Instant.now().plusSeconds(3600),
    )

    Contract(
      Vote.TEMPLATE_ID,
      new Vote.ContractId(validContractId((n))),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  private def confirmation(n: Int, action: ActionRequiringConfirmation) = {
    val template = new Confirmation(
      svcParty.toProtoPrimitive,
      storeSvParty.toProtoPrimitive,
      action,
      Instant.now().plusSeconds(3600),
    )
    Contract(
      Confirmation.TEMPLATE_ID,
      new Confirmation.ContractId(validContractId(n)),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  private def enabledChoices(enableLockedCoinUnlock: Boolean) = new EnabledChoices(
    enableLockedCoinUnlock,
    false,
    false,
    false,
    false,
    true,
    false,
    false,
  )

  private def svcRules(
      members: java.util.Map[String, MemberInfo] = Collections.emptyMap(),
      epoch: Long = 123,
  ) = {
    val template = new SvcRules(
      svcParty.toProtoPrimitive,
      epoch,
      members,
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
        new GlobalDomainConfig(Collections.emptyMap(), 1, 1),
      ),
      Collections.emptyMap(),
      true,
      false,
    )

    Contract(
      SvcRules.TEMPLATE_ID,
      new SvcRules.ContractId(validContractId(1)),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  private def memberInfo(name: String) = {
    new MemberInfo(name, new Round(1L), Collections.emptyMap())
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

    Contract(
      MemberTraffic.TEMPLATE_ID,
      new MemberTraffic.ContractId(nextCid()),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
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

    Contract(
      ElectionRequest.TEMPLATE_ID,
      new ElectionRequest.ContractId(nextCid()),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
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

    Contract(
      CnsEntry.TEMPLATE_ID,
      new CnsEntry.ContractId(nextCid()),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  private def cnsEntryContext(n: Int, name: String) = {
    val template = new CnsEntryContext(
      svcParty.toProtoPrimitive,
      userParty(n).toProtoPrimitive,
      name,
      s"https://example.com/$name",
      s"Test with $name",
    )

    Contract(
      CnsEntryContext.TEMPLATE_ID,
      new CnsEntryContext.ContractId(validContractId(n, "cc")),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  private def subscriptionIdleState(
      n: Int,
      nextPaymentDueAt: Instant,
      cnsEntryContextCid: CnsEntryContext.ContractId,
  ) = {
    val templateId = SubscriptionIdleState.TEMPLATE_ID
    val template = new SubscriptionIdleState(
      new Subscription.ContractId(validContractId(n, "aa")),
      new Subscription(
        userParty(n).toProtoPrimitive,
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        cnsEntryContextCid.toInterface(SubscriptionContext.INTERFACE),
      ),
      new SubscriptionPayData(
        new PaymentAmount(numeric(BigDecimal("1")), Currency.CC),
        new RelTime(1_000_000_000L),
        new RelTime(1_000_000L),
      ),
      nextPaymentDueAt,
    )
    Contract(
      identifier = templateId,
      contractId = new SubscriptionIdleState.ContractId(s"$domain#$n"),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def svOnboardingRequest(
      name: String,
      candidate: PartyId,
      token: String,
      expiry: Instant = Instant.now().plusSeconds(3600),
  ) = {
    val template = new SvOnboardingRequest(
      name,
      candidate.toProtoPrimitive,
      token,
      storeSvParty.toProtoPrimitive,
      svcParty.toProtoPrimitive,
      expiry,
    )

    Contract(
      SvOnboardingRequest.TEMPLATE_ID,
      new SvOnboardingRequest.ContractId(nextCid()),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  private def svOnboardingConfirmed(
      name: String,
      candidate: PartyId,
      expiry: Instant = Instant.now().plusSeconds(3600),
  ) = {
    val template = new SvOnboardingConfirmed(
      candidate.toProtoPrimitive,
      name,
      "reason",
      svcParty.toProtoPrimitive,
      expiry,
    )

    Contract(
      SvOnboardingConfirmed.TEMPLATE_ID,
      new SvOnboardingConfirmed.ContractId(nextCid()),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  private def importCrate(receiver: PartyId, payload: ImportPayload) = {
    val template = new ImportCrate(
      svcParty.toProtoPrimitive,
      receiver.toProtoPrimitive,
      payload,
    )

    Contract(
      ImportCrate.TEMPLATE_ID,
      new ImportCrate.ContractId(nextCid()),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  protected def mkStore(): Future[SvSvcStore]
  protected lazy val transactionTreeSource = TxLogStore.TransactionTreeSource.ForTesting()

  lazy val acsOffset = Offset.fromByteArray(Array(1, 2, 3).map(_.toByte))
  lazy val domain = dummyDomain.toProtoPrimitive
  lazy val storeSvParty = providerParty(42)
  lazy val svDomainConfig = SvDomainConfig(
    SvGlobalDomainConfig(DomainAlias.tryCreate(domain), "https://example.com")
  )
}

class InMemorySvSvcStoreTest extends SvSvcStoreTest {

  override protected def mkStore(): Future[InMemorySvSvcStore] = {
    val store = new InMemorySvSvcStore(
      SvStore.Key(storeSvParty, svcParty),
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      transactionTreeSource,
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
        Seq(
          "dar/canton-coin-0.1.0.dar",
          "dar/validator-lifecycle-0.1.0.dar",
          "dar/svc-governance-0.1.0.dar",
        )
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val store = new DbSvSvcStore(
      SvStore.Key(storeSvParty, svcParty),
      storage,
      svDomainConfig,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      transactionTreeSource,
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

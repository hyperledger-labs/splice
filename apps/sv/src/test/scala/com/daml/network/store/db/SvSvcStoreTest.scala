package com.daml.network.store.db

import com.daml.ledger.javaapi.data.ContractMetadata
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.codegen.java.cc.api.v1.coin.{AppTransferContext, EnabledChoices}
import com.daml.network.codegen.java.cc.coin.{
  CoinRules_MiningRound_Archive,
  CoinRules_SetEnabledChoices,
}
import com.daml.network.codegen.java.cc.round.OpenMiningRound
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.api.v1.round.Round
import com.daml.network.codegen.java.cc.coinimport.{ImportCrate, ImportPayload}
import com.daml.network.codegen.java.cc.coinimport.importpayload.{IP_Coin, IP_ValidatorLicense}
import com.daml.network.codegen.java.cc.globaldomain.MemberTraffic
import com.daml.network.codegen.java.cc.validatorlicense.ValidatorLicense
import com.daml.network.codegen.java.cn.cns.{
  CnsEntry,
  CnsEntryContext,
  CnsEntryContext_CollectInitialEntryPayment,
  CnsRules,
}
import com.daml.network.codegen.java.cn.cometbft.CometBftConfigLimits
import com.daml.network.codegen.java.cn.svc.globaldomain.{
  DomainNodeConfigLimits,
  GlobalDomainConfig,
}
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.{
  ARC_CnsEntryContext,
  ARC_CoinRules,
  ARC_SvcRules,
}
import com.daml.network.codegen.java.cn.svcrules.cnsentrycontext_actionrequiringconfirmation.CNSRARC_CollectInitialEntryPayment
import com.daml.network.codegen.java.cn.svcrules.coinrules_actionrequiringconfirmation.{
  CRARC_MiningRound_Archive,
  CRARC_SetEnabledChoices,
}
import com.daml.network.codegen.java.cn.svcrules.electionrequestreason.ERR_OtherReason
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_RemoveMember
import com.daml.network.codegen.java.cn.svcrules.{
  ActionRequiringConfirmation,
  Confirmation,
  ElectionRequest,
  MemberInfo,
  Reason,
  SvcRules,
  SvcRulesConfig,
  SvcRules_RemoveMember,
  VoteRequest,
}
import com.daml.network.codegen.java.cn.svonboarding.{SvOnboardingConfirmed, SvOnboardingRequest}
import com.daml.network.codegen.java.cn.wallet.subscriptions.SubscriptionInitialPayment
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.environment.RetryProvider
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.store.{Limit, StoreTest}
import com.daml.network.sv.config.{SvDomainConfig, SvGlobalDomainConfig}
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
import com.digitalasset.canton.topology.{
  Identifier,
  Member,
  Namespace,
  ParticipantId,
  PartyId,
  SequencerId,
}
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}
import com.google.protobuf

import java.time.Instant
import java.util.{Collections, Optional}
import scala.concurrent.Future
import scala.util.Random

abstract class SvSvcStoreTest extends StoreTest with HasExecutionContext {

  "SvSvcStore" should {

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

        "return the entry if found" in {
          val created = create
          for {
            store <- mkStore()
            _ <- dummyDomain.create(created)(store.multiDomainAcsStore)
            _ <- Future.traverse(noise)(dummyDomain.create(_)(store.multiDomainAcsStore))
            result <- fetch(store)
          } yield result.value.map(_.contract) should be(Some(created))
        }
      }
    }

    lookupTests("lookupSvcRulesWithOffset")(svcRules())(_.lookupSvcRulesWithOffset())
    lookupTests("lookupCoinRulesWithOffset")(coinRules())(_.lookupCoinRulesWithOffset())
    lookupTests("lookupCoinRulesV1TestWithOffset")(coinRulesV1Test(1))(
      _.lookupCoinRulesV1TestWithOffset()
    )
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
    lookupTests("lookupSvOnboardingConfirmedByPartyOnDomain")(
      create = svOnboardingConfirmed("good", userParty(1)),
      noise = Seq(svOnboardingConfirmed("bad", userParty(2))),
    )( // TODO (#5314): add cases with a different domain
      _.lookupSvOnboardingConfirmedByPartyOnDomain(userParty(1), dummyDomain)
    )
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
            round = 3,
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
            round = 3,
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
          result.map(_.value) should contain theSameElementsAs goodClosed
        }
      }

    }

    "lookupCnsInitialPaymentConfirmationByCnsNameWithOffset" should {

      "find the confirmation by the cns name" in {
        val goodCnsName = cnsEntryContext(userParty(1), "good")
        val goodConfirmation = confirmation(1, cnsEntryContextPaymentAction(goodCnsName.contractId))
        val badCnsName = cnsEntryContext(userParty(2), "bad")
        val badConfirmation = confirmation(2, cnsEntryContextPaymentAction(badCnsName.contractId))
        val unrelatedConfirmation = confirmation(3, enabledChoicesTrueAction)
        for {
          store <- mkStore()
          _ <- Future.traverse(
            Seq(goodCnsName, badCnsName)
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          _ <- Future.traverse(
            Seq(goodConfirmation, badConfirmation, unrelatedConfirmation)
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.lookupCnsInitialPaymentConfirmationByCnsNameWithOffset(
            storeSvParty,
            "good",
          )
        } yield result.value should be(Some(goodConfirmation))
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

    // TODO (#5314): this is missing tests for all the usages of "NotOnDomain"

    "listInitialPaymentConfirmationByCnsName" should {

      "find the confirmation by the cns name" in {
        val goodCnsName = cnsEntryContext(userParty(1), "good")
        val goodConfirmations =
          (1 to 3).map(n => confirmation(n, cnsEntryContextPaymentAction(goodCnsName.contractId)))
        val badCnsName = cnsEntryContext(userParty(2), "bad")
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
  private def cnsEntryContextPaymentAction(
      contextCid: CnsEntryContext.ContractId
  ): ActionRequiringConfirmation = {
    new ARC_CnsEntryContext(
      contextCid,
      new CNSRARC_CollectInitialEntryPayment(
        new CnsEntryContext_CollectInitialEntryPayment(
          new SubscriptionInitialPayment.ContractId(validContractId(1)),
          new AppTransferContext(
            new v1.coin.CoinRules.ContractId(validContractId(1)),
            new v1.round.OpenMiningRound.ContractId(validContractId(1)),
            Optional.empty(),
          ),
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

  private def cnsEntryContext(user: PartyId, name: String) = {
    val template = new CnsEntryContext(
      svcParty.toProtoPrimitive,
      user.toProtoPrimitive,
      name,
      s"https://example.com/$name",
      s"Test with $name",
    )

    Contract(
      CnsEntryContext.TEMPLATE_ID,
      new CnsEntryContext.ContractId(nextCid()),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
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
      svDomainConfig,
      enableCoinRulesUpgrade = true,
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
        Seq(
          "dar/canton-coin-0.1.1.dar",
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
      enableCoinRulesUpgrade = true,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
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

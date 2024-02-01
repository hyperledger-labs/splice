package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.coinrules.CoinRules_AddFutureCoinConfigSchedule
import com.daml.network.codegen.java.cc.coinconfig.{CoinConfig, TransferConfig, USD}
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.{
  ARC_CoinRules,
  ARC_SvcRules,
}
import com.daml.network.codegen.java.cn.svcrules.coinrules_actionrequiringconfirmation.CRARC_AddFutureCoinConfigSchedule
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.{
  SRARC_RemoveMember,
  SRARC_SetConfig,
}
import com.daml.network.codegen.java.cn.svcrules.{
  ActionRequiringConfirmation,
  SvcRulesConfig,
  SvcRules_RemoveMember,
  SvcRules_SetConfig,
}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.sv.automation.leaderbased.ExpireVoteRequestTrigger
import com.daml.network.util.Codec

import java.time.Instant
import scala.jdk.OptionConverters.*

class SvStateManagementIntegrationTest extends SvIntegrationTestBase {

  "SVs can update their CoinPriceVote contracts" in { implicit env =>
    initSvc()
    val svParties =
      Seq(("sv1", sv1Backend), ("sv2", sv2Backend), ("sv3", sv3Backend), ("sv4", sv4Backend)).map {
        case (svName, sv) => svName -> sv.getSvcInfo().svParty
      }.toMap

    clue("initially only sv1 and sv2 have set the CoinPriceVote") {
      // sv1 because it's the SVC founder and sv2 because we configured it to do so
      eventually() {
        getCoinPriceVoteMap() shouldBe Map(
          svParties("sv1") -> Seq(Some(BigDecimal(1.0))),
          svParties("sv2") -> Seq(Some(BigDecimal(1.0))),
          svParties("sv3") -> Seq(None),
          svParties("sv4") -> Seq(None),
        )
      }
    }

    actAndCheck(
      "set CoinPriceVote of sv2, sv3 and sv4", {
        sv2Backend.updateCoinPriceVote(BigDecimal(4.0))
        sv3Backend.updateCoinPriceVote(BigDecimal(3.0))
        sv4Backend.updateCoinPriceVote(BigDecimal(2.0))
      },
    )(
      "CoinPriceVote contract for sv2, sv3 anc sv4 are updated",
      _ => {
        getCoinPriceVoteMap() shouldBe Map(
          svParties("sv1") -> Seq(Some(BigDecimal(1.0))),
          svParties("sv2") -> Seq(Some(BigDecimal(4.0))),
          svParties("sv3") -> Seq(Some(BigDecimal(3.0))),
          svParties("sv4") -> Seq(Some(BigDecimal(2.0))),
        )
      },
    )

    actAndCheck(
      "update CoinPriceVote of sv1", {
        sv1Backend.updateCoinPriceVote(BigDecimal(5.0))
      },
    )(
      "CoinPriceVote contract for sv1 are updated",
      _ => {
        getCoinPriceVoteMap() shouldBe Map(
          svParties("sv1") -> Seq(Some(BigDecimal(5.0))),
          svParties("sv2") -> Seq(Some(BigDecimal(4.0))),
          svParties("sv3") -> Seq(Some(BigDecimal(3.0))),
          svParties("sv4") -> Seq(Some(BigDecimal(2.0))),
        )
      },
    )

    actAndCheck(
      "restarting all SVs", {
        svs.foreach(_.stop())
        startAllSync(svs: _*)
      },
    )(
      "CoinPriceVote contracts didn't change",
      _ => {
        getCoinPriceVoteMap() shouldBe Map(
          svParties("sv1") -> Seq(Some(BigDecimal(5.0))),
          svParties("sv2") -> Seq(Some(BigDecimal(4.0))),
          svParties("sv3") -> Seq(Some(BigDecimal(3.0))),
          svParties("sv4") -> Seq(Some(BigDecimal(2.0))),
        )
      },
    )
  }

  "archive duplicated and non-member CoinPriceVote contracts" in { implicit env =>
    initSvc()
    val svParties =
      Seq(("sv1", sv1Backend), ("sv2", sv2Backend), ("sv3", sv3Backend), ("sv4", sv4Backend)).map {
        case (svName, sv) => svName -> sv.getSvcInfo().svParty
      }.toMap

    eventually() {
      getCoinPriceVoteMap() shouldBe Map(
        svParties("sv1") -> Seq(Some(BigDecimal(1.0))),
        svParties("sv2") -> Seq(Some(BigDecimal(1.0))),
        svParties("sv3") -> Seq(None),
        svParties("sv4") -> Seq(None),
      )
    }

    actAndCheck(
      "remove sv3 on svcRules contract to trigger `GarbageCollectCoinPriceVotesTrigger` to non member votes", {
        val removeAction = new ARC_SvcRules(
          new SRARC_RemoveMember(
            new SvcRules_RemoveMember(
              svParties("sv3").toProtoPrimitive
            )
          )
        )

        val (_, voteRequest) = actAndCheck(
          "Creating vote request",
          eventuallySucceeds() {
            sv1Backend.createVoteRequest(
              sv1Backend.getSvcInfo().svParty.toProtoPrimitive,
              removeAction,
              "url",
              "remove sv3",
              sv1Backend.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
            )
          },
        )("vote request has been created", _ => sv1Backend.listVoteRequests().loneElement)
        Seq(sv2Backend, sv4Backend).foreach { sv =>
          clue(s"${sv.name} accepts vote") {
            val svVoteRequest = eventually() {
              sv.listVoteRequests().loneElement
            }
            svVoteRequest.contractId shouldBe voteRequest.contractId
            eventuallySucceeds() {
              sv.castVote(
                svVoteRequest.contractId,
                true,
                "url",
                "description",
              )
            }
          }
        }
      },
    )(
      "vote of sv3 is remove",
      _ =>
        getCoinPriceVoteMap() shouldBe Map(
          svParties("sv1") -> Seq(Some(BigDecimal(1.0))),
          svParties("sv2") -> Seq(Some(BigDecimal(1.0))),
          svParties("sv4") -> Seq(None),
        ),
    )
  }

  "At least 3 SVs can vote on changing the SvcRules Configuration" in { implicit env =>
    val newNumUnclaimedRewardsThreshold = 42

    clue("Initialize SVC with 4 SVs") {
      initSvc()
      eventually() {
        sv1Backend.getSvcInfo().svcRules.payload.members should have size 4
      }
    }

    val (_, (voteRequestCid, initialNumUnclaimedRewardsThreshold)) = actAndCheck(
      "SV1 create a vote request for a new SvcRules Configuration", {
        val newConfig = new SvcRulesConfig(
          newNumUnclaimedRewardsThreshold,
          sv1Backend.getSvcInfo().svcRules.payload.config.numMemberTrafficContractsThreshold,
          sv1Backend.getSvcInfo().svcRules.payload.config.actionConfirmationTimeout,
          sv1Backend.getSvcInfo().svcRules.payload.config.svOnboardingRequestTimeout,
          sv1Backend.getSvcInfo().svcRules.payload.config.svOnboardingConfirmedTimeout,
          sv1Backend.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
          sv1Backend.getSvcInfo().svcRules.payload.config.leaderInactiveTimeout,
          sv1Backend.getSvcInfo().svcRules.payload.config.domainNodeConfigLimits,
          sv1Backend.getSvcInfo().svcRules.payload.config.maxTextLength,
          sv1Backend.getSvcInfo().svcRules.payload.config.initialTrafficGrant,
          sv1Backend.getSvcInfo().svcRules.payload.config.svChallengeDeadline,
          sv1Backend.getSvcInfo().svcRules.payload.config.globalDomain,
          sv1Backend.getSvcInfo().svcRules.payload.config.nextScheduledDomainUpgrade,
        )

        val action: ActionRequiringConfirmation =
          new ARC_SvcRules(new SRARC_SetConfig(new SvcRules_SetConfig(newConfig)))

        sv1Backend.createVoteRequest(
          sv1Backend.getSvcInfo().svParty.toProtoPrimitive,
          action,
          "url",
          "description",
          sv1Backend.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
        )
      },
    )(
      "The vote request has been created, SV1 accepts as he created it and all other SVs observe it",
      _ => {
        svs.foreach { sv => sv.listVoteRequests() should not be empty }
        val head = sv1Backend.listVoteRequests().head.contractId
        sv1Backend.listVotes(Vector(head.contractId)) should have size 1
        (head, sv1Backend.getSvcInfo().svcRules.payload.config.numUnclaimedRewardsThreshold)
      },
    )

    actAndCheck(
      "SV2 votes on accepting the new configuration", {
        sv2Backend.castVote(voteRequestCid, true, "url", "description")
      },
    )(
      "The majority did not vote yet, thus the trigger should not change the svcRules",
      _ => {
        sv2Backend
          .getSvcInfo()
          .svcRules
          .payload
          .config
          .numUnclaimedRewardsThreshold shouldBe initialNumUnclaimedRewardsThreshold
      },
    )

    actAndCheck(
      "SV3 refuses the new configuration", {
        sv3Backend.castVote(voteRequestCid, false, "url", "description")
      },
    )(
      "The majority has voted but without an acceptance majority, the trigger should not change the svcRules",
      _ => {
        sv3Backend
          .getSvcInfo()
          .svcRules
          .payload
          .config
          .numUnclaimedRewardsThreshold shouldBe initialNumUnclaimedRewardsThreshold
      },
    )

    actAndCheck(
      "SV4 votes on accepting the new configuration", {
        sv4Backend.castVote(voteRequestCid, true, "url", "description")
      },
    )(
      "The majority accepts, the trigger should change the svcRules accordingly",
      _ => {
        sv4Backend
          .getSvcInfo()
          .svcRules
          .payload
          .config
          .numUnclaimedRewardsThreshold shouldBe newNumUnclaimedRewardsThreshold
      },
    )
  }

  "At least 3 SVs can vote on changing the Coin Configuration" in { implicit env =>
    clue("Initialize SVC with 4 SVs") {
      initSvc()
      eventually() {
        sv1Backend.getSvcInfo().svcRules.payload.members should have size 4
      }
    }

    val (_, (voteRequestCid, initialFutureValuesSize)) = actAndCheck(
      "SV1 create a vote request for a new Coin Configuration (changing the transfer config)", {

        val initialValue = sv1Backend.getSvcInfo().coinRules.payload.configSchedule.initialValue
        val transferConfig = initialValue.transferConfig
        val newTransferConfig = new TransferConfig[USD](
          transferConfig.createFee,
          transferConfig.holdingFee,
          transferConfig.transferFee,
          transferConfig.lockHolderFee,
          transferConfig.extraFeaturedAppRewardAmount,
          42,
          42,
          42,
        )

        val futureValue =
          new com.daml.network.codegen.java.da.types.Tuple2[Instant, CoinConfig[USD]](
            Instant.now().plusSeconds(3600),
            new CoinConfig[USD](
              newTransferConfig,
              initialValue.issuanceCurve,
              initialValue.globalDomain,
              initialValue.tickDuration,
              initialValue.packageConfig,
            ),
          )

        val action: ActionRequiringConfirmation =
          new ARC_CoinRules(
            new CRARC_AddFutureCoinConfigSchedule(
              new CoinRules_AddFutureCoinConfigSchedule(futureValue)
            )
          )

        sv1Backend.createVoteRequest(
          sv1Backend.getSvcInfo().svParty.toProtoPrimitive,
          action,
          "url",
          "description",
          sv1Backend.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
        )
      },
    )(
      "The vote request has been created and SV1 accepts as he created it",
      _ => {
        svs.foreach { sv => sv.listVoteRequests() should not be empty }
        val head = sv1Backend.listVoteRequests().head.contractId
        sv1Backend.listVotes(Vector(head.contractId)) should have size 1
        (head, sv1Backend.getSvcInfo().coinRules.payload.configSchedule.futureValues.size())
      },
    )

    actAndCheck(
      "SV2 votes on accepting the new configuration", {
        sv2Backend.castVote(voteRequestCid, true, "url", "description")
      },
    )(
      "The majority did not vote yet, thus the trigger should not change the coin config futureValues",
      _ => {
        sv2Backend
          .getSvcInfo()
          .coinRules
          .payload
          .configSchedule
          .futureValues
          .size() shouldBe initialFutureValuesSize
      },
    )

    actAndCheck(
      "SV3 refuses the new configuration", {
        sv3Backend.castVote(voteRequestCid, false, "url", "description")
      },
    )(
      "The majority has voted but without an acceptance majority, the trigger should not change the coin config futureValues",
      _ => {
        sv3Backend
          .getSvcInfo()
          .coinRules
          .payload
          .configSchedule
          .futureValues
          .size() shouldBe initialFutureValuesSize
      },
    )

    actAndCheck(
      "SV4 votes on accepting the new configuration", {
        sv4Backend.castVote(voteRequestCid, true, "url", "description")
      },
    )(
      "The majority accepts, the trigger should change the coin config futureValues",
      _ => {
        sv4Backend
          .getSvcInfo()
          .coinRules
          .payload
          .configSchedule
          .futureValues
          .size() shouldBe initialFutureValuesSize + 1
      },
    )

    clue("We should be able to query vote requests that have been executed") {
      eventually() {
        val voteResult =
          sv1Backend.listVoteResults(None, Some(true), None, None, None, 1).headOption.value

        voteResult.executed shouldBe true
        voteResult.acceptedBy should contain(
          sv1Backend.getSvcInfo().svParty.toProtoPrimitive
        )
        voteResult.acceptedBy should contain(
          sv2Backend.getSvcInfo().svParty.toProtoPrimitive
        )
        voteResult.acceptedBy should contain(
          sv4Backend.getSvcInfo().svParty.toProtoPrimitive
        )
      }
    }

  }

  "Vote requests expire" in { implicit env =>
    clue("Initialize SVC with 2 SVs") {
      startAllSync(
        sv1Backend,
        sv2Backend,
      )
      eventually() {
        sv1Backend.getSvcInfo().svcRules.payload.members should have size 2
      }
    }
    clue("Pausing vote request expiration automation") {
      sv1Backend.leaderBasedAutomation.trigger[ExpireVoteRequestTrigger].pause().futureValue
    }
    actAndCheck(
      "SV2 creates a vote request for removing SV1", {
        val sv1Party = sv1Backend.getSvcInfo().svParty
        val sv2Party = sv2Backend.getSvcInfo().svParty
        val action: ActionRequiringConfirmation = new ARC_SvcRules(
          new SRARC_RemoveMember(new SvcRules_RemoveMember(sv1Party.toProtoPrimitive))
        )

        sv2Backend.createVoteRequest(
          sv2Party.toProtoPrimitive,
          action,
          "url",
          "description",
          // expire in 5 seconds
          new RelTime(5_000_000L),
        )
      },
    )(
      "The vote request has been created and all SVs observe it",
      _ => {
        sv1Backend.listVoteRequests() should have size 1
        sv2Backend.listVoteRequests() should have size 1
      },
    )
    clue("Resuming vote request expiration automation") {
      sv1Backend.leaderBasedAutomation.trigger[ExpireVoteRequestTrigger].resume()
    }
    clue("Eventually the vote request expires and gets archived") {
      eventually() {
        sv1Backend.listVoteRequests() shouldBe empty
        sv2Backend.listVoteRequests() shouldBe empty
      }
    }
  }

  private def getCoinPriceVoteMap()(implicit env: CNNodeTestConsoleEnvironment) =
    sv1Backend
      .listCoinPriceVotes()
      .groupBy(_.payload.sv)
      .flatMap { case (sv, contracts) =>
        Codec
          .decode(Codec.Party)(sv)
          .map(p =>
            p -> contracts.map(
              _.payload.coinPrice.toScala.map(BigDecimal(_))
            )
          )
          .toOption
      }

}

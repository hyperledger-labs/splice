package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_SetConfig
import com.daml.network.codegen.java.cn.svcrules.{
  ActionRequiringConfirmation,
  SvcRulesConfig,
  SvcRules_SetConfig,
}
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.Codec
import com.digitalasset.canton.topology.PartyId

import java.time.Instant
import scala.jdk.OptionConverters.*

class SvStateManagementIntegrationTest extends SvIntegrationTestBase {

  "SVs can update their CoinPriceVote contracts" in { implicit env =>
    initSvc()
    val svParties = Seq(("sv1", sv1), ("sv2", sv2), ("sv3", sv3), ("sv4", sv4)).map {
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
        sv2.updateCoinPriceVote(BigDecimal(4.0))
        sv3.updateCoinPriceVote(BigDecimal(3.0))
        sv4.updateCoinPriceVote(BigDecimal(2.0))
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
        sv1.updateCoinPriceVote(BigDecimal(5.0))
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
    val svParties = Seq(("sv1", sv1), ("sv2", sv2), ("sv3", sv3), ("sv4", sv4)).map {
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
      "create duplicated vote for sv4", {
        createCoinPriceVote(svParties("sv4"), Some(BigDecimal(3.0)))
        createCoinPriceVote(svParties("sv4"), Some(BigDecimal(4.0)))
      },
    )(
      "observed duplicated coin price of sv4",
      _ =>
        getCoinPriceVoteMap() shouldBe Map(
          svParties("sv1") -> Seq(Some(BigDecimal(1.0))),
          svParties("sv2") -> Seq(Some(BigDecimal(1.0))),
          svParties("sv3") -> Seq(None),
          svParties("sv4") -> Seq(None, Some(BigDecimal(3.0)), Some(BigDecimal(4.0))),
        ),
    )

    actAndCheck(
      "execute an action to remove sv3 on svcRules contract to trigger `GarbageCollectCoinPriceVotesTrigger` to remove duplicated and non member votes", {
        svc.participantClient.ledger_api_extensions.commands.submitWithResult(
          svc.config.svUser,
          actAs = Seq(svcParty),
          readAs = Seq.empty,
          update = sv1
            .getSvcInfo()
            .svcRules
            .contractId
            .exerciseSvcRules_RemoveMember(
              svParties("sv3").toProtoPrimitive
            ),
        )
      },
    )(
      "vote of sv3 is removed and the all votes of sv4 are removed except the latest vote",
      _ =>
        getCoinPriceVoteMap() shouldBe Map(
          svParties("sv1") -> Seq(Some(BigDecimal(1.0))),
          svParties("sv2") -> Seq(Some(BigDecimal(1.0))),
          svParties("sv4") -> Seq(Some(BigDecimal(4.0))),
        ),
    )
  }

  "At least 3 SVs can vote on changing the SvcRules Configuration" in { implicit env =>
    val newNumUnclaimedRewardsThreshold = 42

    clue("Initialize SVC with 4 SVs") {
      initSvc()
      eventually() {
        sv1.getSvcInfo().svcRules.payload.members should have size 4
      }
    }

    val (_, (voteRequestCid, initialNumUnclaimedRewardsThreshold)) = actAndCheck(
      "SV1 create a vote request for a new SvcRules Configuration", {
        val newConfig = new SvcRulesConfig(
          newNumUnclaimedRewardsThreshold,
          sv1.getSvcInfo().svcRules.payload.config.actionConfirmationTimeout,
          sv1.getSvcInfo().svcRules.payload.config.svOnboardingRequestTimeout,
          sv1.getSvcInfo().svcRules.payload.config.svOnboardingConfirmedTimeout,
          sv1.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
          sv1.getSvcInfo().svcRules.payload.config.leaderInactiveTimeout,
          sv1.getSvcInfo().svcRules.payload.config.domainNodeConfigLimits,
          sv1.getSvcInfo().svcRules.payload.config.maxTextLength,
          sv1.getSvcInfo().svcRules.payload.config.globalDomain,
        )

        val action: ActionRequiringConfirmation =
          new ARC_SvcRules(new SRARC_SetConfig(new SvcRules_SetConfig(newConfig)))

        sv1.createVoteRequest(
          sv1.getSvcInfo().svParty.toProtoPrimitive,
          action,
          "url",
          "description",
        )
      },
    )(
      "The vote request has been created, SV1 accepts as he created it and all other SVs observe it",
      _ => {
        svs.foreach { sv => sv.listVoteRequests() should not be empty }
        val head = sv1.listVoteRequests().head.contractId
        sv1.listVotes(Vector(head.contractId)) should have size 1
        (head, sv1.getSvcInfo().svcRules.payload.config.numUnclaimedRewardsThreshold)
      },
    )

    actAndCheck(
      "SV2 votes on accepting the new configuration", {
        sv2.castVote(voteRequestCid, true, "url", "description")
      },
    )(
      "The majority did not vote yet, thus the trigger should not change the svcRules",
      _ => {
        sv2
          .getSvcInfo()
          .svcRules
          .payload
          .config
          .numUnclaimedRewardsThreshold shouldBe initialNumUnclaimedRewardsThreshold
      },
    )

    actAndCheck(
      "SV3 refuses the new configuration", {
        sv3.castVote(voteRequestCid, false, "url", "description")
      },
    )(
      "The majority has voted but without an acceptance majority, the trigger should not change the svcRules",
      _ => {
        sv3
          .getSvcInfo()
          .svcRules
          .payload
          .config
          .numUnclaimedRewardsThreshold shouldBe initialNumUnclaimedRewardsThreshold
      },
    )

    actAndCheck(
      "SV4 votes on accepting the new configuration", {
        sv4.castVote(voteRequestCid, true, "url", "description")
      },
    )(
      "The majority accepts, the trigger should change the svcRules accordingly",
      _ => {
        sv4
          .getSvcInfo()
          .svcRules
          .payload
          .config
          .numUnclaimedRewardsThreshold shouldBe newNumUnclaimedRewardsThreshold
      },
    )

  }

  private def getCoinPriceVoteMap()(implicit env: CNNodeTestConsoleEnvironment) =
    sv1
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

  private def createCoinPriceVote(
      svParty: PartyId,
      coinPrice: Option[BigDecimal],
  )(implicit env: CNNodeTestConsoleEnvironment) =
    svc.participantClient.ledger_api_extensions.commands.submitWithResult(
      svc.config.svUser,
      actAs = Seq(svcParty),
      readAs = Seq.empty,
      update = new cn.svc.coinprice.CoinPriceVote(
        svcParty.toProtoPrimitive,
        svParty.toProtoPrimitive,
        coinPrice.map(_.bigDecimal).toJava,
        Instant.now(),
      ).create,
    )

}

package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.coin.CoinRules_AddFutureCoinConfigSchedule
import com.daml.network.codegen.java.cc.coinconfig.{CoinConfig, TransferConfig, USD}
import com.daml.network.codegen.java.cn
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.{
  ARC_CoinRules,
  ARC_SvcRules,
}
import com.daml.network.codegen.java.cn.svcrules.coinrules_actionrequiringconfirmation.CRARC_AddFutureCoinConfigSchedule
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
        sv1Backend.participantClient.ledger_api_extensions.commands.submitWithResult(
          sv1Backend.config.ledgerApiUser,
          actAs = Seq(svcParty),
          readAs = Seq.empty,
          update = sv1Backend
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

  private def createCoinPriceVote(
      svParty: PartyId,
      coinPrice: Option[BigDecimal],
  )(implicit env: CNNodeTestConsoleEnvironment) =
    sv1Backend.participantClient.ledger_api_extensions.commands.submitWithResult(
      sv1Backend.config.ledgerApiUser,
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

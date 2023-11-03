package com.daml.network.integration.tests

import com.daml.network.codegen.java.{cc, da}
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_SetConfig
import com.daml.network.codegen.java.cn.svcrules.SvcRules_SetConfig
import com.daml.network.environment.DarResources
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import com.daml.network.util.{ConfigScheduleUtil, WalletTestUtil}

import java.util.List
import java.time.Instant
import scala.jdk.CollectionConverters.*

class ModelUpgradeIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with ConfigScheduleUtil
    with WalletTestUtil {

  "daml model upgrade" should {
    "support switching to new svc-governance version" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      aliceWalletClient.tap(10)
      val coin = aliceWalletClient.list().coins.loneElement.contract
      coin.identifier.getPackageId shouldBe DarResources.cantonCoin_0_1_0.packageId
      BigDecimal(coin.payload.amount.initialAmount) shouldBe 10.0

      val svcRules = sv1Backend.getSvcInfo().svcRules
      svcRules.identifier.getPackageId shouldBe DarResources.svcGovernance_0_1_0.packageId
      val coinRules = sv1ScanBackend.getCoinRules()
      // We go for a direct archive + recreate of CoinRules to change
      // the current version instead of having to deal with future
      // dating and associated sleeps/simtime.
      val coinConfig = coinRules.payload.configSchedule.initialValue
      val newSchedule = new cc.schedule.Schedule(
        new cc.coinconfig.CoinConfig(
          coinConfig.transferConfig,
          coinConfig.issuanceCurve,
          coinConfig.globalDomain,
          coinConfig.tickDuration,
          new cc.coinconfig.PackageConfig(
            "0.2.0",
            "0.2.0",
            "0.2.0",
            "0.2.0",
            "0.1.0",
            "0.2.0",
            "0.2.0",
          ),
        ),
        List.of[da.types.Tuple2[Instant, cc.coinconfig.CoinConfig[cc.coinconfig.USD]]](),
      )
      val newCoinRules = new cc.coin.CoinRules(
        coinRules.payload.svc,
        newSchedule,
        coinRules.payload.isDevNet,
        coinRules.payload.upgrade,
      )
      clue("Activating new version") {
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJava(
            applicationId = sv1Backend.config.ledgerApiUser,
            actAs = Seq(svcParty),
            readAs = Seq.empty,
            commands = coinRules.contractId.exerciseArchive().commands.asScala.toSeq ++
              newCoinRules.create.commands.asScala.toSeq,
            optTimeout = None,
          )
      }

      // Vote on a dummy change on svc rules to ensure it is archived and recreated
      // which indicates the new choice is being used.
      val newConfig = sv1Backend.getSvcInfo().svcRules.payload.config
      val action = new ARC_SvcRules(new SRARC_SetConfig(new SvcRules_SetConfig(newConfig)))
      actAndCheck(
        "Voting on an SvcRules config change", {
          val (_, voteRequest) = actAndCheck(
            "Creating vote request",
            eventuallySucceeds() {
              sv1Backend.createVoteRequest(
                sv1Backend.getSvcInfo().svParty.toProtoPrimitive,
                action,
                "url",
                "description",
                sv1Backend.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
              )
            },
          )("vote request has been created", _ => sv1Backend.listVoteRequests().loneElement)
          Seq(sv2Backend, sv3Backend).foreach { sv =>
            clue(s"${sv.name} accepts vote") {
              val svVoteRequest = eventually() {
                sv.listVoteRequests().loneElement
              }
              svVoteRequest.contractId shouldBe voteRequest.contractId
              // We need an eventually to ensure that the SV has ingested the coin rules change.
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
        "observing svc rules with new package id",
        _ => {
          val newSvcRules = sv1Backend.getSvcInfo().svcRules
          newSvcRules.identifier.getPackageId shouldBe DarResources.svcGovernance_0_2_0.packageId
        },
      )

      clue("Alice taps after upgrade") {
        eventuallySucceeds() {
          aliceWalletClient.tap(20)
        }
      }
      clue("Old and new coin get merged together into a new coin") {
        eventually() {
          val coin = aliceWalletClient.list().coins.loneElement.contract
          coin.identifier.getPackageId shouldBe DarResources.cantonCoin_0_2_0.packageId
          BigDecimal(coin.payload.amount.initialAmount) should beWithin(30 - smallAmount, 30)
        }
      }
    }
  }
}

package com.daml.network.integration.tests

import cats.syntax.parallel.*
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.util.FutureInstances.*
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coinrules.CoinRules_AddFutureCoinConfigSchedule
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.{ARC_CoinRules}
import com.daml.network.codegen.java.cn.svcrules.coinrules_actionrequiringconfirmation.CRARC_AddFutureCoinConfigSchedule
import com.daml.network.environment.{CNNodeEnvironmentImpl, DarResources}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{ConfigScheduleUtil, SvTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.jdk.CollectionConverters.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContext, Future}

class ModelUpgradeIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with ConfigScheduleUtil
    with WalletTestUtil
    with SvTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withSequencerConnectionsFromScanDisabled()

  "daml model upgrade" should {
    "support switching to new svc-governance version" in { implicit env =>
      implicit val ec: ExecutionContext = env.executionContext
      val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bob = onboardWalletUser(bobWalletClient, bobValidatorBackend)

      aliceWalletClient.tap(10)
      val coin = aliceWalletClient.list().coins.loneElement.contract
      coin.identifier.getPackageId shouldBe DarResources.cantonCoin_0_1_0.packageId
      BigDecimal(coin.payload.amount.initialAmount) shouldBe 10.0

      p2pTransfer(aliceWalletClient, bobWalletClient, bob, 5.0)

      val svcRules = sv1Backend.getSvcInfo().svcRules
      svcRules.identifier.getPackageId shouldBe DarResources.svcGovernance_0_1_0.packageId

      val coinRules = sv1ScanBackend.getCoinRules()
      val coinConfig = coinRules.payload.configSchedule.initialValue

      // Ideally we'd like the config to take effect immediately. However, we
      // can only schedule configs in the future and this is enforced at the Daml level.
      // So we pick a date that is far enough in the future that we can complete the voting process
      // before it is reached but close enough that we don't need to wait for long.
      // 12 seconds seems to work well empirically.
      val scheduledTime = Instant.now().plus(12, ChronoUnit.SECONDS)
      val upgradeAction = new ARC_CoinRules(
        new CRARC_AddFutureCoinConfigSchedule(
          new CoinRules_AddFutureCoinConfigSchedule(
            new com.daml.network.codegen.java.da.types.Tuple2(
              scheduledTime,
              new cc.coinconfig.CoinConfig(
                coinConfig.transferConfig,
                coinConfig.issuanceCurve,
                coinConfig.globalDomain,
                coinConfig.tickDuration,
                new cc.coinconfig.PackageConfig(
                  "0.2.0",
                  "0.2.0",
                  "0.2.0",
                  "0.1.0",
                  "0.2.0",
                  "0.2.0",
                ),
              ),
            )
          )
        )
      )

      actAndCheck(
        "Voting on a CoinRules config change for upgraded packages", {
          val (_, voteRequest) = actAndCheck(
            "Creating vote request",
            eventuallySucceeds() {
              sv1Backend.createVoteRequest(
                sv1Backend.getSvcInfo().svParty.toProtoPrimitive,
                upgradeAction,
                "url",
                "description",
                sv1Backend.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
              )
            },
          )("vote request has been created", _ => sv1Backend.listVoteRequests().loneElement)

          Seq(sv2Backend, sv3Backend).parTraverse { sv =>
            Future {
              clue("both sv2 and sv3 see the vote request") {
                val svVoteRequest = eventually() {
                  sv.listVoteRequests().loneElement
                }
                svVoteRequest.contractId shouldBe voteRequest.contractId
              }
              clue(s"${sv.name} accepts vote") {
                eventuallySucceeds() {
                  sv.castVote(
                    voteRequest.contractId,
                    true,
                    "url",
                    "description",
                  )
                }
              }
            }
          }.futureValue
        },
      )(
        "observing CoinRules with upgraded config",
        _ => {
          val newCoinRules = sv1Backend.getSvcInfo().coinRules
          val configs =
            (newCoinRules.payload.configSchedule.initialValue :: newCoinRules.payload.configSchedule.futureValues.asScala.toList
              .map(_._2))
          configs.map(_.packageConfig.cantonCoin) should contain("0.2.0")
        },
      )

      // Ensure that the code below really uses the new version. Locally things can be sufficiently
      // fast that you otherwise still end up using the old version.
      env.environment.clock
        .scheduleAt(
          _ => (),
          CantonTimestamp.assertFromInstant(scheduledTime.plus(500, ChronoUnit.MILLIS)),
        )
        .unwrap
        .futureValue

      // Vote on a dummy change on coin rules to ensure it is archived and recreated
      // which indicates the new choice is being used.
      val dummyUpgradeAction = new ARC_CoinRules(
        new CRARC_AddFutureCoinConfigSchedule(
          new CoinRules_AddFutureCoinConfigSchedule(
            new com.daml.network.codegen.java.da.types.Tuple2(
              Instant.now().plus(1, ChronoUnit.HOURS),
              coinConfig,
            )
          )
        )
      )

      actAndCheck(
        "Voting on a CoinRules config change for upgraded packages", {
          val (_, voteRequest) = actAndCheck(
            "Creating vote request",
            eventuallySucceeds() {
              sv1Backend.createVoteRequest(
                sv1Backend.getSvcInfo().svParty.toProtoPrimitive,
                dummyUpgradeAction,
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
        "observing CoinRules with new package id",
        _ => {
          val newCoinRules = sv1Backend.getSvcInfo().coinRules
          newCoinRules.identifier.getPackageId shouldBe DarResources.cantonCoin_0_2_0.packageId
        },
      )

      actAndCheck(
        "Alice taps after upgrade",
        eventuallySucceeds() {
          aliceWalletClient.tap(20)
        },
      )(
        "Old and new coin get merged together into a new coin",
        _ => {
          val coin = aliceWalletClient.list().coins.loneElement.contract
          coin.identifier.getPackageId shouldBe DarResources.cantonCoin_0_2_0.packageId
          BigDecimal(coin.payload.amount.initialAmount) should beWithin(25 - smallAmount, 25)
        },
      )
      // This is just to invalidate the coin rules cache on Bob’s side. In a real upgrade, the upgrade will be announced days or weeks in advance
      // while cache expiration is a few minutes so this is a non-issue.
      clue("Bob taps after upgrade") {
        eventuallySucceeds() {
          bobWalletClient.tap(5)
        }
      }
      actAndCheck(
        "Alice makes p2p transfer after upgrade",
        eventuallySucceeds() {
          p2pTransfer(aliceWalletClient, bobWalletClient, bob, 4.0)
        },
      )(
        "old and new transfers appear in scan tx log",
        _ => {
          val txs = sv1ScanBackend.listActivity(pageEndEventId = None, pageSize = 50)
          // new transfer
          forExactly(1, txs) { tx =>
            val tf = tx.transfer.value
            tf.sender.party shouldBe alice.toProtoPrimitive
            tf.receivers.loneElement.party shouldBe bob.toProtoPrimitive
            BigDecimal(tf.receivers.loneElement.amount) shouldBe 4.0
          }
          // old transfer
          forExactly(1, txs) { tx =>
            val tf = tx.transfer.value
            tf.sender.party shouldBe alice.toProtoPrimitive
            tf.receivers.loneElement.party shouldBe bob.toProtoPrimitive
            BigDecimal(tf.receivers.loneElement.amount) shouldBe 5.0
          }
        },
      )
    }
  }
}

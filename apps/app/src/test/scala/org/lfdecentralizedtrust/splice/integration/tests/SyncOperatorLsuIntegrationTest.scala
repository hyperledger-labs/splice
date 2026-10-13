// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import better.files.*
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.config.{FullClientConfig, NonNegativeFiniteDuration}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, Port}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.version.ProtocolVersion
import org.lfdecentralizedtrust.splice.automation.Trigger
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.GovernanceParameters
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.lsu.{LsuRollForwardTimestamp, LsuTransferTrafficTrigger}
import org.lfdecentralizedtrust.splice.syncoperator.automation.DedicatedLsuTrigger
import org.lfdecentralizedtrust.splice.syncoperator.config.{
  SyncOperatorLsuConfig,
  SyncOperatorMediatorConfig,
  SyncOperatorSequencerConfig,
  SyncOperatorSynchronizerNodeConfig,
}
import org.lfdecentralizedtrust.splice.util.{
  StandaloneCanton,
  SyncOperatorTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}

import java.time.Duration
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Upgrades the synchronizer the operator runs, on a schedule the operator sets itself, and checks
  * that members keep both what they purchased and what they have already spent.
  *
  * The successor's sequencer and mediator are started only for this test, uninitialized, so the
  * operator's LSU trigger initializes them from the synchronizer it is upgrading.
  */
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceDsoGovernance_0_1_31
class SyncOperatorLsuIntegrationTest
    extends IntegrationTest
    with StandaloneCanton
    with SyncOperatorTestUtil
    with TriggerTestUtil
    with WalletTestUtil {

  override def dbsSuffix = "sync_operator_lsu"

  override def usesDbs: Seq[String] = Seq(
    s"sequencer_${dbsSuffix}_successor",
    s"sequencer_driver_${dbsSuffix}_successor",
    s"mediator_${dbsSuffix}_successor",
  ) ++ super.usesDbs

  private val purchase = 2_000_000L
  private val splitwellAlias = SynchronizerAlias.tryCreate("splitwell")

  // The synchronizer is bootstrapped at serial 0 and protocol version 35, see bootstrap-canton.sc.
  private val successorSerial = NonNegativeInt.one
  private val successorPv = ProtocolVersion.v36

  // Ports of the standalone successor nodes, see standalone-sync-operator-successor.conf.
  private val successorSequencerAdminPort = Port.tryCreate(27609)
  private val successorSequencerPublicPort = Port.tryCreate(27608)
  private val successorMediatorAdminPort = Port.tryCreate(27607)

  // The operator reads its schedule from files, so the test writes them once it is ready rather
  // than picking a time while the environment is still starting.
  private lazy val scheduleDir = File.newTemporaryDirectory("sync-operator-lsu")
  private lazy val freezeTimeFile = scheduleDir / "topology-freeze-time"
  private lazy val upgradeTimeFile = scheduleDir / "upgrade-time"

  override def afterAll(): Unit = {
    scheduleDir.delete(swallowIOExceptions = true)
    super.afterAll()
  }

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .fromResources(
        Seq("simple-topology-1sv.conf", "sync-operator-topology.conf"),
        this.getClass.getSimpleName,
      )
      .withOnlyAliceValidatorConnectingToSplitwell
      .withStandardSetup
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateAllSyncOperatorAppConfigs_ { c =>
          c.copy(
            synchronizerNodes = c.synchronizerNodes.copy(
              // The mediator is only read during an upgrade, so the shared topology leaves it out.
              current = c.synchronizerNodes.current.copy(
                mediator = Some(
                  SyncOperatorMediatorConfig(FullClientConfig(port = Port.tryCreate(5707)))
                )
              ),
              successor = Some(
                SyncOperatorSynchronizerNodeConfig(
                  sequencer = SyncOperatorSequencerConfig(
                    adminApi = FullClientConfig(port = successorSequencerAdminPort),
                    internalApi = Some(FullClientConfig(port = successorSequencerPublicPort)),
                    externalPublicApiUrl =
                      Some(s"http://localhost:${successorSequencerPublicPort.unwrap}"),
                  ),
                  mediator = Some(
                    SyncOperatorMediatorConfig(FullClientConfig(port = successorMediatorAdminPort))
                  ),
                  protocolVersion = successorPv,
                  serial = Some(successorSerial),
                )
              ),
            ),
            lsu = Some(
              SyncOperatorLsuConfig(
                topologyFreezeTime = LsuRollForwardTimestamp.TimestampFromFile(freezeTimeFile.path),
                upgradeTime = LsuRollForwardTimestamp.TimestampFromFile(upgradeTimeFile.path),
                newPhysicalSynchronizerSerial = successorSerial,
                newPhysicalSynchronizerProtocolVersion = successorPv,
              )
            ),
            lsuDumpPath = Some((scheduleDir / "lsu-dump.json").path),
            parameters = c.parameters.copy(
              spliceCachingConfigs = c.parameters.spliceCachingConfigs.copy(
                physicalSynchronizerExpiration = NonNegativeFiniteDuration.ofSeconds(1)
              )
            ),
            // Both triggers reach the successor's nodes and log if they cannot, so they stay
            // paused until the test has started them.
            automation = c.automation
              .withPausedTrigger[DedicatedLsuTrigger]
              .withPausedTrigger[LsuTransferTrafficTrigger],
          )
        }(config)
      )

  "sync operator" should {

    "upgrade its synchronizer and carry the traffic state onto the successor" in { implicit env =>
      val operatorParty = syncOperatorBackend.appState.store.key.operatorParty
      val dsoParty = sv1Backend.getDsoInfo().dsoParty
      val dsoRules = sv1Backend.getDsoInfo().dsoRules
      val synchronizerId = aliceValidatorBackend.participantClientWithAdminToken.synchronizers
        .id_of(splitwellAlias)
        .logical
      val member = aliceValidatorBackend.participantClient.id

      clue("the DSO registers the synchronizer to this operator") {
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJava(
            actAs = Seq(dsoParty),
            readAs = Seq(dsoParty),
            commands = dsoRules.contractId
              .exerciseDsoRules_RegisterSynchronizer(
                synchronizerId.toProtoPrimitive,
                operatorParty.toProtoPrimitive,
                new GovernanceParameters(java.math.BigDecimal.ONE.setScale(10)),
              )
              .commands
              .asScala
              .toSeq,
            userId = sv1Backend.config.ledgerApiUser,
          )
      }

      val registration = eventually() {
        sv1ScanBackend.lookupSynchronizerRegistration(synchronizerId.toProtoPrimitive).value
      }

      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(walletUsdToAmulet(200.0))

      actAndCheck(
        "alice buys traffic for the operator's synchronizer",
        buyTraffic(aliceParty, member, synchronizerId, Some(registration), dsoParty, purchase),
      )(
        "the operator grants it on the synchronizer it is about to upgrade",
        _ => extraTrafficLimit(member) shouldBe purchase,
      )

      clue("some of it is spent, so there is consumption to carry across the upgrade") {
        eventually() {
          trafficState(member).map(_.extraTrafficConsumed.value).getOrElse(0L) should be > 0L
        }
      }

      val consumedBefore =
        trafficState(member).map(_.extraTrafficConsumed.value).getOrElse(0L)

      val currentPsid = syncOperatorBackend.appState.sequencerAdminConnection
        .getPhysicalSynchronizerId()
        .futureValue
      currentPsid.serial.value should be < successorSerial.value

      withCanton(
        Seq(testResourcesPath / "standalone-sync-operator-successor.conf"),
        Seq.empty,
        "sync-operator-lsu-successor",
        "SYNC_OPERATOR_SUCCESSOR_SEQUENCER_DB" -> s"sequencer_${dbsSuffix}_successor",
        "SYNC_OPERATOR_SUCCESSOR_SEQUENCER_DRIVER_DB" -> s"sequencer_driver_${dbsSuffix}_successor",
        "SYNC_OPERATOR_SUCCESSOR_MEDIATOR_DB" -> s"mediator_${dbsSuffix}_successor",
      ) {
        val lsuTrigger = syncOperatorBackend.appState.automation.trigger[DedicatedLsuTrigger]
        val trafficTrigger =
          syncOperatorBackend.appState.automation.trigger[LsuTransferTrafficTrigger]

        setTriggersWithin(triggersToResumeAtStart = Seq[Trigger](lsuTrigger, trafficTrigger)) {
          val upgradeTime = actAndCheck(
            "the operator schedules the upgrade", {
              val now = CantonTimestamp.now()
              // Long enough for the successor to be stood up, short enough that the test
              // still crosses it.
              val upgradeTime = now.plus(Duration.ofMinutes(3))
              freezeTimeFile.overwrite(now.toInstant.toString)
              upgradeTimeFile.overwrite(upgradeTime.toInstant.toString)
              upgradeTime
            },
          )(
            "the announcement is published on the synchronizer being upgraded",
            _ =>
              syncOperatorBackend.appState.sequencerAdminConnection
                .listLsuAnnouncements(currentPsid.logical)
                .futureValue
                .map(_.mapping.successorSynchronizerId.serial) should contain(successorSerial),
          )._1

          val successorNode =
            syncOperatorBackend.appState.synchronizerNodes.successor.value

          clue(s"the successor's nodes are initialized from the predecessor before $upgradeTime") {
            eventuallySucceeds(5.minutes) {
              val successorPsid =
                successorNode.sequencerAdminConnection.getPhysicalSynchronizerId().futureValue
              successorPsid.logical shouldBe currentPsid.logical
              successorPsid.serial shouldBe successorSerial
              successorPsid.protocolVersion shouldBe successorPv
            }
            eventually() {
              successorNode.mediatorAdminConnection.isNodeInitialized().futureValue shouldBe true
            }
          }

          clue("both halves of the traffic state are carried onto the successor") {
            eventually(5.minutes) {
              val state = successorNode.sequencerAdminConnection
                .lookupSequencerTrafficControlState(member)
                .futureValue
                .value
              state.extraTrafficLimit.value shouldBe purchase
              // Without the transfer this resets to zero and members get back what they spent.
              state.extraTrafficConsumed.value should be >= consumedBefore
            }
          }

          clue(s"the participant follows the upgrade onto the successor at $upgradeTime") {
            eventually(10.minutes) {
              val psid = aliceValidatorBackend.participantClientWithAdminToken.synchronizers
                .list_connected()
                .find(_.synchronizerAlias == splitwellAlias)
                .value
                .physicalSynchronizerId
              psid.logical shouldBe currentPsid.logical
              psid.serial shouldBe successorSerial
            }
          }

          actAndCheck(10.minutes)(
            "alice buys traffic again once the upgrade has landed",
            buyTraffic(aliceParty, member, synchronizerId, Some(registration), dsoParty, purchase),
          )(
            "the operator grants it on the successor's sequencer, so it switched over too",
            _ =>
              successorNode.sequencerAdminConnection
                .lookupSequencerTrafficControlState(member)
                .futureValue
                .value
                .extraTrafficLimit
                .value shouldBe (purchase * 2),
          )

          clue("and the participant can still transact on the upgraded synchronizer") {
            aliceValidatorBackend.participantClient.health
              .ping(
                aliceValidatorBackend.participantClient.id,
                synchronizerId = Some(synchronizerId),
              )
          }
        }
      }
    }
  }
}

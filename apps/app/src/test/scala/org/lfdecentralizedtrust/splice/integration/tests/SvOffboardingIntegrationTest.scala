// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.topology.{MediatorId, SequencerId}
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.util.FutureInstances.*
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.{
  SRARC_CreateTransferCommandCounter,
  SRARC_OffboardSv,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  IsTheCantonSequencerBFTEnabled,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.ExecuteConfirmedActionTrigger
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.{
  LocalSequencerConnectionsTrigger,
  SvBftSequencerPeerOffboardingTrigger,
}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.offboarding.{
  SvOffboardingMediatorTrigger,
  SvOffboardingSequencerTrigger,
}
import org.lfdecentralizedtrust.splice.util.{ProcessTestUtil, StandaloneCanton}
import org.scalatest.time.{Minute, Span}
import cats.syntax.parallel.*
import cats.instances.seq.*
import org.lfdecentralizedtrust.splice.util.TriggerTestUtil.{
  pauseAllDsoDelegateTriggers,
  resumeAllDsoDelegateTriggers,
}

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.jdk.OptionConverters.RichOptional

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_11
class SvOffboardingIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with StandaloneCanton {

  override protected def runEventHistorySanityCheck: Boolean = false

  override def dbsSuffix = "offboarding"

  // Runs against a temporary Canton instance.
  override lazy val resetRequiredTopologyState = false

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))
  // the port overrides below trip the HTTP one
  override protected def runTokenStandardCliSanityCheck: Boolean = false
  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withPreSetup(_ => ())
      .addConfigTransformsToFront(
        (_, conf) => ConfigTransforms.bumpCantonPortsBy(22_000)(conf),
        (_, conf) => ConfigTransforms.bumpCantonDomainPortsBy(22_000)(conf),
      )
      .addConfigTransformsToFront((_, conf) =>
        ConfigTransforms.bumpRemoteSplitwellPortsBy(22_000)(conf)
      )
      .withSequencerConnectionsFromScanDisabled(22_000)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[LocalSequencerConnectionsTrigger]
        )(config)
      )
      .addConfigTransform((_, config) => ConfigTransforms.withResumedOffboardingTriggers()(config))
      .withCantonNodeNameSuffix("SvOffboarding")
      .withManualStart

  "Off-boarding SV4 updates the topology states" in { implicit env =>
    // Mediator offboarding leaves the offboarded mediator in a permanently broken state.
    // To make sure this doesn't keep a Canton instance that spams us with logs for that mediator,
    // we use a dedicated Canton instance for this test that is shut down at the end.
    import env.executionContext
    withCantonSvNodes(
      (
        Some(sv1Backend),
        Some(sv2Backend),
        Some(sv3Backend),
        Some(sv4Backend),
      ),
      "mediator-offboarding",
    )() {
      clue("Initialize DSO with 4 SVs") {
        startAllSync(
          (sv1Nodes ++ sv2Nodes ++ sv3Nodes ++ sv4Nodes)*
        )
      }

      pauseAllDsoDelegateTriggers[ExecuteConfirmedActionTrigger]

      val externalPartyAmuletRules = sv1ScanBackend.getExternalPartyAmuletRules()
      // Create TransferCommand to trigger creation of confirmations for creating the transfer command counter.
      // We don't want to test external parties in this test so we just create it directly from SV1.
      actAndCheck(
        "Create TransferCommand",
        sv1Backend.participantClient.ledger_api_extensions.commands.submitJava(
          actAs = Seq(sv1Backend.getDsoInfo().svParty),
          readAs = Seq(sv1Backend.getDsoInfo().dsoParty),
          commands = externalPartyAmuletRules.contractId
            .exerciseExternalPartyAmuletRules_CreateTransferCommand(
              sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
              sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
              sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
              BigDecimal(0.0).bigDecimal,
              Instant.now().plus(5, ChronoUnit.MINUTES),
              0L,
              java.util.Optional.empty(),
              java.util.Optional.of(dsoParty.toProtoPrimitive),
            )
            .commands
            .asScala
            .toSeq,
          userId = sv1Backend.config.ledgerApiUser,
        ),
      )(
        "Wait for 4 confirmations to be created for creating transfer command counter",
        _ => {
          val confirmations = sv1Backend.participantClient.ledger_api_extensions.acs
            .filterJava(Confirmation.COMPANION)(
              sv1Backend.getDsoInfo().dsoParty,
              c =>
                c.data.action match {
                  case arcDsoRules: ARC_DsoRules =>
                    arcDsoRules.dsoAction match {
                      case _: SRARC_CreateTransferCommandCounter => true
                      case _ => false
                    }
                  case _ => false
                },
            )
          confirmations should have size (4)
        },
      )

      val (_, voteRequestCid4) = actAndCheck(
        "SV1 create a vote request to remove sv4", {
          val action: ActionRequiringConfirmation =
            new ARC_DsoRules(
              new SRARC_OffboardSv(
                new DsoRules_OffboardSv(sv4Backend.getDsoInfo().svParty.toProtoPrimitive)
              )
            )
          sv1Backend.createVoteRequest(
            sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
            action,
            "https://vote-request-url.com",
            "description",
            new RelTime(durationUntilExpiration.toMillis * 1000),
            Some(env.environment.clock.now.add(durationUntilOffboardingEffectivity).toInstant),
          )
        },
      )(
        "The vote request has been created",
        _ => {
          val voteRequestCid = sv1Backend.listVoteRequests().headOption.value.contractId
          Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).foreach { sv =>
            sv.listVoteRequests().headOption.value.contractId shouldBe voteRequestCid
          }
          voteRequestCid
        },
      )

      // offboarding the sequencer/mediator here can cause very long timeouts in
      // ExecuteConfirmedActionTrigger as transactions from other arbitrary
      // triggers can hold up the sequencing of TransferCommandCounter creation.
      // We're really interested in Splice governance offboarding in this test
      // so focus on robustness to that here.
      def cantonMediatorSequencerTriggers = Seq(sv1Backend, sv2Backend, sv3Backend).flatMap { svb =>
        Seq(
          svb.dsoAutomation.trigger[SvOffboardingMediatorTrigger],
          svb.dsoAutomation.trigger[SvOffboardingSequencerTrigger],
        ) ++ {
          if (IsTheCantonSequencerBFTEnabled) {
            Seq(svb.dsoAutomation.trigger[SvBftSequencerPeerOffboardingTrigger])
          } else {
            Seq.empty
          }
        }
      }
      withClue("pause offboarding triggers") {
        cantonMediatorSequencerTriggers.parTraverse_(_.pause()).futureValue
      }

      actAndCheck(
        "SV2 votes on removing sv4", {
          sv2Backend.castVote(voteRequestCid4, true, "https://vote-request-url.com", "description")
        },
      )(
        "The majority has voted but without an acceptance majority, the trigger should not remove sv4",
        _ => {
          sv3Backend.getDsoInfo().dsoRules.payload.svs should have size 4
        },
      )

      actAndCheck(timeUntilSuccess = 40.seconds)(
        // We need SV4's vote here for immediate offboarding
        "SV3 and SV4 vote on removing sv4", {
          sv3Backend.castVote(voteRequestCid4, true, "https://vote-request-url.com", "description")
          sv4Backend.castVote(voteRequestCid4, true, "https://vote-request-url.com", "description")
        },
      )(
        "Everyone voted, thus the trigger should remove the dso party hosting for sv4",
        _ => {
          sv3Backend.getDsoInfo().dsoRules.payload.svs should have size 3
          suppressFailedClues(loggerFactory) {
            clue("Check partyToParticipant offboarding") {
              val mapping = sv3Backend.appState.participantAdminConnection
                .getPartyToParticipant(
                  decentralizedSynchronizerId,
                  sv3Backend.getDsoInfo().dsoParty,
                )
                .futureValue
                .mapping
              mapping.threshold shouldBe PositiveInt.tryCreate(2)
              mapping.participants.map(_.participantId.uid.namespace) should not contain sv4Backend
                .getDsoInfo()
                .svParty
                .uid
                .namespace
              // wait for SV to be removed from Daml code
              sv3Backend.getDsoInfo().dsoRules.payload.offboardedSvs.keySet() should contain(
                sv4Backend.getDsoInfo().svParty.toProtoPrimitive
              )
            }
          }
        },
      )

      // Check that the ExecuteConfirmedActionTrigger ignores the confirmation from SV4 now that it is no longer a member.
      sv1ScanBackend.lookupTransferCommandCounterByParty(
        sv1Backend.getDsoInfo().svParty
      ) shouldBe None
      actAndCheck(timeUntilSuccess = 60.seconds)(
        "Resume ExecuteConfirmedActionTrigger",
        resumeAllDsoDelegateTriggers[ExecuteConfirmedActionTrigger],
      )(
        "TransferCommandCounter gets created",
        (_: Unit) =>
          sv1ScanBackend.lookupTransferCommandCounterByParty(
            sv1Backend.getDsoInfo().svParty
          ) shouldBe a[Some[_]],
      )

      actAndCheck(
        "resume offboarding triggers",
        cantonMediatorSequencerTriggers.foreach(_.resume()),
      )(
        "mediator and sequencer offboard",
        _ =>
          suppressFailedClues(loggerFactory) {
            clue("Check decentralized namespace offboarding") {
              val decentralizedNamespaces =
                sv1Backend.participantClient.topology.decentralized_namespaces
                  .list(
                    store = TopologyStoreId.Synchronizer(decentralizedSynchronizerId),
                    filterNamespace = dsoParty.uid.namespace.toProtoPrimitive,
                  )
              inside(decentralizedNamespaces) { case Seq(decentralizedNamespace) =>
                decentralizedNamespace.item.owners shouldBe Seq(
                  sv1Backend,
                  sv2Backend,
                  sv3Backend,
                )
                  .map(_.participantClient.id.uid.namespace)
                  .toSet
              }
            }

            clue("Check mediator offboarding") {
              val mediators =
                sv3Backend.appState.participantAdminConnection
                  .getMediatorSynchronizerState(decentralizedSynchronizerId)
                  .futureValue
                  .mapping
                  .active
                  .forgetNE
                  .toSet

              mediators.size shouldBe 3
              mediators shouldBe sv3Backend
                .getDsoInfo()
                .svNodeStates
                .values
                .flatMap(_.payload.state.synchronizerNodes.values().asScala)
                .flatMap(_.mediator.toScala)
                .map(_.mediatorId)
                .flatMap(mediatorId =>
                  MediatorId
                    .fromProtoPrimitive(mediatorId, "mediatorId")
                    .fold(
                      error => {
                        logger.warn(s"Failed to parse mediator id $mediatorId. $error")
                        None
                      },
                      Some(_),
                    )
                )
                .toSet
            }

            clue("Check sequencer offboarding") {
              val sequencers =
                sv3Backend.appState.participantAdminConnection
                  .getSequencerSynchronizerState(decentralizedSynchronizerId)
                  .futureValue
                  .mapping
                  .active
                  .forgetNE
                  .toSet

              sequencers.size shouldBe 3
              sequencers shouldBe sv3Backend
                .getDsoInfo()
                .svNodeStates
                .values
                .flatMap(_.payload.state.synchronizerNodes.values().asScala)
                .flatMap(_.sequencer.toScala)
                .map(_.sequencerId)
                .flatMap(sequencerId =>
                  SequencerId
                    .fromProtoPrimitive(sequencerId, "sequencerId")
                    .fold(
                      error => {
                        logger.warn(s"Failed to parse sequencer id $sequencerId. $error")
                        None
                      },
                      Some(_),
                    )
                )
                .toSet
            }

            if (IsTheCantonSequencerBFTEnabled) {
              clue("check sequencer offboarded from p2p connections") {
                sv3Backend.appState.localSynchronizerNode.value.sequencerAdminConnection
                  .listCurrentPeerEndpoints()
                  .futureValue
                  .size shouldBe 2
              }
            }
          },
      )
    }
  }
}

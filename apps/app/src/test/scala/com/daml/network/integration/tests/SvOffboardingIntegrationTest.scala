// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.integration.tests

import com.daml.network.codegen.java.splice.dsorules.*
import com.daml.network.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import com.daml.network.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_OffboardSv
import com.daml.network.config.ConfigTransforms
import com.daml.network.config.ConfigTransforms.{updateAutomationConfig, ConfigurableApp}
import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import com.daml.network.sv.automation.singlesv.LocalSequencerConnectionsTrigger
import com.daml.network.sv.automation.singlesv.offboarding.{
  SvOffboardingMediatorTrigger,
  SvOffboardingSequencerTrigger,
}
import com.daml.network.util.{ProcessTestUtil, StandaloneCanton}
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.{MediatorId, SequencerId}
import org.scalatest.time.{Minute, Span}

import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.jdk.OptionConverters.RichOptional

class SvOffboardingIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with StandaloneCanton {

  override def dbsSuffix = "offboarding"

  // Runs against a temporary Canton instance.
  override lazy val resetRequiredTopologyState = false

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))
  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
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
          _.withResumedTrigger[SvOffboardingMediatorTrigger]
            .withResumedTrigger[SvOffboardingSequencerTrigger]
            .withPausedTrigger[LocalSequencerConnectionsTrigger]
        )(config)
      )
      .withCantonNodeNameSuffix("SvOffboarding")
      .withManualStart

  "Off-boarding SV4 updates the topology states" in { implicit env =>
    // Mediator offboarding leaves the offboarded mediator in a permanently broken state.
    // To make sure this doesn't keep a Canton instance that spams us with logs for that mediator,
    // we use a dedicated Canton instance for this test that is shut down at the end.
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
          sv1ScanBackend,
          sv2ScanBackend,
          sv1Backend,
          sv2Backend,
          sv3Backend,
          sv4Backend,
          sv1ValidatorBackend,
          sv2ValidatorBackend,
          sv3ValidatorBackend,
          sv4ValidatorBackend,
        )
      }

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
            "url",
            "description",
            sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
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

      actAndCheck(
        "SV2 votes on removing sv4", {
          sv2Backend.castVote(voteRequestCid4, true, "url", "description")
        },
      )(
        "The majority has voted but without an acceptance majority, the trigger should not remove sv4",
        _ => {
          sv3Backend.getDsoInfo().dsoRules.payload.svs should have size 4
        },
      )

      actAndCheck(
        // We need SV4's vote here for immediate offboarding
        "SV3 and SV4 vote on removing sv4", {
          sv3Backend.castVote(voteRequestCid4, true, "url", "description")
          sv4Backend.castVote(voteRequestCid4, true, "url", "description")
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
              sv3Backend.getDsoInfo().dsoRules.payload.offboardedSvs.keySet() should contain(
                sv4Backend.getDsoInfo().svParty.toProtoPrimitive
              )
            }

            clue("Check decentralized namespace offboarding") {
              val decentralizedNamespaces =
                sv1Backend.participantClient.topology.decentralized_namespaces
                  .list(
                    filterStore = decentralizedSynchronizerId.filterString,
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
                  .getMediatorDomainState(decentralizedSynchronizerId)
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
                  .getSequencerDomainState(decentralizedSynchronizerId)
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
          }
        },
      )
    }
  }
}

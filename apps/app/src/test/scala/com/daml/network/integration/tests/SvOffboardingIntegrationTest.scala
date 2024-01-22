package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.svcrules.*
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_RemoveMember
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.UseInMemoryStores
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.automation.singlesv.offboarding.SvOffboardingMediatorTrigger
import com.daml.network.util.ProcessTestUtil
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.MediatorId
import org.scalatest.time.{Minute, Span}

import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.jdk.OptionConverters.RichOptional

// Test can run only once until databases are reset (e.g. through rerunning `./stop-canton.sh && ./start-canton.sh`)
class SvOffboardingIntegrationTest extends CNNodeIntegrationTest with ProcessTestUtil {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))
  // TODO(#9014) make it work with persistend stores
  registerPlugin(new UseInMemoryStores(loggerFactory))

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withPreSetup(_ => ())
      .addConfigTransformsToFront(
        (_, conf) => CNNodeConfigTransforms.bumpCantonPortsBy(24_000)(conf),
        (_, conf) => CNNodeConfigTransforms.bumpCantonDomainPortsBy(24_000)(conf),
      )
      .addConfigTransformsToFront((_, conf) =>
        CNNodeConfigTransforms.bumpRemoteSplitwellPortsBy(24_000)(conf)
      )
      .withSequencerConnectionsFromScanDisabled(24_000)
      .addConfigTransforms((_, config) =>
        CNNodeConfigTransforms.updateAllAutomationConfigs(
          _.withResumedTrigger[SvOffboardingMediatorTrigger]
        )(config)
      )
      .withManualStart

  "Off-boarding SV4 updates the topology states" in { implicit env =>
    // Mediator offboarding leaves the offboarded mediator in a permanently broken state.
    // To make sure this doesn't keep a Canton instance that spams us with logs for that mediator,
    // we use a dedicated Canton instance for this test that is shut down at the end.
    withCanton(
      Seq(
        testResourcesPath / "mediator-offboarding-canton.conf"
      ),
      Seq(),
      "mediator-offboarding",
      "SV1_ADMIN_USER" -> sv1Backend.config.ledgerApiUser,
      "SV2_ADMIN_USER" -> sv2Backend.config.ledgerApiUser,
      "SV3_ADMIN_USER" -> sv3Backend.config.ledgerApiUser,
      "SV4_ADMIN_USER" -> sv4Backend.config.ledgerApiUser,
      "ALICE_ADMIN_USER" -> aliceValidatorBackend.config.ledgerApiUser,
      "BOB_ADMIN_USER" -> bobValidatorBackend.config.ledgerApiUser,
      "SPLITWELL_ADMIN_USER" -> splitwellValidatorBackend.config.ledgerApiUser,
    ) {
      clue("Initialize SVC with 4 SVs") {
        startAllSync(
          sv1ScanBackend,
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
            new ARC_SvcRules(
              new SRARC_RemoveMember(
                new SvcRules_RemoveMember(sv4Backend.getSvcInfo().svParty.toProtoPrimitive)
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
          sv3Backend.getSvcInfo().svcRules.payload.members should have size 4
        },
      )

      actAndCheck(
        "SV3 votes on removing sv4", {
          sv3Backend.castVote(voteRequestCid4, true, "url", "description")
        },
      )(
        "The majority voted yet, thus the trigger should remove the svc party hosting for sv4",
        _ => {
          sv3Backend.getSvcInfo().svcRules.payload.members should have size 3
          suppressFailedClues(loggerFactory) {
            clue("Check partyToParticipant offboarding") {
              val mapping = sv3Backend.appState.participantAdminConnection
                .getPartyToParticipant(globalDomainId, sv3Backend.getSvcInfo().svcParty)
                .futureValue
                .mapping
              mapping.threshold shouldBe PositiveInt.tryCreate(2)
              mapping.participants.map(_.participantId.uid.namespace) should not contain sv4Backend
                .getSvcInfo()
                .svParty
                .uid
                .namespace
              sv3Backend.getSvcInfo().svcRules.payload.offboardedMembers.keySet() should contain(
                sv4Backend.getSvcInfo().svParty.toProtoPrimitive
              )
            }

            clue("Check decentralized namespace offboarding") {
              val decentralizedNamespaces =
                sv1Backend.participantClient.topology.decentralized_namespaces
                  .list(
                    filterStore = globalDomainId.filterString,
                    filterNamespace = svcParty.uid.namespace.toProtoPrimitive,
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
                  .getMediatorDomainState(globalDomainId)
                  .futureValue
                  .mapping
                  .active
                  .forgetNE
                  .toSet

              mediators.size shouldBe 3
              mediators shouldBe sv3Backend
                .getSvcInfo()
                .svcRules
                .payload
                .members
                .values()
                .asScala
                .flatMap(_.domainNodes.values().asScala)
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
          }
        },
      )
    }
  }
}

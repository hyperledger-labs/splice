package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.round.types.Round
import com.daml.network.codegen.java.cn
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_ConfirmSvOnboarding
import com.daml.network.codegen.java.cn.svcrules.{SvcRules, SvcRules_ConfirmSvOnboarding}
import com.daml.network.console.SvAppBackendReference
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.sv.admin.api.client.commands.HttpSvAppClient.SvOnboardingStatus
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import org.slf4j.event.Level

import scala.jdk.CollectionConverters.*

class SvOnboardingAddlIntegrationTest extends SvIntegrationTestBase {

  // remaining states are tested as part of "SVs can onboard new SVs"
  "SV onboarding status is reported correctly for `unknown` and `confirmed` states" in {
    implicit env =>
      // only 1 SV => slightly faster test
      clue("Initialize SVC with 1 SV") {
        startAllSync(sv1ScanBackend, sv1Backend)
        sv1Backend.getSvcInfo().svcRules.payload.members should have size 1
      }
      // SV two’s party hasn't been allocated at this point because the SV app isn't running so we allocate it here.
      val (sv2Party, _) = actAndCheck(
        "allocate sv2 party",
        sv2Backend.participantClientWithAdminToken.ledger_api.parties
          .allocate(sv2Backend.config.ledgerApiUser, sv2Backend.config.ledgerApiUser)
          .party,
      )(
        "sv1 sees sv2 party",
        party =>
          sv1Backend.participantClientWithAdminToken.parties
            .list(filterParty = party.toProtoPrimitive) should not be empty,
      )

      clue("Unknown parties have unknown SV onboarding status") {
        inside(sv1Backend.getSvOnboardingStatus(sv2Party)) { case SvOnboardingStatus.Unknown() =>
          sv1Backend.getSvOnboardingStatus("Canton-Foundation-2") shouldBe sv1Backend
            .getSvOnboardingStatus(
              sv2Party
            )
        }
      }
      actAndCheck(
        "Moving sv2 to confirmed state",
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitWithResult(
            userId = "svc",
            actAs = Seq(svcParty),
            readAs = Seq(),
            update = sv1Backend
              .getSvcInfo()
              .svcRules
              .contractId
              .exerciseSvcRules_ConfirmSvOnboarding(
                sv2Party.toProtoPrimitive,
                "Canton-Foundation-2",
                "no reason",
              ),
          )
          .exerciseResult,
      )(
        "Confirmed SVs get told they are are confirmed",
        svOnboardingConfirmedCid =>
          inside(sv1Backend.getSvOnboardingStatus(sv2Party)) {
            case status: SvOnboardingStatus.Confirmed => {
              status.name shouldBe "Canton-Foundation-2"
              status.svOnboardingConfirmedCid shouldBe svOnboardingConfirmedCid
              sv1Backend.getSvOnboardingStatus("Canton-Foundation-2") shouldBe sv1Backend
                .getSvOnboardingStatus(
                  sv2Party
                )
            }
          },
      )
  }

  "The new sv with same name can be onboarded and overwrite existing member only in devnet" in {
    implicit env =>
      clue("Initialize SVC with 3 SVs") {
        startAllSync(
          sv1ScanBackend,
          sv1Backend,
          sv2Backend,
          sv3Backend,
          sv1ValidatorBackend,
          sv2ValidatorBackend,
          sv3ValidatorBackend,
        )
        sv1Backend.getSvcInfo().svcRules.payload.members should have size 3
      }

      val fakeSv4Party = allocateRandomSvParty("sv4")
      val coinConfig = sv1ScanBackend.getCoinConfigAsOf(env.environment.clock.now)
      actAndCheck(
        "Add a fake sv4 Party to SvcRules.members to simulate sv4 is already added to SVC", {
          sv1Backend.participantClient.ledger_api_extensions.commands.submitWithResult(
            sv1Backend.config.ledgerApiUser,
            actAs = Seq(svcParty),
            readAs = Seq.empty,
            update = sv1Backend
              .getSvcInfo()
              .svcRules
              .contractId
              .exerciseSvcRules_AddMember(
                fakeSv4Party.toProtoPrimitive,
                "Canton-Foundation-4",
                sv1Backend.participantClient.id.toProtoPrimitive,
                new Round(3),
                coinConfig.globalDomain.activeDomain,
              ),
          )
        },
      )(
        "sv4 is added as an SVC member with the fake party Id",
        _ =>
          inside(
            sv1Backend
              .getSvcInfo()
              .svcRules
              .payload
              .members
              .asScala
              .get(fakeSv4Party.toProtoPrimitive)
          ) { case Some(memberInfo) =>
            memberInfo.name shouldBe "Canton-Foundation-4"
          },
      )

      actAndCheck(
        "start sv4 with a party id different from existing sv4 in SVC", {
          startAllSync(sv4Backend, sv4ValidatorBackend)
        },
      )(
        "existing member sv4 is overwritten with different party id",
        _ => {
          inside(
            sv1Backend
              .getSvcInfo()
              .svcRules
              .payload
              .members
              .asScala
              .get(sv4Backend.getSvcInfo().svParty.toProtoPrimitive)
          ) { case Some(memberInfo) =>
            memberInfo.name shouldBe "Canton-Foundation-4"
          }
        },
      )
  }

  "At most 4 SV confirmations are required in devnet" in { implicit env =>
    clue("Initialize SVC with 4 SVs") {
      initSvc()
      eventually() {
        sv1Backend.getSvcInfo().svcRules.payload.members should have size 4
      }
    }

    actAndCheck(
      "Add 3 phantom SVs to SVC", {
        for { i <- 1 to 3 } {
          val name = s"phantom sv$i"
          val partyId = allocateRandomSvParty(name)
          addSvMember(partyId, name, sv1Backend.participantClient.id)
        }
      },
    )(
      "There should be 7 SVC members in total now",
      _ => {
        sv1Backend.getSvcInfo().svcRules.payload.members should have size 7
      },
    )

    actAndCheck(
      "SV1 to SV4 create confirmation to Confirm SVX", {
        val svcRules = sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
          .head
        val newMemberName = "Canton-Foundation-X"
        val newMemberPartyId = allocateRandomSvParty(newMemberName)
        createSvOnboardingConfirmation(svcRules, sv1Backend, newMemberPartyId, newMemberName)
        createSvOnboardingConfirmation(svcRules, sv2Backend, newMemberPartyId, newMemberName)
        createSvOnboardingConfirmation(svcRules, sv3Backend, newMemberPartyId, newMemberName)
        createSvOnboardingConfirmation(svcRules, sv4Backend, newMemberPartyId, newMemberName)
      },
    )(
      "There are 7 SVC members in total but only 4 confirmations are required to confirm a SV",
      _ =>
        inside(
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(cn.svonboarding.SvOnboardingConfirmed.COMPANION)(svcParty)
        ) { case Seq(svOnboardingConfirmed) =>
          svOnboardingConfirmed.data.svName shouldBe "Canton-Foundation-X"
        },
    )
  }

  "The election request succeeds if one SV is onboarded in the middle of an election request" in {
    implicit env =>
      clue("Initialize SVC with 2 SVs") {
        startAllSync(
          sv1ScanBackend,
          sv1Backend,
          sv2Backend,
          sv1ValidatorBackend,
          sv2ValidatorBackend,
        )
        sv1Backend.getSvcInfo().svcRules.payload.members should have size 2
      }

      val currentLeader = sv1Backend.getSvcInfo().svParty.toProtoPrimitive
      val newLeader = sv2Backend.getSvcInfo().svParty.toProtoPrimitive
      val newRanking: Vector[String] = Seq(newLeader, currentLeader).toVector

      // note that the new leader has to vote for himself to prove readiness
      actAndCheck(
        "sv2 creates a new election request for epoch 1", {
          sv2Backend
            .createElectionRequest(newLeader, newRanking)
        },
      )(
        "the epoch stays the same",
        _ => {
          sv1Backend.getSvcInfo().svcRules.payload.leader shouldBe currentLeader
        },
      )

      clue("SV3 gets onboarded") {
        startAllSync(
          sv3Backend,
          sv3ValidatorBackend,
        )
        sv1Backend.getSvcInfo().svcRules.payload.members should have size 3
        sv1Backend.getSvcInfo().svcRules.payload.epoch shouldBe 0
      }

      loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.WARN))(
        actAndCheck(
          "sv3 creates a new election request for epoch 1", {
            val sv3 = sv3Backend.getSvcInfo().svParty.toProtoPrimitive
            sv3Backend
              .createElectionRequest(sv3, newRanking.appended(sv3))
          },
        )(
          "the epoch increased and sv2 is the new leader",
          _ => {
            sv1Backend.getSvcInfo().svcRules.payload.epoch shouldBe 1
            sv1Backend.getSvcInfo().svcRules.payload.leader shouldBe newLeader
          },
        ),
        logEntries => {
          forAtLeast(1, logEntries)(logEntry => {
            logEntry.message should startWith(
              "Noticed an SvcRules epoch change"
            )
          })
        },
      )

  }

  private def createSvOnboardingConfirmation(
      svcRules: SvcRules.Contract,
      svApp: SvAppBackendReference,
      newMemberParty: PartyId,
      newMemberName: String,
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    val svParty = svApp.getSvcInfo().svParty
    svApp.participantClient.ledger_api_extensions.commands.submitWithResult(
      svApp.config.ledgerApiUser,
      actAs = Seq(svParty),
      readAs = Seq(svcParty),
      update = svcRules.id.exerciseSvcRules_ConfirmAction(
        svParty.toProtoPrimitive,
        new ARC_SvcRules(
          new SRARC_ConfirmSvOnboarding(
            new SvcRules_ConfirmSvOnboarding(
              newMemberParty.toProtoPrimitive,
              newMemberName,
              "because",
            )
          )
        ),
      ),
    )
  }
}

package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.Amulet
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_ConfirmSvOnboarding
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_ConfirmSvOnboarding
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.sv.util.{SvOnboardingToken, SvUtil}
import com.digitalasset.canton.sequencing.GrpcSequencerConnection

import scala.jdk.OptionConverters.*
import org.lfdecentralizedtrust.splice.sv.admin.api.client.commands.HttpSvAppClient.SvOnboardingStatus
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, WalletTestUtil}
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.transaction.ParticipantPermission
import org.slf4j.event.Level

import scala.concurrent.duration.*

class SvOnboardingAddlIntegrationTest
    extends SvIntegrationTestBase
    with WalletTestUtil
    with SvTestUtil {

  override def environmentDefinition =
    super.environmentDefinition
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs { (name, config) =>
          if (name == "sv3") {
            config.copy(
              approvedSvIdentities = config.approvedSvIdentities.filter(
                _.name != getSvName(4)
              )
            )
          } else config
        }(config)
      )

  override lazy val updateHistoryIgnoredRootCreates = Seq(
    amuletCodegen.Amulet.TEMPLATE_ID_WITH_PACKAGE_ID
  )

  "SVs can onboard new SVs" in { implicit env =>
    clue("Initialize DSO with 3 SVs") {
      startAllSync(
        sv1ScanBackend,
        sv2ScanBackend,
        sv1Backend,
        sv2Backend,
        sv3Backend,
        sv1ValidatorBackend,
        sv2ValidatorBackend,
        sv3ValidatorBackend,
      )
      sv1Backend.getDsoInfo().dsoRules.payload.svs should have size 3
    }
    clue("Stop SV2 so that SV4 can't gather enough confirmations just yet") {
      sv2Backend.stop()
      // We now need 2 confirmations to execute an action, but only sv1 is
      // active and sv3 hasn't approved sv4.
    }
    clue("SV4 starts") {
      sv4ValidatorBackend.start()
      sv4Backend.start()
    }
    val sv1Party = sv1Backend.getDsoInfo().svParty
    // We are not using sv4.getDsoInfo() to get sv4's party id
    // because the SvApp is not completely initialized yet and hence the http service is not available.
    val sv4Party = eventually() {
      sv4Backend.participantClient.ledger_api.users
        .get(sv4Backend.config.ledgerApiUser)
        .primaryParty
        .value
    }

    val (token, svOnboardingRequestCid) =
      clue("Checking that SV4's `SvOnboarding` contract was created correctly by SV1") {
        eventually()(
          // The onboarding is requested by SV4 during SvApp init.
          inside(
            sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
              .filterJava(splice.svonboarding.SvOnboardingRequest.COMPANION)(dsoParty)
          ) {
            case Seq(svOnboarding) => {
              svOnboarding.data.candidateName shouldBe getSvName(4)
              svOnboarding.data.candidateParty shouldBe sv4Party.toProtoPrimitive
              svOnboarding.data.candidateParticipantId shouldBe sv4Backend.participantClient.id.toProtoPrimitive
              svOnboarding.data.sponsor shouldBe sv1Party.toProtoPrimitive
              svOnboarding.data.dso shouldBe dsoParty.toProtoPrimitive
              // if this check fails:
              // make sure that the values (especially the key) are in sync with sv1's and sv4's config files
              SvOnboardingToken
                .verifyAndDecode(svOnboarding.data.token)
                .value shouldBe SvOnboardingToken(
                getSvName(4),
                "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZMNsDJr1uTwMTIIlzUZpUexTLqVGMsD7cR4Y8sqYYFYhldVMeHG5zSubf+p+WZbLEyMUCT5nBCCBh0oiUY9crA==",
                sv4Party,
                sv4Backend.participantClient.id,
                dsoParty,
              )
              (svOnboarding.data.token, svOnboarding.id)
            }
          }
        )
      }
    clue("Attempting to start an onboarding multiple times has no effect") {
      sv1Backend.startSvOnboarding(token)
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(splice.svonboarding.SvOnboardingRequest.COMPANION)(
          dsoParty
        ) should have length 1
    }
    clue(
      "SVs that haven't approved a candidate refuse to create a `SvOnboarding` contract for it."
    ) {
      assertThrowsAndLogsCommandFailures(
        sv3Backend.startSvOnboarding(token),
        _.errorMessage should include("no matching approved SV identity found"),
      )
    }
    clue("All online and approving SVs confirm SV4's onboarding") {
      eventually() {
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(splice.dsorules.Confirmation.COMPANION)(dsoParty)
          .filter(_.data.action match {
            case a: ARC_DsoRules =>
              a.dsoAction match {
                case confirm: SRARC_ConfirmSvOnboarding =>
                  confirm.dsoRules_ConfirmSvOnboardingValue.newSvName == getSvName(4)
                case _ => false
              }
            case _ => false
          }) should have length 1
      }
      sv1Backend
        .getDsoInfo()
        .dsoRules
        .payload
        .svs
        .keySet should not contain sv4Party.toProtoPrimitive
    }
    clue("SV4's onboarding status is reported correctly.") {
      eventually()(inside(sv1Backend.getSvOnboardingStatus(sv4Party)) {
        case status: SvOnboardingStatus.Requested => {
          status.name shouldBe getSvName(4)
          status.svOnboardingRequestCid shouldBe svOnboardingRequestCid
          status.confirmedBy.sorted shouldBe Vector(getSvName(1))
          status.requiredNumConfirmations shouldBe 2
          sv1Backend.getSvOnboardingStatus(getSvName(4)) shouldBe sv1Backend
            .getSvOnboardingStatus(
              sv4Party
            )
        }
      })
    }
    // higher time required to account for possible domain reconnections when the sequencer is changed from the onboarding sv1 to it's own sv2
    // TODO(#13405) remove increased timeout when canton fails fast during sequencer changes
    actAndCheck(timeUntilSuccess = 2.minute)("SV2 comes back online", sv2Backend.startSync())(
      "SV4's onboarding gathers sufficient confirmations and is completed",
      { _ =>
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(splice.svonboarding.SvOnboardingRequest.COMPANION)(dsoParty) shouldBe empty
        sv1Backend.getDsoInfo().dsoRules.payload.svs.keySet should contain(
          sv4Party.toProtoPrimitive
        )
      },
    )
    clue("SV4's onboarding status is reported as completed.") {
      eventually()(inside(sv1Backend.getSvOnboardingStatus(sv4Party)) {
        case status: SvOnboardingStatus.Completed => {
          status.name shouldBe getSvName(4)
          status.dsoRulesCid shouldBe sv1Backend.getDsoInfo().dsoRules.contractId
          sv1Backend.getSvOnboardingStatus(getSvName(4)) shouldBe sv1Backend
            .getSvOnboardingStatus(
              sv4Party
            )
        }
      })
    }
    sv4Backend.waitForInitialization()
    sv4ValidatorBackend.waitForInitialization()

    // we need to wait for a minute due to non sv validator only connect to sequencers after initialization + sequencerAvailabilityDelay which is is 60s
    eventually(timeUntilSuccess = 1.minutes, maxPollInterval = 1.second) {
      val sv1NodeStates = sv1Backend.getDsoInfo().svNodeStates

      forAll(Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)) { svBackend =>
        val svParty = svBackend.getDsoInfo().svParty
        val decentralizedSynchronizer = svBackend.config.domains.global.alias
        val sequencerConnections = svBackend.participantClient.domains
          .config(decentralizedSynchronizer)
          .value
          .sequencerConnections
          .connections
          .forgetNE

        val localSequencerUrl = inside(sequencerConnections) {
          case Seq(
                GrpcSequencerConnection(defaultSequencerEndpoint, _, _, _)
              ) =>
            defaultSequencerEndpoint.forgetNE.map(_.toURI(false)).headOption.value
          case Seq(
                GrpcSequencerConnection(_, _, _, _),
                GrpcSequencerConnection(localSequencerEndpoint, _, _, _),
              ) =>
            localSequencerEndpoint.forgetNE.map(_.toURI(false)).headOption.value
        }

        val nodeState = sv1NodeStates.get(svParty).value.payload
        forAll(nodeState.state.synchronizerNodes.values()) { synchronizerNode =>
          synchronizerNode.sequencer.toScala.value.url shouldBe localSequencerUrl.toString
          synchronizerNode.mediator.toScala.value.mediatorId should not be empty
        }

        clue("published sequencer information can be seen via scan") {
          inside(sv1ScanBackend.listDsoSequencers()) { case Seq(domainSequencers) =>
            domainSequencers.sequencers should have size 4
            domainSequencers.sequencers.find(s =>
              s.svName == nodeState.svName && s.url == localSequencerUrl.toString
            ) should not be empty
          }
        }
      }
    }
  }

  // remaining states are tested as part of "SVs can onboard new SVs"
  "SV onboarding status is reported correctly for `unknown` and `confirmed` states" in {
    implicit env =>
      // only 1 SV => slightly faster test
      clue("Initialize DSO with 1 SV") {
        startAllSync(sv1ScanBackend, sv1Backend)
        sv1Backend.getDsoInfo().dsoRules.payload.svs should have size 1
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
          sv1Backend.getSvOnboardingStatus(getSvName(2)) shouldBe sv1Backend
            .getSvOnboardingStatus(
              sv2Party
            )
        }
      }
      actAndCheck(
        "Moving sv2 to confirmed state", {
          val confirmingSvs = getConfirmingSvs(Seq(sv1Backend))
          confirmActionByAllSvs(
            confirmingSvs,
            new ARC_DsoRules(
              new SRARC_ConfirmSvOnboarding(
                new DsoRules_ConfirmSvOnboarding(
                  sv2Party.toProtoPrimitive,
                  getSvName(2),
                  "PAR::sv2::1220f3e2",
                  SvUtil.DefaultSV1Weight,
                  "no reason",
                )
              )
            ),
          )
        },
      )(
        "Confirmed SVs get told they are are confirmed",
        _ =>
          inside(sv1Backend.getSvOnboardingStatus(sv2Party)) {
            case status: SvOnboardingStatus.Confirmed => {
              status.name shouldBe getSvName(2)
              sv1Backend.getSvOnboardingStatus(getSvName(2)) shouldBe sv1Backend
                .getSvOnboardingStatus(
                  sv2Party
                )
            }
          },
      )
  }

  "The election request succeeds if one SV is onboarded in the middle of an election request" in {
    implicit env =>
      clue("Initialize DSO with 2 SVs") {
        startAllSync(
          sv1ScanBackend,
          sv2ScanBackend,
          sv1Backend,
          sv2Backend,
          sv1ValidatorBackend,
          sv2ValidatorBackend,
        )
        sv1Backend.getDsoInfo().dsoRules.payload.svs should have size 2
      }

      val currentLeader = sv1Backend.getDsoInfo().svParty.toProtoPrimitive
      val newLeader = sv2Backend.getDsoInfo().svParty.toProtoPrimitive
      val newRanking: Vector[String] = Seq(newLeader, currentLeader).toVector

      // note that the new delegate has to vote for himself to prove readiness
      actAndCheck(
        "sv2 creates a new election request for epoch 1", {
          sv2Backend
            .createElectionRequest(newLeader, newRanking)
        },
      )(
        "the epoch stays the same",
        _ => {
          sv1Backend.getDsoInfo().dsoRules.payload.dsoDelegate shouldBe currentLeader
        },
      )

      clue("SV3 gets onboarded") {
        startAllSync(
          sv3Backend,
          sv3ValidatorBackend,
        )
        sv1Backend.getDsoInfo().dsoRules.payload.svs should have size 3
        sv1Backend.getDsoInfo().dsoRules.payload.epoch shouldBe 0
      }

      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.INFO))(
        actAndCheck(
          "sv3 creates a new election request for epoch 1", {
            val sv3 = sv3Backend.getDsoInfo().svParty.toProtoPrimitive
            sv3Backend
              .createElectionRequest(sv3, newRanking.appended(sv3))
          },
        )(
          "the epoch increased and sv2 is the new delegate",
          _ => {
            sv1Backend.getDsoInfo().dsoRules.payload.epoch shouldBe 1
            sv1Backend.getDsoInfo().dsoRules.payload.dsoDelegate shouldBe newLeader
          },
        ),
        logEntries => {
          val noticedLbRestarts = logEntries collect {
            case logEntry if logEntry.message startsWith "Noticed an DsoRules epoch change" =>
              raw"\bSV=(.+?)\b".r.findFirstMatchIn(logEntry.loggerName).value.group(1)
          }
          noticedLbRestarts should contain theSameElementsAs Seq("sv1", "sv2", "sv3")
        },
      )
  }

  "fail to submit command with actAs = dso if there are more than 1 SV onboarded" in {
    implicit env =>
      startAllSync(
        sv1ScanBackend,
        sv1Backend,
        sv1ValidatorBackend,
      )
      sv1Backend.getDsoInfo().dsoRules.payload.svs should have size 1

      val sv1UserId = sv1WalletClient.config.ledgerApiUser
      val sv1UserParty = onboardWalletUser(sv1WalletClient, sv1ValidatorBackend)
      val amuletAmount = BigDecimal(42)

      clue("create a amulet with actAs = DSO") {
        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.ERROR))(
          {
            val amuletCid = createAmulet(
              sv1ValidatorBackend.participantClientWithAdminToken,
              sv1UserId,
              sv1UserParty,
              amount = amuletAmount,
            )
            eventually() {
              inside(
                sv1ScanBackend.participantClientWithAdminToken.ledger_api_extensions.acs
                  .filterJava(Amulet.COMPANION)(sv1UserParty)
              ) { case Seq(amulet) =>
                amulet.id shouldBe amuletCid
              }
            }
          },
          lines => {
            forAll(lines)(line =>
              line.message should
                include(
                  "Unexpected amulet create event"
                )
            )
            // Error emitted by every ScanTxLogParser plus the one UserWalletTxLogParser
            // associated with the owner of the coin.
            lines should have size 2
          },
        )
      }

      startAllSync(
        sv2ScanBackend,
        sv2Backend,
        sv2ValidatorBackend,
      )
      sv1Backend.getDsoInfo().dsoRules.payload.svs should have size 2

      inside(
        sv1Backend.participantClientWithAdminToken.topology.party_to_participant_mappings.list(
          domain = decentralizedSynchronizerId,
          filterParty = dsoParty.toProtoPrimitive,
        )
      ) { case Seq(mapping) =>
        inside(mapping.item.participants) { case Seq(sv1Participant, sv2Participant) =>
          sv1Participant.participantId shouldBe sv1Backend.participantClientWithAdminToken.id
          sv1Participant.permission shouldBe ParticipantPermission.Submission
          sv2Participant.participantId shouldBe sv2Backend.participantClientWithAdminToken.id
          sv2Participant.permission shouldBe ParticipantPermission.Submission
        }
      }
      clue("create a amulet again with actAs = DSO") {
        withCommandRetryPolicy(_ => _ => false) {
          assertThrowsAndLogsCommandFailures(
            createAmulet(
              sv1ValidatorBackend.participantClientWithAdminToken,
              sv1UserId,
              sv1UserParty,
              amount = amuletAmount,
            ),
            _.errorMessage should (include(
              s"INVALID_ARGUMENT/An error occurred. Please contact the operator and inquire about the request"
            ) or include(
              s"Not connected to a domain on which this participant can submit for all submitters"
            )),
          )
        }
      }
  }

}

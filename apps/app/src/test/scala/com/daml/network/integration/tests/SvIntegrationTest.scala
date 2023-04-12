package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.svcrules.{SvcRules, SvcRules_ConfirmSv}
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_ConfirmSv
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.console.{
  CNRemoteParticipantReference,
  LocalCNNodeAppReference,
  SvAppBackendReference,
}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.admin.api.client.commands.HttpSvAppClient.SvOnboardingStatus
import com.daml.network.sv.util.SvOnboardingToken
import com.daml.network.util.SvTestUtil
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.topology.transaction.{
  ParticipantPermission,
  RequestSide,
  TopologyChangeOp,
}

import java.time.Instant
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class SvIntegrationTest extends CNNodeIntegrationTest with SvTestUtil {

  private val cantonCoinDarPath =
    "daml/canton-coin/.daml/dist/canton-coin-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withManualStart

  "start and restart cleanly" in { implicit env =>
    initSvc()
    sv1.stop()
    sv1.startSync()
  }

  // A test to make debugging bootstrap problems easier
  "SV apps can start one by one" in { implicit env =>
    clue("Starting SVC app and SV1 app") {
      // TODO(#3856) don't start SVC app here once we don't use it anymore for getting the svcParty
      svc.start()
      sv1.startSync()
      svc.waitForInitialization()
    }
    clue("Starting SV2 app") {
      sv2.startSync()
    }
    clue("Starting SV3 app") {
      sv3.startSync()
    }
    clue("Starting SV4 app") {
      sv4.startSync()
    }
    clue("Starting SV5 app") {
      sv5.startSync()
    }
  }

  "The SVC is bootstrapped correctly" in { implicit env =>
    initSvc()
    val svcRules = clue("An SvcRules contract exists") { getSvcRules() }
    val svParties = clue("We have four sv parties and their apps are online") {
      svs.map(_.getDebugInfo().svParty.toProtoPrimitive)
    }
    clue("The four sv apps are all svc members and there are no other svc members") {
      svcRules.data.members.keySet should equal(svParties.toSet.asJava)
    }
    clue("The founding SV app (sv1) is the first leader") {
      getSvcRules().data.leader should equal(sv1.getDebugInfo().svParty.toProtoPrimitive)
    }
  }

  "SV parties can't act as the SVC party and can read as both themselves and the SVC party" in {
    implicit env =>
      initSvc()
      svs.foreach(sv => {
        val rights = sv.remoteParticipant.ledger_api.users.rights.list(sv.config.ledgerApiUser)
        rights.actAs should not contain (svcParty.toLf)
        rights.readAs should contain(svcParty.toLf)
      })
      actAndCheck(
        "creating a `ValidatorOnboarding` contract readable only by sv3", {
          val sv = sv3 // it doesn't really matter which sv we pick
          val svParty = sv.getDebugInfo().svParty
          sv.listOngoingValidatorOnboardings() shouldBe empty
          sv.remoteParticipant.ledger_api_extensions.commands.submitWithResult(
            sv.config.ledgerApiUser,
            actAs = Seq(svParty),
            readAs = Seq.empty,
            update = new cn.validatoronboarding.ValidatorOnboarding(
              svParty.toProtoPrimitive,
              "test",
              env.environment.clock.now.toInstant.plusSeconds(3600),
            ).create,
          )
        },
      )(
        "sv3's store ingests the contract",
        created =>
          inside(sv3.listOngoingValidatorOnboardings()) { case Seq(visible) =>
            visible.contractId shouldBe created.contractId
          },
      )
  }

  "Non-leader SVs can onboard new validators" in { implicit env =>
    initSvc()
    // Upload the DAR so validator onboarding can succeed. Usually this is done through the validator app
    // but because here we don't start one, we need to perform this step manually.
    bobValidator.remoteParticipant.dars.upload(cantonCoinDarPath)
    val sv = sv4 // not a leader
    val svParty = sv.getDebugInfo().svParty
    sv.listOngoingValidatorOnboardings() should have length 0
    val secret = actAndCheck(
      "the sv operator prepares the onboarding", {
        sv.prepareValidatorOnboarding(1.hour)
      },
    )(
      "a validator onboarding contract is created",
      { secret =>
        {
          inside(sv.listOngoingValidatorOnboardings()) { case Seq(vo) =>
            vo.payload.candidateSecret shouldBe secret
          }
        }
      },
    )._1
    val candidate = clue("create a dummy party") {
      val name = "dummy" + env.environment.config.name.getOrElse("")
      PartyId.tryFromLfParty(
        bobValidator.remoteParticipantWithAdminToken.ledger_api.parties
          .allocate(
            name,
            name,
          )
          .party
      )

    }
    clue("try to onboard with a wrong secret, which should fail") {
      assertThrows[CommandFailure](
        loggerFactory.assertLogs(
          sv.onboardValidator(candidate, "wrongsecret")
        )
      )
    }
    actAndCheck(
      "request to onboard the candidate",
      sv.onboardValidator(candidate, secret),
    )(
      "the candidate's secret is marked as used",
      _ => {
        inside(
          svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
            .filterJava(cn.validatoronboarding.UsedSecret.COMPANION)(svParty)
        ) {
          case Seq(usedSecret) => {
            usedSecret.data.secret shouldBe secret
            usedSecret.data.validator shouldBe candidate.toProtoPrimitive
          }
        }
      },
    )
    clue("try to reuse the same secret for a second onboarding, which should fail") {
      assertThrows[CommandFailure](
        loggerFactory.assertLogs(
          sv.onboardValidator(candidate, "dummysecret")
        )
      )
    }
  }

  "Validator candidates can self-service at the validator onboarding tap" in { implicit env =>
    initSvc()
    val sv = sv3 // a random sv
    sv.listOngoingValidatorOnboardings() should have length 0
    actAndCheck(
      "the validator candidate requests a secret from the validator onboarding tap", {
        sv.devNetOnboardValidatorPrepare()
      },
    )(
      "a validator onboarding contract is created",
      { secret =>
        {
          inside(sv.listOngoingValidatorOnboardings()) { case Seq(vo) =>
            vo.payload.candidateSecret shouldBe secret
          }
        }
      },
    )
  }

  "SV Identity can be approved at runtime" in { implicit env =>
    initSvc()
    svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
      .filterJava(cn.svonboarding.ApprovedSvIdentity.COMPANION)(
        sv1.getDebugInfo().svParty
      ) should have length 2
    val svXName = "svX"
    val svXKey =
      "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEj6n2u5RWQdkq2cWvStGbIBe2JmoFs+vZGOVfd6oIm/FqfK2qV2fiHX9DieJ1c6BarDdsAD7IRnksD9BGisU3ZQ=="
    sv1.approveSvIdentity(svXName, svXKey)
    inside(
      svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .filterJava(cn.svonboarding.ApprovedSvIdentity.COMPANION)(sv1.getDebugInfo().svParty)
    ) {
      case approvedSvIds => {
        approvedSvIds should have size 3
        val maybeSvXApprovedSvId = approvedSvIds.find(_.data.candidateName == svXName)
        inside(maybeSvXApprovedSvId) { case Some(svXApprovedSvId) =>
          svXApprovedSvId.data.candidateKey shouldBe svXKey
        }
      }
    }
  }

  "SVs expect onboardings when asked to" in { implicit env =>
    initSvc()
    clue("SV2 has created as many ValidatorOnboarding contracts as it's configured to.") {
      sv2.listOngoingValidatorOnboardings() should have length 3
    }
    clue("SV2 doesn't recreate ValidatorOnboarding contracts on restart...") {
      sv2.stop()
      sv2.startSync()
      sv2.listOngoingValidatorOnboardings() should have length 3
    }
    clue("...even if an onboarding was completed in the meantime...") {
      bobValidator.startSync()
      eventually() {
        sv2.listOngoingValidatorOnboardings() should have length 2
      }
      sv2.stop()
      sv2.startSync()
      sv2.listOngoingValidatorOnboardings() should have length 2
    }
  }

  "SVs create approval contracts for configured approved SV identities" in { implicit env =>
    initSvc()
    clue("SV1 has created an ApprovedSvIdentity contract as it's configured to.") {
      inside(
        svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.ApprovedSvIdentity.COMPANION)(sv1.getDebugInfo().svParty)
      ) {
        case approvedSvIds => {
          // if this check fails:
          // make sure that the values (especially the key) are in sync with sv1's config file
          approvedSvIds should have size 2
          val maybeSv4ApprovedSvId = approvedSvIds.find(_.data.candidateName == "sv4")
          inside(maybeSv4ApprovedSvId) { case Some(sv4ApprovedSvId) =>
            sv4ApprovedSvId.data.candidateKey shouldBe "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZMNsDJr1uTwMTIIlzUZpUexTLqVGMsD7cR4Y8sqYYFYhldVMeHG5zSubf+p+WZbLEyMUCT5nBCCBh0oiUY9crA=="
          }
          val maybeSv5ApprovedSvId = approvedSvIds.find(_.data.candidateName == "sv5")
          inside(maybeSv5ApprovedSvId) { case Some(sv5ApprovedSvId) =>
            sv5ApprovedSvId.data.candidateKey shouldBe "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE+fFVXv7BtvclgrzJTEELbtSn6Vm9pMxsBQbZYyzIG6udZ85glJCkinvWvxcD6wsMIsuDHM7j7JGygIdFWQ5prA=="
          }
        }
      }
    }
  }

  "SVs can onboard new SVs" in { implicit env =>
    clue("Initialize SVC with 3 SVs") {
      Seq(svc: LocalCNNodeAppReference, scan: LocalCNNodeAppReference, sv1, sv2, sv3).foreach(
        _.start()
      )
      Seq(svc: LocalCNNodeAppReference, scan: LocalCNNodeAppReference, sv1, sv2, sv3).foreach(
        _.waitForInitialization()
      )
      getSvcRules().data.members should have size 3
    }
    clue("Simulate that sv3 hasn't approved sv4 by archiving the respective `ApprovedSvIdentity`") {
      inside(
        svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.ApprovedSvIdentity.COMPANION)(
            sv3.getDebugInfo().svParty,
            c => c.data.candidateName == "sv4",
          )
      ) {
        case Seq(approvedSvId) => {
          sv3.remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitWithResult(
            sv3.config.ledgerApiUser,
            actAs = Seq(sv3.getDebugInfo().svParty),
            readAs = Seq.empty,
            update = approvedSvId.id.exerciseArchive(
              new com.daml.network.codegen.java.da.internal.template.Archive()
            ),
          )
        }
      }
    }
    clue("Stop SV2 so that SV4 can't gather enough confirmations just yet") {
      sv2.stop()
      // We now need 2 confirmations to execute an action, but only sv1 is
      // active and sv3 hasn't approved sv4.
    }
    clue("SV4 starts") {
      sv4.start()
    }
    val sv1Party = sv1.getDebugInfo().svParty
    // We are not using sv4.getDebugInfo() to get sv4's party id
    // because the SvApp is not completely initialized yet and hence the http service is not available.
    val sv4Party = svc.remoteParticipant.ledger_api.users
      .get(sv4.config.ledgerApiUser)
      .primaryParty
      .map(PartyId.fromLfParty(_))
      .value
      .value

    val (token, svOnboardingCid) =
      clue("Checking that SV4's `SvOnboarding` contract was created correctly by SV1") {
        eventually()(
          // The onboarding is requested by SV4 during SvApp init.
          inside(
            svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
              .filterJava(cn.svonboarding.SvOnboarding.COMPANION)(svcParty)
          ) {
            case Seq(svOnboarding) => {
              svOnboarding.data.candidateName shouldBe "sv4"
              svOnboarding.data.candidateParty shouldBe sv4Party.toProtoPrimitive
              svOnboarding.data.sponsor shouldBe sv1Party.toProtoPrimitive
              svOnboarding.data.svc shouldBe svcParty.toProtoPrimitive
              // if this check fails:
              // make sure that the values (especially the key) are in sync with sv1's and sv4's config files
              SvOnboardingToken
                .verifyAndDecode(svOnboarding.data.token)
                .value shouldBe SvOnboardingToken(
                "sv4",
                "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZMNsDJr1uTwMTIIlzUZpUexTLqVGMsD7cR4Y8sqYYFYhldVMeHG5zSubf+p+WZbLEyMUCT5nBCCBh0oiUY9crA==",
                sv4Party,
                svcParty,
              )
              (svOnboarding.data.token, svOnboarding.id)
            }
          }
        )
      }
    clue("Attempting to start an onboarding multiple times has no effect") {
      sv1.onboardSv(token)
      svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .filterJava(cn.svonboarding.SvOnboarding.COMPANION)(svcParty) should have length 1
    }
    clue(
      "SVs that haven't approved a candidate refuse to create a `SvOnboarding` contract for it."
    ) {
      assertThrowsAndLogsCommandFailures(
        sv3.onboardSv(token),
        _.errorMessage should include("no matching approved SV identity found"),
      )
    }
    clue("All online and approving SVs confirm SV4's onboarding") {
      eventually() {
        svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.Confirmation.COMPANION)(svcParty)
          .filter(_.data.action.toValue.getConstructor() == "ARC_SvcRules") should have length 1
      }
      getSvcRules().data.members.keySet should not contain sv4Party.toProtoPrimitive
    }
    clue("SV4's onboarding status is reported correctly.") {
      eventually()(inside(sv1.getSvOnboardingStatus(sv4Party)) {
        case status: SvOnboardingStatus.Onboarding => {
          status.name shouldBe "sv4"
          status.svOnboardingCid shouldBe svOnboardingCid
          status.confirmedBy.sorted shouldBe Vector("sv1")
          status.requiredNumConfirmations shouldBe 2
          sv1.getSvOnboardingStatus("sv4") shouldBe sv1.getSvOnboardingStatus(sv4Party)
        }
      })
    }
    actAndCheck("SV2 comes back online", sv2.start())(
      "SV4's onboarding gathers suffcient confirmations and is completed",
      { _ =>
        svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.SvOnboarding.COMPANION)(svcParty) shouldBe empty
        getSvcRules().data.members.keySet should contain(sv4Party.toProtoPrimitive)
      },
    )
    clue("SV4's onboarding status is reported as completed.") {
      eventually()(inside(sv1.getSvOnboardingStatus(sv4Party)) {
        case status: SvOnboardingStatus.Completed => {
          status.name shouldBe "sv4"
          status.svcRulesCid shouldBe getSvcRules().id
          sv1.getSvOnboardingStatus("sv4") shouldBe sv1.getSvOnboardingStatus(sv4Party)
        }
      })
    }
  }

  // remaining states are tested as part of "SVs can onboard new SVs"
  "SV onboarding status is reported correctly for `unknown` and `confirmed` states" in {
    implicit env =>
      // only 1 SV => slightly faster test
      clue("Initialize SVC with 1 SV") {
        Seq(svc: LocalCNNodeAppReference, scan: LocalCNNodeAppReference, sv1).foreach(
          _.start()
        )
        Seq(svc: LocalCNNodeAppReference, scan: LocalCNNodeAppReference, sv1).foreach(
          _.waitForInitialization()
        )
        getSvcRules().data.members should have size 1
      }
      // We are not using sv2.getDebugInfo() to get sv2's party id because we
      // don't want SV2 to actually start and get bootstrapped for this test
      // and hence the http service is not available.
      val sv2Party = svc.remoteParticipant.ledger_api.users
        .get(sv2.config.ledgerApiUser)
        .primaryParty
        .map(PartyId.fromLfParty(_))
        .value
        .value

      clue("Unknown parties have unknown SV onboarding status") {
        inside(sv1.getSvOnboardingStatus(sv2Party)) { case SvOnboardingStatus.Unknown() =>
          sv1.getSvOnboardingStatus("sv2") shouldBe sv1.getSvOnboardingStatus(sv2Party)
        }
      }
      actAndCheck(
        "Moving sv2 to confirmed state",
        svc.remoteParticipantWithAdminToken.ledger_api_extensions.commands
          .submitWithResult(
            userId = "svc",
            actAs = Seq(svcParty),
            readAs = Seq(),
            update = getSvcRules().id
              .exerciseSvcRules_ConfirmSv(
                sv2Party.toProtoPrimitive,
                "sv2",
                "no reason",
              ),
          )
          .exerciseResult,
      )(
        "Confirmed SVs get told they are are confirmed",
        svConfirmedCid =>
          inside(sv1.getSvOnboardingStatus(sv2Party)) {
            case status: SvOnboardingStatus.Confirmed => {
              status.name shouldBe "sv2"
              status.svConfirmedCid shouldBe svConfirmedCid
              sv1.getSvOnboardingStatus("sv2") shouldBe sv1.getSvOnboardingStatus(sv2Party)
            }
          },
      )
  }

  "The new sv with same name can be onboarded and overwrite existing member only in devnet" in {
    implicit env =>
      clue("Initialize SVC with 3 SVs") {
        Seq(svc: LocalCNNodeAppReference, scan: LocalCNNodeAppReference, sv1, sv2, sv3).foreach(
          _.start()
        )
        Seq(svc: LocalCNNodeAppReference, scan: LocalCNNodeAppReference, sv1, sv2, sv3).foreach(
          _.waitForInitialization()
        )
        getSvcRules().data.members should have size 3
      }

      val fakeSv4Party = allocateRandomSvParty("sv4")
      actAndCheck(
        "Add a fake sv4 Party to SvcRules.members to simulate sv4 is already added to SVC", {
          svc.remoteParticipant.ledger_api_extensions.commands.submitWithResult(
            svc.config.ledgerApiUser,
            actAs = Seq(svcParty),
            readAs = Seq.empty,
            update = getSvcRules().id.exerciseSvcRules_AddMember(
              fakeSv4Party.toProtoPrimitive,
              "sv4",
              "want to see if it overwrite the existing member",
            ),
          )
        },
      )(
        "sv4 is added as an SVC member with the fake party Id",
        _ =>
          inside(getSvcRules().data.members.asScala.get(fakeSv4Party.toProtoPrimitive)) {
            case Some(memberInfo) =>
              memberInfo.name shouldBe "sv4"
          },
      )

      actAndCheck(
        "start sv4 with a party id different from existing sv4 in SVC", {
          sv4.startSync()
        },
      )(
        "existing member sv4 is overwritten with different party id",
        _ => {
          inside(
            getSvcRules().data.members.asScala.get(sv4.getDebugInfo().svParty.toProtoPrimitive)
          ) { case Some(memberInfo) =>
            memberInfo.name shouldBe "sv4"
          }
        },
      )
  }

  "At most 4 SV confirmations are required in devnet" in { implicit env =>
    clue("Initialize SVC with 4 SVs") {
      initSvc()
      eventually() {
        getSvcRules().data.members should have size 4
      }
    }

    actAndCheck(
      "Add 6 phantom SVs to SVC", {
        for { i <- 1 to 6 } {
          val name = s"phantom sv$i"
          val partyId = allocateRandomSvParty(name)
          addSvMember(partyId, name)
        }
      },
    )(
      "There should be 10 SVC members in total now",
      _ => {
        getSvcRules().data.members should have size 10
      },
    )

    actAndCheck(
      "SV1 to SV4 create confirmation to Confirm SV5", {
        val svcRules = getSvcRules()
        val newMemberName = "svX"
        val newMemberPartyId = allocateRandomSvParty(newMemberName)
        createSvConfirmedConfirmation(svcRules, sv1, newMemberPartyId, newMemberName)
        createSvConfirmedConfirmation(svcRules, sv2, newMemberPartyId, newMemberName)
        createSvConfirmedConfirmation(svcRules, sv3, newMemberPartyId, newMemberName)
        createSvConfirmedConfirmation(svcRules, sv4, newMemberPartyId, newMemberName)
      },
    )(
      "There are 10 SVC members in total but only 4 confirmations are required to confirm a SV",
      _ =>
        inside(
          svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
            .filterJava(cn.svonboarding.SvConfirmed.COMPANION)(svcParty)
        ) { case Seq(svConfirmed) =>
          svConfirmed.data.svName shouldBe "svX"
        },
    )
  }

  "The SVC Party can be setup in the participant after SV has been confirmed to be part of the SVC" in {
    implicit env =>
      initSvc()

      val svcParty = svcClient.getDebugInfo().svcParty
      val svcPartyStr: String = svcParty.toProtoPrimitive
      val svcParticipant = svc.remoteParticipant
      val sv5Participant = sv5.remoteParticipant
      svcParticipant.ledger_api.acs.of_party(svcParty) should not be empty
      sv5Participant.ledger_api.acs.of_party(svcParty) shouldBe empty

      createCoinOwnBySvc(svcParticipant, 1.0)

      clue("start onboarding new SV and SVC party setup on new SV's dedicated participant") {
        // SV5 is configured to join the SVC. After the SV is onboarded, it will start the SVC party hosting on its own dedicated participant
        sv5.startSync()
      }

      createCoinOwnBySvc(svcParticipant, 2.0)

      val globalDomainId = inside(sv5Participant.domains.list_connected()) { case Seq(domain) =>
        domain.domainId
      }

      eventually() {
        svcParticipant.topology.party_to_participant_mappings
          .list(
            operation = Some(TopologyChangeOp.Add),
            filterStore = globalDomainId.toProtoPrimitive,
            filterParty = svcPartyStr,
            filterParticipant = sv5Participant.id.uid.id.unwrap,
            filterRequestSide = Some(RequestSide.From),
            filterPermission = Some(ParticipantPermission.Observation),
          ) should have size 1

        sv5Participant.topology.party_to_participant_mappings
          .list(
            operation = Some(TopologyChangeOp.Add),
            filterStore = globalDomainId.toProtoPrimitive,
            filterParty = svcPartyStr,
            filterParticipant = sv5Participant.id.uid.id.unwrap,
            filterRequestSide = Some(RequestSide.To),
            filterPermission = Some(ParticipantPermission.Observation),
          ) should have size 1
        val coinFromSv5Participant = getCoins(sv5Participant, svcParty)
        val coinFromSvcParticipant = getCoins(svcParticipant, svcParty)

        coinFromSv5Participant should have size 2
        coinFromSv5Participant shouldBe coinFromSvcParticipant
      }

      clue("sv5 can exercise CoinRules_DevNet_Tap without disclosed contracts or extra observer.") {
        val sv5Party = sv5.getDebugInfo().svParty

        val coinRules = sv5Participant.ledger_api_extensions.acs
          .filterJava(cc.coin.CoinRules.COMPANION)(svcParty)
          .head

        val openRound = sv5Participant.ledger_api_extensions.acs
          .filterJava(cc.round.OpenMiningRound.COMPANION)(
            svcParty,
            _.data.opensAt.isBefore(Instant.now),
          )
          .maxBy(_.data.round.number)

        sv5Participant.ledger_api_extensions.commands.submitWithResult(
          sv5.config.ledgerApiUser,
          actAs = Seq(sv5Party),
          readAs = Seq(svcParty),
          update = coinRules.id.exerciseCoinRules_DevNet_Tap(
            sv5Party.toProtoPrimitive,
            BigDecimal(100.0).bigDecimal,
            openRound.id,
          ),
        )

        def checkCoinContract(participant: CNRemoteParticipantReference, party: PartyId) = {
          val coins = getCoins(participant, party, _.data.owner == sv5Party.toProtoPrimitive)
          inside(coins) { case Seq(coin) =>
            coin.data.svc shouldBe svcPartyStr
            coin.data.amount.initialAmount shouldBe BigDecimal(100.0).bigDecimal.setScale(10)
            coin.data.owner shouldBe sv5Party.toProtoPrimitive
          }
        }

        eventually() {
          checkCoinContract(svcParticipant, svcParty)
          checkCoinContract(sv5Participant, sv5Party)
        }
      }

      clue("sv5 can restart") {
        sv5.stop()
        sv5.startSync()
      }
  }

  private def expiringAmount(amount: Double) = new cc.fees.ExpiringAmount(
    BigDecimal(amount).bigDecimal,
    new cc.api.v1.round.Round(0L),
    new cc.fees.RatePerRound(BigDecimal(amount).bigDecimal),
  )

  private def coin(amount: Double, party: PartyId) = new cc.coin.Coin(
    party.toProtoPrimitive,
    party.toProtoPrimitive,
    expiringAmount(amount),
  )

  private def createCoinOwnBySvc(
      participant: CNRemoteParticipantReference,
      amount: Double,
  )(implicit env: CNNodeTestConsoleEnvironment) =
    participant.ledger_api_extensions.commands.submitWithResult(
      svc.config.ledgerApiUser,
      actAs = Seq(svcParty),
      readAs = Seq.empty,
      update = coin(amount, svcParty).create,
    )

  def getCoins(
      participant: CNRemoteParticipantReference,
      party: PartyId,
      predicate: cc.coin.Coin.Contract => Boolean = _ => true,
  ): Seq[cc.coin.Coin.Contract] = {
    participant.ledger_api_extensions.acs
      .filterJava(cc.coin.Coin.COMPANION)(party, predicate)
      .sortBy(_.data.amount.initialAmount)
  }

  def getCoinRules()(implicit env: CNNodeTestConsoleEnvironment) =
    clue("There is exactly one CoinRules contract") {
      val foundCoinRules = svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .filterJava(cc.coin.CoinRules.COMPANION)(svcParty)
      foundCoinRules should have length 1
      foundCoinRules.head
    }

  private def createSvConfirmedConfirmation(
      svcRules: SvcRules.Contract,
      svApp: SvAppBackendReference,
      newMemberParty: PartyId,
      newMemberName: String,
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    val svParty = svApp.getDebugInfo().svParty
    svApp.remoteParticipant.ledger_api_extensions.commands.submitWithResult(
      svApp.config.ledgerApiUser,
      actAs = Seq(svParty),
      readAs = Seq(svcParty),
      update = svcRules.id.exerciseSvcRules_ConfirmAction(
        svParty.toProtoPrimitive,
        new ARC_SvcRules(
          new SRARC_ConfirmSv(
            new SvcRules_ConfirmSv(newMemberParty.toProtoPrimitive, newMemberName, "because")
          )
        ),
      ),
    )
  }
}

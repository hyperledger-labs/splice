package com.daml.network.integration.tests

import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.console.CoinRemoteParticipantReference
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.sv.util.SvOnboardingToken
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
import com.daml.network.console.LocalCoinAppReference

class SvIntegrationTest extends CoinIntegrationTest {

  private val cantonCoinDarPath =
    "daml/canton-coin/.daml/dist/canton-coin-0.1.0.dar"

  private val svcGovernanceDarPath =
    "daml/svc-governance/.daml/dist/svc-governance-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withManualStart

  "start and restart cleanly" in { implicit env =>
    initSvc()
    sv1.stop()
    sv1.startSync()
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
      sv2.listOngoingValidatorOnboardings() should have length 2
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
        case Seq(approvedSvId) => {
          // if this check fails:
          // make sure that the values (especially the key) are in sync with sv1's config file
          approvedSvId.data.candidateName shouldBe "sv4"
          approvedSvId.data.candidateKey shouldBe "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1eb+JkH2QFRCZedO/P5cq5d2+yfdwP+jE+9w3cT6BqfHxCd/PyA0mmWMePovShmf97HlUajFuN05kZgxvjcPQw=="
        }
      }
    }
  }

  "SVs can onboard new SVs" in { implicit env =>
    clue("Initialize SVC with 3 SVs") {
      Seq(svc: LocalCoinAppReference, scan: LocalCoinAppReference, sv1, sv2, sv3).foreach(_.start())
      Seq(svc: LocalCoinAppReference, scan: LocalCoinAppReference, sv1, sv2, sv3).foreach(
        _.waitForInitialization()
      )
      getSvcRules().data.members should have size 3
    }
    clue(
      "Add a phantom SV and stop SV2 so that SV4 can't gather enough confirmations just yet"
    ) {
      addPhantomSv()
      sv2.stop()
      getSvcRules().data.members should have size 4
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

    val token = clue("Checking that SV4's `SvOnboarding` contract was created correctly by SV1") {
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
              "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1eb+JkH2QFRCZedO/P5cq5d2+yfdwP+jE+9w3cT6BqfHxCd/PyA0mmWMePovShmf97HlUajFuN05kZgxvjcPQw==",
              sv4Party,
              svcParty,
            )
            svOnboarding.data.token
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
        // TODO(#3369) consider making this check a bit nicer and more precise
        svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.Confirmation.COMPANION)(svcParty)
          .filter(_.data.action.toValue.getConstructor() == "ARC_SvcRules") should have length 1
      }
      getSvcRules().data.members.keySet should not contain sv4Party.toProtoPrimitive
    }
    actAndCheck("SV2 comes back online", sv2.start())(
      "SV4's onboarding gathers suffcient confirmations and is completed",
      _ => getSvcRules().data.members.keySet should contain(sv4Party.toProtoPrimitive),
    )
  }

  "The SVC party can be setup on the SV5 participant" in { implicit env =>
    // ensure the SVC is initialized, so there are some contracts in the name of the SVC party
    initSvc()

    val sv5Participant = sv5.remoteParticipant
    val svcParticipant = svc.remoteParticipant

    createCoinOwnBySvc(svcParticipant, 1.0)

    // Upload the package that the contracts reference
    sv5Participant.ledger_api.packages.upload_dar(svcGovernanceDarPath)

    // Get the svcParty, which will be a fresh one for every test environment
    val svcParty = svcClient.getDebugInfo().svcParty
    val svcPartyStr: String = svcParty.toProtoPrimitive

    svcParticipant.ledger_api.acs.of_party(svcParty) should not be empty
    sv5Participant.ledger_api.acs.of_party(svcParty) shouldBe empty

    val globalDomainId =
      clue("sv5Participant approves hosting of SVC party") {
        // Ensure sv5Participant is connected to exactly the global domain
        sv5Participant.domains.reconnect_all(ignoreFailures = false)

        // We expect exactly one connected domain-id
        val globalDomainId = inside(sv5Participant.domains.list_connected()) { case Seq(domain) =>
          domain.domainId
        }

        // Allow hosting of svcParty on the sv5 participant from sv5's side
        sv5Participant.topology.party_to_participant_mappings.authorize(
          TopologyChangeOp.Add,
          svcParty,
          sv5Participant.id,
          RequestSide.To,
          ParticipantPermission.Observation,
        )

        globalDomainId
      }

    createCoinOwnBySvc(svcParticipant, 2.0)

    clue("disconnect sv5Participant") {
      // Disconnect sv5Participant, so that no transactions reach it during the ACS migration
      sv5Participant.domains.disconnect_all()
    }

    val acsBytes = clue(
      "svcParticipant approves hosting of SVC party and exports ACS"
    ) {
      // Approve hosting of the svcParty on the sv5 participant from the svcParticipant side
      svcParticipant.topology.party_to_participant_mappings.authorize(
        TopologyChangeOp.Add,
        svcParty,
        sv5Participant.id,
        RequestSide.From,
        ParticipantPermission.Observation,
      )

      createCoinOwnBySvc(svcParticipant, 3.0)

      // Wait for the authorization to be reflected in the party to participant mappings
      // Determine timestamp as-of which the party was added
      val addedPartyAt = eventually() {
        val mappings = svcParticipant.topology.party_to_participant_mappings
          .list(
            filterStore = globalDomainId.toProtoPrimitive,
            filterParty = svcPartyStr,
            filterParticipant = sv5Participant.id.uid.id.unwrap,
            filterRequestSide = Some(RequestSide.From),
            filterPermission = Some(ParticipantPermission.Observation),
          )
        inside(mappings) { case Seq(partyToParticipant) =>
          partyToParticipant.context.validFrom
        }
      }

      createCoinOwnBySvc(svcParticipant, 4.0)

      logger.info(s"Added party on sv5Participant as of $addedPartyAt")

      // Export ACS as of that time
      val (_, bytes) = svcParticipant.parties.migration.downloadAcsSnapshot(
        Set(svcParty),
        filterDomainId = globalDomainId.toProtoPrimitive,
        timestamp = Some(addedPartyAt),
      )

      createCoinOwnBySvc(svcParticipant, 5.0)

      bytes
    }

    clue("Import the ACS on sv5Participant and reconnect sv5Participant") {
      // Import the ACS on sv5Participant
      sv5Participant.parties.migration.uploadAcsSnapshot(acsBytes)

      // Reconnect sv5Participant
      sv5Participant.domains.reconnect_all()

      // Ensure sv5Participant is connected to exactly the global domain
      inside(sv5Participant.domains.list_connected()) { case Seq(domain) =>
        domain.domainId shouldBe globalDomainId
      }

      createCoinOwnBySvc(svcParticipant, 6.0)

      // sv5Participant should see the same acs as svcParty
      // compare both acs without comparing eventIds and metadata as they will be different.
      eventually() {
        val coinFromSv5Participant = getCoins(sv5Participant, svcParty)
        val coinFromSvcParticipant = getCoins(svcParticipant, svcParty)

        coinFromSv5Participant should have size 6
        coinFromSv5Participant shouldBe coinFromSvcParticipant
      }
    }

    clue("sv5 can exercise CoinRules_DevNet_Tap without disclosed contracts or extra observer.") {
      val sv5User = sv5Participant.ledger_api.users.get(sv5.config.ledgerApiUser)

      // We are not using sv.getDebugInfo() to get sv5 party id
      // because the SVApp is not started hence the Http service is not available.
      // We do not start the SVApp as this test is independent of what the SvApp is doing.
      val sv5Party = sv5User.primaryParty
        .flatMap(PartyId.fromLfParty(_).toOption)
        .getOrElse(fail("sv5 primary party not found"))

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

      def checkCoinContract(participant: CoinRemoteParticipantReference, party: PartyId) = {
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
      participant: CoinRemoteParticipantReference,
      amount: Double,
  )(implicit env: CoinTestConsoleEnvironment) =
    participant.ledger_api_extensions.commands.submitWithResult(
      svc.config.ledgerApiUser,
      actAs = Seq(svcParty),
      readAs = Seq.empty,
      update = coin(amount, svcParty).create,
    )

  def getCoins(
      participant: CoinRemoteParticipantReference,
      party: PartyId,
      predicate: cc.coin.Coin.Contract => Boolean = _ => true,
  ): Seq[cc.coin.Coin.Contract] = {
    participant.ledger_api_extensions.acs
      .filterJava(cc.coin.Coin.COMPANION)(party, predicate)
      .sortBy(_.data.amount.initialAmount)
  }

  def getSvcRules()(implicit env: CoinTestConsoleEnvironment) =
    clue("There is exactly one SvcRules contract") {
      val foundSvcRules = svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
      foundSvcRules should have length 1
      foundSvcRules.head
    }

  def getCoinRules()(implicit env: CoinTestConsoleEnvironment) =
    clue("There is exactly one CoinRules contract") {
      val foundCoinRules = svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .filterJava(cc.coin.CoinRules.COMPANION)(svcParty)
      foundCoinRules should have length 1
      foundCoinRules.head
    }

  def addPhantomSv()(implicit env: CoinTestConsoleEnvironment) = {
    // random value for test
    val svXParty = PartyId
      .fromProtoPrimitive(
        "svX::122020c99a2f48cd66782404648771eeaa104f108131c0c876a6ed04dd2e4175f27d"
      )
      .value
    actAndCheck(
      "Add the phantom SV \"svX\"",
      svc.remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitJava(
        actAs = Seq(svcParty),
        optTimeout = None,
        commands = getSvcRules().id
          .exerciseSvcRules_AddMember(svXParty.toProtoPrimitive, "addPhantomSv")
          .commands
          .asScala
          .toSeq,
      ),
    )(
      "SvX is a member of the SvcRules",
      _ => getSvcRules().data.members should contain key svXParty.toProtoPrimitive,
    )
  }
}

package com.daml.network.integration.tests

import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.console.CNParticipantClientReference
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.topology.transaction.TopologyChangeOpX
import java.time.Instant
import java.util.Optional
import org.slf4j.event.Level

class SvSvcPartyManagementIntegrationTest extends SvIntegrationTestBase {

  "SV users can act as SV party and act or read as the SVC party" in { implicit env =>
    initSvc()
    val rights =
      sv1Backend.participantClient.ledger_api.users.rights.list(sv1Backend.config.ledgerApiUser)
    rights.actAs should contain(svcParty)
    rights.readAs shouldBe empty
    Seq(sv2Backend, sv3Backend, sv4Backend).foreach(sv => {
      val rights = sv.participantClient.ledger_api.users.rights.list(sv.config.ledgerApiUser)
      rights.actAs should not contain svcParty
      rights.readAs should contain(svcParty)
    })
    actAndCheck(
      "creating a `ValidatorOnboarding` contract readable only by sv3", {
        val sv = sv3Backend // it doesn't really matter which sv we pick
        val svParty = sv.getSvcInfo().svParty
        sv.listOngoingValidatorOnboardings() shouldBe empty
        sv.participantClient.ledger_api_extensions.commands.submitWithResult(
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
        inside(sv3Backend.listOngoingValidatorOnboardings()) { case Seq(visible) =>
          visible.contractId shouldBe created.contractId
        },
    )
  }

  "The SVC Party can be setup in the participant after SV has been confirmed to be part of the SVC" in {
    implicit env =>
      val nrOfScanBackends = 1
      clue("Starting SVC app and SV1 app") {
        startAllSync(sv1ScanBackend, sv1Backend)
      }

      val svcParty = sv1Backend.getSvcInfo().svcParty
      val svcPartyStr: String = svcParty.toProtoPrimitive
      val svcParticipant = sv1Backend.participantClient
      val sv4Participant = sv4Backend.participantClient

      clue(
        "svc party hosting authorization request with party which is not confirmed will be rejected by sponsor SV"
      ) {
        val randomParty = allocateRandomSvParty("random")
        assertThrowsAndLogsCommandFailures(
          sv1Backend.onboardSvPartyMigrationAuthorize(
            sv4Backend.participantClient.id,
            randomParty,
          ),
          _.errorMessage should include(
            "Candidate party is not a member and no `SvOnboardingConfirmed` for the candidate party is found."
          ),
        )
      }

      clue(
        "svc party hosting authorization request with party which is not hosted on the target participant"
      ) {
        val sv1Party = sv1Backend.getSvcInfo().svParty
        assertThrowsAndLogsCommandFailures(
          sv1Backend.onboardSvPartyMigrationAuthorize(
            sv4Backend.participantClient.id,
            sv1Party,
          ),
          _.errorMessage should include(
            s"Candidate party $sv1Party is not authorized by participant"
          ),
        )
      }

      createCoinOwnBySvc(svcParticipant, 1.0, nrOfScanBackends)

      clue("start onboarding new SV and SVC party setup on new SV's dedicated participant") {
        // SV4 is configured to join the SVC. After the SV is onboarded, it will start the SVC party hosting on its own dedicated participant
        startAllSync(sv4ValidatorBackend, sv4Backend)
      }

      createCoinOwnBySvc(svcParticipant, 2.0, nrOfScanBackends)

      val globalDomainId = inside(sv4Participant.domains.list_connected()) { case Seq(domain) =>
        domain.domainId
      }

      eventually() {
        svcParticipant.topology.party_to_participant_mappings
          .list(
            operation = Some(TopologyChangeOpX.Replace),
            filterStore = globalDomainId.filterString,
            filterParty = svcPartyStr,
            filterParticipant = sv4Participant.id.toProtoPrimitive,
          ) should have size 1

        sv4Participant.topology.party_to_participant_mappings
          .list(
            operation = Some(TopologyChangeOpX.Replace),
            filterStore = globalDomainId.filterString,
            filterParty = svcPartyStr,
            filterParticipant = sv4Participant.id.toProtoPrimitive,
          ) should have size 1
        val coinFromSv4Participant = getCoins(sv4Participant, svcParty)
        val coinFromSvcParticipant = getCoins(svcParticipant, svcParty)

        coinFromSv4Participant should have size 2
        coinFromSv4Participant shouldBe coinFromSvcParticipant

        sv4Participant.ledger_api.acs.of_party(svcParty) should not be empty
      }

      clue("sv4 can exercise CoinRules_DevNet_Tap without disclosed contracts or extra observer.") {
        val sv4Party = sv4Backend.getSvcInfo().svParty

        val coinRules = sv4Participant.ledger_api_extensions.acs
          .filterJava(cc.coinrules.CoinRules.COMPANION)(svcParty)
          .head

        val openRound = sv4Participant.ledger_api_extensions.acs
          .filterJava(cc.round.OpenMiningRound.COMPANION)(
            svcParty,
            _.data.opensAt.isBefore(Instant.now),
          )
          .maxBy(_.data.round.number)

        sv4Participant.ledger_api_extensions.commands.submitWithResult(
          sv4Backend.config.ledgerApiUser,
          actAs = Seq(sv4Party),
          readAs = Seq(svcParty),
          update = coinRules.id.exerciseCoinRules_DevNet_Tap(
            sv4Party.toProtoPrimitive,
            BigDecimal(100.0).bigDecimal,
            openRound.id,
          ),
        )

        def checkCoinContract(participant: CNParticipantClientReference, party: PartyId) = {
          val coins = getCoins(participant, party, _.data.owner == sv4Party.toProtoPrimitive)
          inside(coins) { case Seq(coin) =>
            coin.data.svc shouldBe svcPartyStr
            coin.data.amount.initialAmount shouldBe BigDecimal(100.0).bigDecimal.setScale(10)
            coin.data.owner shouldBe sv4Party.toProtoPrimitive
          }
        }

        eventually() {
          checkCoinContract(svcParticipant, svcParty)
          checkCoinContract(sv4Participant, sv4Party)
        }
      }

      clue("sv4 can restart") {
        sv4Backend.stop()
        sv4Backend.startSync()
      }
  }

  private def getCoins(
      participant: CNParticipantClientReference,
      party: PartyId,
      predicate: cc.coin.Coin.Contract => Boolean = _ => true,
  ): Seq[cc.coin.Coin.Contract] = {
    participant.ledger_api_extensions.acs
      .filterJava(cc.coin.Coin.COMPANION)(party, predicate)
      .sortBy(_.data.amount.initialAmount)
  }

  private def createCoinOwnBySvc(
      participant: CNParticipantClientReference,
      amount: Double,
      nrOfScanBackends: Int,
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
      {
        participant.ledger_api_extensions.commands.submitWithResult(
          userId = sv1Backend.config.ledgerApiUser,
          actAs = Seq(svcParty),
          readAs = Seq.empty,
          update = coin(amount, svcParty).create,
        )
      },
      logs =>
        inside(logs) { case logLines =>
          logLines
            .filter(_.errorMessage contains ("RuntimeException"))
            .foreach(_.errorMessage should include("Unexpected coin create event"))
          logLines should have size (nrOfScanBackends.toLong)
        },
    )
  }

  private def coin(amount: Double, party: PartyId) = new cc.coin.Coin(
    party.toProtoPrimitive,
    party.toProtoPrimitive,
    expiringAmount(amount),
    Optional.empty(),
  )

  private def expiringAmount(amount: Double) = new cc.fees.ExpiringAmount(
    BigDecimal(amount).bigDecimal,
    new cc.round.types.Round(0L),
    new cc.fees.RatePerRound(BigDecimal(amount).bigDecimal),
  )

}

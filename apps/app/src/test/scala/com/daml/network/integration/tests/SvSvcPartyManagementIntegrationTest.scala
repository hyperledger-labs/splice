package com.daml.network.integration.tests

import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.console.CNParticipantClientReference
import com.daml.network.sv.util.{SvOnboardingToken, SvUtil}
import com.digitalasset.canton.topology.transaction.TopologyChangeOpX
import com.digitalasset.canton.topology.{PartyId, UniqueIdentifier}

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
      clue("Starting SVC app and SV1 app") {
        startAllSync(sv1ScanBackend, sv1Backend, sv4Backend, sv1ValidatorBackend)
      }

      val svcParty = sv1Backend.getSvcInfo().svcParty
      val svcPartyStr: String = svcParty.toProtoPrimitive
      val sv1Participant = sv1Backend.participantClient
      val sv4Participant = sv4Backend.participantClient

      val sv1Party = sv1Backend.getSvcInfo().svParty
      val sv4Party = sv4Backend.getSvcInfo().svParty

      val publicKey =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZMNsDJr1uTwMTIIlzUZpUexTLqVGMsD7cR4Y8sqYYFYhldVMeHG5zSubf+p+WZbLEyMUCT5nBCCBh0oiUY9crA=="
      val privateKey =
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgxED/gH8AeSwNujZAVLhBRSN55Hx0ntC6FKKhgn+7h92hRANCAARkw2wMmvW5PAxMgiXNRmlR7FMupUYywPtxHhjyyphgViGV1Ux4cbnNK5t/6n5ZlssTIxQJPmcEIIGHSiJRj1ys"

      clue(
        "SVC party hosting authorization request with party which is not confirmed will be rejected by sponsor SV"
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
        "SVC party hosting authorization request with party which is not hosted on the target participant"
      ) {
        val unAuthorizedParty =
          PartyId(UniqueIdentifier(id = sv1Party.uid.id, namespace = sv4Party.uid.namespace))
        assertThrowsAndLogsCommandFailures(
          sv1Backend.startSvOnboarding(
            SvOnboardingToken(
              "Canton-Foundation-1",
              publicKey,
              unAuthorizedParty,
              sv4Backend.participantClient.id,
              svcParty,
            ).signAndEncode(SvUtil.parsePrivateKey(privateKey).value).value
          ),
          _.errorMessage should include(
            s"Candidate party ${unAuthorizedParty} is not authorized by participant "
          ),
        )
      }

      clue(
        "SV party namespace matches the namespace of its participant."
      ) {
        val sv4PartyWithWrongNamespace =
          PartyId(UniqueIdentifier(id = sv4Party.uid.id, namespace = sv1Party.uid.namespace))
        assertThrowsAndLogsCommandFailures(
          sv4Backend.startSvOnboarding(
            SvOnboardingToken(
              "Canton-Foundation-4",
              publicKey,
              sv4PartyWithWrongNamespace,
              sv4Backend.participantClient.id,
              svcParty,
            ).signAndEncode(
              SvUtil
                .parsePrivateKey(
                  privateKey
                )
                .value
            ).value
          ),
          _.errorMessage should include(
            s"Party $sv4PartyWithWrongNamespace does not have the same namespace than its participant"
          ),
        )
      }

      sv1WalletClient.tap(1.0)

      clue("start onboarding new SV and SVC party setup on new SV's dedicated participant") {
        // SV4 is configured to join the SVC. After the SV is onboarded, it will start the SVC party hosting on its own dedicated participant
        startAllSync(sv4ValidatorBackend, sv4Backend, sv4ValidatorBackend)
      }

      sv1WalletClient.tap(2.0)

      val globalDomainId = inside(sv4Participant.domains.list_connected()) { case Seq(domain) =>
        domain.domainId
      }

      eventually() {
        sv1Participant.topology.party_to_participant_mappings
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
        val coinFromSv1Participant = getCoins(sv1Participant, svcParty)

        coinFromSv4Participant should have size 2
        coinFromSv4Participant shouldBe coinFromSv1Participant

        sv4Participant.ledger_api.state.acs.of_party(svcParty) should not be empty
      }

      clue("sv4 can exercise CoinRules_DevNet_Tap without disclosed contracts or extra observer.") {
        val sv4Party = sv4Backend.getSvcInfo().svParty

        wc("sv4Wallet").tap(100.0)

        def checkSv4CoinContract(participant: CNParticipantClientReference, party: PartyId) = {
          val coins = getCoins(participant, party, _.data.owner == sv4Party.toProtoPrimitive)
          inside(coins) { case Seq(coin) =>
            coin.data.svc shouldBe svcPartyStr
            // the amount might diverge slightly due to (merged) SV rewards and fees
            BigDecimal(coin.data.amount.initialAmount) should beAround(BigDecimal(100.0))
            coin.data.owner shouldBe sv4Party.toProtoPrimitive
          }
        }

        eventually() {
          checkSv4CoinContract(sv1Participant, svcParty)
          checkSv4CoinContract(sv4Participant, sv4Party)
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
}

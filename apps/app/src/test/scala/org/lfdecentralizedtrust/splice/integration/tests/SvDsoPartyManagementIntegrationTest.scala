package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
import org.lfdecentralizedtrust.splice.sv.util.{SvOnboardingToken, SvUtil}
import org.lfdecentralizedtrust.splice.util.WalletTestUtil
import com.digitalasset.canton.topology.transaction.TopologyChangeOp
import com.digitalasset.canton.topology.{PartyId, UniqueIdentifier}

class SvDsoPartyManagementIntegrationTest extends SvIntegrationTestBase with WalletTestUtil {

  "SV users can act as SV party and act the DSO party" in { implicit env =>
    initDso()
    env.svs.local.foreach { sv =>
      val rights = sv.participantClient.ledger_api.users.rights.list(sv.config.ledgerApiUser)
      rights.actAs should contain(dsoParty)
      rights.readAs shouldBe empty
    }
    actAndCheck(
      "creating a `ValidatorOnboarding` contract readable only by sv3", {
        val sv = sv3Backend // it doesn't really matter which sv we pick
        val svParty = sv.getDsoInfo().svParty
        sv.listOngoingValidatorOnboardings() shouldBe empty
        sv.participantClient.ledger_api_extensions.commands.submitWithResult(
          sv.config.ledgerApiUser,
          actAs = Seq(svParty),
          readAs = Seq.empty,
          update = new splice.validatoronboarding.ValidatorOnboarding(
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
          visible.contract.contractId shouldBe created.contractId
        },
    )
  }

  "The DSO Party can be setup in the participant after SV has been confirmed to be part of the DSO" in {
    implicit env =>
      clue("Starting DSO app and SV1 app") {
        startAllSync((sv1Nodes ++ sv3Nodes)*)
      }

      val dsoParty = sv1Backend.getDsoInfo().dsoParty
      val dsoPartyStr: String = dsoParty.toProtoPrimitive
      val sv1Participant = sv1Backend.participantClient
      val sv3Participant = sv3Backend.participantClient

      val sv1Party = sv1Backend.getDsoInfo().svParty
      val sv3Party = sv3Backend.getDsoInfo().svParty

      val publicKey =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZMNsDJr1uTwMTIIlzUZpUexTLqVGMsD7cR4Y8sqYYFYhldVMeHG5zSubf+p+WZbLEyMUCT5nBCCBh0oiUY9crA=="
      val privateKey =
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgxED/gH8AeSwNujZAVLhBRSN55Hx0ntC6FKKhgn+7h92hRANCAARkw2wMmvW5PAxMgiXNRmlR7FMupUYywPtxHhjyyphgViGV1Ux4cbnNK5t/6n5ZlssTIxQJPmcEIIGHSiJRj1ys"

      clue(
        "DSO party hosting authorization request with party which is not confirmed will be rejected by sponsor SV"
      ) {
        val randomParty = allocateRandomSvParty("random")
        assertThrowsAndLogsCommandFailures(
          sv1Backend.onboardSvPartyMigrationAuthorize(
            sv3Backend.participantClient.id,
            randomParty,
          ),
          _.errorMessage should include(
            "Candidate party is not an sv and no `SvOnboardingConfirmed` for the candidate party is found."
          ),
        )
      }

      clue(
        "DSO party hosting authorization request with party which is not hosted on the target participant"
      ) {
        val unAuthorizedParty =
          PartyId(
            UniqueIdentifier.tryCreate(
              sv1Party.uid.identifier.str,
              namespace = sv3Party.uid.namespace,
            )
          )
        assertThrowsAndLogsCommandFailures(
          sv1Backend.startSvOnboarding(
            SvOnboardingToken(
              getSvName(1),
              publicKey,
              unAuthorizedParty,
              sv3Backend.participantClient.id,
              dsoParty,
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
        val sv3PartyWithWrongNamespace =
          PartyId(
            UniqueIdentifier.tryCreate(
              sv3Party.uid.identifier.str,
              namespace = sv1Party.uid.namespace,
            )
          )
        assertThrowsAndLogsCommandFailures(
          sv3Backend.startSvOnboarding(
            SvOnboardingToken(
              getSvName(4),
              publicKey,
              sv3PartyWithWrongNamespace,
              sv3Backend.participantClient.id,
              dsoParty,
            ).signAndEncode(
              SvUtil
                .parsePrivateKey(
                  privateKey
                )
                .value
            ).value
          ),
          _.errorMessage should include(
            s"Party $sv3PartyWithWrongNamespace does not have the same namespace than its participant"
          ),
        )
      }

      sv1WalletClient.tap(1.0)

      clue("start onboarding new SV and DSO party setup on new SV's dedicated participant") {
        // sv3 is configured to join the DSO. After the SV is onboarded, it will start the DSO party hosting on its own dedicated participant
        startAllSync(sv3Nodes*)
      }

      sv1WalletClient.tap(2.0)

      val decentralizedSynchronizerId = inside(sv3Participant.synchronizers.list_connected()) {
        case Seq(domain) =>
          domain.synchronizerId
      }

      eventually() {
        sv1Participant.topology.party_to_participant_mappings
          .list(
            operation = Some(TopologyChangeOp.Replace),
            synchronizerId = decentralizedSynchronizerId,
            filterParty = dsoPartyStr,
            filterParticipant = sv3Participant.id.toProtoPrimitive,
          ) should have size 1

        sv3Participant.topology.party_to_participant_mappings
          .list(
            operation = Some(TopologyChangeOp.Replace),
            synchronizerId = decentralizedSynchronizerId,
            filterParty = dsoPartyStr,
            filterParticipant = sv3Participant.id.toProtoPrimitive,
          ) should have size 1
        val amuletFromsv3Participant = getAmulets(sv3Participant, dsoParty)
        val amuletFromSv1Participant = getAmulets(sv1Participant, dsoParty)

        amuletFromsv3Participant should have size 2
        amuletFromsv3Participant shouldBe amuletFromSv1Participant

        sv3Participant.ledger_api.state.acs.of_party(dsoParty) should not be empty
      }

      clue(
        "sv3 can exercise AmuletRules_DevNet_Tap without disclosed contracts or extra observer."
      ) {
        val sv3Party = sv3Backend.getDsoInfo().svParty

        wc("sv3Wallet").tap(100.0)

        def checksv3AmuletContract(participant: ParticipantClientReference, party: PartyId) = {
          val amulets = getAmulets(participant, party, _.data.owner == sv3Party.toProtoPrimitive)
          inside(amulets) { case Seq(amulet) =>
            amulet.data.dso shouldBe dsoPartyStr
            // the amount might diverge slightly due to (merged) SV rewards and fees
            BigDecimal(amulet.data.amount.initialAmount) should
              beWithin(walletUsdToAmulet(99), walletUsdToAmulet(101))
            amulet.data.owner shouldBe sv3Party.toProtoPrimitive
          }
        }

        eventually() {
          checksv3AmuletContract(sv1Participant, dsoParty)
          checksv3AmuletContract(sv3Participant, sv3Party)
        }
      }

      clue("sv3 can restart") {
        sv3Backend.stop()
        sv3Backend.startSync()
      }
  }

  private def getAmulets(
      participant: ParticipantClientReference,
      party: PartyId,
      predicate: splice.amulet.Amulet.Contract => Boolean = _ => true,
  ): Seq[splice.amulet.Amulet.Contract] = {
    participant.ledger_api_extensions.acs
      .filterJava(splice.amulet.Amulet.COMPANION)(party, predicate)
      .sortBy(_.data.amount.initialAmount)
  }
}

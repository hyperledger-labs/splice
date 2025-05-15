package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.TransferInstruction
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransferInstructionResultOutput.members
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.WalletTestUtil

import java.util.UUID

class TokenStandardTransferIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with HasActorSystem
    with HasExecutionContext {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
  }

  "Token Standard Transfers should" should {

    "support create, list, accept, reject and withdraw" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      aliceWalletClient.tap(100)

      val responses = (1 to 4).map { i =>
        actAndCheck(
          "Alice creates transfer offer",
          aliceWalletClient.createTokenStandardTransfer(
            bobUserParty,
            10,
            s"Transfer #$i",
            CantonTimestamp.now().plusSeconds(3600L),
            UUID.randomUUID().toString,
          ),
        )(
          "Alice and Bob see it",
          _ => {
            Seq(aliceWalletClient, bobWalletClient).foreach(
              _.listTokenStandardTransfers().transfers should have size i.toLong
            )
          },
        )._1
      }

      val cids = responses.map { response =>
        response.output match {
          case members.TransferInstructionPending(value) =>
            new TransferInstruction.ContractId(value.transferInstructionCid)
          case _ => fail("The transfers were expected to be pending.")
        }
      }

      clue("Scan sees all the transfers") {
        cids.foreach { cid =>
          eventuallySucceeds() {
            sv1ScanBackend.getTransferInstructionRejectContext(cid)
          }
        }
      }

      inside(cids.toList) { case toReject :: toWithdraw :: toAccept :: _toIgnore :: Nil =>
        actAndCheck(
          "Bob rejects one transfer offer",
          bobWalletClient.rejectTokenStandardTransfer(toReject),
        )(
          "The offer is removed, no change to Bob's balance",
          result => {
            inside(result.output) { case members.TransferInstructionFailed(_) => () }
            Seq(aliceWalletClient, bobWalletClient).foreach(
              _.listTokenStandardTransfers().transfers should have size (cids.length.toLong - 1L)
            )
            bobWalletClient.balance().unlockedQty should be(BigDecimal(0))
          },
        )

        actAndCheck(
          "Alice withdraws one transfer offer",
          aliceWalletClient.withdrawTokenStandardTransfer(toWithdraw),
        )(
          "The offer is removed, no change to Bob's balance",
          result => {
            inside(result.output) { case members.TransferInstructionFailed(_) => () }
            Seq(aliceWalletClient, bobWalletClient).foreach(
              _.listTokenStandardTransfers().transfers should have size (cids.length.toLong - 2L)
            )
            bobWalletClient.balance().unlockedQty should be(BigDecimal(0))
          },
        )

        actAndCheck(
          "Bob accepts one transfer offer",
          bobWalletClient.acceptTokenStandardTransfer(toAccept),
        )(
          "The offer is removed and bob's balance is updated",
          result => {
            inside(result.output) { case members.TransferInstructionCompleted(_) => () }
            Seq(aliceWalletClient, bobWalletClient).foreach(
              _.listTokenStandardTransfers().transfers should have size (cids.length.toLong - 3L)
            )
            bobWalletClient.balance().unlockedQty should be > BigDecimal(0)
          },
        )
      }
    }

    "prevent duplicate transfer creation" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      aliceWalletClient.tap(100.0)

      val expiration = CantonTimestamp.now().plusSeconds(3600L)

      val trackingId = UUID.randomUUID().toString

      val created = aliceWalletClient.createTokenStandardTransfer(
        bobUserParty,
        10,
        "ok",
        expiration,
        trackingId,
      )

      assertThrows[CommandFailure](
        loggerFactory.assertLogs(
          aliceWalletClient.createTokenStandardTransfer(
            bobUserParty,
            10,
            "not ok, resubmitted same trackingId so should be rejected",
            expiration,
            trackingId,
          ),
          _.errorMessage should include("Command submission already exists"),
        )
      )

      eventually() {
        inside(aliceWalletClient.listTokenStandardTransfers().transfers) { case Seq(t) =>
          t.contractId should be(created.output match {
            case members.TransferInstructionPending(value) => value.transferInstructionCid
            case x => fail(s"Expected pending transfer, got $x")
          })
        }
      }
    }

  }

}

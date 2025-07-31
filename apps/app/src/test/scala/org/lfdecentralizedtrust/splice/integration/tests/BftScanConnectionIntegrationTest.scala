package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.console.CommandFailure
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, WalletTestUtil}
import com.digitalasset.canton.logging.SuppressionRule
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.TransferInstruction
import org.slf4j.event.Level

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class BftScanConnectionIntegrationTest extends IntegrationTest with WalletTestUtil with SvTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withManualStart

  "init fast enough even if there are unavailable scans" in { implicit env =>
    startAllSync(
      sv1ScanBackend,
      sv1ValidatorBackend,
      sv1Backend,
      sv2ScanBackend,
      sv2ValidatorBackend,
      sv2Backend,
    )

    // make sure both scans get registered
    eventually() {
      val dsoInfo = sv1Backend.getDsoInfo()
      val scans = for {
        (_, nodeState) <- dsoInfo.svNodeStates
        (_, synchronizerNode) <- nodeState.payload.state.synchronizerNodes.asScala
        scan <- synchronizerNode.scan.toScala
      } yield scan
      scans should have size 2 // sv1&2's scans
    }

    // Alice's validator will see the two scans, but SV2's won't connect
    sv2ScanBackend.stop()
    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
      {
        aliceValidatorBackend.startSync()
        aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
      },
      logs =>
        (logs
          .map(_.message)
          .forall(msg =>
            msg
              .contains(s"Failed to connect to scan of ${getSvName(2)} (http://localhost:5112).") ||
              msg.contains("Encountered 4 consecutive transient failures")
          ) should be(true)).withClue(s"Actual Logs: $logs"),
    )

    eventuallySucceeds() {
      aliceAnsExternalClient.listAnsEntries()
    }
  }

  "agree on failed HttpCommandException" in { implicit env =>
    startAllSync(
      sv1ScanBackend,
      sv1Backend,
      sv2ScanBackend,
      sv2Backend,
      sv3ScanBackend,
      sv3Backend,
      sv4ScanBackend,
      sv4Backend,
    )

    aliceValidatorBackend.startSync()

    val fakeCid = "00" + s"01" * 31 + "42"

    loggerFactory.assertThrowsAndLogsSeq[CommandFailure](
      aliceValidatorWalletClient.acceptTokenStandardTransfer(
        new TransferInstruction.ContractId(fakeCid)
      ),
      entries => {
        forAll(entries)(_.message should not include "Consensus not reached")
        forExactly(1, entries)(
          _.message should include(
            "HTTP 404 Not Found"
          ).and(include(s"AmuletTransferInstruction '$fakeCid' not found"))
        )
      },
    )
  }
}

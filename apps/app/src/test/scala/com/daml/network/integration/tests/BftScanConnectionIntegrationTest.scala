package com.daml.network.integration.tests

import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import com.daml.network.util.{ConfigScheduleUtil, SvTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class BftScanConnectionIntegrationTest
    extends IntegrationTest
    with ConfigScheduleUtil
    with WalletTestUtil
    with SvTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
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
          .forall(
            _.contains(s"Failed to connect to scan of ${getSvName(2)} (http://localhost:5112).")
          ) should be(true)).withClue(s"Actual Logs: $logs"),
    )

    eventuallySucceeds() {
      aliceAnsExternalClient.listAnsEntries()
    }
  }
}

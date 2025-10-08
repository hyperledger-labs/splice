package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.TransferInstruction
import org.lfdecentralizedtrust.splice.http.v0.wallet as http
import org.lfdecentralizedtrust.splice.http.v0.wallet.AcceptTokenStandardTransferResponse
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.tokenstandard.transferinstruction
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v1.GetTransferInstructionAcceptContextResponse
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v1.definitions.GetChoiceContextRequest
import org.slf4j.event.Level

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class BftScanConnectionIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with SvTestUtil
    with HasExecutionContext
    with HasActorSystem {

  override protected def runEventHistorySanityCheck: Boolean = false

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
    onboardWalletUser(aliceValidatorWalletClient, aliceValidatorBackend)
    val walletUserToken =
      OAuth2BearerToken(aliceValidatorWalletClient.token.valueOrFail("No token found"))

    val fakeCid = new TransferInstruction.ContractId("00" + s"01" * 31 + "42")

    val singleScanClient = transferinstruction.v1.Client
      .httpClient(
        Http().singleRequest(_),
        s"http://${sv1ScanBackend.config.adminApi.address}:${sv1ScanBackend.config.adminApi.port.unwrap}",
      )

    val singleScanResponse =
      singleScanClient
        .getTransferInstructionAcceptContext(fakeCid.contractId, GetChoiceContextRequest(None))
        .value
        .futureValue

    val bftClient = transferinstruction.v1.Client
      .httpClient(
        Http().singleRequest(_),
        s"http://${aliceValidatorBackend.config.adminApi.address}:${aliceValidatorBackend.config.adminApi.port.unwrap}/api/validator/v0/scan-proxy",
      )
    val bftResponse = bftClient
      .getTransferInstructionAcceptContext(
        fakeCid.contractId,
        GetChoiceContextRequest(None),
        List(Authorization(walletUserToken)),
      )
      .value
      .futureValue

    inside((bftResponse, singleScanResponse)) {
      case (
            Right(err1),
            Right(err2),
          ) =>
        err1 should be(err2)
    }

    val walletClient = http.WalletClient.httpClient(
      Http().singleRequest(_),
      s"http://${aliceValidatorBackend.config.adminApi.address}:${aliceValidatorBackend.config.adminApi.port.unwrap}",
    )

    val endpointThatUsesBftCallResult =
      walletClient
        .acceptTokenStandardTransfer(
          fakeCid.contractId,
          List(Authorization(walletUserToken)),
        )
        .value
        .futureValue

    inside((endpointThatUsesBftCallResult, singleScanResponse)) {
      case (
            Right(AcceptTokenStandardTransferResponse.NotFound(err1)),
            Right(GetTransferInstructionAcceptContextResponse.NotFound(err2)),
          ) =>
        // still different types...
        err1.error should be(err2.error)
    }
  }

}

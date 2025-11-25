// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import cats.data.{NonEmptyList, OptionT}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.TransferInstruction
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.http.v0.wallet as http
import org.lfdecentralizedtrust.splice.http.v0.wallet.AcceptTokenStandardTransferResponse
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.BftScanClientConfig
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.validator.store.ValidatorConfigProvider.ScanUrlInternalConfig
import org.lfdecentralizedtrust.tokenstandard.transferinstruction
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v1.GetTransferInstructionAcceptContextResponse
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v1.definitions.GetChoiceContextRequest
import org.slf4j.event.Level

import scala.concurrent.Future
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
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs {
          case (name, c) if name == "aliceValidator" =>
            val dbEnabledConfig = BftScanClientConfig.Bft(
              seedUrls = NonEmptyList.of(
                Uri("http://127.0.0.1:5012")
              ),
              scansRefreshInterval = NonNegativeFiniteDuration.ofSeconds(60),
            )
            c.copy(scanClient = dbEnabledConfig)
          case (_, c) => c
        }(config)
      )
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
              msg.contains("Encountered 4 consecutive transient failures") || msg.contains(
                "Failed to connect to scan of FAILED Seed URL #0 (http://localhost:5112)."
              )
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
        List(
          Authorization(
            OAuth2BearerToken(aliceValidatorBackend.token.valueOrFail("No token found"))
          )
        ),
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
          List(
            Authorization(
              OAuth2BearerToken(aliceValidatorBackend.token.valueOrFail("No token found"))
            )
          ),
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

  "validator onboarding and recovery succeed with internal config turned on" in { implicit env =>
    startAllSync(
      sv1Backend,
      sv1ScanBackend,
      sv2Backend,
      sv2ScanBackend,
      sv3Backend,
      sv3ScanBackend,
      sv4Backend,
      sv4ScanBackend,
    )

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        aliceValidatorBackend.startSync()
      },
      logs => {
        val messages = logs.map(_.message)
        withClue("Validator should first bootstrap with 1 and then 4 scans") {
          messages.filter(_.contains(bootstrapsWith1UrlLog)) should have length 1
          messages.filter(_.contains(bootstrapsWith4UrlsLog)) should have length 1
        }
      },
    )

    val persistedState: OptionT[Future, Seq[ScanUrlInternalConfig]] =
      aliceValidatorBackend.appState.configProvider.getScanUrlInternalConfig()

    val expectedConfigs = Seq(
      ScanUrlInternalConfig(getSvName(1), "http://localhost:5012"),
      ScanUrlInternalConfig(getSvName(2), "http://localhost:5112"),
      ScanUrlInternalConfig(getSvName(3), "http://localhost:5212"),
      ScanUrlInternalConfig(getSvName(4), "http://localhost:5312"),
    )

    withClue("Persisted state should contain the expected four internal scan configurations") {
      val maybeActualConfigs: Option[Seq[ScanUrlInternalConfig]] = persistedState.value.futureValue

      maybeActualConfigs.fold {
        fail("The persisted state for scan URLs was None (not found or not persisted).")
      } { actualConfigs =>
        actualConfigs should contain theSameElementsAs expectedConfigs
      }
    }

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        aliceValidatorBackend.stop()
        aliceValidatorBackend.startSync()
      },
      logs => {
        val messages = logs.map(_.message)
        withClue("Validator should bootstrap with 4 scans only during recovery") {
          messages.filter(_.contains(bootstrapsWith1UrlLog)) should have length 0
          messages.filter(_.contains(bootstrapsWith4UrlsLog)) should have length 2
        }
      },
    )

    withClue("Alice's validator should be able to onboard a user after establishing connections.") {
      eventuallySucceeds() {
        aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
      }
    }

  }

  private val bootstrapsWith1UrlLog =
    s"Validator bootstrapping with 1 seed URLs: List(http://127.0.0.1:5012)"

  private val bootstrapsWith4UrlsLog =
    s"Validator bootstrapping with 4 seed URLs: List(http://localhost:5012, http://localhost:5112, http://localhost:5212, http://localhost:5312)"

}

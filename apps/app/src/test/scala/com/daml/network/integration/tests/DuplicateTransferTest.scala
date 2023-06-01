package com.daml.network.integration.tests

import com.digitalasset.canton.{DomainAlias, LfTimestamp}

import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.tracing.TracerProvider
import com.daml.network.automation.TransferInTrigger
import com.daml.network.codegen.java.cc.coin.ValidatorRight
import com.daml.network.environment.{CNLedgerClient, RetryProvider}
import com.daml.network.environment.ledger.api.LedgerClient.TransferCommand
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTest
import com.daml.network.util.WalletTestUtil

import scala.util.Using

class DuplicateTransferTest extends CNNodeIntegrationTest with WalletTestUtil {

  private val darPath = "daml/canton-coin/.daml/dist/canton-coin-0.1.0.dar"

  override def environmentDefinition =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withManualStart
      .withAdditionalSetup(implicit env => {
        aliceValidator.participantClient.upload_dar_unless_exists(darPath)
      })

  val globalDomain = DomainAlias.tryCreate("global")
  val splitwellDomain = DomainAlias.tryCreate("splitwell")

  // Copied from com.digitalasset.canton.participant.grpc.GrpcConversions.
  private def toApiTransferId(ts: LfTimestamp): String = {
    val instant = ts.toInstant
    /*
      LfTimestamp has micro resolution so dividing getNano by 1000 does
      not incur loss of precision.
     */
    val secondsToMicros = 1000000L
    val epochMicros = instant.getEpochSecond * secondsToMicros + instant.getNano / 1000
    epochMicros.toString
  }

  "duplicate transfer in" in { implicit env =>
    val alice = aliceValidator.participantClientWithAdminToken.ledger_api.parties
      .allocate(aliceWallet.config.ledgerApiUser, aliceWallet.config.ledgerApiUser)
      .party
    Using.resource(
      new CNLedgerClient(
        config = aliceValidator.participantClientWithAdminToken.config.ledgerApi,
        applicationId = "test",
        token = aliceValidator.participantClientWithAdminToken.config.token,
        timeouts = env.environment.config.parameters.timeouts.processing,
        apiLoggingConfig = env.environment.config.monitoring.logging.api,
        loggerFactory = loggerFactory,
        tracerProvider = TracerProvider.Factory(env.environment.configuredOpenTelemetry, "test"),
        retryProvider =
          RetryProvider(loggerFactory, env.environment.config.parameters.timeouts.processing),
      )(
        env.environment.executionContext,
        env.actorSystem,
        env.environment.executionSequencerFactory,
        ErrorLoggingContext.fromTracedLogger(logger),
      )
    ) { client =>
      val connection = client.connection("test", loggerFactory)

      val globalDomainId =
        aliceValidator.participantClientWithAdminToken.domains.id_of(globalDomain)
      val splitwellDomainId =
        aliceValidator.participantClientWithAdminToken.domains.id_of(splitwellDomain)

      // We do the setup through console commands which are a bit simpler to use.
      val cid = aliceValidator.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitWithResult(
          aliceWallet.config.ledgerApiUser,
          actAs = Seq(alice),
          readAs = Seq.empty,
          update = new ValidatorRight(
            alice.toProtoPrimitive,
            alice.toProtoPrimitive,
            alice.toProtoPrimitive,
          ).create,
        )
        .contractId
      val outId = aliceValidator.participantClientWithAdminToken.transfer.out(
        alice,
        cid,
        globalDomain,
        splitwellDomain,
      )

      connection
        .submitTransferAndWaitNoDedup(
          alice,
          TransferCommand.In(
            transferOutId = toApiTransferId(outId.transferOutTimestamp.toLf),
            source = globalDomainId,
            target = splitwellDomainId,
          ),
        )
        .futureValue

      val ex = connection
        .submitTransferAndWaitNoDedup(
          alice,
          TransferCommand.In(
            transferOutId = toApiTransferId(outId.transferOutTimestamp.toLf),
            source = globalDomainId,
            target = splitwellDomainId,
          ),
        )
        .failed
        .futureValue

      ex should matchPattern { case TransferInTrigger.TransferCompletedException(_) => }
    }
  }
}

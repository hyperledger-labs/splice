// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import org.lfdecentralizedtrust.splice.automation.TriggerContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.amuletoperation
import org.lfdecentralizedtrust.splice.environment.{PackageVersionSupport, SpliceLedgerConnection}
import SpliceLedgerConnection.CommandId
import org.lfdecentralizedtrust.splice.environment.ledger.api.DedupDuration
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.util.ContractWithState
import org.lfdecentralizedtrust.splice.wallet.config.WalletSweepConfig
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import org.lfdecentralizedtrust.splice.wallet.treasury.TreasuryService
import TreasuryService.AmuletOperationDedupConfig
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

class WalletPreapprovalSweepTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: SpliceLedgerConnection,
    config: WalletSweepConfig,
    scanConnection: ScanConnection,
    treasury: TreasuryService,
    dedupDuration: DedupDuration,
    packageVersionSupport: PackageVersionSupport,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    mat: Materializer,
) extends WalletSweepTrigger(context, store, config, scanConnection) {

  override protected def getOutstandingBalanceUsd(
      amuletPrice: java.math.BigDecimal,
      currentAmuletConfig: AmuletConfig[USD],
  )(implicit tc: TraceContext) = Future.successful(BigDecimal(0.0))

  override protected def outstandingDescription: Nothing = throw Status.INTERNAL
    .withDescription(
      s"outstandingDescription should never be called for ${this.getClass.getSimpleName}"
    )
    .asRuntimeException

  protected def preapprovalDescriptionIfSupported(
      parties: Seq[PartyId],
      description: String,
  )(implicit tc: TraceContext): Future[Option[String]] = {
    packageVersionSupport
      .supportsDescriptionInTransferPreapprovals(parties, context.clock.now)
      .map { featureSupport =>
        Option.when(featureSupport.supported)(description)
      }
  }

  private def getTransferPreapproval()(implicit tc: TraceContext) =
    scanConnection
      .lookupTransferPreapprovalByParty(config.receiver)
      .map(
        _.getOrElse(
          throw Status.INVALID_ARGUMENT
            .withDescription(
              s"No transfer preapproval for receiver ${config.receiver}, check that the party id is correct. If it is, either reach out to the receiver to ask them to preapprove all incoming transfers or disable `use-transfer-preapproval` in the wallet config"
            )
            .asRuntimeException
        )
      )

  override protected def extraRetrieveTasksValidation()(implicit tc: TraceContext) =
    getTransferPreapproval().map(_ => ())

  override protected def sweep(
      task: WalletSweepTrigger.Task,
      balanceUsd: BigDecimal,
      outstandingBalanceUsd: BigDecimal,
      amountToSendBeforeFeesUsd: BigDecimal,
      amountToSendAfterFeesUsd: BigDecimal,
      amountToSendAfterFeesCC: java.math.BigDecimal,
  )(implicit tc: TraceContext): Future[Unit] = {
    for {
      preapproval <- getTransferPreapproval()
      featuredAppRight <- scanConnection.lookupFeaturedAppRight(
        PartyId.tryFromProtoPrimitive(preapproval.payload.provider)
      )
      description <- preapprovalDescriptionIfSupported(
        Seq(
          store.key.endUserParty,
          store.key.dsoParty,
          PartyId.tryFromProtoPrimitive(preapproval.payload.receiver),
        ),
        "sweep",
      )
      _ <- treasury.enqueueAmuletOperation(
        operation = new amuletoperation.CO_TransferPreapprovalSend(
          preapproval.contractId,
          featuredAppRight.map(_.contractId).toJava,
          amountToSendAfterFeesCC,
          description.toJava,
        ),
        dedup = Some(
          AmuletOperationDedupConfig(
            CommandId(
              "transferPreapprovalSend",
              Seq(store.key.endUserParty),
              task.trackingId,
            ),
            dedupDuration,
          )
        ),
        extraDisclosedContracts = connection.disclosedContracts(
          preapproval,
          featuredAppRight.map(ContractWithState(_, preapproval.state)).toList*
        ),
      )
    } yield ()
  }
}

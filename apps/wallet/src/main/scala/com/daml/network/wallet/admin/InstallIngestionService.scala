package com.daml.network.wallet.admin

import com.daml.ledger.api.v1.transaction.Transaction
import com.daml.ledger.client.binding.Primitive
import com.daml.network.admin.LedgerAutomationService
import com.digitalasset.canton.participant.ledger.api.client.DecodeUtil
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.daml.network.codegen.CN.{Wallet => walletCodegen}
import com.daml.network.util.Contract
import com.daml.network.wallet.store.WalletAppPartyStore
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.Future

// TODO (i713): This whole class is only a workaround for missing `read-as-any-party` rights in the ledger API.
class InstallIngestionService(
    partyStore: WalletAppPartyStore,
    protected val loggerFactory: NamedLoggerFactory,
) extends LedgerAutomationService
    with NamedLogging {

  override def templateIds: Seq[Primitive.TemplateId[_]] = Seq(walletCodegen.WalletAppInstall.id)

  override def processTransaction(tx: Transaction)(implicit
      traceContext: TraceContext
  ): Future[Unit] = Future.successful {
    DecodeUtil
      .decodeAllCreated(walletCodegen.WalletAppInstall)(tx)
      .foreach(c =>
        partyStore.addParty(PartyId.tryFromPrim(Contract.fromCodegenContract(c).payload.endUser))
      )
    // TODO(i763): not handling archive events, uninstalling first party apps is not supported yet
  }

  override def close(): Unit = ()

}

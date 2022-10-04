package com.daml.network.wallet.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.wallet.store.WalletStore
import com.daml.network.store.{AcsStore, InMemoryAcsStore}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.daml.network.codegen.CN.{Wallet => walletCodegen}

import scala.concurrent._

class InMemoryWalletStore(
    override val key: WalletStore.Key,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends WalletStore
    with NamedLogging {

  private val inMemoryAcsStore =
    new InMemoryAcsStore(
      loggerFactory,
      WalletStore.contractFilter(key),
      logAllStateUpdates = true,
    )

  noTracingLogger.debug(s"Created InMemoryWalletStore for $key")

  val acsStore: AcsStore = inMemoryAcsStore

  override val acsIngestionSink: AcsStore.IngestionSink = inMemoryAcsStore.ingestionSink

  def listParties()(implicit tc: TraceContext): Future[Seq[PartyId]] =
    acsStore.listContracts(walletCodegen.WalletAppInstall).map { case QueryResult(_, installs) =>
      installs.map(co => PartyId.tryFromPrim(co.payload.endUser))
    }

  def getPartiesStream(implicit tc: TraceContext): Source[Seq[PartyId], NotUsed] =
    acsStore
      .streamContracts(walletCodegen.WalletAppInstall)
      .map(install => PartyId.tryFromPrim(install.payload.endUser))
      // TODO(#790): support off-boarding parties -- should be driven from a stream of onboarding and offboarding events
      .scan(Seq.empty: Seq[PartyId])((existingParties, newParty) =>
        existingParties.prepended(newParty)
      )

  override def close(): Unit = ()

}

package com.daml.network.wallet.store.memory

import akka.NotUsed
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.Source
import com.daml.network.wallet.store.WalletAppStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.concurrent.{ExecutionContext, Future, blocking}
import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CC.Coin.Coin
import com.daml.network.util.Contract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.collection.mutable

/** Example for in-memory store in the store pattern. */
class InMemoryWalletAppStore(override protected val loggerFactory: NamedLoggerFactory)(implicit
    ec: ExecutionContext,
    mat: Materializer,
) extends WalletAppStore
    with NamedLogging {

  private val activeCoins: mutable.Map[Primitive.ContractId[Coin], Contract[Coin]] =
    mutable.Map.empty[Primitive.ContractId[Coin], Contract[Coin]]

  override def addCoin(coin: Contract[Coin])(implicit tc: TraceContext): Future[Unit] =
    Future {
      blocking {
        synchronized {
          logger.debug(
            s"Adding ${coin.payload.quantity.initialQuantity}CC for ${coin.payload.owner}"
          )
          activeCoins += (coin.contractId -> coin)
          ()
        }
      }
    }

  override def archiveCoin(
      cid: Primitive.ContractId[Coin]
  )(implicit tc: TraceContext): Future[Unit] =
    Future {
      blocking {
        synchronized {
          logger.debug(s"Removing coin $cid")
          activeCoins -= cid
          ()
        }
      }
    }

  override def listCoins(party: PartyId)(implicit tc: TraceContext): Future[Seq[Contract[Coin]]] =
    Future {
      blocking {
        synchronized {
          activeCoins.values.filter(coin => coin.payload.owner == party.toPrim).toSeq
        }
      }
    }

  private val parties: mutable.Buffer[PartyId] = mutable.Buffer.empty
  private val (partiesQueue, partiesSource) =
    Source.queue[Seq[PartyId]](2, OverflowStrategy.dropHead).preMaterialize()

  override def addParty(party: PartyId)(implicit tc: TraceContext): Future[Unit] =
    Future {
      blocking {
        synchronized {
          logger.debug(s"Adding party $party")
          parties.append(party)
        }
      }
    }.flatMap(_ => partiesQueue.offer(parties.toSeq).map(_ => ()))

  override def listParties()(implicit tc: TraceContext): Future[Seq[PartyId]] =
    Future {
      blocking {
        synchronized {
          parties.toSeq
        }
      }
    }

  override def getPartiesStream(implicit tc: TraceContext): Source[Seq[PartyId], NotUsed] =
    partiesSource.wireTap(ps => logger.debug(s"Emitting parties $ps on the stream"))

  override def close(): Unit = {
    partiesQueue.complete()
  }
}

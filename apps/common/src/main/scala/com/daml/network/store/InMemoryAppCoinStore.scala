package com.daml.network.store

import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CC.Coin.Coin
import com.daml.network.util.Contract
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, blocking}

/** Memory store for unlocked coins on ledger. */
class InMemoryAppCoinStore(protected val loggerFactory: NamedLoggerFactory)(implicit
    ec: ExecutionContext
) extends NamedLogging
    with AppCoinStore {

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
}

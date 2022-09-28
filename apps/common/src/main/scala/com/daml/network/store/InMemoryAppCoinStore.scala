package com.daml.network.store

import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CC.Coin.{Coin, LockedCoin}
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

  private val activeLockedCoins
      : mutable.Map[Primitive.ContractId[LockedCoin], Contract[LockedCoin]] =
    mutable.Map.empty[Primitive.ContractId[LockedCoin], Contract[LockedCoin]]

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

  override def addLockedCoin(
      lockedCoin: Contract[LockedCoin]
  )(implicit tc: TraceContext): Future[Unit] =
    Future {
      blocking {
        synchronized {
          logger.debug(
            s"Adding locked ${lockedCoin.payload.coin.quantity.initialQuantity}CC for ${lockedCoin.payload.coin.owner}"
          )
          activeLockedCoins += (lockedCoin.contractId -> lockedCoin)
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

  override def archiveLockedCoin(
      cid: Primitive.ContractId[LockedCoin]
  )(implicit tc: TraceContext): Future[Unit] =
    Future {
      blocking {
        synchronized {
          logger.debug(s"Removing locked coin $cid")
          activeLockedCoins -= cid
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

  override def listLockedCoins(
      party: PartyId
  )(implicit tc: TraceContext): Future[Seq[Contract[LockedCoin]]] =
    Future {
      blocking {
        synchronized {
          activeLockedCoins.values
            .filter(lockedCoin => lockedCoin.payload.coin.owner == party.toPrim)
            .toSeq
        }
      }
    }
}

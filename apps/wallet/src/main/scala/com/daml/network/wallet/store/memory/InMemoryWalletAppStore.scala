package com.daml.network.wallet.store.memory

import akka.NotUsed
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.Source
import com.daml.ledger.client
import com.daml.ledger.client.binding
import com.daml.network.wallet.store.WalletAppStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.concurrent.{ExecutionContext, Future, blocking}
import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CC.Coin.Coin
import com.daml.network.codegen.CN.Wallet.{AppPaymentRequest, OnChannelPaymentRequest}
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

  private val appPaymentRequests
      : mutable.Map[Primitive.ContractId[AppPaymentRequest], Contract[AppPaymentRequest]] =
    mutable.Map.empty[Primitive.ContractId[AppPaymentRequest], Contract[AppPaymentRequest]]

  private val onChannelPaymentRequests
      : mutable.Map[Primitive.ContractId[OnChannelPaymentRequest], Contract[
        OnChannelPaymentRequest
      ]] =
    mutable.Map
      .empty[Primitive.ContractId[OnChannelPaymentRequest], Contract[OnChannelPaymentRequest]]

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

  override def addAppPaymentRequest(
      req: Contract[AppPaymentRequest]
  )(implicit tc: TraceContext): Future[Unit] =
    Future {
      blocking {
        synchronized {
          logger.debug(
            s"Adding app payment request from ${req.payload.sender} to ${req.payload.receiver} for ${req.payload.quantity}"
          )
          appPaymentRequests += (req.contractId -> req)
          ()
        }
      }
    }

  override def removeAppPaymentRequest(req: binding.Primitive.ContractId[AppPaymentRequest])(
      implicit tc: TraceContext
  ): Future[Unit] =
    Future {
      blocking {
        synchronized {
          logger.debug(s"Removing app payment request $req")
          appPaymentRequests -= req
          ()
        }
      }
    }

  override def listAppPaymentRequests(party: PartyId)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[AppPaymentRequest]]] =
    Future {
      blocking {
        synchronized {
          appPaymentRequests.values
            .filter(req =>
              req.payload.sender == party.toPrim || req.payload.receiver == party.toPrim
            )
            .toSeq
        }
      }
    }

  override def addOnChannelPaymentRequest(
      req: Contract[OnChannelPaymentRequest]
  )(implicit tc: TraceContext): Future[Unit] =
    Future {
      blocking {
        synchronized {
          logger.debug(
            s"Adding on channel payment request from ${req.payload.sender} to ${req.payload.receiver} for ${req.payload.quantity}, labeled '${req.payload.description}'"
          )
          onChannelPaymentRequests += (req.contractId -> req)
          ()
        }
      }
    }

  override def removeOnChannelPaymentRequest(
      req: client.binding.Primitive.ContractId[OnChannelPaymentRequest]
  )(implicit tc: TraceContext): Future[Unit] =
    Future {
      blocking {
        synchronized {
          logger.debug(s"Removing on channel payment request $req")
          onChannelPaymentRequests -= req
          ()
        }
      }
    }

  override def listOnChannelPaymentRequests(party: PartyId)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[OnChannelPaymentRequest]]] =
    Future {
      blocking {
        synchronized {
          onChannelPaymentRequests.values
            .filter(req =>
              req.payload.sender == party.toPrim || req.payload.receiver == party.toPrim
            )
            .toSeq
        }
      }
    }

  override def close(): Unit = {
    partiesQueue.complete()
  }
}

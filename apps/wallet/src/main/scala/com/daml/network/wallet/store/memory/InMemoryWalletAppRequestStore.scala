package com.daml.network.wallet.store.memory

import com.daml.ledger.client
import com.daml.ledger.client.binding
import com.daml.network.wallet.store.WalletAppRequestStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.concurrent.{ExecutionContext, Future, blocking}
import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CN.Wallet.{AppPaymentRequest, OnChannelPaymentRequest}
import com.daml.network.util.Contract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.collection.mutable

/** Memory store for payment requests on ledger. */
class InMemoryWalletAppRequestStore(override protected val loggerFactory: NamedLoggerFactory)(
    implicit ec: ExecutionContext
) extends WalletAppRequestStore
    with NamedLogging {

  private val appPaymentRequests
      : mutable.Map[Primitive.ContractId[AppPaymentRequest], Contract[AppPaymentRequest]] =
    mutable.Map.empty[Primitive.ContractId[AppPaymentRequest], Contract[AppPaymentRequest]]

  private val onChannelPaymentRequests
      : mutable.Map[Primitive.ContractId[OnChannelPaymentRequest], Contract[
        OnChannelPaymentRequest
      ]] =
    mutable.Map
      .empty[Primitive.ContractId[OnChannelPaymentRequest], Contract[OnChannelPaymentRequest]]

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
}

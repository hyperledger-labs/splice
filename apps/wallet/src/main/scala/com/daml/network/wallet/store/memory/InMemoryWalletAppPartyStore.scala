package com.daml.network.wallet.store.memory

import akka.NotUsed
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.Source
import com.daml.network.wallet.store.WalletAppPartyStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.concurrent.{ExecutionContext, Future, blocking}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.collection.mutable

class InMemoryWalletAppPartyStore(override protected val loggerFactory: NamedLoggerFactory)(implicit
    ec: ExecutionContext,
    mat: Materializer,
) extends WalletAppPartyStore
    with NamedLogging {

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

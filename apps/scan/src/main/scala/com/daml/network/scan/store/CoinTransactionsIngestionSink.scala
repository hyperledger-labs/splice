package com.daml.network.scan.store

import com.daml.ledger.javaapi.data.{
  CreatedEvent,
  ExercisedEvent,
  Identifier,
  TransactionTree,
  TreeEvent,
}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coin.{Coin, LockedCoin}
import com.daml.network.history.*
import com.daml.network.store.{AuditLogIngestionSink, CCHistoryStore}
import com.daml.network.util.{ExerciseNode, JavaContract => Contract, Trees}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.ledger.api.client.JavaDecodeUtil as DecodeUtil
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ErrorUtil

import scala.collection.concurrent.TrieMap
import scala.collection.{concurrent, mutable}
import scala.concurrent.{ExecutionContext, Future}

class CoinTransactionsIngestionSink(
    party: PartyId,
    store: CCHistoryStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends AuditLogIngestionSink
    with NamedLogging {

  override def filterParty: PartyId = party

  override def templateIds: Seq[Identifier] =
    Seq(cc.coin.Coin.TEMPLATE_ID, cc.coin.LockedCoin.TEMPLATE_ID)

  /** This works as follows:
    * - read the flat transaction stream filtered for `Coin` creates and archives
    * - for each create or archival, grab the corresponding tx tree
    * - traverse the corresponding TransactionTree to find the creates and archives and then add them to TxStore with the context
    * derived from the full transaction tree
    */
  override def processTransaction(tx: TransactionTree)(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    for {
      events <- traverseForest(tx)
      metadata = TransactionMetadata(tx)
      _ <-
        if (events.nonEmpty) {
          store.addTransaction(CoinTransaction(events, metadata))
        } else Future.successful(())
    } yield ()
  }

  private val activeCoins: concurrent.Map[Coin.ContractId, Contract[Coin.ContractId, Coin]] =
    TrieMap[Coin.ContractId, Contract[Coin.ContractId, Coin]]()

  private val activeLockedCoins
      : concurrent.Map[LockedCoin.ContractId, Contract[LockedCoin.ContractId, LockedCoin]] =
    TrieMap[LockedCoin.ContractId, Contract[LockedCoin.ContractId, LockedCoin]]()

  /** Traverse a Ledger API TransactionTree via a pre-order DFS and extract the CoinEvents */
  @SuppressWarnings(Array("org.wartremover.warts.While"))
  private def traverseForest(tree: TransactionTree)(implicit
      traceContext: TraceContext
  ): Future[Seq[CoinEvent]] =
    Future {

      val coinEvents: mutable.Buffer[CoinEvent] = mutable.ListBuffer()

      def addToEvents(event: EventTypeAndCoin, parentO: Option[TreeEvent]) = {
        parentO match {
          case None =>
            event match {
              case CoinArchive(contract: LockedCoinContract) =>
                logger.debug(
                  s"An archive of a LockedCoin contract had no parent node in the transaction tree. Note that this is" +
                    s"expected when, e.g., paying for a directory entry without involving the SVC. Contract: $contract"
                )
              case _ =>
                logger.warn(
                  s"Following Coin or LockedCoin event had no parent node in the transaction tree: $event"
                )
            }
            coinEvents.append(CoinEvent(event, None))
          case Some(parent) =>
            coinEvents.append(CoinEvent(event, parseParentEvent(parent)))
        }
      }

      Trees.traverseTree(
        tree,
        onCreate = (created: CreatedEvent, pathToNode: Seq[TreeEvent]) => {
          DecodeUtil
            .decodeCreated(Coin.COMPANION)(created)
            .foreach(c => {
              val coinContract = Contract.fromCodegenContract(c)
              activeCoins.addOne((coinContract.contractId, coinContract))
              addToEvents(CoinCreate(CoinContract(coinContract)), pathToNode.lastOption)
            })
          DecodeUtil
            .decodeCreated(LockedCoin.COMPANION)(created)
            .foreach(c => {
              val coinContract = Contract.fromCodegenContract(c)
              activeLockedCoins.addOne((coinContract.contractId, coinContract))
              addToEvents(CoinCreate(LockedCoinContract(coinContract)), pathToNode.lastOption)
            })
        },
        onExercise = (exercised: ExercisedEvent, pathToNode: Seq[TreeEvent]) => {
          val coinContractOpt = DecodeUtil.decodeArchivedExercise(Coin.COMPANION)(exercised)
          coinContractOpt.foreach(cid => {
            val coinContract = activeCoins
              .remove(cid)
              .getOrElse(
                ErrorUtil.invalidState(
                  s"Observed an archive for the coin contract with contract id $cid, but we haven't observed the corresponding create"
                )
              )
            addToEvents(CoinArchive(CoinContract(coinContract)), pathToNode.lastOption)
          })
          val lockedCoinContractOpt =
            DecodeUtil.decodeArchivedExercise(LockedCoin.COMPANION)(exercised)
          lockedCoinContractOpt.foreach(cid => {
            val coinContract = activeLockedCoins
              .remove(cid)
              .getOrElse(
                ErrorUtil.invalidState(
                  s"Observed an archive for the LockedCoin contract with contract id $cid, but we haven't observed the corresponding create"
                )
              )
            addToEvents(CoinArchive(LockedCoinContract(coinContract)), pathToNode.lastOption)
          })
        },
      )

      coinEvents.toSeq
    }

  // Some helper methods

  private def parseEvent(event: TreeEvent)(implicit
      traceContext: TraceContext
  ): Option[ParentNode] =
    event match {
      case exercised: ExercisedEvent =>
        LazyList[ParentNodeCompanion](
          Transfer,
          Tap,
          Mint,
          StartIssuing,
          CoinUnlock,
          OwnerExpireLock,
        ).map(c => ExerciseNode.decodeExerciseEvent(c)(exercised).map(c.toParentNode(_)))
          .collectFirst { case Some(n) =>
            n
          }
      case _ => None
    }

  private def parseParentEvent(parent: TreeEvent)(implicit
      traceContext: TraceContext
  ): Option[ParentNode] = {
    val ev = parseEvent(parent)
    ev match {
      case None =>
        parent match {
          case other: ExercisedEvent =>
            logger.warn(
              s"Parent of coin create or archival was not a tap, mint, transfer or start issuing but ${other.getClass} ($other)"
            )
            None
          case created: CreatedEvent =>
            ErrorUtil.invalidState(
              "Observed that the parent of a Coin create or archival is a `Created` node" +
                s"$created. However, a `Created` node should never have any children. "
            )
          case _ =>
            logger.warn(
              s"Ancestor of Coin event in the transaction tree was an unknown node $parent"
            )
            None
        }
      case _ => ev
    }
  }
}

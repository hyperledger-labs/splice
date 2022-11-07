package com.daml.network.scan.admin

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.event.ExercisedEvent
import com.daml.ledger.api.v1.transaction.TreeEvent.Kind.{Created, Empty, Exercised}
import com.daml.ledger.api.v1.transaction.{Transaction, TransactionTree, TreeEvent}
import com.daml.ledger.client.binding.Primitive
import com.daml.ledger.javaapi.data.{Identifier, Transaction as JavaTransaction}
import com.daml.network.admin.LedgerAutomationService
import com.daml.network.codegen.CC.Coin.{Coin, LockedCoin}
import com.daml.network.codegen.CC.CoinRules.CoinRules
import com.daml.network.codegen.java.cc
import com.daml.network.environment.JavaCoinLedgerConnection
import com.daml.network.history.*
import com.daml.network.scan.store.ScanCCHistoryStore
import com.daml.network.util.{Contract, ExerciseNode, Trees}
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.ledger.api.client.DecodeUtil
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ErrorUtil

import scala.collection.concurrent.TrieMap
import scala.collection.{concurrent, mutable}
import scala.concurrent.{ExecutionContext, Future}

class ReadCoinTransactionsService(
    svcParty: PartyId,
    connection: JavaCoinLedgerConnection,
    store: ScanCCHistoryStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, tc: TraceContext)
    extends LedgerAutomationService
    with NamedLogging {

  override def templateIds: Seq[Identifier] =
    Seq(cc.coin.Coin.TEMPLATE_ID, cc.coin.LockedCoin.TEMPLATE_ID)

  /** This works as follows:
    * - read the flat transaction stream filtered for `Coin` creates and archives
    * - for each create or archival, grab the corresponding tx tree
    * - traverse the corresponding TransactionTree to find the creates and archives and then add them to TxStore with the context
    * derived from the full transaction tree
    */
  override def processTransaction(tx: JavaTransaction)(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    for {
      tree <- connection.tryGetTransactionTreeById(Seq(svcParty), tx.getTransactionId)
      events <- traverseForest(TransactionTree.fromJavaProto(tree.toProto))
      metadata = TransactionMetadata(Transaction.fromJavaProto(tx.toProto))
      _ <- store.addTransaction(CoinTransaction(events, metadata))
    } yield ()
  }

  private val activeCoins: concurrent.Map[Primitive.ContractId[Coin], Contract[Coin]] =
    TrieMap[Primitive.ContractId[Coin], Contract[Coin]]()

  private val activeLockedCoins
      : concurrent.Map[Primitive.ContractId[LockedCoin], Contract[LockedCoin]] =
    TrieMap[Primitive.ContractId[LockedCoin], Contract[LockedCoin]]()

  /** Traverse a Ledger API TransactionTree via a pre-order DFS and extract the CoinEvents */
  @SuppressWarnings(Array("org.wartremover.warts.While"))
  private def traverseForest(tree: TransactionTree): Future[Seq[CoinEvent]] =
    Future {

      val coinEvents: mutable.Buffer[CoinEvent] = mutable.ListBuffer()

      def addToEvents(event: EventTypeAndCoin, parentO: Option[TreeEvent.Kind]) = {
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
        onCreate = (created: Created, pathToNode: Seq[TreeEvent.Kind]) => {
          DecodeUtil
            .decodeCreated(Coin)(created.value)
            .foreach(c => {
              val coinContract = Contract.fromCodegenContract(c)
              activeCoins.addOne((coinContract.contractId, coinContract))
              addToEvents(CoinCreate(CoinContract(coinContract)), pathToNode.lastOption)
            })
          DecodeUtil
            .decodeCreated(LockedCoin)(created.value)
            .foreach(c => {
              val coinContract = Contract.fromCodegenContract(c)
              activeLockedCoins.addOne((coinContract.contractId, coinContract))
              addToEvents(CoinCreate(LockedCoinContract(coinContract)), pathToNode.lastOption)
            })
        },
        onExercise = (exercised: Exercised, pathToNode: Seq[TreeEvent.Kind]) => {
          val coinContractOpt = DecodeUtil.decodeArchivedExercise(Coin)(exercised)
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
          val lockedCoinContractOpt = DecodeUtil.decodeArchivedExercise(LockedCoin)(exercised)
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
  private val rulesTemplate = ApiTypes.TemplateId.unwrap(CoinRules.id)
  private val lockedCoinTemplate = ApiTypes.TemplateId.unwrap(LockedCoin.id)
  private def isCoinRules(event: ExercisedEvent) = event.templateId.contains(rulesTemplate)
  private def isLockedCoin(event: ExercisedEvent) = event.templateId.contains(lockedCoinTemplate)

  private def isTap(event: ExercisedEvent) = event.choice == "CoinRules_Tap" && isCoinRules(event)
  private def isCoinUnlock(event: ExercisedEvent) =
    event.choice == "Coin_Unlock" && isLockedCoin(event)
  private def isTransfer(event: ExercisedEvent) =
    event.choice == "CoinRules_Transfer" && isCoinRules(event)
  private def isSvcExpireLock(event: ExercisedEvent) =
    event.choice == "Coin_SvcExpireLock" && isLockedCoin(event)
  private def isOwnerExpireLock(event: ExercisedEvent) =
    event.choice == "Coin_OwnerExpireLock" && isLockedCoin(event)
  private def isStartIssuing(event: ExercisedEvent) =
    event.choice == "CoinRules_MiningRound_StartIssuing" && isCoinRules(event)

  import cats.syntax.option._

  private def parseEvent(event: TreeEvent.Kind): Option[ParentNode] = {
    event match {
      case exercised: Exercised if isTransfer(exercised.value) =>
        Transfer(ExerciseNode.tryFromProtoEvent(Transfer)(exercised)).some
      case exercised: Exercised if isTap(exercised.value) =>
        Tap(ExerciseNode.tryFromProtoEvent(Tap)(exercised)).some
      case exercised: Exercised if isStartIssuing(exercised.value) =>
        StartIssuing(ExerciseNode.tryFromProtoEvent(StartIssuing)(exercised)).some
      case exercised: Exercised if isCoinUnlock(exercised.value) =>
        CoinUnlock(ExerciseNode.tryFromProtoEvent(CoinUnlock)(exercised)).some
      case exercised: Exercised if isSvcExpireLock(exercised.value) =>
        SvcExpireLock(ExerciseNode.tryFromProtoEvent(SvcExpireLock)(exercised)).some
      case exercised: Exercised if isOwnerExpireLock(exercised.value) =>
        OwnerExpireLock(ExerciseNode.tryFromProtoEvent(OwnerExpireLock)(exercised)).some
      case _ => None
    }
  }

  private def parseParentEvent(parent: TreeEvent.Kind): Option[ParentNode] = {
    val ev = parseEvent(parent)
    ev match {
      case None =>
        parent match {
          case other: Exercised =>
            logger.warn(
              s"Parent of coin create or archival was not a tap, transfer or start issuing but ${other.getClass} ($other)"
            )
            None
          case created: Created =>
            ErrorUtil.invalidState(
              "Observed that the parent of a Coin create or archival is a `Created` node" +
                s"$created. However, a `Created` node should never have any children. "
            )
          case Empty =>
            logger.warn("Ancestor of Coin event in the transaction tree was an empty node")
            None
        }
      case _ => ev
    }
  }

  override def close(): Unit = Lifecycle.close(connection)(logger)

}

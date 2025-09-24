package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.daml.lf.data.Numeric
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules as amuletrulesCodegen
import org.lfdecentralizedtrust.splice.console.WalletAppClientReference
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon
import org.lfdecentralizedtrust.splice.store.Limit
import org.lfdecentralizedtrust.splice.util.{TimeTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.wallet.store.{
  BalanceChangeTxLogEntry,
  TransferTxLogEntry,
  TxLogEntry,
}
import org.scalatest.Assertion

import scala.annotation.tailrec

trait WalletTxLogTestUtil extends TestCommon with WalletTestUtil with TimeTestUtil {

  // Amount paid by `createSelfPaymentRequest()`
  val selfPaymentAmount: BigDecimal = BigDecimal(10.0)

  val scale = Numeric.Scale.assertFromInt(10)

  type CheckTxHistoryFn = PartialFunction[TxLogEntry, Assertion]

  sealed trait TopupIgnoreStrategy

  /** This is a DevNet-like clusters and traffic topups should be ignored when checking history */
  final case object IgnoreTopupsDevNet extends TopupIgnoreStrategy

  /** This is a non-DevNet-like clusters and traffic topups should be ignored when checking history */
  final case object IgnoreTopupsNonDevNet extends TopupIgnoreStrategy

  /** Traffic topups should not be ignored when checking history */
  final case object KeepTopups extends TopupIgnoreStrategy

  def checkTxHistory(
      wallet: WalletAppClientReference,
      expected: Seq[CheckTxHistoryFn],
      previousEventId: Option[String] = None,
      trafficTopups: TopupIgnoreStrategy = KeepTopups,
      ignore: TxLogEntry => Boolean = _ => false,
  ): Unit = {

    eventually() {
      val actual = wallet.listTransactions(None, pageSize = Limit.MaxPageSize)
      val withoutIgnored = actual
        .takeWhile(e => !previousEventId.contains(e.eventId))
        .filterNot(ignore)

      // Traffic topups happen in the background and can lead to randomly appearing TxLog entries.
      // Since we are using shared Canton instances, we can't purchase a large amount of traffic up front
      // without affecting subsequent tests.
      // Thus the only option is to ignore all traffic topups in tests where random topups MIGHT appear.
      // We are using the TopupIgnoreStrategy type to force callers to explicitly state whether topups might appear.
      val toCompare = trafficTopups match {
        case IgnoreTopupsDevNet => withoutDevNetTopups(withoutIgnored)
        case IgnoreTopupsNonDevNet => withoutNonDevNetTopups(withoutIgnored)
        case KeepTopups => withoutIgnored
      }

      toCompare should have length expected.size.toLong

      toCompare
        .zip(expected)
        .zipWithIndex
        .foreach { case ((entry, pf), i) =>
          clue(s"Entry at position $i") {
            inside(entry)(pf)
          }
        }

      // ingestion can happen in-between the call `actual=listTransactions()` and the paginated ones,
      // so both need to be inside the same `eventually` block

      // Confirm that the paginated result is equal to the non-paginated result
      val paginatedResult = Iterator
        .unfold[Seq[TxLogEntry], Option[String]](None)(beginAfterId => {
          val page = wallet.listTransactions(beginAfterId, pageSize = 2)
          if (page.isEmpty)
            None
          else
            Some(page -> Some(page.last.eventId))
        })
        .toSeq
        .flatten

      paginatedResult should contain theSameElementsInOrderAs actual
    }
  }

  def withoutDevNetTopups(
      txs: Seq[TxLogEntry.TransactionHistoryTxLogEntry]
  ): Seq[TxLogEntry.TransactionHistoryTxLogEntry] = {
    @tailrec
    def go(
        txs: List[TxLogEntry.TransactionHistoryTxLogEntry],
        acc: Seq[TxLogEntry.TransactionHistoryTxLogEntry],
    ): Seq[TxLogEntry.TransactionHistoryTxLogEntry] =
      txs match {
        case Nil => acc
        case (first: TransferTxLogEntry) :: (second: BalanceChangeTxLogEntry) :: tail =>
          // On DevNet-like clusters, traffic topups auto-tap amulets (in the same transaction).
          // A traffic topup therefore always generates two TxLog events: one for the traffic topup itself,
          // and one for the amulet tap. Note that events are in reverse chronological order.
          if (
            first.getSubtype.choice == amuletrulesCodegen.AmuletRules.CHOICE_AmuletRules_BuyMemberTraffic.name &&
            second.getSubtype.choice == amuletrulesCodegen.AmuletRules.CHOICE_AmuletRules_DevNet_Tap.name
          ) {
            go(tail, acc)
          } else {
            go(tail, acc :+ first :+ second)
          }
        case head :: tail => go(tail, acc :+ head)
      }
    go(txs.toList, Seq.empty)
  }

  // prevents flakes from traffic purchases - possible to happen in Validator wallets
  def withoutNonDevNetTopups(
      txs: Seq[TxLogEntry.TransactionHistoryTxLogEntry]
  ): Seq[TxLogEntry.TransactionHistoryTxLogEntry] = {
    // On non-DevNet like clusters, traffic topups take input amulets that must have been created beforehand.
    txs.filterNot {
      case transfer: TransferTxLogEntry =>
        transfer.getSubtype.choice == amuletrulesCodegen.AmuletRules.CHOICE_AmuletRules_BuyMemberTraffic.name
      case _ => false
    }
  }
}

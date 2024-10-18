package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.daml.lf.data.Numeric
import org.lfdecentralizedtrust.splice.console.WalletAppClientReference
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon
import org.lfdecentralizedtrust.splice.store.Limit
import org.lfdecentralizedtrust.splice.util.{TimeTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.wallet.store.TxLogEntry
import org.scalatest.Assertion

trait WalletTxLogTestUtil extends TestCommon with WalletTestUtil with TimeTestUtil {

  // Amount paid by `createSelfPaymentRequest()`
  val selfPaymentAmount: BigDecimal = BigDecimal(10.0)

  val scale = Numeric.Scale.assertFromInt(10)

  type CheckTxHistoryFn = PartialFunction[TxLogEntry, Assertion]

  def checkTxHistory(
      wallet: WalletAppClientReference,
      expected: Seq[CheckTxHistoryFn],
      previousEventId: Option[String] = None,
      ignore: TxLogEntry => Boolean = _ => false,
  ): Unit = {

    val (actual, toCompare) = eventually() {
      val actual = wallet.listTransactions(None, pageSize = Limit.MaxPageSize)
      val toCompare = actual
        .takeWhile(e => !previousEventId.contains(e.eventId))
        .filter(!ignore(_))

      toCompare should have length expected.size.toLong
      (actual, toCompare)
    }

    toCompare
      .zip(expected)
      .zipWithIndex
      .foreach { case ((entry, pf), i) =>
        clue(s"Entry at position $i") {
          inside(entry)(pf)
        }
      }

    clue("Paginated result should be equal to non-paginated result") {
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
}

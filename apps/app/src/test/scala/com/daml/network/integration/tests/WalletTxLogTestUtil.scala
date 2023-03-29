package com.daml.network.integration.tests

import com.daml.lf.data.Numeric
import com.daml.network.console.WalletAppClientReference
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestCommon
import com.daml.network.util.{TimeTestUtil, WalletTestUtil}
import com.daml.network.wallet.store.UserWalletTxLogParser
import org.scalatest.Assertion

trait WalletTxLogTestUtil extends CNNodeTestCommon with WalletTestUtil with TimeTestUtil {

  // Amount paid by `createSelfPaymentRequest()`
  val selfPaymentAmount: BigDecimal = BigDecimal(10.0)

  val scale = Numeric.Scale.assertFromInt(10)

  type CheckTxHistoryFn = PartialFunction[UserWalletTxLogParser.TxLogEntry, Assertion]

  def checkTxHistory(
      wallet: WalletAppClientReference,
      expected: Seq[CheckTxHistoryFn],
  ): Unit = {

    val actual = eventually() {
      val actual = wallet.listTransactions(None, pageSize = 100000)

      actual should have length expected.size.toLong
      actual
    }

    actual.zip(expected).zipWithIndex.foreach { case ((entry, pf), i) =>
      clue(s"Entry at position $i") {
        inside(entry)(pf)
      }
    }

    clue("Paginated result should be equal to non-paginated result") {
      val paginatedResult = Iterator
        .unfold[Seq[UserWalletTxLogParser.TxLogEntry], Option[String]](None)(beginAfterId => {
          val page = wallet.listTransactions(beginAfterId, pageSize = 2)
          if (page.isEmpty)
            None
          else
            Some(page -> Some(page.last.indexRecord.eventId))
        })
        .toSeq
        .flatten

      paginatedResult should contain theSameElementsInOrderAs actual
    }
  }
}

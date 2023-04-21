package com.daml.network.util

import com.daml.network.integration.tests.FrontendTestCommon
import com.daml.network.util.WalletFrontendTestUtil.FrontendTransaction
import com.digitalasset.canton.topology.PartyId
import org.scalatest.Assertion

trait WalletFrontendTestUtil { self: FrontendTestCommon =>

  protected def tapCoins(tapQuantity: BigDecimal)(implicit webDriver: WebDriverType): Unit = {
    val transactionsBefore = findAll(className("tx-row")).toSeq.map(readTransactionFromRow)

    click on "tap-amount-field"
    numberField("tap-amount-field").underlying.sendKeys(tapQuantity.toString)
    click on "tap-button"

    // Make sure the tap has been processed
    // This will have to change if we add a reload button here instead of auto-refreshing transactions.
    eventually() {
      val transactionsAfter = findAll(className("tx-row")).toSeq.map(readTransactionFromRow)
      val newTxs = transactionsAfter.diff(transactionsBefore)
      forExactly(1, newTxs) { balanceChange =>
        balanceChange.action should matchText("Balance Change")
        balanceChange.ccAmount should be(tapQuantity)
      }
    }
  }

  protected def matchBalance(balanceCC: String, balanceUSD: String)(implicit
      webDriverType: WebDriverType
  ): Assertion = {
    find(id("wallet-balance-cc"))
      .valueOrFail("Couldn't find balance")
      .text should matchText(s"$balanceCC CC")

    find(id("wallet-balance-usd"))
      .valueOrFail("Couldn't find balance")
      .text should matchText(s"$balanceUSD USD")
  }

  protected def readTransactionFromRow(transactionRow: Element): FrontendTransaction = {
    def parseAmountText(str: String, currency: String) = {
      try {
        BigDecimal(
          str
            .replace(currency, "")
            .trim
        )
      } catch {
        case e: Throwable =>
          throw new RuntimeException(s"Could not parse the string '$str' as a coin amount", e)
      }
    }

    FrontendTransaction(
      action = transactionRow.childElement(className("tx-action")).text,
      partyDescription = transactionRow.findChildElement(className("tx-party")).map(_.text),
      ccAmount = parseAmountText(
        transactionRow
          .childElement(className("tx-amount-cc"))
          .text,
        currency = "CC",
      ),
      usdAmount = parseAmountText(
        transactionRow
          .childElement(className("tx-amount-usd"))
          .text,
        currency = "USD",
      ),
      rate = transactionRow.childElement(className("tx-amount-rate")).text,
    )
  }

  protected def matchTransaction(transactionRow: Element)(
      coinPrice: BigDecimal,
      expectedAction: String,
      expectedPartyDescription: Option[String],
      expectedAmountCC: BigDecimal,
  ): Assertion = {
    val transaction = readTransactionFromRow(transactionRow)
    val expectedAmountUSD = expectedAmountCC * coinPrice

    transaction.action should matchText(expectedAction)
    (transaction.partyDescription, expectedPartyDescription) match {
      case (None, None) => ()
      case (Some(party), Some(ep)) => party should matchText(ep)
      case _ => fail(s"Unexpected party in transaction: $transaction")
    }
    transaction.ccAmount should beWithin(expectedAmountCC - smallAmount, expectedAmountCC)
    transaction.usdAmount should beWithin(
      expectedAmountUSD - smallAmount * coinPrice,
      expectedAmountUSD,
    )
    transaction.rate should matchText(s"${BigDecimal(1) / coinPrice} CC/USD")
  }

  protected def createTransferOffer(
      receiver: PartyId,
      transferAmount: BigDecimal,
      expiryDays: Int,
      description: String = "by party ID",
  )(implicit
      driver: WebDriverType
  ) = {
    click on "navlink-transfer"
    click on "create-offer-receiver"
    setDirectoryField(
      textField("create-offer-receiver"),
      receiver.toProtoPrimitive,
      receiver.toProtoPrimitive,
    )

    click on "create-offer-cc-amount"
    numberField("create-offer-cc-amount").value = ""
    numberField("create-offer-cc-amount").underlying.sendKeys(transferAmount.toString())

    click on "create-offer-expiration-days"
    singleSel("create-offer-expiration-days").value = expiryDays.toString

    click on "create-offer-description"
    textArea("create-offer-description").underlying.sendKeys(description)

    click on "create-offer-submit-button"
  }

}

object WalletFrontendTestUtil {

  case class FrontendTransaction(
      action: String,
      partyDescription: Option[String],
      ccAmount: BigDecimal,
      usdAmount: BigDecimal,
      rate: String,
  )

}

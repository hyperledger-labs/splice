package com.daml.network.util

import com.daml.network.integration.tests.FrontendTestCommon
import com.daml.network.util.WalletFrontendTestUtil.FrontendTransaction
import com.digitalasset.canton.topology.PartyId
import org.scalatest.Assertion

import scala.concurrent.duration.*

trait WalletFrontendTestUtil extends WalletTestUtil { self: FrontendTestCommon =>

  protected def tapAmulets(tapQuantity: BigDecimal)(implicit webDriver: WebDriverType): Unit = {
    val tapsBefore =
      clue("Getting state before tap") {
        // The long eventually makes this robust against `StaleElementReferenceException` errors
        eventually(timeUntilSuccess = 2.minute)(
          findAll(className("tx-row")).toSeq.flatMap(readTapCCAmountFromRow)
        )
      }

    clue("Tapping...") {
      click on "tap-amount-field"
      numberField("tap-amount-field").underlying.clear()
      numberField("tap-amount-field").underlying.sendKeys(tapQuantity.toString())
      click on "tap-button"
    }

    clue("Making sure the tap has been processed") {
      // This will have to change if we add a reload button here instead of auto-refreshing transactions.
      // The long eventually makes this robust against `StaleElementReferenceException` errors
      eventually(timeUntilSuccess = 2.minute) {
        val tapsAfter = findAll(className("tx-row")).toSeq.flatMap(readTapCCAmountFromRow)
        val newTaps = tapsAfter.diff(tapsBefore)
        forExactly(1, newTaps) { tap =>
          tap shouldBe walletUsdToAmulet(tapQuantity)
        }
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
          throw new RuntimeException(s"Could not parse the string '$str' as a amulet amount", e)
      }
    }

    FrontendTransaction(
      action = transactionRow.childElement(className("tx-action")).text,
      subtype = transactionRow.childElement(className("tx-subtype")).text.replaceAll("[()]", ""),
      partyDescription = for {
        senderOrReceiver <- transactionRow
          .findChildElement(className("sender-or-receiver"))
          .map(seleniumText)
        providerId <- transactionRow.findChildElement(className("provider-id")).map(seleniumText)
      } yield s"${senderOrReceiver} ${providerId}",
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
      amuletPrice: BigDecimal,
      expectedAction: String,
      expectedSubtype: String,
      expectedPartyDescription: Option[String],
      expectedAmountCC: BigDecimal,
  ): Assertion = {
    val expectedUSD = expectedAmountCC * amuletPrice
    matchTransactionAmountRange(transactionRow)(
      amuletPrice,
      expectedAction,
      expectedSubtype,
      expectedPartyDescription,
      (expectedAmountCC - smallAmount, expectedAmountCC),
      (expectedUSD - smallAmount * amuletPrice, expectedUSD),
    )
  }

  protected def matchTransactionAmountRange(transactionRow: Element)(
      amuletPrice: BigDecimal,
      expectedAction: String,
      expectedSubtype: String,
      expectedPartyDescription: Option[String],
      expectedAmountCC: (BigDecimal, BigDecimal),
      expectedAmountUSD: (BigDecimal, BigDecimal),
  ): Assertion = {
    val transaction = readTransactionFromRow(transactionRow)

    transaction.action should matchText(expectedAction)
    transaction.subtype should matchText(expectedSubtype)
    (transaction.partyDescription, expectedPartyDescription) match {
      case (None, None) => ()
      case (Some(party), Some(ep)) => party should matchText(ep)
      case _ => fail(s"Unexpected party in transaction: $transaction")
    }
    transaction.ccAmount should beWithin(expectedAmountCC._1, expectedAmountCC._2)
    transaction.usdAmount should beWithin(
      expectedAmountUSD._1,
      expectedAmountUSD._2,
    )
    transaction.rate should matchText(s"${BigDecimal(1) / amuletPrice} CC/USD")
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
    setAnsField(
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

  private def readTapCCAmountFromRow(transactionRow: Element): Option[BigDecimal] = {
    if (
      transactionRow
        .childElement(className("tx-action"))
        .text
        .contains("Balance Change") && transactionRow
        .childElement(className("tx-subtype"))
        .text
        .contains("Tap")
    ) {
      Some(
        parseAmountText(
          transactionRow
            .childElement(className("tx-amount-cc"))
            .text,
          currency = "CC",
        )
      )
    } else None
  }

  private def parseAmountText(str: String, currency: String) = {
    try {
      BigDecimal(
        str
          .replace(currency, "")
          .trim
      )
    } catch {
      case e: Throwable =>
        throw new RuntimeException(s"Could not parse the string '$str' as a amulet amount", e)
    }
  }
}

object WalletFrontendTestUtil {

  case class FrontendTransaction(
      action: String,
      subtype: String,
      partyDescription: Option[String],
      ccAmount: BigDecimal,
      usdAmount: BigDecimal,
      rate: String,
  )

}

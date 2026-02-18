package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.integration.tests.FrontendTestCommon
import org.lfdecentralizedtrust.splice.util.WalletFrontendTestUtil.*
import com.digitalasset.canton.topology.PartyId
import org.scalatest.Assertion

import scala.concurrent.duration.*
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment

trait WalletFrontendTestUtil extends WalletTestUtil { self: FrontendTestCommon =>

  /** Tap amulets by interacting with the UI, and waits until the amulets are visible in the transaction history. */
  protected def tapAmulets(
      tapQuantity: BigDecimal
  )(implicit webDriver: WebDriverType, env: SpliceTestConsoleEnvironment): Unit = {

    def tap(): Unit = {
      eventuallyClickOn(id("tap-amount-field"))
      numberField("tap-amount-field").underlying.clear()
      numberField("tap-amount-field").underlying.sendKeys(tapQuantity.toString())
      eventuallyClickOn(id("tap-button"))
    }

    val txDatesBefore = clue("Getting state before tap") {
      // The long eventually makes this robust against `StaleElementReferenceException` errors
      eventually(timeUntilSuccess = 2.minute)(
        findAll(className("tx-row")).toSeq.map(readDateFromRow)
      )
    }
    def assertTapResultIsVisible(): Unit = {
      val newTaps = clue(s"Checking for new taps") {
        val txs = findAll(className("tx-row")).toSeq
        val txDatesAfter = txs.map(readDateFromRow)
        logger.debug(s"Transaction dates after tap: $txDatesAfter")
        val tapsAfter = txs.flatMap(readTapFromRow)
        val newTaps = tapsAfter.filter(tap => !txDatesBefore.exists(_ == tap.date))
        logger.debug(s"New taps: $newTaps")
        newTaps
      }
      forAtLeast(1, newTaps) { tap =>
        {
          val roundError = 0.01
          assertInRange(tap.tapAmountUsd, (tapQuantity - roundError, tapQuantity + roundError))
          val tapQuantityAmt = walletUsdToAmulet(tapQuantity, 1.0 / tap.usdToAmulet)
          assertInRange(
            tap.tapAmountAmulet,
            (tapQuantityAmt - roundError, tapQuantityAmt + roundError),
          )
        }
      }
      logger.debug(s"Tap succeeded, the following new taps were found: $newTaps")
    }

    logger.debug(s"Transaction dates before tap: $txDatesBefore")

    clue("Tapping...") {
      tap()
    }

    /** Notes: the tap API endpoint is synchronous, and not idempotent.
      * - The validator backend picks command id, and retries picking a round and submitting the tap command.
      *   It will only retry if it encounters a retryable error. For example, it will not retry if the tap
      *   failed due to missing traffic balance, even though we know that there is another background process
      *   that tops up traffic balance and would likely make a retry successful.
      *   See [[org.lfdecentralizedtrust.splice.wallet.admin.http.HttpWalletHandler.tap]].
      * - The frontend code does NOT retry the tap call, since it is not idempotent.
      *   See `apps/wallet/frontend/src/hooks/useTap.ts`
      * - The HTTP server (the validator backend) aborts the call if it exceeds a timeout of 38sec.
      *   See `apps/app/src/main/resources/application.conf.
      * - The HTTP client (the web frontend using react-query) does NOT abort calls due to timeouts.
      */
    clue("Making sure the tap has been processed") {
      // This will have to change if we add a reload button here instead of auto-refreshing transactions.
      // The long eventually makes this robust against `StaleElementReferenceException` errors
      eventually(timeUntilSuccess = 2.minute) {
        find(className(errorDisplayElementClass)).map { errElem =>
          (
            errElem.text.trim,
            find(className(errorDetailsElementClass)).map(_.text.trim) match {
              case Some(errDetails) if errDetails.contains("UNABLE_TO_GET_TOPOLOGY_SNAPSHOT") =>
                tap()
                fail("Tapping again due to UNABLE_TO_GET_TOPOLOGY_SNAPSHOT error")
              case Some(errDetails)
                  if errDetails.contains("Traffic balance below reserved traffic amount") =>
                // Wait for traffic topup trigger to do its thing
                tap()
                fail("Tapping again due to Traffic balance below reserved traffic amount error")
              case Some(errDetails) if errDetails.contains("NOT_CONNECTED_TO_DOMAIN") =>
                tap()
                fail(s"Tapping again due to a participant not connected to domain error")
              case Some(errDetails)
                  if errDetails.contains("The server is taking too long to respond") =>
                // The tap might still succeed after the HTTP server timeout,
                // for example if the command completion is delayed because of the record order publisher.
                // The ledger api command might also have failed after the HTTP timeout (e.g., if it times out with a
                // NOT_SEQUENCED_TIMEOUT), but we can't easily distinguish that from the case where the command
                // is just taking a long time to complete.
                logger.debug(
                  s"Tap call timed out, checking if the tap result appears in case the command completed after the HTTP timeout."
                )
                assertTapResultIsVisible()
              case Some(errDetails) =>
                fail(s"Tap failed: ${errElem.text.trim} ($errDetails)")
              case None =>
                assertTapResultIsVisible()
            },
          )
        }
      }
    }
  }

  protected def matchBalance(balanceCC: String, balanceUSD: String)(implicit
      webDriverType: WebDriverType,
      env: SpliceTestConsoleEnvironment,
  ): Assertion = {
    find(id("wallet-balance-amulet"))
      .valueOrFail("Couldn't find balance")
      .text should matchText(s"$balanceCC ${spliceInstanceNames.amuletNameAcronym}")

    find(id("wallet-balance-usd"))
      .valueOrFail("Couldn't find balance")
      .text should matchText(s"$balanceUSD USD")
  }

  def parseAmountText(str: String, unit: String) = {
    try {
      BigDecimal(
        str
          .replace(unit, "")
          .trim
          .replace(",", "")
      )
    } catch {
      case e: Throwable =>
        fail(s"Could not parse the string '$str' as a amulet amount", e)
    }
  }

  def readPartyDescriptionFromRow(
      transactionRow: Element
  ): Option[String] =
    transactionRow
      .findChildElement(className("sender-or-receiver"))
      .map(seleniumText)

  protected def readTransactionFromRow(
      transactionRow: Element
  )(implicit env: SpliceTestConsoleEnvironment): FrontendTransaction = {

    val acronym = spliceInstanceNames.amuletNameAcronym

    FrontendTransaction(
      action = transactionRow.childElement(className("tx-action")).text,
      subtype = transactionRow.childElement(className("tx-subtype")).text.replaceAll("[()]", ""),
      partyDescription = readPartyDescriptionFromRow(transactionRow),
      ccAmount = parseAmountText(
        transactionRow
          .childElement(className("tx-row-cell-balance-change"))
          .childElement(className("tx-amount-amulet"))
          .text,
        unit = acronym,
      ),
      usdAmount = parseAmountText(
        transactionRow
          .childElement(className("tx-row-cell-balance-change"))
          .childElement(className("tx-amount-usd"))
          .text,
        unit = "USD",
      ),
      rate = transactionRow.findChildElement(className("tx-amount-rate")).map(_.text),
      appRewardsUsed = parseAmountText(
        transactionRow
          .childElement(className("tx-row-cell-rewards"))
          .findChildElement(className("tx-reward-app-amulet"))
          .map(_.text)
          .getOrElse(s"0 $acronym"),
        unit = acronym,
      ),
      validatorRewardsUsed = parseAmountText(
        transactionRow
          .childElement(className("tx-row-cell-rewards"))
          .findChildElement(className("tx-reward-validator-amulet"))
          .map(_.text)
          .getOrElse(s"0 $acronym"),
        unit = acronym,
      ),
      svRewardsUsed = parseAmountText(
        transactionRow
          .childElement(className("tx-row-cell-rewards"))
          .findChildElement(className("tx-reward-sv-amulet"))
          .map(_.text)
          .getOrElse(s"0 $acronym"),
        unit = acronym,
      ),
      updateId = transactionRow
        .findChildElement(className("update-id"))
        .map(seleniumText)
        .getOrElse(""),
    )
  }

  protected def matchTransaction(transactionRow: Element)(
      amuletPrice: BigDecimal,
      expectedAction: String,
      expectedSubtype: String,
      expectedPartyDescription: Option[String],
      expectedAmountAmulet: BigDecimal,
  )(implicit env: SpliceTestConsoleEnvironment): Assertion = {
    val expectedUSD = expectedAmountAmulet * amuletPrice
    matchTransactionAmountRange(transactionRow)(
      amuletPrice,
      expectedAction,
      expectedSubtype,
      expectedPartyDescription,
      (expectedAmountAmulet - smallAmount, expectedAmountAmulet),
      (expectedUSD - smallAmount * amuletPrice, expectedUSD),
    )
  }

  protected def matchTransactionAmountRange(transactionRow: Element)(
      amuletPrice: BigDecimal,
      expectedAction: String,
      expectedSubtype: String,
      expectedPartyDescription: Option[String],
      expectedAmountAmulet: (BigDecimal, BigDecimal),
      expectedAmountUSD: (BigDecimal, BigDecimal),
  )(implicit env: SpliceTestConsoleEnvironment): Assertion = {
    val transaction = readTransactionFromRow(transactionRow)

    transaction.action should matchText(expectedAction) withClue "action"
    transaction.subtype should matchText(expectedSubtype) withClue "subtype"
    inside((transaction.partyDescription, expectedPartyDescription)) {
      case (None, None) => succeed
      case (Some(party), Some(ep)) => party should matchText(ep)
    } withClue s"Unexpected party in transaction: $transaction"
    transaction.ccAmount should beWithin(expectedAmountAmulet._1, expectedAmountAmulet._2)
    transaction.usdAmount should beWithin(
      expectedAmountUSD._1,
      expectedAmountUSD._2,
    )

    transaction.rate match {
      case Some(rate) =>
        rate should matchText(
          s"${BigDecimal(1) / amuletPrice} ${spliceInstanceNames.amuletNameAcronym}/USD"
        )
      // Rate text should be missing iff the price is zero
      case None => amuletPrice shouldBe BigDecimal(0)
    }
  }

  protected def createTransferOffer(
      receiver: PartyId,
      transferAmount: BigDecimal,
      expiryDays: Int,
      description: String = "by party ID",
      shouldDisableTokenStandardSwitch: Boolean = false,
  )(implicit
      driver: WebDriverType
  ) = {
    assert(transferAmount.scale <= 10, "Amulet amount must have at most 10 decimal places")
    eventuallyClickOn(id("navlink-transfer"))

    if (shouldDisableTokenStandardSwitch) {
      click on "toggle-token-standard-transfer"
    }

    eventuallyClickOn(id("create-offer-receiver"))
    setAnsField(
      textField("create-offer-receiver"),
      receiver.toProtoPrimitive,
      receiver.toProtoPrimitive,
    )

    eventuallyClickOn(id("create-offer-amulet-amount"))
    numberField("create-offer-amulet-amount").value = ""
    numberField("create-offer-amulet-amount").underlying.sendKeys(transferAmount.toString())

    eventuallyClickOn(id("create-offer-expiration-days"))
    singleSel("create-offer-expiration-days").value = expiryDays.toString

    eventuallyClickOn(id("create-offer-description"))
    textArea("create-offer-description").underlying.sendKeys(description)

    eventuallyClickOn(id("create-offer-submit-button"))
  }

  def readTapFromRow(
      transactionRow: Element
  )(implicit env: SpliceTestConsoleEnvironment): Option[Tap] = {
    val date = readDateFromRow(transactionRow)
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
        Tap(
          date,
          parseAmountText(
            transactionRow
              .childElement(className("tx-amount-amulet"))
              .text,
            unit = amuletNameAcronym,
          ),
          parseAmountText(
            transactionRow
              .childElement(className("tx-amount-usd"))
              .text,
            unit = "USD",
          ),
          parseAmountText(
            transactionRow
              .childElement(className("tx-amount-rate"))
              .text,
            unit = s"$amuletNameAcronym/USD",
          ),
        )
      )
    } else None
  }

  protected def waitForTrafficPurchase()(implicit driver: WebDriverType) = {
    clue("Waitig for a traffic purchase") {
      eventually(1.minute) {
        val txs = findAll(className("tx-row")).toSeq
        val trafficPurchases = txs.filter { txRow =>
          txRow.childElement(className("tx-action")).text.contains("Sent") &&
          txRow.childElement(className("tx-subtype")).text.contains("Extra Traffic Purchase")
        }
        trafficPurchases should not be empty
      }
    }
  }

  private def readDateFromRow(transactionRow: Element): String =
    transactionRow
      .childElement(className("tx-row-cell-date"))
      .text
}

object WalletFrontendTestUtil {

  case class FrontendTransaction(
      action: String,
      subtype: String,
      partyDescription: Option[String],
      ccAmount: BigDecimal,
      usdAmount: BigDecimal,
      rate: Option[String],
      appRewardsUsed: BigDecimal,
      validatorRewardsUsed: BigDecimal,
      svRewardsUsed: BigDecimal,
      updateId: String,
  )

  val errorDisplayElementClass = "error-display-message"
  val errorDetailsElementClass = "error-display-details"

  final case class Tap(
      date: String,
      tapAmountAmulet: BigDecimal,
      tapAmountUsd: BigDecimal,
      usdToAmulet: BigDecimal,
  )
}

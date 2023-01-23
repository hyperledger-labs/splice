// Commands needed to initialize the CN apps, tap some coins and make some transfers. Can be run as a bootstrap script.

import com.digitalasset.canton.data.CantonTimestamp
import java.time.Duration
import java.util.UUID

// Onboard Test Parties
val aliceUserName = aliceWallet.config.ledgerApiUser
val bobUserName = bobWallet.config.ledgerApiUser

println(s"Onboarding Alice user: " + aliceUserName)
val aliceParty = validatorApp.onboardUser(aliceUserName)

println(s"Onboarding Bob user: " + aliceUserName)
val bobParty = validatorApp.onboardUser(bobUserName)

aliceWallet.tap(100.0)
utils.retry_until_true(aliceWallet.list().coins.length == 1)

require(bobWallet.list().coins.length == 0)

val expiration = CantonTimestamp.now().plus(Duration.ofMinutes(1))
val uuid = UUID.randomUUID().toString()
val transferOffer = aliceWallet.createTransferOffer(bobParty, 10.0, "p2ptransfer", expiration, uuid)
utils.retry_until_true(bobWallet.listTransferOffers().length == 1)
bobWallet.acceptTransferOffer(transferOffer)

utils.retry_until_true(bobWallet.list().coins.length == 1)
bobWallet.list().coins.foreach(coin => require(BigDecimal(coin.contract.payload.amount.initialAmount) == 10))

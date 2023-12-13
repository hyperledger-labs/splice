import com.daml.lf.value.Value.ContractId
import com.daml.network.codegen.java.cn.{splitwell => splitwellCodegen}
import com.daml.network.console.{CnsExternalAppClientReference, WalletAppClientReference}
import com.daml.network.console.LedgerApiExtensions._
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.duration._

println("Waiting for SVC initialization...")
// We need to do this at the beginning, otherwise later commands can fail because CoinRules is locked.n
Seq(sv1, sv2, sv3, sv4).foreach(
  _.waitForInitialization(NonNegativeDuration.tryFromDuration(5.minute))
)

println("Waiting for validator initialization...")
aliceValidator.waitForInitialization()
bobValidator.waitForInitialization()

println("Waiting for scan initialization...")
sv1Scan.waitForInitialization()

println("Uploading DAR files...")
Seq(aliceValidator.participantClient, bobValidator.participantClient).foreach { p =>
  p.upload_dar_unless_exists("daml/splitwell/.daml/dist/splitwell-0.1.0.dar")
}

println("Onboarding users...")
val charlieValidator = aliceValidator
val charlieCns = aliceCns

val aliceUserParty = aliceValidator.onboardUser(aliceWallet.config.ledgerApiUser)
val bobUserParty = bobValidator.onboardUser(bobWallet.config.ledgerApiUser)
val charlieUserParty = charlieValidator.onboardUser(charlieWallet.config.ledgerApiUser)

println("Ensuring that CNS entries are allocated correctly...")
def ensureCnsEntry(
    user: PartyId,
    name: String,
    url: String,
    description: String,
    cns: CnsExternalAppClientReference,
    wallet: WalletAppClientReference,
) {
  try {
    val nameUser = sv1Scan.lookupEntryByName(name).payload.user
    if (nameUser == user.toProtoPrimitive) {
      println(s"CNS name \"$name\" already allocated to \"$user\". Doing nothing.")
    } else {
      sys.error(s"CNS name \"$name\" allocated to \"$nameUser\". Can't allocate to \"$user\".")
    }
  } catch {
    case e: CommandFailure => {
      println(s"Requesting CNS name \"$name\" for user \"$user\".")
      cns.createCnsEntry(name, url, description)
      println("Waiting for wallet initialization to complete")
      wallet.waitForInitialization()
      println("Wallet initialization complete, tapping coin")
      wallet.tap(5.0)
      utils.retry_until_true { wallet.listSubscriptionRequests().length == 1 }
      wallet.acceptSubscriptionRequest(
        wallet.listSubscriptionRequests()(0).contractId
      )
    }
  }
}
ensureCnsEntry(
  aliceUserParty,
  "alice.unverified.cns",
  "https://alice-url.cns.com",
  "",
  aliceCns,
  aliceWallet,
)
ensureCnsEntry(
  bobUserParty,
  "bob.unverified.cns",
  "https://bob-url.cns.com",
  "",
  bobCns,
  bobWallet,
)
ensureCnsEntry(
  charlieUserParty,
  "charlie.unverified.cns",
  "https://charlie-url.cns.com",
  "",
  charlieCns,
  charlieWallet,
)

println("Waiting for splitwell initialization...")
providerSplitwellBackend.waitForInitialization()
val providerParty = providerSplitwellBackend.getProviderPartyId()

Seq(
  aliceSplitwell -> aliceUserParty,
  bobSplitwell -> bobUserParty,
  charlieSplitwell -> charlieUserParty,
).foreach { case (splitwell, party) =>
  splitwell.createInstallRequests()
  splitwell.ledgerApi.ledger_api_extensions.acs
    .awaitJava(splitwellCodegen.SplitwellInstall.COMPANION)(party)
}

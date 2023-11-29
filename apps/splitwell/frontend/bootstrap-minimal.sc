import com.daml.lf.value.Value.ContractId
import com.daml.network.codegen.java.cn.{directory => codegen}
import com.daml.network.codegen.java.cn.{splitwell => splitwellCodegen}
import com.daml.network.console.{DirectoryExternalAppClientReference, WalletAppClientReference}
import com.daml.network.console.LedgerApiExtensions._
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.topology.PartyId

println("Waiting for SVC initialization...")
// We need to do this at the beginning, otherwise later commands can fail because CoinRules is locked.n
sv1.waitForInitialization()

println("Waiting for validator initialization...")
aliceValidator.waitForInitialization()

println("Waiting for scan initialization...")
sv1Scan.waitForInitialization()

println("Uploading DAR files...")
// all user validator shared the same participant so we can only upload once.
aliceValidator.participantClient.upload_dar_unless_exists(
  "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"
)

println("Onboarding users...")
val bobValidator = aliceValidator
val charlieValidator = aliceValidator

val aliceUserParty = aliceValidator.onboardUser(aliceWallet.config.ledgerApiUser)
val bobUserParty = bobValidator.onboardUser(bobWallet.config.ledgerApiUser)
val charlieUserParty = charlieValidator.onboardUser(charlieWallet.config.ledgerApiUser)

println("Ensuring that directory entries are allocated correctly...")
def ensureDirectoryEntry(
    user: PartyId,
    name: String,
    url: String,
    description: String,
    directory: DirectoryExternalAppClientReference,
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
      directory.createDirectoryEntry(name, url, description)
      println("Waiting for wallet initialization to complete")
      wallet.waitForInitialization()
      println("Wallet initialization complete, tapping coin")
      wallet.tap(5.0)
      println("Waiting for submission request")
      utils.retry_until_true { wallet.listSubscriptionRequests().length == 1 }
      println("Accepting submission request")
      wallet.acceptSubscriptionRequest(
        wallet.listSubscriptionRequests()(0).contractId
      )
      println("Waiting for CNS entry allocation")
      utils.retry_until_true {
        scala.util
          .Try(sv1Scan.lookupEntryByName(name).payload.user)
          .toOption
          .exists(_ == user.toProtoPrimitive)
      }
    }
  }
}
ensureDirectoryEntry(
  aliceUserParty,
  "alice.unverified.cns",
  "https://alice-url.cns.com",
  "",
  aliceDirectory,
  aliceWallet,
)
ensureDirectoryEntry(
  bobUserParty,
  "bob.unverified.cns",
  "https://bob-url.cns.com",
  "",
  bobDirectory,
  bobWallet,
)
ensureDirectoryEntry(
  charlieUserParty,
  "charlie.unverified.cns",
  "https://charlie-url.cns.com",
  "",
  charlieDirectory,
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

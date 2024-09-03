import com.digitalasset.daml.lf.value.Value.ContractId
import com.daml.network.codegen.java.splice.{splitwell => splitwellCodegen}
import com.daml.network.console.{AnsExternalAppClientReference, WalletAppClientReference}
import com.daml.network.console.LedgerApiExtensions._
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.topology.PartyId

println("Waiting for DSO initialization...")
// We need to do this at the beginning, otherwise later commands can fail because AmuletRules is locked.n
sv1.waitForInitialization()

println("Waiting for validator initialization...")
aliceValidator.waitForInitialization()

println("Waiting for scan initialization...")
sv1Scan.waitForInitialization()

println("Uploading DAR files...")
// all user validator shared the same participant so we can only upload once.
aliceValidator.participantClient.upload_dar_unless_exists(
  "daml/splitwell/.daml/dist/splitwell-current.dar"
)

println("Onboarding users...")
val bobValidator = aliceValidator
val charlieValidator = aliceValidator

val aliceUserParty = aliceValidator.onboardUser(aliceWallet.config.ledgerApiUser)
val bobUserParty = bobValidator.onboardUser(bobWallet.config.ledgerApiUser)
val charlieUserParty = charlieValidator.onboardUser(charlieWallet.config.ledgerApiUser)

println("Ensuring that ANS entries are allocated correctly...")
def ensureAnsEntry(
    user: PartyId,
    name: String,
    url: String,
    description: String,
    ans: AnsExternalAppClientReference,
    wallet: WalletAppClientReference,
) {
  try {
    val nameUser = sv1Scan.lookupEntryByName(name).user
    if (nameUser == user.toProtoPrimitive) {
      println(s"ANS name \"$name\" already allocated to \"$user\". Doing nothing.")
    } else {
      sys.error(s"ANS name \"$name\" allocated to \"$nameUser\". Can't allocate to \"$user\".")
    }
  } catch {
    case e: CommandFailure => {
      println(s"Requesting ANS name \"$name\" for user \"$user\".")
      ans.createAnsEntry(name, url, description)
      println("Waiting for wallet initialization to complete")
      wallet.waitForInitialization()
      println("Wallet initialization complete, tapping amulet")
      wallet.tap(5.0)
      println("Waiting for submission request")
      utils.retry_until_true { wallet.listSubscriptionRequests().length == 1 }
      println("Accepting submission request")
      wallet.acceptSubscriptionRequest(
        wallet.listSubscriptionRequests()(0).contractId
      )
      println("Waiting for ANS entry allocation")
      utils.retry_until_true {
        scala.util
          .Try(sv1Scan.lookupEntryByName(name).user)
          .toOption
          .exists(_ == user.toProtoPrimitive)
      }
    }
  }
}

val ansAcronym = sv1Scan.getSpliceInstanceNames().nameServiceNameAcronym.toLowerCase()

ensureAnsEntry(
  aliceUserParty,
  s"alice.unverified.$ansAcronym",
  "https://alice-url.ans.com",
  "",
  aliceAns,
  aliceWallet,
)
ensureAnsEntry(
  bobUserParty,
  s"bob.unverified.$ansAcronym",
  "https://bob-url.ans.com",
  "",
  bobAns,
  bobWallet,
)
ensureAnsEntry(
  charlieUserParty,
  s"charlie.unverified.$ansAcronym",
  "https://charlie-url.ans.com",
  "",
  charlieAns,
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

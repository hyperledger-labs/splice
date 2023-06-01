import com.daml.lf.value.Value.ContractId
import com.daml.network.codegen.java.cn.{directory => codegen}
import com.daml.network.codegen.java.cn.{splitwell => splitwellCodegen}
import com.daml.network.console.{DirectoryAppClientReference, WalletAppClientReference}
import com.daml.network.console.LedgerApiExtensions._
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.topology.PartyId

println("Waiting for SVC initialization...")
// We need to do this at the beginning, otherwise later commands can fail because CoinRules is locked.n
Seq(sv1, sv2, sv3, sv4).foreach(_.waitForInitialization())

println("Waiting for validator initialization...")
aliceValidator.waitForInitialization()
bobValidator.waitForInitialization()

println("Uploading DAR files...")
Seq(aliceValidator.participantClient, bobValidator.participantClient).foreach { p =>
  p.upload_dar_unless_exists("daml/splitwell/.daml/dist/splitwell-0.1.0.dar")
  p.upload_dar_unless_exists("daml/directory-service/.daml/dist/directory-service-0.1.0.dar")
}

println("Onboarding users...")
val charlieValidator = aliceValidator

val aliceUserParty = aliceValidator.onboardUser(aliceWallet.config.ledgerApiUser)
val bobUserParty = bobValidator.onboardUser(bobWallet.config.ledgerApiUser)
val charlieUserParty = charlieValidator.onboardUser(charlieWallet.config.ledgerApiUser)

println("Waiting for directory initialization...")
`directory-app`.waitForInitialization()

val aliceRequestCid = aliceDirectory.requestDirectoryInstall()
val bobRequestCid = bobDirectory.requestDirectoryInstall()
val charlieRequestCid = charlieDirectory.requestDirectoryInstall()

aliceValidator.participantClient.ledger_api_extensions.acs
  .awaitJava(codegen.DirectoryInstall.COMPANION)(aliceUserParty)
bobValidator.participantClient.ledger_api_extensions.acs
  .awaitJava(codegen.DirectoryInstall.COMPANION)(bobUserParty)
charlieValidator.participantClient.ledger_api_extensions.acs
  .awaitJava(codegen.DirectoryInstall.COMPANION)(charlieUserParty)

println("Ensuring that directory entries are allocated correctly...")
def ensureDirectoryEntry(
    user: PartyId,
    name: String,
    directory: DirectoryAppClientReference,
    wallet: WalletAppClientReference,
) {
  try {
    val nameUser = directory.lookupEntryByName(name).payload.user
    if (nameUser == user.toProtoPrimitive) {
      println(s"CNS name \"$name\" already allocated to \"$user\". Doing nothing.")
    } else {
      sys.error(s"CNS name \"$name\" allocated to \"$nameUser\". Can't allocate to \"$user\".")
    }
  } catch {
    case e: CommandFailure => {
      println(s"Requesting CNS name \"$name\" for user \"$user\".")
      directory.requestDirectoryEntry(name)
      println("Waiting for wallet initialization to complete")
      wallet.waitForInitialization()
      println("Wallet initialization complete, tapping coin")
      wallet.tap(5.0)
      utils.retry_until_true { wallet.listSubscriptionRequests().length == 1 }
      wallet.acceptSubscriptionRequest(
        wallet.listSubscriptionRequests()(0).subscriptionRequest.contractId
      )
    }
  }
}
ensureDirectoryEntry(aliceUserParty, "alice.cns", aliceDirectory, aliceWallet)
ensureDirectoryEntry(bobUserParty, "bob.cns", bobDirectory, bobWallet)
ensureDirectoryEntry(charlieUserParty, "charlie.cns", charlieDirectory, charlieWallet)

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

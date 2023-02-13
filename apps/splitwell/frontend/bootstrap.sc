import com.daml.lf.value.Value.ContractId
import com.daml.network.codegen.java.cn.{directory => codegen}
import com.daml.network.codegen.java.cn.{splitwell => splitwellCodegen}
import com.daml.network.console.{RemoteDirectoryAppReference, WalletAppClientReference}
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.topology.PartyId

println("Waiting for validator initialization...")
aliceValidator.waitForInitialization()
bobValidator.waitForInitialization()

println("Uploading DAR files...")
Seq(aliceValidator.remoteParticipant, bobValidator.remoteParticipant).foreach { p =>
  p.dars.upload("daml/splitwell/.daml/dist/splitwell-0.1.0.dar")
  p.dars.upload("daml/directory-service/.daml/dist/directory-service-0.1.0.dar")
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

aliceValidator.remoteParticipant.ledger_api.acs
  .awaitJava(codegen.DirectoryInstall.COMPANION)(aliceUserParty)
bobValidator.remoteParticipant.ledger_api.acs
  .awaitJava(codegen.DirectoryInstall.COMPANION)(bobUserParty)
charlieValidator.remoteParticipant.ledger_api.acs
  .awaitJava(codegen.DirectoryInstall.COMPANION)(charlieUserParty)

println("Ensuring that directory entries are allocated correctly...")
def ensureDirectoryEntry(
    user: PartyId,
    name: String,
    directory: RemoteDirectoryAppReference,
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
      wallet.tap(5.0)
      utils.retry_until_true { wallet.listSubscriptionRequests().length == 1 }
      wallet.acceptSubscriptionRequest(wallet.listSubscriptionRequests()(0).contractId)
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
  splitwell.createInstallRequest()
  splitwell.ledgerApi.ledger_api.acs.awaitJava(splitwellCodegen.SplitwellInstall.COMPANION)(party)
}

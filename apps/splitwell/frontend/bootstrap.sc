import com.daml.lf.value.Value.ContractId
import com.daml.network.codegen.java.cn.{directory => codegen}
import com.daml.network.codegen.java.cn.{splitwell => splitwellCodegen}

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

aliceValidator.remoteParticipant.ledger_api.acs.awaitJava(codegen.DirectoryInstall.COMPANION)(aliceUserParty)
bobValidator.remoteParticipant.ledger_api.acs.awaitJava(codegen.DirectoryInstall.COMPANION)(bobUserParty)
charlieValidator.remoteParticipant.ledger_api.acs.awaitJava(codegen.DirectoryInstall.COMPANION)(charlieUserParty)

println("Creating directory entries...")
aliceDirectory.requestDirectoryEntry("alice.cns")
bobDirectory.requestDirectoryEntry("bob.cns")
charlieDirectory.requestDirectoryEntry("charlie.cns")

val aliceCoin = aliceWallet.tap(5.0)
utils.retry_until_true { aliceWallet.listSubscriptionRequests().length == 1 }
val aliceAccepted = aliceWallet.acceptSubscriptionRequest(aliceWallet.listSubscriptionRequests()(0).contractId)
val bobCoin = bobWallet.tap(5.0)
utils.retry_until_true { bobWallet.listSubscriptionRequests().length == 1 }
val bobAccepted = bobWallet.acceptSubscriptionRequest(bobWallet.listSubscriptionRequests()(0).contractId)
val charlieCoin = charlieWallet.tap(5.0)
utils.retry_until_true { charlieWallet.listSubscriptionRequests().length == 1 }
val charlieAccepted = charlieWallet.acceptSubscriptionRequest(charlieWallet.listSubscriptionRequests()(0).contractId)

println("Waiting for splitwell initialization...")
providerSplitwellBackend.waitForInitialization()
val providerParty = providerSplitwellBackend.getProviderPartyId()

Seq(aliceSplitwell -> aliceUserParty,
    bobSplitwell -> bobUserParty,
    charlieSplitwell -> charlieUserParty).foreach { case (splitwell, party) =>
  splitwell.createInstallRequest()
  splitwell.ledgerApi.ledger_api.acs.awaitJava(splitwellCodegen.SplitwellInstall.COMPANION)(party)
}

// This is a canton bootstrap script that will export the transaction tree stream
// for selected parties in a human-readable, pretty printed format to the file ./log/trees.txt.
//
// This script is not optimized, it is unlikely to work on large ledgers.
// Even on small ledgers, it takes a while to execute.
//
// Example usage:
// sbt "apps-app/runMain org.lfdecentralizedtrust.splice.SpliceApp --config ${CONFIG} --bootstrap ./scripts/dumpAllTrees.sc"

import com.digitalasset.canton.logging.pretty.CantonPrettyPrinter
import com.digitalasset.canton.topology.PartyId
import com.daml.ledger.api.v2.transaction.TransactionTree
import scala.collection.mutable.ListBuffer
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets

// --------------------------------------------------------------------------------------
// Config - edit this part
// --------------------------------------------------------------------------------------
val ledgerApis = Seq(
  aliceValidator.remoteParticipantWithAdminToken.ledger_api -> "aliceValidator"
)
def includeParty(id: PartyId): Boolean = {
  id.toString.contains("_service")
}

// --------------------------------------------------------------------------------------
// Implementation - no need to edit anything below
// --------------------------------------------------------------------------------------
val printer = new CantonPrettyPrinter(1000000, 10000)
def treeToString(t: TransactionTree): String = {
  printer.printAdHoc(t)
}

val content = ListBuffer[String]()

ledgerApis.foreach { case (ledgerApi, ledgerApiName) =>
  val parties = ledgerApi.parties.list().map(_.party).map(p => PartyId.tryFromLfParty(p))
  parties.foreach(party => {
    if (includeParty(party)) {
      println(s"Loading trees for ${party} on ${ledgerApiName}")
      val trees = ledgerApi.transactions.trees(partyIds = Set(party), completeAfter = 10000)
      content.append("===========================================================================")
      content.append(s"${party} @ ${ledgerApiName}")
      content.append("===========================================================================")
      trees.foreach(tree => {
        content.append(treeToString(tree))
      })
      content.append("")
      content.append("")
    } else {
      println(s"Skipping ${party} on ${ledgerApiName}")
    }
  })
}

println("Writing to file...")
Files.write(
  Paths.get("./log/trees.txt"),
  content.mkString(System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
)

println("Done")

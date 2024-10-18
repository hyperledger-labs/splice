import java.io.File
import scala.collection.mutable

import org.lfdecentralizedtrust.splice.config.SpliceConfig

// TODO(tech-debt): make it easier/automatic to select the input config files
// TODO(tech-debt): print daml user names used in the config
object ConfigSummaryPrinter extends App {

  if (args.isEmpty) {
    println("Prints a summary of the ports used in the given Canton Network config file(s)")
    println("Usage: <config-file-name> [<config-file-name> ...]")
    sys.exit(1)
  }

  val files = args.toIndexedSeq.map(new File(_))
  printPortSummary(files)

  private def printPortSummary(files: Seq[File]): Unit = {
    val ports = mutable.ListBuffer[(Int, String)]()

    val config = SpliceConfig.parseAndLoadOrThrow(files)

    config.domains.foreach { case (name, i) =>
      ports.addOne(
        i.publicApi.internalPort.fold(0)(_.unwrap) -> s"Domain '${name.unwrap}', Public API"
      )
      ports.addOne(
        i.adminApi.internalPort.fold(0)(_.unwrap) -> s"Domain '${name.unwrap}', Admin API"
      )
    }
    config.participants.foreach { case (name, i) =>
      ports.addOne(
        i.ledgerApi.internalPort.fold(0)(_.unwrap) -> s"Participant '${name.unwrap}', Ledger API"
      )
      ports.addOne(
        i.adminApi.internalPort.fold(0)(_.unwrap) -> s"Participant '${name.unwrap}', Admin API"
      )
    }
    config.directoryApp.foreach(i =>
      ports.addOne(i.adminApi.internalPort.fold(0)(_.unwrap) -> s"Directory App, Admin API")
    )
    config.scanApp.foreach(i =>
      ports.addOne(i.adminApi.internalPort.fold(0)(_.unwrap) -> s"Scan App, Admin API")
    )
    config.validatorApps.foreach { case (name, i) =>
      ports.addOne(
        i.adminApi.internalPort.fold(0)(_.unwrap) -> s"Validator App '${name.unwrap}', Admin API"
      )
    }
    config.walletAppBackends.foreach { case (name, i) =>
      ports.addOne(
        i.adminApi.internalPort.fold(0)(_.unwrap) -> s"Wallet App '${name.unwrap}', Admin API"
      )
    }
    config.splitwellApps.foreach { case (name, i) =>
      ports.addOne(
        i.adminApi.internalPort.fold(0)(_.unwrap) -> s"Splitwell App '${name.unwrap}', Admin API"
      )
    }

    println("Ports used by local nodes:")
    ports.sortBy(_._1).foreach { case (port, name) =>
      println(s"${port.toString.padTo(5, ' ')}\t$name")
    }
  }
}

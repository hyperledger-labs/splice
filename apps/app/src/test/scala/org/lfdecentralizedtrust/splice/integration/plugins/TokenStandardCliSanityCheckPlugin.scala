package org.lfdecentralizedtrust.splice.integration.plugins

import com.daml.ledger.javaapi.data.Identifier
import com.digitalasset.canton.ScalaFuturesWithPatience
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import com.digitalasset.canton.logging.SuppressingLogger
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import org.lfdecentralizedtrust.splice.config.SpliceConfig
import org.lfdecentralizedtrust.splice.environment.{DarResources, SpliceEnvironment}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig
import org.lfdecentralizedtrust.splice.util.CommonAppInstanceReferences
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inside, Inspectors, LoneElement}

import scala.collection.mutable
import scala.sys.process.{Process, ProcessLogger}

import java.nio.file.Files
import java.util.UUID
import TokenStandardCliSanityCheckPlugin.*

import scala.collection.parallel.CollectionConverters.*
import scala.util.control.NonFatal
import scala.util.{Success, Try}

import org.scalatest.OptionValues

/** Runs `token-standard/cli`, to make sure that we have:
  * - no transactions that would break it.
  * - no transactions that get parsed as raw creates/archives,
  *   as they should all be under some known/parseable exercise.
  *   Unless their templates are ignored in `rawBehavior`,
  *   in which case we know they're created via ledger API on purpose.
  */
class TokenStandardCliSanityCheckPlugin(
    rawBehavior: OutputCreateArchiveBehavior,
    protected val loggerFactory: SuppressingLogger,
) extends EnvironmentSetupPlugin[SpliceConfig, SpliceEnvironment]
    with Matchers
    with Eventually
    with Inspectors
    with ScalaFuturesWithPatience
    with LoneElement
    with Inside
    with CommonAppInstanceReferences
    with OptionValues {

  override def beforeEnvironmentDestroyed(
      config: SpliceConfig,
      environment: SpliceTestConsoleEnvironment,
  ): Unit = {
    try {
      TraceContext.withNewTraceContext("beforeEnvironmentDestroyed") { implicit tc =>
        val sv1 = environment.svs.local.find(_.name == "sv1").value
        val amuletVersion = inside(sv1.config.onboarding) {
          case Some(foundDso: SvOnboardingConfig.FoundDso) =>
            PackageVersion.assertFromString(foundDso.initialPackageConfig.amuletVersion)
        }
        if (amuletVersion < DarResources.amulet_0_1_9.metadata.version) {
          logger.info(s"Amulet version $amuletVersion does not support token standard, skipping")
        } else {
          // `SuppressingLogger.suppress` support neither nested nor **concurrent** calls
          // but we need it for (expected) connection errors
          loggerFactory.suppressWarningsAndErrors {
            // Using `par` to save some time per test
            environment.wallets.par.foreach { wallet =>
              val ledgerApiUser = wallet.config.ledgerApiUser
              val validator = environment.validators.local
                .find(_.config.adminApi.port.unwrap == wallet.config.adminApi.url.effectivePort)
                .getOrElse(
                  throw new RuntimeException(
                    s"Failed to find validator for wallet of $ledgerApiUser"
                  )
                )
              // JSON API port = gRPCport + 1000
              val participantHttpApiPort =
                validator.participantClient.config.ledgerApi.port.unwrap + 1000

              if (
                validator.config.enableWallet &&
                Try(wallet.httpLive && validator.participantClient.health.is_running())
                  .getOrElse(false)
              ) {
                // Sometimes userStatus will fail with 404
                Try(wallet.userStatus()) match {
                  case Success(userStatus)
                      if userStatus.userOnboarded && userStatus.userWalletInstalled =>
                    val party = PartyId.tryFromProtoPrimitive(userStatus.party)
                    val authToken = wallet.token.getOrElse(
                      throw new IllegalStateException(
                        s"No auth token found for wallet of ${party.toProtoPrimitive}"
                      )
                    )
                    // check no command blows up
                    runCommand("list-holdings", party, authToken, participantHttpApiPort, Seq.empty)
                    val holdingTxsDebugPath =
                      Files.createTempFile(UUID.randomUUID().toString, ".json")
                    logger.info(
                      s"Debug Output of token-standard command list-holding-txs will be written to $holdingTxsDebugPath"
                    )
                    // check also that list-holding-txs does not contain raw events
                    val holdingTxsExtraArgs =
                      Seq("-d", holdingTxsDebugPath.toAbsolutePath.toString) ++ (rawBehavior match {
                        case OutputCreateArchiveBehavior.IgnoreAll => Seq.empty
                        case OutputCreateArchiveBehavior.IgnoreForTemplateIds(templateIds) =>
                          Seq("--strict") ++ templateIds.headOption.toList.flatMap(tId =>
                            Seq("--strict-ignore", tId.getEntityName) ++ templateIds.tail
                              .map(_.getEntityName)
                          )
                      })
                    runCommand(
                      "list-holding-txs",
                      party,
                      authToken,
                      participantHttpApiPort,
                      holdingTxsExtraArgs,
                    )
                  case result =>
                    logger.info(
                      s"Not checking wallet for user $ledgerApiUser because it's not available: $result"
                    )
                }
              } else {
                logger.info(
                  s"Token-standard CLI not running for $ledgerApiUser because either wallet or participant are not alive."
                )
              }
            }
          }
        }
      }
    } catch {
      case NonFatal(ex) =>
        val msg = "Failed to run token-standard sanity check"
        logger.error(msg)(TraceContext.empty)
        throw new RuntimeException(msg, ex)
    }
  }

  private def runCommand(
      command: String,
      partyId: PartyId,
      token: String,
      participantPort: Int,
      extraArgs: Seq[String],
      retries: Int = 3,
  )(implicit
      tc: TraceContext
  ): String = {
    val readLines = mutable.Buffer[String]()
    val logProcessor = ProcessLogger { line =>
      {
        logger.debug(s"CLI output: $line")
        readLines.append(line)
      }
    }
    val cwd = new java.io.File("token-standard/cli")

    val args = Seq(
      "npm",
      "run",
      "cli",
      "--",
      command,
      partyId.toProtoPrimitive,
      "-l",
      s"http://localhost:$participantPort",
      "-a",
      token,
    ) ++ extraArgs
    val exitCodeTry = Try(Process(args, cwd).!(logProcessor))
    if (exitCodeTry.toOption.exists(_ != 0) || exitCodeTry.isFailure) {
      logger.error(s"Failed to run $args: $exitCodeTry. Dumping output.")(TraceContext.empty)
      readLines.foreach(logger.error(_)(TraceContext.empty))
      if (retries > 0) {
        // node commands sometimes give no logs whatsoever, and sometimes they segfault and dump a native stack trace,
        // so retrying seems like the best solution.
        // If the cause of the error is not-node-related, it should fail quick enough that it won't slow down tests too much.
        logger.info(s"Retrying command $args")
        runCommand(command, partyId, token, participantPort, extraArgs, retries - 1)
      } else {
        // sometimes readlines is empty...?
        throw new RuntimeException(s"$args failed: ${readLines}")
      }
    }
    val result = readLines
      .dropWhile(line => !line.startsWith("[") && !line.startsWith("{")) // remove npm noise
      .mkString("\n")
    val tmpFile = Files.createTempFile(UUID.randomUUID().toString, ".json")
    Files.writeString(tmpFile, result)
    logger.debug(s"Output of token-standard command $command written to $tmpFile")
    result
  }

}

object TokenStandardCliSanityCheckPlugin {
  sealed trait OutputCreateArchiveBehavior
  object OutputCreateArchiveBehavior {

    /** Presence of a type:"Created"|"Archived" will fail unless explicitly ignored in the Seq.
      */
    case class IgnoreForTemplateIds(templateIds: Seq[Identifier])
        extends OutputCreateArchiveBehavior

    /** The CLI will still be run, making sure that it doesn't crash, but its output won't be validated.
      */
    case object IgnoreAll extends OutputCreateArchiveBehavior
  }
}

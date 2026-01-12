package org.lfdecentralizedtrust.splice.performance

import cats.syntax.all.*
import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import com.monovore.decline.*
import org.apache.pekko.actor.ActorSystem

import java.nio.file.Paths
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, ExecutionContext}
import scala.util.control.NonFatal

object SplicePerf
    extends CommandApp(
      name = "splice-perf",
      header = "Splice Performance Testing Tool",
      main = SplicePerfImpl.main(),
    )

object SplicePerfImpl {
  def main(): Opts[Unit] = {
    // good enough for what we're doing
    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global
    implicit val loggerFactory: NamedLoggerFactory = NamedLoggerFactory.root
    implicit val as: ActorSystem = ActorSystem("PerformanceRunner")
    implicit val tc: TraceContext = TraceContext.empty
    implicit val metricsFactory: LabeledMetricsFactory = NoOpMetricsFactory

    def run(runnable: Runnable): Unit = {
      try {
        runnable.run()
      } catch {
        case NonFatal(e) =>
          loggerFactory
            .getLogger(runnable.getClass)
            .error(s"Failed to run ${runnable.getClass.getName}.", e)
          throw e
      } finally {
        Await.result(as.terminate(), 1.minute)
      }
    }

    Opts
      .subcommand(
        Command(
          "download-scan-updates",
          "Downloads the updates from the provided SV (default DA SV-1 MainNet) for the given time window.",
        )(
          (
            Opts
              .option[String]("host", "Host URI of Scan. Default: DA SV-2's Scan on MainNet", "h")
              .withDefault("https://scan.sv-2.global.canton.network.digitalasset.com"),
            Opts.option[Int]("migration-id", "Migration ID to query the updates for", "m"),
            Opts
              .option[String]("write-path", "Path to write the updates to", "w")
              .map(
                Paths.get(_)
              ),
            Opts
              .option[String](
                "download-start-time",
                "The time from which to start downloading updates (ISO-8601 format). Default: 1h ago",
                "s",
              )
              .map(Instant.parse)
              .withDefault(Instant.now().minus(1, ChronoUnit.HOURS)),
            Opts
              .option[String]("duration", "Duration (Scala Duration notation). Default: 1h", "d")
              .map(Duration.create)
              .withDefault(Duration(1, TimeUnit.HOURS)),
          ).mapN { (host, migrationId, writePath, startAt, duration) =>
            run(new DownloadScanUpdates(host, migrationId, writePath, startAt, duration))
          }
        )
      )
      .orElse(
        Opts.subcommand(
          Command(
            "download-scan-acs-snapshot",
            "Downloads the ACS snapshot from the provided SV (default DA SV-1 MainNet) at the given time.",
          )(
            (
              Opts
                .option[String]("host", "Host URI of Scan. Default: DA SV-2's Scan on MainNet", "h")
                .withDefault("https://scan.sv-2.global.canton.network.digitalasset.com"),
              Opts.option[Int]("migration-id", "Migration ID to query the ACS snapshot for", "m"),
              Opts
                .option[String]("write-path", "Path to write the ACS snapshot to", "w")
                .map(
                  Paths.get(_)
                ),
              Opts
                .option[String](
                  "snapshot-time",
                  "The time at which to download the ACS snapshot (ISO-8601 format). Default: now",
                  "s",
                )
                .map(Instant.parse)
                .withDefault(Instant.now()),
            ).mapN { (host, migrationId, writePath, snapshotTime) =>
              run(new DownloadScanAcsSnapshot(host, migrationId, writePath, snapshotTime))
            }
          )
        )
      )
      .orElse(
        Opts.subcommand(
          Command(
            "run",
            "Runs the given test(s)",
          )(
            (
              Opts
                .option[String](
                  "test-name",
                  "Name of the test to run (supports globbing)",
                  "t",
                ),
              Opts
                .option[String]("config-path", "Path to the config file", "c")
                .map(
                  Paths.get(_)
                ),
              Opts
                .option[String](
                  "dump-path",
                  """Path to the dump file containing Update History data.
                    |This can be obtained by running the `download-scan-updates` command.""".stripMargin,
                  "d",
                )
                .map(
                  Paths.get(_)
                ),
            ).mapN { (testNames, configPath, dumpPath) =>
              run(new TestRunner(testNames, configPath, dumpPath))
            }
          )
        )
      )
  }
}

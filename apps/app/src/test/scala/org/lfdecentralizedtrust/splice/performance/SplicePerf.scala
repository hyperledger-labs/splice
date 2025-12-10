package org.lfdecentralizedtrust.splice.performance

import cats.syntax.all.*
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import com.monovore.decline.*
import org.apache.pekko.actor.ActorSystem

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

object SplicePerf
    extends CommandApp(
      name = "splice-perf",
      header = "Splice Performance Testing Tool",
      main = {
        // good enough for what we're doing
        implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global
        implicit val loggerFactory: NamedLoggerFactory = NamedLoggerFactory.root
        implicit val as: ActorSystem = ActorSystem("PerformanceRunner")
        implicit val tc: TraceContext = TraceContext.empty

        try {
          Opts
            .subcommand(
              Command(
                "download-scan-updates",
                "Downloads the updates from the provided SV (default DA SV-1 MainNet) for the given time window.",
              )(
                (
                  Opts.option[String]("host", "Host URI of the SV node", "h"),
                  Opts.option[Int]("migration-id", "Migration ID to query the updates for", "m"),
                ).mapN { (host, migrationId) =>
                  new DownloadScanUpdates(host, migrationId).run()
                }
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
                    Opts.option[String]("config-path", "Path to the config file", "c"),
                  ).mapN { (testNames, configPath) =>
                    new TestRunner(testNames, configPath).run()
                  }
                )
              )
            )
        } finally {
          Await.result(as.terminate(), 1.minute)
        }
      },
    )

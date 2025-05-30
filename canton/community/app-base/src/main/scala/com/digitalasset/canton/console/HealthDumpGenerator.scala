// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.console

import better.files.File
import com.digitalasset.canton.admin.api.client.commands.{
  GrpcAdminCommand,
  MediatorAdminCommands,
  ParticipantAdminCommands,
  SequencerAdminCommands,
}
import com.digitalasset.canton.admin.api.client.data.{CantonStatus, NodeStatus}
import com.digitalasset.canton.config.{LocalNodeConfig, SharedCantonConfig}
import com.digitalasset.canton.console.CommandErrors.CommandError
import com.digitalasset.canton.environment.Environment
import com.digitalasset.canton.metrics.MetricsSnapshot
import com.digitalasset.canton.version.ReleaseVersion
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.*

/** Generates a health dump zip file containing information about the current Canton process This is
  * the core of the implementation of the HealthDump gRPC endpoint.
  */
class HealthDumpGenerator(
    val environment: Environment[_ <: SharedCantonConfig[_]],
    val grpcAdminCommandRunner: GrpcAdminCommandRunner,
) {
  private implicit val statusEncoder: Encoder[CantonStatus] = {
    import io.circe.generic.auto.*
    import CantonHealthAdministrationEncoders.*
    deriveEncoder[CantonStatus]
  }

  def status(): CantonStatus =
    CantonStatus.getStatus(
      statusMap(
        environment.config.sequencersByString,
        SequencerAdminCommands.Health.SequencerStatusCommand(),
      ),
      statusMap(
        environment.config.mediatorsByString,
        MediatorAdminCommands.Health.MediatorStatusCommand(),
      ),
      statusMap(
        environment.config.participantsByString,
        ParticipantAdminCommands.Health.ParticipantStatusCommand(),
      ),
    )

  private def getStatusForNode[S <: NodeStatus.Status](
      nodeName: String,
      nodeConfig: LocalNodeConfig,
      nodeStatusCommand: GrpcAdminCommand[?, ?, NodeStatus[S]],
  ): NodeStatus[S] =
    grpcAdminCommandRunner
      .runCommand(
        nodeName,
        nodeStatusCommand,
        nodeConfig.clientAdminApi,
        None,
      ) match {
      case CommandSuccessful(value) => value
      case err: CommandError => NodeStatus.Failure(err.cause)
    }

  private def statusMap[S <: NodeStatus.Status](
      nodes: Map[String, LocalNodeConfig],
      nodeStatusCommand: GrpcAdminCommand[?, ?, NodeStatus[S]],
  ): Map[String, () => NodeStatus[S]] =
    nodes.map { case (nodeName, nodeConfig) =>
      nodeName -> (() => getStatusForNode[S](nodeName, nodeConfig, nodeStatusCommand))
    }

  def generateHealthDump(
      outputFile: File,
      extraFilesToZip: Seq[File] = Seq.empty,
  ): Unit = {
    import io.circe.generic.auto.*
    import CantonHealthAdministrationEncoders.*

    final case class EnvironmentInfo(os: String, javaVersion: String)

    final case class CantonDump(
        releaseVersion: String,
        environment: EnvironmentInfo,
        config: String,
        status: CantonStatus,
        metrics: MetricsSnapshot,
        traces: Map[Thread, Array[StackTraceElement]],
    )

    val javaVersion = System.getProperty("java.version")
    val cantonVersion = ReleaseVersion.current.fullVersion
    val env = EnvironmentInfo(sys.props("os.name"), javaVersion)

    val metricsSnapshot = MetricsSnapshot(
      environment.configuredOpenTelemetry.onDemandMetricsReader
    )
    val config = environment.config.dumpString

    val traces = {
      import scala.jdk.CollectionConverters.*
      Thread.getAllStackTraces.asScala.toMap
    }

    val dump = CantonDump(cantonVersion, env, config, status(), metricsSnapshot, traces)

    val logFile =
      File(
        sys.env
          .get("LOG_FILE_NAME")
          .orElse(sys.props.get("LOG_FILE_NAME")) // This is set in Cli.installLogging
          .getOrElse("log/canton.log")
      )

    val logLastErrorsFile = File(
      sys.env
        .get("LOG_LAST_ERRORS_FILE_NAME")
        .orElse(sys.props.get("LOG_LAST_ERRORS_FILE_NAME"))
        .getOrElse("log/canton_errors.log")
    )

    // This is a guess based on the default logback config as to what the rolling log files look like
    // If we want to be more robust we'd have to access logback directly, extract the pattern from there, and use it to
    // glob files.
    val rollingLogs = logFile.siblings
      .filter { f =>
        f.name.contains(logFile.name) && f.extension.contains(".gz")
      }
      .toSeq
      .sortBy(_.name)
      .take(environment.config.monitoring.dumpNumRollingLogFiles.unwrap)

    File.usingTemporaryFile("canton-dump-", ".json") { tmpFile =>
      tmpFile.append(dump.asJson.spaces2)
      val files = Iterator(logFile, logLastErrorsFile, tmpFile).filter(_.nonEmpty)
      outputFile.zipIn(files ++ extraFilesToZip.iterator ++ rollingLogs)
    }
  }
}

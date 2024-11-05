// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import cats.data.OptionT
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice as daml
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.SynchronizerNodeConfig
import org.lfdecentralizedtrust.splice.config.{NetworkAppClientConfig, UpgradesConfig}
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.sv.config.SvScanConfig
import org.lfdecentralizedtrust.splice.sv.onboarding.SynchronizerNodeConfigClient
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.store.DsoRulesStore
import org.lfdecentralizedtrust.splice.sv.util.SvUtil
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.OptionConverters.*
import scala.util.{Failure, Success}

class PublishScanConfigTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
    scanConfig: SvScanConfig,
    upgradesConfig: UpgradesConfig,
)(implicit
    override val ec: ExecutionContextExecutor,
    override val tracer: Tracer,
    httpClient: HttpClient,
    templateJsonDecoder: TemplateJsonDecoder,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[
      PublishScanConfigTrigger.PublishLocalConfigTask
    ]
    with SynchronizerNodeConfigClient {

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[PublishScanConfigTrigger.PublishLocalConfigTask]] =
    (for {
      case (rulesAndState, synchronizerNodeConfig) <- getCometBftNodeConfigDsoState(
        store,
        store.key.svParty,
      )
      domainId = rulesAndState.dsoRules.domain
      damlScanConfig = new daml.dso.decentralizedsynchronizer.ScanConfig(
        scanConfig.publicUrl.toString
      )
      // Check if config is already up2date first so we can avoid even querying scan if it is.
      if Some(damlScanConfig) != synchronizerNodeConfig.flatMap(_.scan.toScala)
      // We create the scan connection here because the version check
      // makes it hard to create it outside of this trigger as scan might not be running yet.
      scanConnection <- OptionT {
        ScanConnection
          .singleUncached(
            ScanAppClientConfig(NetworkAppClientConfig(scanConfig.internalUrl)),
            upgradesConfig,
            context.clock,
            context.retryProvider,
            loggerFactory,
            // this is a polling trigger so just retry next time
            retryConnectionOnInitialFailure = false,
          )
          .transform {
            case Failure(ex) =>
              logger.debug("Failed to get scan connection, likely still initializing", ex)
              Success(None)
            case Success(con) => Success(Some(con))
          }
      }
      // Check if scan is initialized
      _ <- OptionT {
        scanConnection.getStatus().transform {
          case Failure(ex) =>
            logger.debug("Failed to query scan for status, likely still initializing", ex)
            Success(None)
          case Success(status) =>
            status match {
              case NodeStatus.Success(_) => Success(Some(()))
              case _: NodeStatus.NotInitialized | _: NodeStatus.Failure =>
                logger.debug(show"Scan is not yet initialized, current status: $status")
                Success(None)
            }
        }
      }
    } yield PublishScanConfigTrigger.PublishLocalConfigTask(
      rulesAndState,
      synchronizerNodeConfig,
      damlScanConfig,
      // TODO(#4906): this domain-id is likely the wrong one to use in a soft-domain migration context
      domainId,
    )).value
      .map(_.toList)

  override protected def completeTask(
      task: PublishScanConfigTrigger.PublishLocalConfigTask
  )(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    for {
      _ <- updateSynchronizerNodeConfig(
        task.dsoRulesAndState,
        task.damlSvNodeConfig,
        store,
        connection,
      )
    } yield TaskSuccess(show"Updated DSO-wide scan node configuration for ${store.key.svParty}")
  }

  override protected def isStaleTask(
      task: PublishScanConfigTrigger.PublishLocalConfigTask
  )(implicit tc: TraceContext): Future[Boolean] =
    task.dsoRulesAndState.isStale(store.multiDomainAcsStore)
}

object PublishScanConfigTrigger {
  case class PublishLocalConfigTask(
      dsoRulesAndState: DsoRulesStore.DsoRulesWithSvNodeState,
      synchronizerNodeConfig: Option[SynchronizerNodeConfig],
      scanConfig: daml.dso.decentralizedsynchronizer.ScanConfig,
      domainId: DomainId,
  ) extends PrettyPrinting {

    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*

    override def pretty: Pretty[this.type] = prettyOfClass(
      param("dsoRulesAndState", _.dsoRulesAndState),
      param("publicScanUrl", _.scanConfig.publicUrl.unquoted),
    )

    val damlSvNodeConfig: daml.dso.decentralizedsynchronizer.SynchronizerNodeConfig =
      new daml.dso.decentralizedsynchronizer.SynchronizerNodeConfig(
        synchronizerNodeConfig.fold(SvUtil.emptyCometBftConfig)(_.cometBft),
        synchronizerNodeConfig.flatMap(_.sequencer.toScala).toJava,
        synchronizerNodeConfig.flatMap(_.mediator.toScala).toJava,
        Some(scanConfig).toJava,
        synchronizerNodeConfig.flatMap(_.legacySequencerConfig.toScala).toJava,
      )
  }
}

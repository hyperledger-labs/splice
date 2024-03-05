package com.daml.network.sv.automation.singlesv

import cats.data.OptionT
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn as daml
import com.daml.network.codegen.java.cn.cometbft.CometBftConfig
import com.daml.network.codegen.java.cn.svc.globaldomain.{MediatorConfig, SequencerConfig}
import com.daml.network.config.NetworkAppClientConfig
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.SvScanConfig
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.{AssignedContract, TemplateJsonDecoder}
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.stream.Materializer

import java.util.Optional
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.{Failure, Success}

class PublishScanConfigTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
    scanConfig: SvScanConfig,
)(implicit
    override val ec: ExecutionContextExecutor,
    override val tracer: Tracer,
    httpClient: HttpRequest => Future[HttpResponse],
    templateJsonDecoder: TemplateJsonDecoder,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[
      PublishScanConfigTrigger.PublishLocalConfigTask
    ] {

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[PublishScanConfigTrigger.PublishLocalConfigTask]] =
    (for {
      svcRules <- OptionT(store.lookupSvcRules())
      svNodeMemberInfo <- OptionT.fromOption[Future](
        CometBftNode.extractSvNodeMemberInfo(svcRules.payload, store.key.svParty)
      )
      domainId = svcRules.domain
      domainNodeConfig = svNodeMemberInfo.domainNodes.asScala.get(domainId.toProtoPrimitive)
      damlScanConfig = new daml.svc.globaldomain.ScanConfig(scanConfig.publicUrl.toString)
      // Check if config is already up2date first so we can avoid even querying scan if it is.
      if Some(damlScanConfig) != domainNodeConfig.flatMap(_.scan.toScala)
      // We create the scan connection here because the version check
      // makes it hard to create it outside of this trigger as scan might not be running yet.
      scanConnection <- OptionT {
        ScanConnection
          .singleUncached(
            ScanAppClientConfig(NetworkAppClientConfig(scanConfig.internalUrl)),
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
      svNodeMemberInfo.name,
      svcRules,
      domainNodeConfig.fold(SvUtil.emptyCometBftConfig)(_.cometBft),
      domainNodeConfig.flatMap(_.sequencer.toScala).toJava,
      domainNodeConfig.flatMap(_.mediator.toScala).toJava,
      damlScanConfig,
      domainId,
    )).value
      .map(_.toList)

  override protected def completeTask(
      task: PublishScanConfigTrigger.PublishLocalConfigTask
  )(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val cmd = task.svcRules.exercise(
      _.exerciseSvcRules_SetDomainNodeConfig(
        store.key.svParty.toProtoPrimitive,
        task.domainId.toProtoPrimitive,
        task.damlSvNodeConfig,
      )
    )
    for {
      _ <- connection
        .submit(Seq(store.key.svParty), Seq(store.key.svcParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess(show"Updated SVC-wide scan node configuration for ${store.key.svParty}")
  }

  override protected def isStaleTask(
      task: PublishScanConfigTrigger.PublishLocalConfigTask
  )(implicit tc: TraceContext): Future[Boolean] = {
    for {
      contract <- store.multiDomainAcsStore
        .lookupContractById(daml.svcrules.SvcRules.COMPANION)(task.svcRules.contractId)
    } yield {
      contract.isEmpty
    }
  }
}

object PublishScanConfigTrigger {
  case class PublishLocalConfigTask(
      svNodeId: String,
      svcRules: AssignedContract[daml.svcrules.SvcRules.ContractId, daml.svcrules.SvcRules],
      cometBftConfig: CometBftConfig,
      sequencerConfig: Optional[SequencerConfig],
      mediatorConfig: Optional[MediatorConfig],
      scanConfig: daml.svc.globaldomain.ScanConfig,
      domainId: DomainId,
  ) extends PrettyPrinting {

    import com.daml.network.util.PrettyInstances.*

    override def pretty: Pretty[this.type] = prettyOfClass(
      param("svcRules", _.svcRules),
      param("publicScanUrl", _.scanConfig.publicUrl.unquoted),
    )

    val damlSvNodeConfig: daml.svc.globaldomain.DomainNodeConfig =
      new daml.svc.globaldomain.DomainNodeConfig(
        cometBftConfig,
        sequencerConfig,
        mediatorConfig,
        Some(scanConfig).toJava,
      )
  }
}

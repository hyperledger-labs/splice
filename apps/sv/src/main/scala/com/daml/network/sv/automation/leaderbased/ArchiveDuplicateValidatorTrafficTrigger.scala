package com.daml.network.sv.automation.leaderbased

import akka.stream.Materializer
import com.daml.network.automation.{
  OnReadyContractTrigger,
  TaskOutcome,
  TaskStale,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc.globaldomain.ValidatorTraffic
import com.daml.network.sv.store.SvSvcStore.DuplicateValidatorTrafficContracts
import com.daml.network.util.ReadyContract
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ArchiveDuplicateValidatorTrafficTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnReadyContractTrigger.Template[ValidatorTraffic.ContractId, ValidatorTraffic](
      svTaskContext.svcStore,
      ValidatorTraffic.COMPANION,
    )
    with SvTaskBasedTrigger[ReadyContract[ValidatorTraffic.ContractId, ValidatorTraffic]] {

  private val store = svTaskContext.svcStore
  private val trafficContractsLimit = 100

  override def completeTaskAsLeader(
      validatorTraffic: ReadyContract[ValidatorTraffic.ContractId, ValidatorTraffic]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val validatorParty = PartyId.tryFromProtoPrimitive(validatorTraffic.payload.validator)
    val domainId = DomainId.tryFromString(validatorTraffic.payload.domainId)
    store
      .listDuplicateValidatorTrafficContracts(validatorParty, domainId, trafficContractsLimit)
      .flatMap(
        _.map(archiveDuplicateValidatorTrafficContracts)
          .getOrElse(Future.successful(TaskStale))
      )
  }

  private def archiveDuplicateValidatorTrafficContracts(
      duplicateContracts: DuplicateValidatorTrafficContracts
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    if (duplicateContracts.duplicates.isEmpty)
      Future.successful(
        TaskSuccess(
          show"skipping as there are no duplicates in ${duplicateContracts}"
        )
      )
    else {
      for {
        domainId <- store.domains.waitForDomainConnection(store.defaultAcsDomain)
        svcRules <- store.getSvcRules()
        coinRules <- store.getCoinRules()
        cmd = svcRules.contractId.exerciseSvcRules_ArchiveDuplicateValidatorTrafficContracts(
          coinRules.contractId,
          duplicateContracts.reference.contractId,
          duplicateContracts.duplicates.map(_.contractId).asJava,
        )
        _ <- svTaskContext.connection.submitWithResultNoDedup(
          Seq(store.key.svParty),
          Seq(store.key.svcParty),
          cmd,
          domainId,
        )
      } yield {
        TaskSuccess(
          show"duplicate contracts from ${duplicateContracts} archived"
        )
      }
    }
  }
}

package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnReadyContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.store.MultiDomainAcsStore.ReadyContract
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.sv.util.ExpiringLock
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ReconcileSequencerTrafficLimitWithPurchasedTrafficTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    participantAdminConnection: ParticipantAdminConnection,
    globalLock: ExpiringLock,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnReadyContractTrigger.Template[
      cc.globaldomain.ValidatorTraffic.ContractId,
      cc.globaldomain.ValidatorTraffic,
    ](
      store,
      cc.globaldomain.ValidatorTraffic.COMPANION,
    ) {

  private def getValidatorParticipant(
      domainId: DomainId,
      validatorParty: PartyId,
  )(implicit traceContext: TraceContext) = {
    participantAdminConnection
      .getPartyToParticipant(domainId, validatorParty)
      .map(
        _.mapping.participants.headOption.getOrElse(
          throw Status.NOT_FOUND
            .withDescription(
              s"Could not find participant hosting validator party ${validatorParty}"
            )
            .asRuntimeException()
        )
      )
  }

  override def completeTask(
      validatorTraffic: ReadyContract[
        cc.globaldomain.ValidatorTraffic.ContractId,
        cc.globaldomain.ValidatorTraffic,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val validatorTraffic_ = validatorTraffic.contract.payload
    val domainId = DomainId.tryFromString(validatorTraffic_.domainId)
    val validatorPartyId = PartyId.tryFromProtoPrimitive(validatorTraffic_.validator)
    for {
      validatorParticipant <- getValidatorParticipant(domainId, validatorPartyId)
      svcRules <- store.getSvcRules()
      trafficLimitOffset = svcRules.payload.initialTrafficState.asScala
        .get(validatorParticipant.participantId.toProtoPrimitive)
        .fold(0L)(_.consumedTraffic)
      newExtraTrafficLimit = trafficLimitOffset + validatorTraffic_.totalPurchased
      currentExtraTrafficLimit <- participantAdminConnection
        .lookupTrafficControlState(domainId, validatorParticipant.participantId)
        .map(_.fold(0L)(_.mapping.totalExtraTrafficLimit.value))
      taskOutcome <-
        if (currentExtraTrafficLimit < newExtraTrafficLimit)
          participantAdminConnection
            .getParticipantId()
            .flatMap(svParticipantId =>
              globalLock
                .withGlobalLock(
                  s"Updating traffic limit for validator ${validatorTraffic_.validator}"
                )(
                  participantAdminConnection
                    .ensureTrafficControlState(
                      domainId,
                      validatorParticipant.participantId,
                      newExtraTrafficLimit,
                      svParticipantId.uid.namespace.fingerprint,
                    )
                )
                .map(_ =>
                  TaskSuccess(
                    s"Updated extra traffic limit for validator ${validatorTraffic_.validator} " +
                      s"hosted on ${validatorParticipant} to ${newExtraTrafficLimit}"
                  )
                )
            )
        else {
          Future(TaskSuccess("Skipping since traffic limit is already up to date"))
        }
    } yield taskOutcome
  }

}

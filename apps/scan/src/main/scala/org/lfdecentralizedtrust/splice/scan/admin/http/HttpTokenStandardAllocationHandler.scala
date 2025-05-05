// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletallocation
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1.AnyContract
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.util.{AmuletConfigSchedule, Contract}
import org.lfdecentralizedtrust.tokenstandard.allocation.v1
import org.lfdecentralizedtrust.tokenstandard.allocation.v1.definitions.GetChoiceContextRequest
import org.lfdecentralizedtrust.tokenstandard.allocation.v1.{Resource, definitions}

import java.time.ZoneOffset
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class HttpTokenStandardAllocationHandler(
    store: ScanStore,
    clock: Clock,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v1.Handler[TraceContext]
    with Spanning
    with NamedLogging {

  private val workflowId = this.getClass.getSimpleName

  override def getAllocationTransferContext(
      respond: Resource.GetAllocationTransferContextResponse.type
  )(
      allocationId: String,
      body: GetChoiceContextRequest,
  )(extracted: TraceContext): Future[Resource.GetAllocationTransferContextResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getAllocationTransferContext") { _ => _ =>
      for {
        amuletAlloc <- store.multiDomainAcsStore
          .lookupContractById(amuletallocation.AmuletAllocation.COMPANION)(
            new amuletallocation.AmuletAllocation.ContractId(allocationId)
          )
          .map(
            _.getOrElse(
              throw io.grpc.Status.NOT_FOUND
                .withDescription(s"AmuletAllocation '$allocationId' not found.")
                .asRuntimeException()
            )
          )
        lockedAmuletId = amuletAlloc.contract.payload.lockedAmulet
        lockedAmulet <- store.multiDomainAcsStore
          .lookupContractById(amulet.LockedAmulet.COMPANION)(
            lockedAmuletId
          )
          .map(
            _.getOrElse(
              throw io.grpc.Status.NOT_FOUND
                .withDescription(
                  s"LockedAmulet '$lockedAmuletId' for AmuletAllocation '$allocationId' not found."
                )
                .asRuntimeException()
            )
          )
        amuletRules <- store.getAmuletRules()
        newestOpenRound <- store
          .lookupLatestUsableOpenMiningRound(CantonTimestamp.now())
          .map(
            _.getOrElse(
              throw io.grpc.Status.NOT_FOUND
                .withDescription(s"No open usable OpenMiningRound found.")
                .asRuntimeException()
            )
          )
      } yield {
        val activeSynchronizerId =
          AmuletConfigSchedule(amuletRules.payload.configSchedule)
            .getConfigAsOf(clock.now)
            .decentralizedSynchronizer
            .activeSynchronizer
        v1.Resource.GetAllocationTransferContextResponseOK(
          definitions.ChoiceContext(
            choiceContextData = io.circe.parser
              .parse(
                new metadatav1.ChoiceContext(
                  Map(
                    // TODO(#18575): also retrieve and serve featured app right
                    "amulet-rules" -> amuletRules.contractId.contractId,
                    "open-round" -> newestOpenRound.contractId.contractId,
                  ).map[String, metadatav1.AnyValue] { case (k, v) =>
                    k -> new metadatav1.anyvalue.AV_ContractId(new AnyContract.ContractId(v))
                  }.asJava
                ).toJson
              )
              .getOrElse(
                throw new IllegalArgumentException("Just-serialized JSON cannot be parsed.")
              ),
            disclosedContracts = Vector(
              toTokenStandardDisclosedContract(lockedAmulet.contract, activeSynchronizerId),
              toTokenStandardDisclosedContract(amuletRules, activeSynchronizerId),
              toTokenStandardDisclosedContract(newestOpenRound.contract, activeSynchronizerId),
            ),
          )
        )
      }
    }
  }

  // The HTTP definition of the standard differs from any other
  private def toTokenStandardDisclosedContract[TCId, T](
      contract: Contract[TCId, T],
      synchronizerId: String,
  )(implicit elc: ErrorLoggingContext): definitions.DisclosedContract = {
    val asHttp = contract.toHttp
    definitions.DisclosedContract(
      templateId = asHttp.templateId,
      contractId = asHttp.contractId,
      createdEventBlob = asHttp.createdEventBlob,
      synchronizerId = synchronizerId,
      debugPackageName =
        DarResources.lookupPackageId(contract.identifier.getPackageId).map(_.metadata.name),
      debugPayload = Some(asHttp.payload),
      debugCreatedAt = Some(contract.createdAt.atOffset(ZoneOffset.UTC)),
    )
  }

  // TODO(#18576): implement cancel and withdraw
  override def getAllocationCancelContext(
      respond: Resource.GetAllocationCancelContextResponse.type
  )(allocationId: String, body: Option[GetChoiceContextRequest])(
      extracted: TraceContext
  ): Future[Resource.GetAllocationCancelContextResponse] = ???

  override def getAllocationWithdrawContext(
      respond: Resource.GetAllocationWithdrawContextResponse.type
  )(allocationId: String, body: GetChoiceContextRequest)(
      extracted: TraceContext
  ): Future[Resource.GetAllocationWithdrawContextResponse] = ???
}

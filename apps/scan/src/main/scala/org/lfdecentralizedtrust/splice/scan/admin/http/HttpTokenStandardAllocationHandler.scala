// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletallocation
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.scan.util
import org.lfdecentralizedtrust.splice.util.Contract
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

  import HttpTokenStandardAllocationHandler.*

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
        choiceContext <- getAllocationChoiceContext(
          allocationId,
          requireLockedAmulet = true,
          canBeFeatured = true,
          excludeDebugFields = body.excludeDebugFields.getOrElse(false),
        )
      } yield v1.Resource.GetAllocationTransferContextResponseOK(choiceContext)
    }
  }

  override def getAllocationCancelContext(
      respond: Resource.GetAllocationCancelContextResponse.type
  )(allocationId: String, body: GetChoiceContextRequest)(
      extracted: TraceContext
  ): Future[Resource.GetAllocationCancelContextResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getAllocationCancelContext") { _ => _ =>
      for {
        choiceContext <- getAllocationChoiceContext(
          allocationId,
          requireLockedAmulet = false,
          canBeFeatured = false,
          excludeDebugFields = body.excludeDebugFields.getOrElse(false),
        )
      } yield v1.Resource.GetAllocationCancelContextResponseOK(choiceContext)
    }
  }

  override def getAllocationWithdrawContext(
      respond: Resource.GetAllocationWithdrawContextResponse.type
  )(allocationId: String, body: GetChoiceContextRequest)(
      extracted: TraceContext
  ): Future[Resource.GetAllocationWithdrawContextResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getAllocationWithdrawContext") { _ => _ =>
      for {
        choiceContext <- getAllocationChoiceContext(
          allocationId,
          requireLockedAmulet = false,
          canBeFeatured = false,
          excludeDebugFields = body.excludeDebugFields.getOrElse(false),
        )
      } yield v1.Resource.GetAllocationWithdrawContextResponseOK(choiceContext)
    }
  }

  /** Generic method to fetch choice contexts for all choices on an allocation */
  private def getAllocationChoiceContext(
      allocationId: String,
      requireLockedAmulet: Boolean,
      canBeFeatured: Boolean,
      excludeDebugFields: Boolean,
  )(implicit
      tc: TraceContext
  ): Future[definitions.ChoiceContext] = {
    for {
      amuletAlloc <- store.multiDomainAcsStore
        .lookupContractById(amuletallocation.AmuletAllocation.COMPANION)(
          new amuletallocation.AmuletAllocation.ContractId(
            allocationId
          )
        )
        .map(
          _.getOrElse(
            throw io.grpc.Status.NOT_FOUND
              .withDescription(s"AmuletAllocation '$allocationId' not found.")
              .asRuntimeException()
          )
        )
      context <- util.ChoiceContextBuilder.getTwoStepTransferContext[
        definitions.DisclosedContract,
        definitions.ChoiceContext,
        ChoiceContextBuilder,
      ](
        s"AmuletAllocation '$allocationId'",
        amuletAlloc.contract.payload.lockedAmulet,
        amuletAlloc.contract.payload.allocation.settlement.settleBefore,
        requireLockedAmulet,
        Option.when(canBeFeatured)(
          PartyId.tryFromProtoPrimitive(amuletAlloc.payload.allocation.settlement.executor)
        ),
        store,
        clock,
        new ChoiceContextBuilder(_, excludeDebugFields),
      )
    } yield context
  }
}

object HttpTokenStandardAllocationHandler {

  final class ChoiceContextBuilder(activeSynchronizerId: String, excludeDebugFields: Boolean)(
      implicit elc: ErrorLoggingContext
  ) extends util.ChoiceContextBuilder[
        definitions.DisclosedContract,
        definitions.ChoiceContext,
        ChoiceContextBuilder,
      ](activeSynchronizerId, excludeDebugFields) {

    def build(): definitions.ChoiceContext = definitions.ChoiceContext(
      choiceContextData = io.circe.parser
        .parse(
          new metadatav1.ChoiceContext(contextEntries.asJava).toJson
        )
        .getOrElse(
          throw new RuntimeException("Just-serialized JSON cannot be parsed.")
        ),
      disclosedContracts = disclosedContracts.toVector,
    )

    // The HTTP definition of the standard differs from any other
    override protected def toTokenStandardDisclosedContract[TCId, T](
        contract: Contract[TCId, T],
        synchronizerId: String,
        excludeDebugFields: Boolean,
    ): definitions.DisclosedContract = {
      val asHttp = contract.toHttp
      definitions.DisclosedContract(
        templateId = asHttp.templateId,
        contractId = asHttp.contractId,
        createdEventBlob = asHttp.createdEventBlob,
        synchronizerId = synchronizerId,
        debugPackageName =
          if (excludeDebugFields) None
          else
            DarResources
              .lookupPackageId(contract.identifier.getPackageId)
              .map(_.metadata.name),
        debugPayload = if (excludeDebugFields) None else Some(asHttp.payload),
        debugCreatedAt =
          if (excludeDebugFields) None
          else Some(contract.createdAt.atOffset(ZoneOffset.UTC)),
      )
    }
  }
}

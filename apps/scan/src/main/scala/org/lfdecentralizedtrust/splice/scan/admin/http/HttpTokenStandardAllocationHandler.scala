// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.LockedAmulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletallocation
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletallocationv2
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationv2
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.scan.util
import org.lfdecentralizedtrust.splice.store.ChoiceContextContractFetcher
import org.lfdecentralizedtrust.splice.util.{AmuletConfigSchedule, Contract, DarResourcesUtil}
import org.lfdecentralizedtrust.tokenstandard.allocation.{v1, v2}

import java.time.ZoneOffset
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.{Failure, Success, Try}

class HttpTokenStandardAllocationHandler(
    store: ScanStore,
    contractFetcher: ChoiceContextContractFetcher,
    clock: Clock,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v1.Handler[TraceContext]
    with v2.Handler[TraceContext]
    with Spanning
    with NamedLogging {

  import HttpTokenStandardAllocationHandler.*

  private val workflowId = this.getClass.getSimpleName

  override def getAllocationTransferContext(
      respond: v1.Resource.GetAllocationTransferContextResponse.type
  )(
      allocationId: String,
      body: v1.definitions.GetChoiceContextRequest,
  )(extracted: TraceContext): Future[v1.Resource.GetAllocationTransferContextResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getAllocationTransferContext") { _ => _ =>
      for {
        choiceContext <- getAllocationChoiceContext[
          v1.definitions.DisclosedContract,
          v1.definitions.ChoiceContext,
          V1ChoiceContextBuilder,
        ](
          allocationId,
          requireLockedAmulet = true,
          canBeFeatured = true,
          synchronizerId =>
            new V1ChoiceContextBuilder(synchronizerId, body.excludeDebugFields.getOrElse(false)),
        )
      } yield v1.Resource.GetAllocationTransferContextResponseOK(choiceContext)
    }
  }

  override def getAllocationCancelContext(
      respond: v1.Resource.GetAllocationCancelContextResponse.type
  )(allocationId: String, body: v1.definitions.GetChoiceContextRequest)(
      extracted: TraceContext
  ): Future[v1.Resource.GetAllocationCancelContextResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getAllocationCancelContext") { _ => _ =>
      for {
        choiceContext <- getAllocationChoiceContext[
          v1.definitions.DisclosedContract,
          v1.definitions.ChoiceContext,
          V1ChoiceContextBuilder,
        ](
          allocationId,
          requireLockedAmulet = false,
          canBeFeatured = false,
          synchronizerId =>
            new V1ChoiceContextBuilder(synchronizerId, body.excludeDebugFields.getOrElse(false)),
        )
      } yield v1.Resource.GetAllocationCancelContextResponseOK(choiceContext)
    }
  }

  override def getAllocationWithdrawContext(
      respond: v1.Resource.GetAllocationWithdrawContextResponse.type
  )(allocationId: String, body: v1.definitions.GetChoiceContextRequest)(
      extracted: TraceContext
  ): Future[v1.Resource.GetAllocationWithdrawContextResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getAllocationWithdrawContext") { _ => _ =>
      for {
        choiceContext <- getAllocationChoiceContext[
          v1.definitions.DisclosedContract,
          v1.definitions.ChoiceContext,
          V1ChoiceContextBuilder,
        ](
          allocationId,
          requireLockedAmulet = false,
          canBeFeatured = false,
          synchronizerId =>
            new V1ChoiceContextBuilder(synchronizerId, body.excludeDebugFields.getOrElse(false)),
        )
      } yield v1.Resource.GetAllocationWithdrawContextResponseOK(choiceContext)
    }
  }

  override def getSettlementFactory(respond: v2.Resource.GetSettlementFactoryResponse.type)(
      body: v2.definitions.GetFactoryRequest
  )(extracted: TraceContext): Future[v2.Resource.GetSettlementFactoryResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getSettlementFactory") { _ => _ =>
      for {
        settleBatch <- Try(
          allocationv2.SettlementFactory_SettleBatch.fromJson(body.choiceArguments.noSpaces)
        ) match {
          case Success(settleBatch) => Future.successful(settleBatch)
          case Failure(err) =>
            Future.failed(
              io.grpc.Status.INVALID_ARGUMENT
                .withDescription(
                  s"Field `choiceArguments` does not contain a valid `${allocationv2.SettlementFactory.CHOICE_SettlementFactory_SettleBatch.name}`: $err"
                )
                .asRuntimeException()
            )
        }
        choiceContextBuilder <- getAmuletRulesTransferContext(
          body.excludeDebugFields.getOrElse(false)
        )
        externalPartyAmuletRules <- store.getExternalPartyAmuletRules()
        // TODO: don't do N queries but just one with =ANY()
        allocations <- Future.traverse(settleBatch.allocationCids.asScala) { allocationCid =>
          contractFetcher.lookupContractById(amuletallocationv2.AmuletAllocationV2.COMPANION)(
            allocationCid
          )
        }
        lockedAmulets <- Future.traverse(
          allocations.flatten.flatMap(_.payload.lockedAmulet.toScala)
        ) { lockedAmuletCid =>
          contractFetcher.lookupContractById(LockedAmulet.COMPANION)(lockedAmuletCid)
        }
      } yield v2.Resource.GetSettlementFactoryResponseOK(
        v2.definitions
          .FactoryWithChoiceContext(
            externalPartyAmuletRules.contractId.contractId,
            choiceContextBuilder
              .disclose(externalPartyAmuletRules.contract)
              .discloseAll(lockedAmulets.flatten)
              .build(),
          )
      )
    }
  }

  private def getAmuletRulesTransferContext(excludeDebugFields: Boolean)(implicit
      tc: TraceContext
  ): Future[V2ChoiceContextBuilder] = {
    val now = clock.now
    for {
      amuletRules <- store.getAmuletRules()
      externalPartyConfigStateO <- store.lookupLatestExternalPartyConfigState()
    } yield {
      val choiceContextBuilder = new V2ChoiceContextBuilder(
        AmuletConfigSchedule(amuletRules.payload.configSchedule)
          .getConfigAsOf(now)
          .decentralizedSynchronizer
          .activeSynchronizer,
        excludeDebugFields,
      )
      choiceContextBuilder
        .addOptionalContract("external-party-config-state" -> externalPartyConfigStateO)
    }
  }

  override def getAllocationCancelContext(
      respond: v2.Resource.GetAllocationCancelContextResponse.type
  )(allocationId: String, body: v2.definitions.GetChoiceContextRequest)(
      extracted: TraceContext
  ): Future[v2.Resource.GetAllocationCancelContextResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getAllocationV2CancelContext") { _ => _ =>
      for {
        choiceContext <- getAllocationChoiceContext[
          v2.definitions.DisclosedContract,
          v2.definitions.ChoiceContext,
          V2ChoiceContextBuilder,
        ](
          allocationId,
          requireLockedAmulet = false,
          canBeFeatured = false,
          synchronizerId =>
            new V2ChoiceContextBuilder(synchronizerId, body.excludeDebugFields.getOrElse(false)),
        )
      } yield v2.Resource.GetAllocationCancelContextResponse(choiceContext)
    }
  }

  override def getAllocationWithdrawContext(
      respond: v2.Resource.GetAllocationWithdrawContextResponse.type
  )(allocationId: String, body: v2.definitions.GetChoiceContextRequest)(
      extracted: TraceContext
  ): Future[v2.Resource.GetAllocationWithdrawContextResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getAllocationV2WithdrawContext") { _ => _ =>
      for {
        choiceContext <- getAllocationChoiceContext[
          v2.definitions.DisclosedContract,
          v2.definitions.ChoiceContext,
          V2ChoiceContextBuilder,
        ](
          allocationId,
          requireLockedAmulet = false,
          canBeFeatured = false,
          synchronizerId =>
            new V2ChoiceContextBuilder(synchronizerId, body.excludeDebugFields.getOrElse(false)),
        )
      } yield v2.Resource.GetAllocationWithdrawContextResponse(choiceContext)
    }
  }

  /** Generic method to fetch choice contexts for all choices on an allocation */
  private def getAllocationChoiceContext[
      DisclosedContract,
      ChoiceContext,
      Builder <: util.ChoiceContextBuilder[
        DisclosedContract,
        ChoiceContext,
        Builder,
      ],
  ](
      allocationId: String,
      requireLockedAmulet: Boolean,
      canBeFeatured: Boolean,
      newBuilder: String => Builder,
  )(implicit
      tc: TraceContext
  ): Future[ChoiceContext] = {
    for {
      amuletAlloc <- contractFetcher
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
        DisclosedContract,
        ChoiceContext,
        Builder,
      ](
        s"AmuletAllocation '$allocationId'",
        amuletAlloc.payload.lockedAmulet,
        amuletAlloc.payload.allocation.settlement.settleBefore,
        requireLockedAmulet,
        Option.when(canBeFeatured)(
          PartyId.tryFromProtoPrimitive(amuletAlloc.payload.allocation.settlement.executor)
        ),
        store,
        contractFetcher,
        clock,
        activeSynchronizerId => newBuilder(activeSynchronizerId),
      )
    } yield context
  }
}

object HttpTokenStandardAllocationHandler {

  final class V1ChoiceContextBuilder(activeSynchronizerId: String, excludeDebugFields: Boolean)(
      implicit elc: ErrorLoggingContext
  ) extends util.ChoiceContextBuilder[
        v1.definitions.DisclosedContract,
        v1.definitions.ChoiceContext,
        V1ChoiceContextBuilder,
      ](activeSynchronizerId, excludeDebugFields) {

    def build(): v1.definitions.ChoiceContext = v1.definitions.ChoiceContext(
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
    ): v1.definitions.DisclosedContract = {
      val asHttp = contract.toHttp
      v1.definitions.DisclosedContract(
        templateId = asHttp.templateId,
        contractId = asHttp.contractId,
        createdEventBlob = asHttp.createdEventBlob,
        synchronizerId = synchronizerId,
        debugPackageName =
          if (excludeDebugFields) None
          else
            DarResourcesUtil
              .lookupPackageId(contract.identifier.getPackageId)
              .map(_.metadata.name),
        debugPayload = if (excludeDebugFields) None else Some(asHttp.payload),
        debugCreatedAt =
          if (excludeDebugFields) None
          else Some(contract.createdAt.atOffset(ZoneOffset.UTC)),
      )
    }
  }

  final class V2ChoiceContextBuilder(activeSynchronizerId: String, excludeDebugFields: Boolean)(
      implicit elc: ErrorLoggingContext
  ) extends util.ChoiceContextBuilder[
        v2.definitions.DisclosedContract,
        v2.definitions.ChoiceContext,
        V2ChoiceContextBuilder,
      ](activeSynchronizerId, excludeDebugFields) {

    def build(): v2.definitions.ChoiceContext = v2.definitions.ChoiceContext(
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
    ): v2.definitions.DisclosedContract = {
      val asHttp = contract.toHttp
      v2.definitions.DisclosedContract(
        templateId = asHttp.templateId,
        contractId = asHttp.contractId,
        createdEventBlob = asHttp.createdEventBlob,
        synchronizerId = synchronizerId,
        debugPackageName =
          if (excludeDebugFields) None
          else
            DarResourcesUtil
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

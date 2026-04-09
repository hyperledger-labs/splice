// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.scan.util
import org.lfdecentralizedtrust.splice.util.{AmuletConfigSchedule, Contract, DarResourcesUtil}
import org.lfdecentralizedtrust.tokenstandard.allocationinstruction.v1
import org.lfdecentralizedtrust.tokenstandard.allocationinstruction.v2
import org.lfdecentralizedtrust.tokenstandard.allocationinstruction.v2

import java.time.ZoneOffset
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class HttpTokenStandardAllocationInstructionHandler(
    store: ScanStore,
    clock: Clock,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v1.Handler[TraceContext]
    with v2.Handler[TraceContext]
    with Spanning
    with NamedLogging {

  import HttpTokenStandardAllocationInstructionHandler.*

  private val workflowId = this.getClass.getSimpleName

  override def getAllocationFactory(respond: v1.Resource.GetAllocationFactoryResponse.type)(
      body: v1.definitions.GetFactoryRequest
  )(extracted: TraceContext): Future[v1.Resource.GetAllocationFactoryResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getAllocationFactory") { _ => _ =>
      val now = clock.now
      for {
        externalPartyAmuletRules <- store.getExternalPartyAmuletRules()
        amuletRules <- store.getAmuletRules()
        newestOpenRound <- store
          .lookupLatestUsableOpenMiningRound(now)
          .map(
            _.getOrElse(
              throw io.grpc.Status.NOT_FOUND
                .withDescription(s"No open usable OpenMiningRound found.")
                .asRuntimeException()
            )
          )
        // TODO(#4950) Don't include amulet rules and newest open round when informees all have vetted the newest version.
        externalPartyConfigStateO <- store.lookupLatestExternalPartyConfigState()
      } yield {
        val activeSynchronizerId =
          AmuletConfigSchedule(amuletRules.payload.configSchedule)
            .getConfigAsOf(now)
            .decentralizedSynchronizer
            .activeSynchronizer
        val excludeDebugFields = body.excludeDebugFields.getOrElse(false)
        val choiceContextBuilder = new V1ChoiceContextBuilder(
          activeSynchronizerId,
          excludeDebugFields,
        )
        v1.Resource.GetAllocationFactoryResponseOK(
          v1.definitions.FactoryWithChoiceContext(
            externalPartyAmuletRules.contractId.contractId,
            choiceContextBuilder
              .addContracts(
                "amulet-rules" -> amuletRules,
                "open-round" -> newestOpenRound.contract,
              )
              .addOptionalContract("external-party-config-state" -> externalPartyConfigStateO)
              .disclose(externalPartyAmuletRules.contract)
              .build(),
          )
        )
      }
    }
  }

  override def getAllocationFactory(respond: v2.Resource.GetAllocationFactoryResponse.type)(
      body: v2.definitions.GetFactoryRequest
  )(extracted: TraceContext): Future[v2.Resource.GetAllocationFactoryResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getAllocationFactory") { _ => _ =>
      val now = clock.now
      for {
        externalPartyAmuletRules <- store.getExternalPartyAmuletRules()
        amuletRules <- store.getAmuletRules()
        externalPartyConfigStateO <- store.lookupLatestExternalPartyConfigState()
      } yield {
        val activeSynchronizerId =
          AmuletConfigSchedule(amuletRules.payload.configSchedule)
            .getConfigAsOf(now)
            .decentralizedSynchronizer
            .activeSynchronizer
        val excludeDebugFields = body.excludeDebugFields.getOrElse(false)
        val choiceContextBuilder = new V2ChoiceContextBuilder(
          activeSynchronizerId,
          excludeDebugFields,
        )
        v2.Resource.GetAllocationFactoryResponseOK(
          v2.definitions.FactoryWithChoiceContext(
            externalPartyAmuletRules.contractId.contractId,
            choiceContextBuilder
              .addOptionalContract("external-party-config-state" -> externalPartyConfigStateO)
              .disclose(externalPartyAmuletRules.contract)
              .build(),
          )
        )
      }
    }
  }
}

object HttpTokenStandardAllocationInstructionHandler {
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

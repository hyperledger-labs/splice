// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.scan.util
import org.lfdecentralizedtrust.splice.util.{AmuletConfigSchedule, Contract}
import org.lfdecentralizedtrust.tokenstandard.allocationinstruction.v1
import org.lfdecentralizedtrust.tokenstandard.allocationinstruction.v1.definitions

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
    with Spanning
    with NamedLogging {

  import HttpTokenStandardAllocationInstructionHandler.*

  private val workflowId = this.getClass.getSimpleName

  override def getAllocationFactory(respond: v1.Resource.GetAllocationFactoryResponse.type)(
      body: definitions.GetFactoryRequest
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
        // TODO(#3630) Don't include amulet rules and newest open round when informees all have vetted the newest version.
        externalPartyConfigStateO <- store.lookupLatestExternalPartyConfigState()
      } yield {
        val activeSynchronizerId =
          AmuletConfigSchedule(amuletRules.payload.configSchedule)
            .getConfigAsOf(now)
            .decentralizedSynchronizer
            .activeSynchronizer
        val excludeDebugFields = body.excludeDebugFields.getOrElse(false)
        val choiceContextBuilder = new ChoiceContextBuilder(
          activeSynchronizerId,
          excludeDebugFields,
        )
        v1.Resource.GetAllocationFactoryResponseOK(
          definitions.FactoryWithChoiceContext(
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
}

object HttpTokenStandardAllocationInstructionHandler {
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

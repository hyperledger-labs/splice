// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v0
import v0.definitions
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.util.{AmuletConfigSchedule, Contract}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1.AnyContract

import java.time.ZoneOffset
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.jdk.CollectionConverters.*

class HttpTokenStandardTransferInstructionHandler(
    store: ScanStore,
    clock: Clock,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.Handler[TraceContext]
    with Spanning
    with NamedLogging {

  private val workflowId = this.getClass.getSimpleName

  override def getTransferFactory(respond: v0.Resource.GetTransferFactoryResponse.type)(
      body: definitions.GetFactoryRequest
  )(extracted: TraceContext): Future[v0.Resource.GetTransferFactoryResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getTransferFactory") { _ => _ =>
      for {
        transfer <- Try(
          transferinstructionv1.TransferFactory_Transfer.fromJson(body.choiceArguments.noSpaces)
        ) match {
          case Success(transfer) => Future.successful(transfer)
          case Failure(_) =>
            Future.failed(
              io.grpc.Status.INVALID_ARGUMENT
                .withDescription(
                  "Field `choiceArguments` does not contain a valid `TransferFactory_Transfer`."
                )
                .asRuntimeException()
            )
        }
        receiver = PartyId.tryFromProtoPrimitive(transfer.transfer.receiver)
        externalPartyAmuletRules <- store.getExternalPartyAmuletRules()
        amuletRules <- store.getAmuletRules()
        transferPreapproval <- store
          .lookupTransferPreapprovalByParty(receiver)
          .map(
            _.getOrElse(
              throw io.grpc.Status.NOT_FOUND
                .withDescription(s"No TransferPreapproval found for party: $receiver")
                .asRuntimeException()
            )
          )
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
        v0.Resource.GetTransferFactoryResponseOK(
          definitions.FactoryWithChoiceContext(
            externalPartyAmuletRules.contractId.contractId,
            definitions.ChoiceContext(
              choiceContextData = Some(
                io.circe.parser
                  .parse(
                    new metadatav1.ChoiceContext(
                      Map(
                        "amulet-rules" -> amuletRules.contractId.contractId,
                        "open-round" -> newestOpenRound.contractId.contractId,
                        "transfer-preapproval" -> transferPreapproval.contractId.contractId,
                      ).map[String, metadatav1.AnyValue] { case (k, v) =>
                        k -> new metadatav1.anyvalue.AV_ContractId(new AnyContract.ContractId(v))
                      }.asJava
                    ).toJson
                  )
                  .getOrElse(
                    throw new IllegalArgumentException("Just-serialized JSON cannot be parsed.")
                  )
              ),
              disclosedContracts = Vector(
                toTokenStandardDisclosedContract(
                  externalPartyAmuletRules.contract,
                  activeSynchronizerId,
                ),
                toTokenStandardDisclosedContract(amuletRules, activeSynchronizerId),
                toTokenStandardDisclosedContract(newestOpenRound.contract, activeSynchronizerId),
                toTokenStandardDisclosedContract(transferPreapproval.contract, activeSynchronizerId),
              ),
            ),
            validUntil = newestOpenRound.payload.targetClosesAt.atOffset(
              ZoneOffset.UTC
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

}

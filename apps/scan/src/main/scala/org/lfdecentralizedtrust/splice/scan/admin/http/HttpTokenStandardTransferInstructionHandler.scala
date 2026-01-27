// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  metadatav1,
  transferinstructionv1,
}
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.scan.util
import org.lfdecentralizedtrust.splice.store.ChoiceContextContractFetcher
import org.lfdecentralizedtrust.splice.util.{AmuletConfigSchedule, Contract}
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v1
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v1.{Resource, definitions}

import java.time.ZoneOffset
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

class HttpTokenStandardTransferInstructionHandler(
    store: ScanStore,
    contractFetcher: ChoiceContextContractFetcher,
    clock: Clock,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v1.Handler[TraceContext]
    with Spanning
    with NamedLogging {

  import org.lfdecentralizedtrust.splice.scan.admin.http.HttpTokenStandardTransferInstructionHandler.*

  private val workflowId = this.getClass.getSimpleName

  override def getTransferFactory(respond: v1.Resource.GetTransferFactoryResponse.type)(
      body: definitions.GetFactoryRequest
  )(extracted: TraceContext): Future[v1.Resource.GetTransferFactoryResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getTransferFactory") { _ => _ =>
      for {
        transferInstr <- Try(
          transferinstructionv1.TransferFactory_Transfer.fromJson(body.choiceArguments.noSpaces)
        ) match {
          case Success(transfer) => Future.successful(transfer)
          case Failure(err) =>
            Future.failed(
              io.grpc.Status.INVALID_ARGUMENT
                .withDescription(
                  s"Field `choiceArguments` does not contain a valid `TransferFactory_Transfer`: $err"
                )
                .asRuntimeException()
            )
        }
        (choiceContextBuilder, newestOpenRound) <- getAmuletRulesTransferContext(
          body.excludeDebugFields.getOrElse(false)
        )
        externalPartyAmuletRules <- store.getExternalPartyAmuletRules()
        // pre-approval and featured app rights are only provided if they exist and are required
        receiver = PartyId.tryFromProtoPrimitive(transferInstr.transfer.receiver)
        isSelfTransfer = transferInstr.transfer.receiver == transferInstr.transfer.sender
        optTransferPreapproval <-
          if (isSelfTransfer) Future.successful(None) // no pre-approval required for self-transfers
          else
            store.lookupTransferPreapprovalByParty(receiver)
        optFeaturedAppRight <- optTransferPreapproval match {
          case None => Future.successful(None)
          case Some(preapproval) =>
            store.lookupFeaturedAppRight(
              PartyId.tryFromProtoPrimitive(preapproval.payload.provider)
            )
        }
      } yield {
        val kind =
          if (isSelfTransfer) definitions.TransferFactoryWithChoiceContext.TransferKind.Self
          else if (optTransferPreapproval.isDefined)
            definitions.TransferFactoryWithChoiceContext.TransferKind.Direct
          else definitions.TransferFactoryWithChoiceContext.TransferKind.Offer
        v1.Resource.GetTransferFactoryResponseOK(
          definitions.TransferFactoryWithChoiceContext(
            externalPartyAmuletRules.contractId.contractId,
            kind,
            choiceContext = choiceContextBuilder
              .addOptionalContracts(
                "featured-app-right" -> optFeaturedAppRight,
                "transfer-preapproval" -> optTransferPreapproval,
              )
              .disclose(externalPartyAmuletRules.contract)
              .build(),
          )
        )
      }
    }
  }

  override def getTransferInstructionAcceptContext(
      respond: Resource.GetTransferInstructionAcceptContextResponse.type
  )(transferInstructionId: String, body: definitions.GetChoiceContextRequest)(
      extracted: TraceContext
  ): Future[Resource.GetTransferInstructionAcceptContextResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getTransferInstructionAcceptContext") { _ => _ =>
      for {
        choiceContext <- getTransferInstructionChoiceContext(
          transferInstructionId,
          requireLockedAmulet = true,
          excludeDebugFields = body.excludeDebugFields.getOrElse(false),
        )
      } yield {
        v1.Resource.GetTransferInstructionAcceptContextResponseOK(choiceContext)
      }
    }
  }

  override def getTransferInstructionRejectContext(
      respond: Resource.GetTransferInstructionRejectContextResponse.type
  )(transferInstructionId: String, body: definitions.GetChoiceContextRequest)(
      extracted: TraceContext
  ): Future[Resource.GetTransferInstructionRejectContextResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getTransferInstructionRejectContext") { _ => _ =>
      for {
        choiceContext <- getTransferInstructionChoiceContext(
          transferInstructionId,
          requireLockedAmulet = false,
          excludeDebugFields = body.excludeDebugFields.getOrElse(false),
        )
      } yield {
        v1.Resource.GetTransferInstructionRejectContextResponseOK(choiceContext)
      }
    }
  }

  override def getTransferInstructionWithdrawContext(
      respond: Resource.GetTransferInstructionWithdrawContextResponse.type
  )(transferInstructionId: String, body: definitions.GetChoiceContextRequest)(
      extracted: TraceContext
  ): Future[Resource.GetTransferInstructionWithdrawContextResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getTransferInstructionWithdrawContext") { _ => _ =>
      for {
        choiceContext <- getTransferInstructionChoiceContext(
          transferInstructionId,
          requireLockedAmulet = false,
          excludeDebugFields = body.excludeDebugFields.getOrElse(false),
        )
      } yield {
        v1.Resource.GetTransferInstructionWithdrawContextResponseOK(choiceContext)
      }
    }
  }

  private def getAmuletRulesTransferContext(excludeDebugFields: Boolean)(implicit
      tc: TraceContext
  ): Future[(ChoiceContextBuilder, splice.round.OpenMiningRound)] = {
    val now = clock.now
    for {
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
    } yield {
      val choiceContextBuilder = new ChoiceContextBuilder(
        AmuletConfigSchedule(amuletRules.payload.configSchedule)
          .getConfigAsOf(now)
          .decentralizedSynchronizer
          .activeSynchronizer,
        excludeDebugFields,
      )

      (
        choiceContextBuilder.addContracts(
          "amulet-rules" -> amuletRules,
          "open-round" -> newestOpenRound.contract,
        ),
        newestOpenRound.contract.payload,
      )
    }
  }

  /** Generic method to fetch choice contexts for all choices on a transfer instruction */
  private def getTransferInstructionChoiceContext(
      transferInstructionId: String,
      requireLockedAmulet: Boolean,
      excludeDebugFields: Boolean,
  )(implicit
      tc: TraceContext
  ): Future[definitions.ChoiceContext] = {
    for {
      amuletInstr <- contractFetcher
        .lookupContractById(
          splice.amulettransferinstruction.AmuletTransferInstruction.COMPANION
        )(
          new splice.amulettransferinstruction.AmuletTransferInstruction.ContractId(
            transferInstructionId
          )
        )
        .map(
          _.getOrElse(
            throw io.grpc.Status.NOT_FOUND
              .withDescription(s"AmuletTransferInstruction '$transferInstructionId' not found.")
              .asRuntimeException()
          )
        )
      context <- util.ChoiceContextBuilder.getTwoStepTransferContext[
        definitions.DisclosedContract,
        definitions.ChoiceContext,
        ChoiceContextBuilder,
      ](
        s"AmuletTransferInstruction '$transferInstructionId'",
        amuletInstr.payload.lockedAmulet,
        amuletInstr.payload.transfer.executeBefore,
        requireLockedAmulet,
        None,
        store,
        contractFetcher,
        clock,
        new ChoiceContextBuilder(_, excludeDebugFields),
      )
    } yield context
  }

}

object HttpTokenStandardTransferInstructionHandler {

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

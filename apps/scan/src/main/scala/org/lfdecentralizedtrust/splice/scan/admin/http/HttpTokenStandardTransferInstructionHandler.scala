// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  metadatav1,
  transferinstructionv1,
  transferinstructionv2,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyconfigstate.ExternalPartyConfigState
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.scan.util
import org.lfdecentralizedtrust.splice.store.{ChoiceContextContractFetcher, MiningRoundsStore}
import org.lfdecentralizedtrust.splice.util.{
  AmuletConfigSchedule,
  AssignedContract,
  Contract,
  ContractWithState,
  DarResourcesUtil,
  TokenStandardAccount,
}
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v1
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v2

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
    with v2.Handler[TraceContext]
    with Spanning
    with NamedLogging {

  import org.lfdecentralizedtrust.splice.scan.admin.http.HttpTokenStandardTransferInstructionHandler.*

  private val workflowId = this.getClass.getSimpleName

  override def getTransferFactory(respond: v1.Resource.GetTransferFactoryResponse.type)(
      body: v1.definitions.GetFactoryRequest
  )(extracted: TraceContext): Future[v1.Resource.GetTransferFactoryResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getTransferFactoryV1") { _ => _ =>
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
        choiceContextBuilder <- getAmuletRulesTransferContextV1(
          body.excludeDebugFields.getOrElse(false)
        )
        dsoRules <- store.getDsoRules()
        result <- buildTransferFactory(
          PartyId.tryFromProtoPrimitive(transferInstr.transfer.sender),
          PartyId.tryFromProtoPrimitive(transferInstr.transfer.receiver),
          body.excludeDebugFields.getOrElse(false),
        )((optTransferPreapproval, optFeaturedAppRight, externalPartyAmuletRules) => {
          val isSelfTransfer = transferInstr.transfer.receiver == transferInstr.transfer.sender
          val kind =
            if (isSelfTransfer) v1.definitions.TransferFactoryWithChoiceContext.TransferKind.Self
            else if (optTransferPreapproval.isDefined)
              v1.definitions.TransferFactoryWithChoiceContext.TransferKind.Direct
            else v1.definitions.TransferFactoryWithChoiceContext.TransferKind.Offer
          v1.Resource.GetTransferFactoryResponseOK(
            v1.definitions.TransferFactoryWithChoiceContext(
              externalPartyAmuletRules.contractId.contractId,
              kind,
              choiceContext = choiceContextBuilder
                .addOptionalContracts(
                  "transfer-preapproval" -> optTransferPreapproval
                )
                .addFeaturedAppRight(clock, dsoRules.payload, optFeaturedAppRight)
                .disclose(externalPartyAmuletRules.contract)
                .build(),
            )
          )
        })
      } yield result
    }
  }

  override def getTransferInstructionAcceptContext(
      respond: v1.Resource.GetTransferInstructionAcceptContextResponse.type
  )(transferInstructionId: String, body: v1.definitions.GetChoiceContextRequest)(
      extracted: TraceContext
  ): Future[v1.Resource.GetTransferInstructionAcceptContextResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getTransferInstructionAcceptContext") { _ => _ =>
      for {
        choiceContext <- getTransferInstructionChoiceContextV1(
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
      respond: v1.Resource.GetTransferInstructionRejectContextResponse.type
  )(transferInstructionId: String, body: v1.definitions.GetChoiceContextRequest)(
      extracted: TraceContext
  ): Future[v1.Resource.GetTransferInstructionRejectContextResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getTransferInstructionRejectContext") { _ => _ =>
      for {
        choiceContext <- getTransferInstructionChoiceContextV1(
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
      respond: v1.Resource.GetTransferInstructionWithdrawContextResponse.type
  )(transferInstructionId: String, body: v1.definitions.GetChoiceContextRequest)(
      extracted: TraceContext
  ): Future[v1.Resource.GetTransferInstructionWithdrawContextResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getTransferInstructionWithdrawContext") { _ => _ =>
      for {
        choiceContext <- getTransferInstructionChoiceContextV1(
          transferInstructionId,
          requireLockedAmulet = false,
          excludeDebugFields = body.excludeDebugFields.getOrElse(false),
        )
      } yield {
        v1.Resource.GetTransferInstructionWithdrawContextResponseOK(choiceContext)
      }
    }
  }

  override def getTransferFactory(respond: v2.Resource.GetTransferFactoryResponse.type)(
      body: v2.definitions.GetFactoryRequest
  )(extracted: TraceContext): Future[v2.Resource.GetTransferFactoryResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getTransferFactoryV2") { _ => _ =>
      for {
        transferInstr <- Try(
          transferinstructionv2.TransferFactory_Transfer.fromJson(body.choiceArguments.noSpaces)
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
        choiceContextBuilder <- getAmuletRulesTransferContextV2(
          body.excludeDebugFields.getOrElse(false)
        )
        dsoRules <- store.getDsoRules()
        result <- buildTransferFactory(
          PartyId.tryFromProtoPrimitive(
            TokenStandardAccount.tryGetRegularAccountOwner(transferInstr.transfer.sender)
          ),
          PartyId.tryFromProtoPrimitive(
            TokenStandardAccount.tryGetRegularAccountOwner(transferInstr.transfer.receiver)
          ),
          body.excludeDebugFields.getOrElse(false),
        )((optTransferPreapproval, optFeaturedAppRight, externalPartyAmuletRules) => {
          val isSelfTransfer = transferInstr.transfer.receiver == transferInstr.transfer.sender
          val kind =
            if (isSelfTransfer) v2.definitions.TransferFactoryWithChoiceContext.TransferKind.Self
            else if (optTransferPreapproval.isDefined)
              v2.definitions.TransferFactoryWithChoiceContext.TransferKind.Direct
            else v2.definitions.TransferFactoryWithChoiceContext.TransferKind.Offer
          v2.Resource.GetTransferFactoryResponseOK(
            v2.definitions.TransferFactoryWithChoiceContext(
              externalPartyAmuletRules.contractId.contractId,
              kind,
              choiceContext = choiceContextBuilder
                .addOptionalContracts(
                  "transfer-preapproval" -> optTransferPreapproval
                )
                .addFeaturedAppRight(clock, dsoRules.payload, optFeaturedAppRight)
                .disclose(externalPartyAmuletRules.contract)
                .build(),
            )
          )
        })
      } yield result
    }
  }
  override def getTransferInstructionAcceptContext(
      respond: v2.Resource.GetTransferInstructionAcceptContextResponse.type
  )(transferInstructionId: String, body: v2.definitions.GetChoiceContextRequest)(
      extracted: TraceContext
  ): Future[v2.Resource.GetTransferInstructionAcceptContextResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getTransferInstructionAcceptContextV2") { _ => _ =>
      for {
        choiceContext <- getTransferInstructionChoiceContextV2(
          transferInstructionId,
          requireLockedAmulet = true,
          excludeDebugFields = body.excludeDebugFields.getOrElse(false),
        )
      } yield {
        v2.Resource.GetTransferInstructionAcceptContextResponseOK(choiceContext)
      }
    }
  }

  override def getTransferInstructionRejectContext(
      respond: v2.Resource.GetTransferInstructionRejectContextResponse.type
  )(transferInstructionId: String, body: v2.definitions.GetChoiceContextRequest)(
      extracted: TraceContext
  ): Future[v2.Resource.GetTransferInstructionRejectContextResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getTransferInstructionRejectContextV2") { _ => _ =>
      for {
        choiceContext <- getTransferInstructionChoiceContextV2(
          transferInstructionId,
          requireLockedAmulet = false,
          excludeDebugFields = body.excludeDebugFields.getOrElse(false),
        )
      } yield {
        v2.Resource.GetTransferInstructionRejectContextResponseOK(choiceContext)
      }
    }
  }

  override def getTransferInstructionWithdrawContext(
      respond: v2.Resource.GetTransferInstructionWithdrawContextResponse.type
  )(transferInstructionId: String, body: v2.definitions.GetChoiceContextRequest)(
      extracted: TraceContext
  ): Future[v2.Resource.GetTransferInstructionWithdrawContextResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getTransferInstructionWithdrawContextV2") { _ => _ =>
      for {
        choiceContext <- getTransferInstructionChoiceContextV2(
          transferInstructionId,
          requireLockedAmulet = false,
          excludeDebugFields = body.excludeDebugFields.getOrElse(false),
        )
      } yield {
        v2.Resource.GetTransferInstructionWithdrawContextResponseOK(choiceContext)
      }
    }
  }

  private def buildTransferFactory[FactoryResponse](
      sender: PartyId,
      receiver: PartyId,
      excludeDebugFields: Boolean,
  )(
      build: (
          Option[ContractWithState[
            splice.amuletrules.TransferPreapproval.ContractId,
            splice.amuletrules.TransferPreapproval,
          ]],
          Option[ContractWithState[FeaturedAppRight.ContractId, FeaturedAppRight]],
          ContractWithState[
            splice.externalpartyamuletrules.ExternalPartyAmuletRules.ContractId,
            splice.externalpartyamuletrules.ExternalPartyAmuletRules,
          ],
      ) => FactoryResponse
  )(implicit
      tc: TraceContext
  ): Future[FactoryResponse] = {
    for {
      choiceContextBuilder <- getAmuletRulesTransferContextV1(excludeDebugFields)
      externalPartyAmuletRules <- store.getExternalPartyAmuletRules()
      // pre-approval and featured app rights are only provided if they exist and are required
      isSelfTransfer = receiver == sender
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
    } yield build(
      optTransferPreapproval,
      optFeaturedAppRight,
      externalPartyAmuletRules,
    )

  }

  private def getAmuletRulesTransferContextV1(excludeDebugFields: Boolean)(implicit
      tc: TraceContext
  ): Future[V1ChoiceContextBuilder] = {
    val now = clock.now
    getAmuletRulesTransferContext(now) {
      (amuletRules, newestOpenRound, externalPartyConfigStateO) =>
        val choiceContextBuilder = new V1ChoiceContextBuilder(
          AmuletConfigSchedule(amuletRules.payload.configSchedule)
            .getConfigAsOf(now)
            .decentralizedSynchronizer
            .activeSynchronizer,
          excludeDebugFields,
        )
        choiceContextBuilder
          .addContracts(
            "amulet-rules" -> amuletRules,
            "open-round" -> newestOpenRound.contract,
          )
          .addOptionalContract("external-party-config-state" -> externalPartyConfigStateO)
    }
  }

  private def getAmuletRulesTransferContextV2(excludeDebugFields: Boolean)(implicit
      tc: TraceContext
  ): Future[V2ChoiceContextBuilder] = {
    val now = clock.now
    getAmuletRulesTransferContext(now) {
      (amuletRules, newestOpenRound, externalPartyConfigStateO) =>
        val choiceContextBuilder = new V2ChoiceContextBuilder(
          AmuletConfigSchedule(amuletRules.payload.configSchedule)
            .getConfigAsOf(now)
            .decentralizedSynchronizer
            .activeSynchronizer,
          excludeDebugFields,
        )
        choiceContextBuilder
          .addContracts(
            "amulet-rules" -> amuletRules,
            "open-round" -> newestOpenRound.contract,
          )
          .addOptionalContract("external-party-config-state" -> externalPartyConfigStateO)
    }
  }

  private def getAmuletRulesTransferContext[CB](now: CantonTimestamp)(
      build: (
          Contract[splice.amuletrules.AmuletRules.ContractId, splice.amuletrules.AmuletRules],
          MiningRoundsStore.OpenMiningRound[AssignedContract],
          Option[Contract[ExternalPartyConfigState.ContractId, ExternalPartyConfigState]],
      ) => CB
  )(implicit
      tc: TraceContext
  ): Future[CB] = {
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
      // TODO(#4950) Don't include amulet rules and newest open round when informees all have vetted the newest version.
      externalPartyConfigStateO <- store.lookupLatestExternalPartyConfigState()
    } yield build(amuletRules, newestOpenRound, externalPartyConfigStateO)
  }

  /** Generic method to fetch choice contexts for all choices on a transfer instruction */
  private def getTransferInstructionChoiceContextV1(
      transferInstructionId: String,
      requireLockedAmulet: Boolean,
      excludeDebugFields: Boolean,
  )(implicit
      tc: TraceContext
  ): Future[v1.definitions.ChoiceContext] = {
    for {
      amuletInstr <- getAmuletTransferInstruction(transferInstructionId)
      context <- util.ChoiceContextBuilder.getTwoStepTransferContext[
        v1.definitions.DisclosedContract,
        v1.definitions.ChoiceContext,
        V1ChoiceContextBuilder,
      ](
        s"AmuletTransferInstruction '$transferInstructionId'",
        Some(amuletInstr.payload.lockedAmulet),
        Some(amuletInstr.payload.transfer.executeBefore),
        requireLockedAmulet,
        None,
        store,
        contractFetcher,
        clock,
        new V1ChoiceContextBuilder(_, excludeDebugFields),
      )
    } yield context
  }

  private def getTransferInstructionChoiceContextV2(
      transferInstructionId: String,
      requireLockedAmulet: Boolean,
      excludeDebugFields: Boolean,
  )(implicit
      tc: TraceContext
  ): Future[v2.definitions.ChoiceContext] = {
    for {
      amuletInstr <- getAmuletTransferInstruction(transferInstructionId)
      context <- util.ChoiceContextBuilder.getTwoStepTransferContext[
        v2.definitions.DisclosedContract,
        v2.definitions.ChoiceContext,
        V2ChoiceContextBuilder,
      ](
        s"AmuletTransferInstruction '$transferInstructionId'",
        Some(amuletInstr.payload.lockedAmulet),
        Some(amuletInstr.payload.transfer.executeBefore),
        requireLockedAmulet,
        None,
        store,
        contractFetcher,
        clock,
        new V2ChoiceContextBuilder(_, excludeDebugFields),
      )
    } yield context
  }

  private def getAmuletTransferInstruction(
      transferInstructionId: String
  )(implicit tc: TraceContext) = {
    contractFetcher
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
  }

}

object HttpTokenStandardTransferInstructionHandler {

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

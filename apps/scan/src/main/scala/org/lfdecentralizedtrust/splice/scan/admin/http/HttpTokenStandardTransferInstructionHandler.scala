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
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v1
import v1.{Resource, definitions}
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.util.{AmuletConfigSchedule, Contract, ContractWithState}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1.AnyContract

import java.time.ZoneOffset
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
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
          case Failure(_) =>
            Future.failed(
              io.grpc.Status.INVALID_ARGUMENT
                .withDescription(
                  "Field `choiceArguments` does not contain a valid `TransferFactory_Transfer`."
                )
                .asRuntimeException()
            )
        }
        (choiceContextBuilder, newestOpenRound) <- getAmuletRulesTransferContext()
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
        )
      } yield {
        v1.Resource.GetTransferInstructionWithdrawContextResponseOK(choiceContext)
      }
    }
  }

  private def getAmuletRulesTransferContext()(implicit
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
          .activeSynchronizer
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
  )(implicit
      tc: TraceContext
  ): Future[definitions.ChoiceContext] = {
    for {
      amuletInstr <- store.multiDomainAcsStore
        .lookupContractById(splice.amulettransferinstruction.AmuletTransferInstruction.COMPANION)(
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
      lockedAmuletId = amuletInstr.contract.payload.lockedAmulet
      optLockedAmulet <- store.multiDomainAcsStore.lookupContractById(
        splice.amulet.LockedAmulet.COMPANION
      )(lockedAmuletId)
      (choiceContextBuilder, _) <- getAmuletRulesTransferContext()
    } yield {
      if (optLockedAmulet.isEmpty) {
        // the locked amulet did expire and was unlocked
        if (requireLockedAmulet) {
          val expiresAt =
            CantonTimestamp.fromInstant(amuletInstr.contract.payload.transfer.executeBefore)
          throw io.grpc.Status.NOT_FOUND
            .withDescription(
              s"LockedAmulet '$lockedAmuletId' not found for AmuletTransferInstruction '$transferInstructionId', which expires on $expiresAt"
            )
            .asRuntimeException()
        } else {
          // only communicate that the amulet does not need to be unlocked
          new ChoiceContextBuilder(choiceContextBuilder.activeSynchronizerId)
            .addBool("expire-lock", false)
            .build()
        }
      } else {
        optLockedAmulet.foreach(co => choiceContextBuilder.disclose(co.contract))
        choiceContextBuilder
          // the choice implementation should only attempt to expire the lock if it exists
          .addBool("expire-lock", optLockedAmulet.isDefined)
          .build()
      }
    }
  }

}

object HttpTokenStandardTransferInstructionHandler {

  final class ChoiceContextBuilder(val activeSynchronizerId: String)(implicit
      elc: ErrorLoggingContext
  ) {

    val disclosedContracts: ListBuffer[definitions.DisclosedContract] = ListBuffer.empty
    val contextEntries: mutable.Map[String, metadatav1.AnyValue] = mutable.Map.empty

    def disclose(contract: Contract[?, ?]): ChoiceContextBuilder = {
      disclosedContracts.addOne(toTokenStandardDisclosedContract(contract, activeSynchronizerId))
      this
    }

    def addContract(contextKey: String, contract: Contract[?, ?]): ChoiceContextBuilder = {
      contextEntries.addOne(
        contextKey ->
          new metadatav1.anyvalue.AV_ContractId(
            new AnyContract.ContractId(contract.contractId.contractId)
          )
      )
      disclose(contract)
    }

    def addContract(keyedContract: (String, Contract[?, ?])): ChoiceContextBuilder =
      this.addContract(keyedContract._1, keyedContract._2)

    def addContracts(keyedContracts: (String, Contract[?, ?])*): ChoiceContextBuilder = {
      keyedContracts.foreach(this.addContract)
      this
    }

    def addOptionalContract(
        contextKey: String,
        optContract: Option[Contract[?, ?]],
    ): ChoiceContextBuilder = {
      optContract.foreach(addContract(contextKey, _))
      this
    }

    def addOptionalContract(
        keyedContract: (String, Option[Contract[?, ?]])
    ): ChoiceContextBuilder = addOptionalContract(keyedContract._1, keyedContract._2)

    def addOptionalContracts(
        keyedContracts: (String, Option[ContractWithState[?, ?]])*
    ): ChoiceContextBuilder = {
      keyedContracts.foreach(x => addOptionalContract(x._1, x._2.map(_.contract)))
      this
    }

    def addBool(contextKey: String, value: Boolean): ChoiceContextBuilder = {
      contextEntries.addOne(contextKey -> new metadatav1.anyvalue.AV_Bool(value))
      this
    }

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

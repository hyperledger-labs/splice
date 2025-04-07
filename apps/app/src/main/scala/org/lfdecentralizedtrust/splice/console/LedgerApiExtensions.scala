// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.console

import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.api.v2.commands.{Command, DisclosedContract}
import com.daml.ledger.api.v2.event.CreatedEvent
import com.daml.ledger.api.v2.transaction.TransactionTree
import com.daml.ledger.javaapi
import com.daml.ledger.javaapi.data.TransactionTree as JavaTransactionTree
import com.daml.ledger.javaapi.data.codegen.{ContractId, Exercised, Update}
import org.lfdecentralizedtrust.splice.environment.{SpliceLedgerConnection, PackageIdResolver}
import org.lfdecentralizedtrust.splice.util.{Contract, JavaDecodeUtil, PackageQualifiedName}
import com.digitalasset.canton.admin.api.client.commands.LedgerApiCommands
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.console.{
  ConsoleCommandResult,
  ConsoleMacros,
  FeatureFlag,
  Help,
  LedgerApiCommandRunner,
}
import com.digitalasset.canton.console.commands.BaseLedgerApiAdministration
import com.digitalasset.canton.data.DeduplicationPeriod
import com.digitalasset.canton.topology.{SynchronizerId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

trait LedgerApiExtensions {
  implicit class LedgerApiSyntax(
      private val ledgerApi: BaseLedgerApiAdministration with LedgerApiCommandRunner
  ) {
    private val packageIdResolver: PackageIdResolver = PackageIdResolver.staticTesting(
      ledgerApi.consoleEnvironment.environment.executionContext
    )

    object ledger_api_extensions {
      object commands {
        @Help.Summary(
          "Submit command and wait for the resulting transaction, returning the transaction tree or failing otherwise",
          FeatureFlag.Testing,
        )
        @Help.Description(
          """Submits a command on behalf of the `actAs` parties, waits for the resulting transaction to commit and returns it.
          | If the timeout is set, it also waits for the transaction to appear at all other configured
          | participants who were involved in the transaction. The call blocks until the transaction commits or fails;
          | the timeout only specifies how long to wait at the other participants.
          | Fails if the transaction doesn't commit, or if it doesn't become visible to the involved participants in
          | the allotted time.
          | Note that if the optTimeout is set and the involved parties are concurrently enabled/disabled or their
          | participants are connected/disconnected, the command may currently result in spurious timeouts or may
          | return before the transaction appears at all the involved participants."""
        )
        def submitJava(
            actAs: Seq[PartyId],
            commands: Seq[javaapi.data.Command],
            synchronizerId: Option[SynchronizerId] = None,
            commandId: String = "",
            deduplicationPeriod: Option[DeduplicationPeriod] = None,
            submissionId: String = "",
            minLedgerTimeAbs: Option[Instant] = None,
            readAs: Seq[PartyId] = Seq.empty,
            userId: String = LedgerApiCommands.defaultUserId,
            disclosedContracts: Seq[CommandsOuterClass.DisclosedContract] = Seq.empty,
        ): JavaTransactionTree = {
          val cmds = commands.map(cmd =>
            Await.result(packageIdResolver.resolvePackageId(cmd)(TraceContext.empty), 1.second)
          )
          val tx = ledgerApi.consoleEnvironment.run {
            ledgerApi.ledgerApiCommand(
              LedgerApiCommands.CommandService.SubmitAndWaitTransactionTree(
                actAs.map(_.toLf),
                readAs.map(_.toLf),
                cmds.map(c => Command.fromJavaProto(c.toProtoCommand)),
                workflowId = "",
                commandId = commandId,
                deduplicationPeriod = deduplicationPeriod,
                submissionId = submissionId,
                minLedgerTimeAbs = minLedgerTimeAbs,
                disclosedContracts = disclosedContracts
                  // We often have duplicates when merging choice contexts from multiple off-ledger APIs.
                  // Cull them here to avoid sending them twice; and because the Ledger API server
                  // currently errors out on them.
                  // TODO(#18566): remove the note wrt the error once that's no longer the case
                  .distinctBy(_.getContractId)
                  .map(DisclosedContract.fromJavaProto),
                synchronizerId = synchronizerId,
                userId = userId,
                packageIdSelectionPreference = Seq.empty,
              )
            )
          }
          JavaTransactionTree.fromProto(
            TransactionTree.toJavaProto(
              // Never set the timeout, as Canton tries to be too clever and attempts to read the transaction
              // with an empty 'required_parties' parameter, which then fails.
              ledgerApi.optionallyAwait(tx, tx.updateId, tx.synchronizerId, optTimeout = None)
            )
          )
        }

        def submitWithResult[T](
            userId: String,
            actAs: Seq[PartyId],
            readAs: Seq[PartyId],
            update: Update[T],
            commandId: Option[String] = None,
            synchronizerId: Option[SynchronizerId] = None,
            disclosedContracts: Seq[CommandsOuterClass.DisclosedContract] = Seq.empty,
        ): T = {
          val tree = submitJava(
            actAs,
            update.commands.asScala.toSeq,
            synchronizerId = synchronizerId,
            commandId.getOrElse(""),
            readAs = readAs,
            userId = userId,
            disclosedContracts = disclosedContracts,
          )
          SpliceLedgerConnection.decodeExerciseResult(
            update,
            tree,
          )
        }

        // Submit a command that produces a contract id as a result and return that contract.
        // This can be used to get the contract for an exercise that creates a contract in the choice body.
        def submitWithCreate[TCid <: ContractId[_], T <: javaapi.data.codegen.DamlRecord[_]](
            companion: Contract.Companion.Template[TCid, T]
        )(
            userId: String,
            actAs: Seq[PartyId],
            readAs: Seq[PartyId],
            update: Update[Exercised[TCid]],
            commandId: Option[String] = None,
            synchronizerId: Option[SynchronizerId] = None,
            disclosedContracts: Seq[CommandsOuterClass.DisclosedContract] = Seq.empty,
        ): Contract[TCid, T] = {
          val tree = submitJava(
            actAs,
            update.commands.asScala.toSeq,
            synchronizerId = synchronizerId,
            commandId.getOrElse(""),
            readAs = readAs,
            userId = userId,
            disclosedContracts = disclosedContracts,
          )
          val cid = SpliceLedgerConnection
            .decodeExerciseResult(
              update,
              tree,
            )
            .exerciseResult
          val createdEvent = tree.getEventsById.values.asScala
            .collectFirst {
              case ev: javaapi.data.CreatedEvent if ev.getContractId == cid.contractId => ev
            }
            .getOrElse(
              throw new IllegalArgumentException(
                "Did not get a create event for the exercise result"
              )
            )
          Contract
            .fromCreatedEvent(companion)(createdEvent)
            .getOrElse(
              throw new IllegalArgumentException("Failed to convert created event to contract")
            )
        }
      }

      object users {
        def getPrimaryParty(userId: String) = {
          val user = ledgerApi.ledger_api.users.get(userId)
          user.primaryParty.getOrElse(
            throw new RuntimeException(s"User $userId has no primary party")
          )
        }
      }

      object transactions {

        @Help.Summary("Get transaction trees", FeatureFlag.Testing)
        @Help.Description(
          """This function connects to the transaction tree stream for the given parties and collects transaction trees
          |until either `completeAfter` transaction trees have been received or `timeout` has elapsed.
          |The returned transaction trees can be filtered to be between the given offsets (default: no filtering).
          |If the participant has been pruned via `pruning.prune` and if `beginOffset` is lower than the pruning offset,
          |this command fails with a `NOT_FOUND` error."""
        )
        def treesJava(
            partyIds: Set[PartyId],
            completeAfter: PositiveInt,
            beginOffset: Long,
            endOffset: Option[Long] = None,
            verbose: Boolean = true,
            timeout: NonNegativeDuration = ledgerApi.timeouts.ledgerCommand,
        ): Seq[JavaTransactionTree] = {
          ledgerApi.ledger_api.updates
            .trees(partyIds, completeAfter, beginOffset, endOffset, verbose, timeout)
            .collect { case LedgerApiCommands.UpdateService.TransactionTreeWrapper(tree) =>
              JavaTransactionTree.fromProto(TransactionTree.toJavaProto(tree))
            }
        }
      }

      object acs {
        @Help.Summary("Wait until a contract becomes available", FeatureFlag.Testing)
        @Help.Description(
          """This function can be used for contracts with a code-generated Scala model.
          |You can refine your search using the `filter` function argument.
          |The command will wait until the contract appears or throw an exception once it times out."""
        )
        def awaitJava[
            TC <: javaapi.data.codegen.Contract[TCid, T],
            TCid <: javaapi.data.codegen.ContractId[T],
            T <: javaapi.data.Template,
        ](companion: javaapi.data.codegen.ContractCompanion[TC, TCid, T])(
            partyId: PartyId,
            predicate: TC => Boolean = (_: TC) => true,
            timeout: NonNegativeDuration = ledgerApi.timeouts.ledgerCommand,
        ): TC = {
          val result = new AtomicReference[Option[TC]](None)
          ConsoleMacros.utils.retry_until_true(timeout) {
            val tmp = filterJava(companion)(partyId, predicate)
            result.set(tmp.headOption)
            tmp.nonEmpty
          }
          ledgerApi.consoleEnvironment.run {
            ConsoleCommandResult.fromEither(
              result
                .get()
                .toRight(
                  s"Failed to find contract of type ${companion.getTemplateIdWithPackageId} after ${timeout}"
                )
            )
          }
        }

        @Help.Summary(
          "Filter the ACS for contracts of a particular Java code-generated template",
          FeatureFlag.Testing,
        )
        @Help.Description(
          """To use this function, ensure a code-generated Java model for the target template exists.
          |You can refine your search using the `predicate` function argument."""
        )
        def filterJava[
            TC <: javaapi.data.codegen.Contract[TCid, T],
            TCid <: javaapi.data.codegen.ContractId[T],
            T <: javaapi.data.Template,
        ](templateCompanion: javaapi.data.codegen.ContractCompanion[TC, TCid, T])(
            partyId: PartyId,
            predicate: TC => Boolean = (_: TC) => true,
        ): Seq[TC] = {
          val filterIdentifier = PackageQualifiedName(templateCompanion.getTemplateIdWithPackageId)
          val templateId = TemplateId(
            s"#${filterIdentifier.packageName}",
            filterIdentifier.qualifiedName.moduleName,
            filterIdentifier.qualifiedName.entityName,
          )
          ledgerApi.ledger_api.state.acs
            .of_party(partyId, filterTemplates = Seq(templateId))
            .map(_.event)
            .flatMap(ev =>
              JavaDecodeUtil
                .decodeCreated(templateCompanion)(
                  javaapi.data.CreatedEvent.fromProto(CreatedEvent.toJavaProto(ev))
                )
                .toList
            )
            .filter(predicate)
        }

        def lookup_contract_domain(
            partyId: PartyId,
            contractIds: Set[String],
        ): Map[String, SynchronizerId] = {
          val contracts = ledgerApi.ledger_api.state.acs.active_contracts_of_party(partyId)
          contracts.view
            .map(c =>
              c.getCreatedEvent.contractId -> SynchronizerId.tryFromString(c.synchronizerId)
            )
            .filter({ case (c, _) => contractIds.contains(c) })
            .toMap
        }

        def of_party[
            TC <: javaapi.data.codegen.Contract[TCid, T],
            TCid <: javaapi.data.codegen.ContractId[T],
            T <: javaapi.data.Template,
        ](templateCompanion: javaapi.data.codegen.ContractCompanion[TC, TCid, T])(
            partyId: PartyId
        ): Seq[CreatedEvent] = {
          val filterIdentifier = PackageQualifiedName(templateCompanion.getTemplateIdWithPackageId)
          val templateId = TemplateId(
            s"#${filterIdentifier.packageName}",
            filterIdentifier.qualifiedName.moduleName,
            filterIdentifier.qualifiedName.entityName,
          )
          ledgerApi.ledger_api.state.acs
            .of_party(partyId, filterTemplates = Seq(templateId))
            .map(_.event)
        }
      }
    }
  }
}

object LedgerApiExtensions extends LedgerApiExtensions {}

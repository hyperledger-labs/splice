// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.console

import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.api.v2.commands.{Command, DisclosedContract}
import com.daml.ledger.api.v2.event.CreatedEvent
import com.daml.ledger.api.v2.transaction.Transaction
import com.daml.ledger.api.v2.transaction_filter.TransactionShape
import com.daml.ledger.javaapi
import com.daml.ledger.javaapi.data.Transaction as JavaTransaction
import com.daml.ledger.javaapi.data.codegen.{ContractId, Exercised, Update}
import com.digitalasset.canton.admin.api.client.commands.LedgerApiCommands
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.console.commands.BaseLedgerApiAdministration
import com.digitalasset.canton.console.{
  ConsoleCommandResult,
  ConsoleMacros,
  FeatureFlag,
  Help,
  LedgerApiCommandRunner,
}
import com.digitalasset.canton.crypto.provider.jce.JcePureCrypto
import com.digitalasset.canton.crypto.{SigningKeyUsage, SigningPrivateKey}
import com.digitalasset.canton.data.{CantonTimestamp, DeduplicationPeriod}
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.console.LedgerApiExtensions.RichPartyId
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.util.{Contract, JavaDecodeUtil, PackageQualifiedName}

import java.time.{Duration, Instant}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.AppendedClues
import org.scalatest.matchers.should.Matchers
import scala.annotation.nowarn
import scala.jdk.CollectionConverters.*

trait LedgerApiExtensions extends AppendedClues with Matchers {
  implicit class LedgerApiSyntax(
      private val ledgerApi: BaseLedgerApiAdministration with LedgerApiCommandRunner
  ) {
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
            includeCreatedEventBlob: Boolean = false,
        ): JavaTransaction = {
          val tx = ledgerApi.consoleEnvironment.run {
            ledgerApi.ledgerApiCommand(
              LedgerApiCommands.CommandService.SubmitAndWaitTransaction(
                actAs.map(_.toLf),
                readAs.map(_.toLf),
                commands.map(c => Command.fromJavaProto(c.toProtoCommand)),
                workflowId = "",
                commandId = commandId,
                deduplicationPeriod = deduplicationPeriod,
                submissionId = submissionId,
                minLedgerTimeAbs = minLedgerTimeAbs,
                disclosedContracts = disclosedContracts
                  // We often have duplicates when merging choice contexts from multiple off-ledger APIs.
                  // Cull them here to avoid sending them twice; and because the Ledger API server
                  // currently errors out on them.
                  // TODO(#560): remove the note wrt the error once that's no longer the case
                  .distinctBy(_.getContractId)
                  .map(DisclosedContract.fromJavaProto),
                synchronizerId = synchronizerId,
                userId = userId,
                packageIdSelectionPreference = Seq.empty,
                transactionShape = TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS,
                includeCreatedEventBlob = includeCreatedEventBlob,
              )
            )
          }
          JavaTransaction.fromProto(
            Transaction.toJavaProto(
              // Never set the timeout, as Canton tries to be too clever and attempts to read the transaction
              // with an empty 'required_parties' parameter, which then fails.
              ledgerApi.optionallyAwait(tx, tx.updateId, tx.synchronizerId, optTimeout = None)
            )
          )
        }
        @Help.Summary(
          "Submit command for an external or a local party.",
          FeatureFlag.Testing,
        )
        @nowarn(raw"msg=unused value of type org.scalatest.Assertion")
        def submitJavaExternalOrLocal(
            actingParty: RichPartyId,
            commands: Seq[javaapi.data.Command],
            synchronizerId: Option[SynchronizerId] = None,
            commandId: String = UUID.randomUUID().toString,
            deduplicationPeriod: Option[DeduplicationPeriod] = None,
            submissionId: String = UUID.randomUUID().toString,
            minLedgerTimeAbs: Option[Instant] = None,
            userId: String = LedgerApiCommands.defaultUserId,
            disclosedContracts: Seq[CommandsOuterClass.DisclosedContract] = Seq.empty,
            expectedTimeBounds: Option[(CantonTimestamp, CantonTimestamp)] = None,
            advanceTimeBeforeExecute: Option[Duration] = None,
        )(implicit tc: TraceContext): Unit = {
          actingParty.externalSigningInfo match {
            case None =>
              expectedTimeBounds shouldBe None withClue ("Time bounds should not be set for local submissions as they are ignored")
              advanceTimeBeforeExecute shouldBe None withClue ("advanceTimeBeforeExecute should not be set for local submissions as it is ignored")
              val _ = submitJava(
                actAs = Seq(actingParty.partyId),
                commands = commands,
                synchronizerId = synchronizerId,
                commandId = commandId,
                deduplicationPeriod = deduplicationPeriod,
                submissionId = submissionId,
                minLedgerTimeAbs = minLedgerTimeAbs,
                readAs = Seq(actingParty.partyId),
                userId = userId,
                disclosedContracts = disclosedContracts,
              )
            case Some((signingPrivateKey, crypto)) =>
              submitJavaExternal(
                actingParty = actingParty.partyId,
                signingKey = signingPrivateKey,
                crypto = crypto,
                commands = commands,
                synchronizerId = synchronizerId,
                commandId = commandId,
                deduplicationPeriod = deduplicationPeriod,
                submissionId = submissionId,
                minLedgerTimeAbs = minLedgerTimeAbs,
                userId = userId,
                disclosedContracts = disclosedContracts,
                expectedTimeBounds = expectedTimeBounds,
                advanceTimeBeforeExecute = advanceTimeBeforeExecute,
              )
          }
        }

        @Help.Summary(
          "Submit command for an external party by preparing and executing it in one go.",
          FeatureFlag.Testing,
        )
        def submitJavaExternal(
            actingParty: PartyId,
            signingKey: SigningPrivateKey,
            crypto: JcePureCrypto,
            commands: Seq[javaapi.data.Command],
            synchronizerId: Option[SynchronizerId] = None,
            commandId: String = UUID.randomUUID().toString,
            deduplicationPeriod: Option[DeduplicationPeriod] = None,
            submissionId: String = UUID.randomUUID().toString,
            minLedgerTimeAbs: Option[Instant] = None,
            userId: String = LedgerApiCommands.defaultUserId,
            disclosedContracts: Seq[CommandsOuterClass.DisclosedContract] = Seq.empty,
            expectedTimeBounds: Option[(CantonTimestamp, CantonTimestamp)] = None,
            advanceTimeBeforeExecute: Option[Duration] = None,
        )(implicit tc: TraceContext): Unit = {
          val preparedTx =
            ledgerApi.ledger_api.interactive_submission.prepare(
              actAs = Seq(actingParty),
              readAs = Seq(actingParty),
              commands = commands.map(c => Command.fromJavaProto(c.toProtoCommand)),
              commandId = commandId,
              minLedgerTimeAbs = minLedgerTimeAbs,
              disclosedContracts = disclosedContracts
                // We often have duplicates when merging choice contexts from multiple off-ledger APIs.
                // Cull them here to avoid sending them twice; and because the Ledger API server
                // currently errors out on them.
                // TODO(#560): remove the note wrt the error once that's no longer the case
                .distinctBy(_.getContractId)
                .map(DisclosedContract.fromJavaProto),
              synchronizerId = synchronizerId,
              userId = userId,
              userPackageSelectionPreference = Seq.empty,
              verboseHashing = true,
            )

          val metadata = preparedTx.getPreparedTransaction.getMetadata
          expectedTimeBounds.foreach { case (min, max) =>
            (
              metadata.minLedgerEffectiveTime.fold(CantonTimestamp.MinValue)(
                CantonTimestamp.assertFromLong(_)
              ),
              metadata.maxLedgerEffectiveTime.fold(CantonTimestamp.MaxValue)(
                CantonTimestamp.assertFromLong(_)
              ),
            ) shouldBe (min, max)
          }

          advanceTimeBeforeExecute.foreach { duration =>
            val now = ledgerApi.ledger_api.time.get()
            ledgerApi.ledger_api.time.set(now, now.plus(duration))
          }

          val _ = ledgerApi.ledger_api.interactive_submission.execute(
            preparedTransaction = preparedTx.getPreparedTransaction,
            transactionSignatures = Map(
              actingParty -> Seq(
                crypto
                  .signBytes(
                    preparedTx.preparedTransactionHash,
                    signingKey,
                    usage = SigningKeyUsage.ProtocolOnly,
                  )
                  .fold(
                    err => throw new RuntimeException(s"Failed to sign for $actingParty: $err"),
                    sig => sig,
                  )
              )
            ),
            submissionId = submissionId,
            hashingSchemeVersion = preparedTx.hashingSchemeVersion,
            userId = userId,
            deduplicationPeriod = deduplicationPeriod,
            minLedgerTimeAbs = minLedgerTimeAbs,
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
        def submitWithCreate[TCid <: ContractId[?], T <: javaapi.data.codegen.DamlRecord[?]](
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
        ): Seq[JavaTransaction] = {
          ledgerApi.ledger_api.updates
            .transactions(
              // upcast from PartyId to Party
              partyIds.map(p => p),
              completeAfter,
              beginOffset,
              endOffset,
              verbose,
              timeout,
              transactionShape = TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS,
            )
            .collect { case LedgerApiCommands.UpdateService.TransactionWrapper(tree) =>
              JavaTransaction.fromProto(Transaction.toJavaProto(tree))
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
          val filterIdentifier =
            PackageQualifiedName.fromJavaCodegenCompanion(templateCompanion)
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
          val filterIdentifier = PackageQualifiedName.fromJavaCodegenCompanion(templateCompanion)
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

object LedgerApiExtensions extends LedgerApiExtensions {

  /** PartyIds that can be used to uniformly submit Ledger API commands for local and external parties. */
  case class RichPartyId(
      partyId: PartyId,
      externalSigningInfo: Option[
        (SigningPrivateKey, JcePureCrypto)
      ], // only set for external parties
  )

  object RichPartyId {
    def local(partyId: PartyId): RichPartyId = RichPartyId(partyId, None)
    def external(
        partyId: PartyId,
        privateKey: SigningPrivateKey,
        crypto: JcePureCrypto,
    ): RichPartyId =
      RichPartyId(partyId, Some((privateKey, crypto)))
  }
}

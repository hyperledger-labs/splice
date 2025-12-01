// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.admin.http

import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.{ContractId, Update}
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection.CommandId
import org.lfdecentralizedtrust.splice.environment.{CommandPriority, SpliceLedgerConnection}
import org.lfdecentralizedtrust.splice.environment.ledger.api.DedupConfig
import org.lfdecentralizedtrust.splice.util.{Contract, DisclosedContracts}
import org.lfdecentralizedtrust.splice.http.v0.definitions as d0
import org.lfdecentralizedtrust.splice.wallet.{UserWalletManager, UserWalletService}
import org.lfdecentralizedtrust.splice.wallet.store.{UserWalletStore, WalletStore}
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install as installCodegen
import com.digitalasset.canton.topology.PartyId
import scala.concurrent.{ExecutionContext, Future}
import io.opentelemetry.api.trace.Tracer

trait HttpWalletHandlerUtil extends Spanning with NamedLogging {
  protected val walletManager: UserWalletManager
  protected val store: WalletStore = walletManager.store
  private val validatorParty: PartyId = store.walletKey.validatorParty
  protected val workflowId: String

  protected def listContracts[TCid <: ContractId[T], T <: Template, ResponseT](
      templateCompanion: Contract.Companion.Template[TCid, T],
      user: String,
      mkResponse: Vector[d0.Contract] => ResponseT,
  )(implicit ec: ExecutionContext, traceContext: TraceContext, tracer: Tracer): Future[ResponseT] =
    withSpan(s"$workflowId.listContracts") { _ => _ =>
      for {
        userStore <- getUserStore(user)
        contracts <- userStore.multiDomainAcsStore.listContracts(
          templateCompanion
        )
      } yield mkResponse(contracts.map(_.contract.toHttp).toVector)
    }

  protected def listContractsWithState[TCid <: ContractId[T], T <: Template, ResponseT](
      templateCompanion: Contract.Companion.Template[TCid, T],
      user: String,
      mkResponse: Vector[d0.ContractWithState] => ResponseT,
  )(implicit ec: ExecutionContext, traceContext: TraceContext, tracer: Tracer): Future[ResponseT] =
    withSpan(s"$workflowId.listContractsWithState") { _ => _ =>
      for {
        userStore <- getUserStore(user)
        contracts <- userStore.multiDomainAcsStore.listContracts(
          templateCompanion
        )
      } yield mkResponse(contracts.map(_.toHttp).toVector)
    }

  protected def getUserStore(
      user: String
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[UserWalletStore] =
    getUserWallet(user).map(_.store)

  protected def getUserWallet(
      user: String
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[UserWalletService] =
    walletManager
      .lookupUserWallet(user)
      .map(
        _.getOrElse(
          throw Status.NOT_FOUND
            .withDescription(
              show"No wallet found for user ${user.singleQuoted}?"
            )
            .asRuntimeException()
        )
      )

  /** Executes a wallet action by calling a choice on the WalletInstall contract for the given user.
    *
    * The choice is always executed with the validator party as the submitter, and the
    * wallet user party as a readAs party.
    *
    * Note: curried syntax helps with type inference
    */
  protected def exerciseWalletAction[Response](
      getUpdate: (
          installCodegen.WalletAppInstall.ContractId,
          UserWalletStore,
      ) => Future[Update[Response]]
  )(
      user: String,
      dedup: Option[(CommandId, DedupConfig)] = None,
      disclosedContracts: SpliceLedgerConnection => DisclosedContracts = _ => DisclosedContracts(),
      priority: CommandPriority = CommandPriority.Low,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[Response] = {
    for {
      userWallet <- getUserWallet(user)
      userStore = userWallet.store
      userParty = userStore.key.endUserParty
      // TODO (#998) pick install based on disclosed contracts' domain IDs
      install <- userStore.getInstall()
      unadornedUpdate <- getUpdate(install.contractId, userStore)
      update = install.exercise(_ => unadornedUpdate)
      result <- dedup match {
        case None =>
          userWallet.connection
            .submit(Seq(validatorParty), Seq(userParty), update, priority = priority)
            .withDisclosedContracts(disclosedContracts(userWallet.connection))
            .noDedup
            .yieldResult()
        case Some((commandId, dedupConfig)) =>
          userWallet.connection
            .submit(
              Seq(validatorParty),
              Seq(userParty),
              update,
              priority = priority,
            )
            .withDedup(commandId, dedupConfig)
            .withDisclosedContracts(disclosedContracts(userWallet.connection))
            .yieldResult()
      }
    } yield result
  }
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.store

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install as installCodegen
import org.lfdecentralizedtrust.splice.store.AppStore
import org.lfdecentralizedtrust.splice.util.Contract
import com.digitalasset.canton.logging.pretty.*
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

/** A store for serving all queries used by the wallet backend's gRPC request handlers and automation
  * that require the visibility of the validator user.
  */
trait WalletStore extends AppStore {

  protected implicit val ec: ExecutionContext

  /** The key identifying the parties considered by this store. */
  def walletKey: WalletStore.Key

  def lookupInstallByParty(
      endUserParty: PartyId
  )(implicit tc: TraceContext): Future[Option[
    Contract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]
  ]]

  def lookupInstallByName(
      endUserName: String
  )(implicit tc: TraceContext): Future[Option[
    Contract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]
  ]]

  def lookupValidatorFeaturedAppRight()(implicit
      tc: TraceContext
  ): Future[
    Option[Contract[amuletCodegen.FeaturedAppRight.ContractId, amuletCodegen.FeaturedAppRight]]
  ]
}

object WalletStore {
  case class Key(
      /** The validator party. */
      validatorParty: PartyId,
      /** The party-id of the DSO issuing CC managed by this wallet. */
      dsoParty: PartyId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("validatorParty", _.validatorParty),
      param("dsoParty", _.dsoParty),
    )
  }
}

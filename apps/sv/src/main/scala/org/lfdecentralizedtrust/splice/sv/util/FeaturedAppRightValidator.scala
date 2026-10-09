// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.util

import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight_UpdateConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.ActionRequiringConfirmation
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.{
  SRARC_CreateFeaturedAppConfigUpdateInstruction,
  SRARC_GrantFeaturedAppRight,
}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

object FeaturedAppRightValidator {
  // Scope of the overlap check: opsParties-vs-opsParties only. A party that is both an opsParty
  // here and a provider of another FeaturedAppRight (or vice versa) is NOT treated as a conflict;
  // cross-role provider/opsParty collisions are intentionally out of scope.
  def validateFeaturedAppRightAction(
      action: ActionRequiringConfirmation,
      store: SvDsoStore,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[Either[String, Unit]] = {
    action match {
      case arc: ARC_DsoRules =>
        arc.dsoAction match {
          case g: SRARC_GrantFeaturedAppRight =>
            val value = g.dsoRules_GrantFeaturedAppRightValue
            val providerPartyId = PartyId.tryFromProtoPrimitive(value.provider)
            val proposed =
              SvUtil.optionalJavaListToSeq(value.opsParties).map(PartyId.tryFromProtoPrimitive)
            store.lookupFeaturedAppRight(providerPartyId).flatMap {
              case Some(_) =>
                Future
                  .successful(Left(s"provider ${value.provider} already has a FeaturedAppRight"))
              case None => checkOps(store, providerPartyId, proposed, None)
            }

          case u: SRARC_CreateFeaturedAppConfigUpdateInstruction =>
            val value = u.dsoRules_CreateFeaturedAppConfigUpdateInstructionValue
            // One instruction can amend multiple providers' configs (the primary `update` plus every
            // entry in `additionalUpdates`), each carrying its own opsParties. Validate them all,
            // short-circuiting on the first failure, so conflicting opsParties cannot be smuggled in
            // via additionalUpdates.
            // Residual: two entries in the same instruction claiming the same opsParty both pass,
            // since neither is in the ACS yet — same cross-contract race as the TOCTOU note below.
            val allUpdates: Seq[(String, FeaturedAppRight_UpdateConfig)] =
              (value.provider, value.update) +:
                value.additionalUpdates.asScala.toSeq.map(t => (t._1, t._2))
            allUpdates.foldLeft(Future.successful(Right(()): Either[String, Unit])) {
              (accF, entry) =>
                accF.flatMap {
                  case Left(err) => Future.successful(Left(err))
                  case Right(()) => validateAmend(store, entry._1, entry._2)
                }
            }
          case _ => Future.successful(Right(()))
        }
      case _ => Future.successful(Right(()))
    }
  }

  private def validateAmend(
      store: SvDsoStore,
      providerRaw: String,
      update: FeaturedAppRight_UpdateConfig,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[Either[String, Unit]] = {
    val providerPartyId = PartyId.tryFromProtoPrimitive(providerRaw)
    store.lookupFeaturedAppRight(providerPartyId).flatMap {
      case None =>
        Future.successful(Left(s"no FeaturedAppRight for provider $providerRaw to amend"))
      case Some(current) =>
        val baseParties = SvUtil
          .optionalJavaListToSeq(update.baseConfig.opsParties)
          .map(PartyId.tryFromProtoPrimitive)
        val proposedParties = SvUtil
          .optionalJavaListToSeq(update.newConfig.opsParties)
          .map(PartyId.tryFromProtoPrimitive)
        val currentParties = SvUtil
          .optionalJavaListToSeq(current.payload.opsParties)
          .map(PartyId.tryFromProtoPrimitive)
        val effective = resolveOps(baseParties, currentParties, proposedParties)
        checkOps(store, providerPartyId, effective, Some(current.contractId.contractId))
    }
  }

  // TOCTOU: this runs at propose time and each SV validates independently against its own
  // ACS snapshot, so two concurrent proposals with overlapping opsParties can both pass here
  // and both execute. DAML `ensure` is single-contract and cannot catch a cross-contract
  // conflict, so this residual race is not fully closed by backend validation.
  def checkOps(
      store: SvDsoStore,
      provider: PartyId,
      proposed: Seq[PartyId],
      excludeCid: Option[String],
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Either[String, Unit]] =
    if (proposed.distinct.sizeIs != proposed.size)
      Future.successful(Left("duplicate opsParties in request"))
    else if (proposed.contains(provider))
      Future.successful(Left("provider cannot be its own opsParty"))
    else
      store.findConflictingOpsParties(proposed, excludeCid).map {
        case Nil => Right(())
        case c => Left(s"opsParties already in use: ${c.mkString(", ")}")
      }

  // Decision: None and empty [] opsParties are treated as equivalent — SvUtil.optionalJavaListToSeq
  // maps both to Nil. This diverges from DAML patchScalar's raw-Optional equality only at the
  // None-vs-[] boundary, and only ever widens the validated set (conservative): at worst a
  // false-reject of a clear-to-empty amend, never a missed conflict.
  def resolveOps(
      baseParties: Seq[PartyId],
      currentParties: Seq[PartyId],
      proposedParties: Seq[PartyId],
  ): Seq[PartyId] =
    if (baseParties == proposedParties) {
      currentParties
    } else {
      proposedParties
    }
}

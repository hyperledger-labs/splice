// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.util

import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.ActionRequiringConfirmation
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.{
  SRARC_CreateFeaturedAppConfigUpdateInstruction,
  SRARC_GrantFeaturedAppRight,
}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import scala.concurrent.{ExecutionContext, Future}

object FeaturedAppRightValidator {
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
                Future.successful(Left(s"provider $providerPartyId already has a FeaturedAppRight"))
              case None => checkOps(store, providerPartyId, proposed, None)
            }

          case u: SRARC_CreateFeaturedAppConfigUpdateInstruction =>
            val value = u.dsoRules_CreateFeaturedAppConfigUpdateInstructionValue
            val providerPartyId = PartyId.tryFromProtoPrimitive(value.provider)
            store.lookupFeaturedAppRight(providerPartyId).flatMap {
              case None =>
                Future
                  .successful(Left(s"no FeaturedAppRight for provider ${value.provider} to amend"))
              case Some(current) =>
                val proposedParties = SvUtil
                  .optionalJavaListToSeq(value.update.newConfig.opsParties)
                  .map(PartyId.tryFromProtoPrimitive)
                val currentParties = SvUtil
                  .optionalJavaListToSeq(current.payload.opsParties)
                  .map(PartyId.tryFromProtoPrimitive)
                val effective = resolveOps(currentParties, proposedParties)
                checkOps(store, providerPartyId, effective, Some(current.contractId.contractId))
            }
          case _ => Future.successful(Right(()))
        }
      case _ => Future.successful(Right(()))
    }
  }

  private def checkOps(
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
        case Nil => Right(());
        case c => Left(s"opsParties already in use: ${c.mkString(", ")}")
      }

  private def resolveOps(
      currentParties: Seq[PartyId],
      proposedParties: Seq[PartyId],
  ): Seq[PartyId] =
    if (currentParties == proposedParties) {
      currentParties
    } else {
      proposedParties
    }
}

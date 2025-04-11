// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{DsoRules, VoteRequest}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_SetConfig
import org.lfdecentralizedtrust.splice.config.Thresholds

import java.time.Instant
import scala.compat.java8.OptionConverters.RichOptionalGeneric
import scala.jdk.CollectionConverters.*

/** Scala representation of amulet configuration schedule. */
case class AmuletConfigSchedule(
    initialConfig: splice.amuletconfig.AmuletConfig[splice.amuletconfig.USD],
    futureConfigs: Seq[(Instant, splice.amuletconfig.AmuletConfig[splice.amuletconfig.USD])],
) {

  /** Method to retrieve the config effective as-of the specific time. */
  def getConfigAsOf(
      t: CantonTimestamp
  ): splice.amuletconfig.AmuletConfig[splice.amuletconfig.USD] = {
    val tInstant = t.toInstant
    val effectiveConfigs = futureConfigs.takeWhile { case (effectiveAt, _) =>
      effectiveAt == tInstant || effectiveAt.isBefore(tInstant)
    }
    effectiveConfigs.lastOption.fold(initialConfig)(_._2)
  }
}

object AmuletConfigSchedule {

  /** Convenience constructor to get a AmuletRules' config schedule. */
  def apply(cr: Contract.Has[?, splice.amuletrules.AmuletRules]): AmuletConfigSchedule =
    AmuletConfigSchedule(cr.payload)

  def apply(
      cr: splice.amuletrules.AmuletRules
  ): AmuletConfigSchedule =
    AmuletConfigSchedule(cr.configSchedule)

  def apply(
      schedule: splice.schedule.Schedule[Instant, splice.amuletconfig.AmuletConfig[
        splice.amuletconfig.USD
      ]]
  ): AmuletConfigSchedule =
    AmuletConfigSchedule(
      initialConfig = schedule.initialValue,
      futureConfigs = schedule.futureValues.asScala.map { t =>
        t._1 -> t._2
      }.toSeq,
    )

  /** Helper to filter `CRARC_SetConfig` actions that have a `targetEffectiveAt` and have been accepted. This is
    * used primarily by the vetting logic to vet new packages ahead of time with the effective date on the vote request.
    */
  def getAcceptedEffectiveVoteRequests(
      dsoRules: Contract[DsoRules.ContractId, DsoRules],
      voteRequests: Seq[Contract[VoteRequest.ContractId, VoteRequest]],
  ): Seq[(Option[Instant], AmuletConfig[USD])] = {
    voteRequests.flatMap { voteRequest =>
      voteRequest.payload.action match {
        case action: ARC_AmuletRules =>
          action.amuletRulesAction match {
            case action: CRARC_SetConfig =>
              voteRequest.payload.targetEffectiveAt.asScala match {
                case Some(effectiveAt) =>
                  val currentSvs = dsoRules.payload.svs.asScala.keySet
                  val uniqueSvAccepters: Set[String] = voteRequest.payload.votes.asScala.values
                    .filter(v => currentSvs.contains(v.sv) && v.accept)
                    .map(_.sv)
                    .toSet
                  Option.when(uniqueSvAccepters.size >= Thresholds.requiredNumVotes(dsoRules))(
                    (Some(effectiveAt), action.amuletRules_SetConfigValue.newConfig)
                  )
                case None =>
                  // We don't bother with votes without an effectivity. They just take effect when they're accepted.
                  None
              }
            case _ => None
          }
        case _ => None
      }
    }
  }
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice

import java.time.Instant
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
}

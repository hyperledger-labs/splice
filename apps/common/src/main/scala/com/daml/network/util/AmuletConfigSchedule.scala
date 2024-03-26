package com.daml.network.util

import com.daml.network.codegen.java.cc
import com.digitalasset.canton.data.CantonTimestamp

import java.time.Instant

import scala.jdk.CollectionConverters.*

/** Scala representation of amulet configuration schedule. */
case class AmuletConfigSchedule(
    initialConfig: cc.amuletconfig.AmuletConfig[cc.amuletconfig.USD],
    futureConfigs: Seq[(Instant, cc.amuletconfig.AmuletConfig[cc.amuletconfig.USD])],
) {

  /** Method to retrieve the config effective as-of the specific time. */
  def getConfigAsOf(t: CantonTimestamp): cc.amuletconfig.AmuletConfig[cc.amuletconfig.USD] = {
    val tInstant = t.toInstant
    val effectiveConfigs = futureConfigs.takeWhile { case (effectiveAt, _) =>
      effectiveAt == tInstant || effectiveAt.isBefore(tInstant)
    }
    effectiveConfigs.lastOption.fold(initialConfig)(_._2)
  }
}

object AmuletConfigSchedule {

  /** Convenience constructor to get a AmuletRules' config schedule. */
  def apply(cr: Contract.Has[?, cc.amuletrules.AmuletRules]): AmuletConfigSchedule =
    AmuletConfigSchedule(cr.payload)

  def apply(
      cr: cc.amuletrules.AmuletRules
  ): AmuletConfigSchedule =
    AmuletConfigSchedule(cr.configSchedule)

  def apply(
      schedule: cc.schedule.Schedule[Instant, cc.amuletconfig.AmuletConfig[cc.amuletconfig.USD]]
  ): AmuletConfigSchedule =
    AmuletConfigSchedule(
      initialConfig = schedule.initialValue,
      futureConfigs = schedule.futureValues.asScala.map { t =>
        t._1 -> t._2
      }.toSeq,
    )
}

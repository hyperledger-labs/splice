package com.daml.network.util

import com.daml.network.codegen.java.cc
import com.digitalasset.canton.data.CantonTimestamp

import java.time.Instant

import scala.jdk.CollectionConverters.*

/** Scala representation of coin configuration schedule. */
case class CoinConfigSchedule(
    initialConfig: cc.coinconfig.CoinConfig[cc.coinconfig.USD],
    futureConfigs: Seq[(Instant, cc.coinconfig.CoinConfig[cc.coinconfig.USD])],
) {

  /** Method to retrieve the config effective as-of the specific time. */
  def getConfigAsOf(t: CantonTimestamp): cc.coinconfig.CoinConfig[cc.coinconfig.USD] = {
    val tInstant = t.toInstant
    val effectiveConfigs = futureConfigs.takeWhile { case (effectiveAt, _) =>
      effectiveAt == tInstant || effectiveAt.isBefore(tInstant)
    }
    effectiveConfigs.lastOption.fold(initialConfig)(_._2)
  }
}

object CoinConfigSchedule {

  /** Convenience constructor to get a CoinRules' config schedule. */
  def apply(cr: Contract.Has[?, cc.coin.CoinRules]): CoinConfigSchedule =
    CoinConfigSchedule(cr.payload)

  def apply(
      cr: cc.coin.CoinRules
  ): CoinConfigSchedule =
    CoinConfigSchedule(cr.configSchedule)

  def apply(
      schedule: cc.schedule.Schedule[Instant, cc.coinconfig.CoinConfig[cc.coinconfig.USD]]
  ): CoinConfigSchedule =
    CoinConfigSchedule(
      initialConfig = schedule.initialValue,
      futureConfigs = schedule.futureValues.asScala.map { t =>
        t._1 -> t._2
      }.toSeq,
    )
}

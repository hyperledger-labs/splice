// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory}
import com.daml.metrics.api.MetricQualification.Debug
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, SyncCloseable}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future, blocking}
import org.lfdecentralizedtrust.splice.automation.{PollingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics
import org.lfdecentralizedtrust.splice.sv.automation.AmuletPriceMetricsTrigger.AmuletPriceMetrics
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import scala.collection.concurrent.TrieMap
import scala.jdk.OptionConverters.*

class AmuletPriceMetricsTrigger(
    override protected val context: TriggerContext,
    dsoStore: SvDsoStore,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    val mat: Materializer,
) extends PollingTrigger {

  private val amuletPriceMetrics = new AmuletPriceMetrics(context.metricsFactory)

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      currentVotes <- dsoStore.listSvAmuletPriceVotes()
      _ = currentVotes.foreach { vote =>
        {
          vote.payload.amuletPrice.toScala match {
            case Some(price) => amuletPriceMetrics.updateSvAmuletPrice(vote.payload.sv, price)
            case None =>
              logger.debug(s"SV ${vote.payload.sv} has not voted on a price yet")
          }
        }
      }
      _ = amuletPriceMetrics.closeAllOffboardedSvMetrics(currentVotes.map(_.payload.sv))
      latestRound <- dsoStore.lookupLatestUsableOpenMiningRound(context.clock.now)
      _ = latestRound match {
        case Some(round) => amuletPriceMetrics.updateLatestRoundPrice(round.payload.amuletPrice)
        case None =>
          logger.debug(s"No usable open mining round yet")
      }

    } yield false

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = super
    .closeAsync()
    .appended(SyncCloseable("amulet price metrics", { amuletPriceMetrics.close() }))
}

object AmuletPriceMetricsTrigger {
  case class AmuletPriceMetrics(metricsFactory: LabeledMetricsFactory) extends AutoCloseable {

    private val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "amulet_price"

    private val svAmuletPrices: TrieMap[String, Gauge[Double]] = TrieMap.empty
    private val latestOpenRoundPrice = metricsFactory.gauge(
      MetricInfo(
        prefix :+ "latest_open_round_price",
        "The price in the latest open round",
        Debug,
      ),
      Double.NaN,
    )(MetricsContext.Empty)

    private def getSvStatusMetrics(sv: String): Gauge[Double] =
      svAmuletPrices.getOrElse(
        sv,
        // We must synchronize here to avoid allocating the metrics for the same sv multiple times, which would lead to
        // duplicate metric labels being reported by OpenTelemetry.
        blocking {
          synchronized {
            svAmuletPrices.getOrElseUpdate(
              sv,
              metricsFactory.gauge(
                MetricInfo(
                  prefix :+ "voted_price",
                  "The latest price that this SV has voted on",
                  Debug,
                ),
                Double.NaN,
              )(MetricsContext.Empty.withExtraLabels("sv" -> sv)),
            )
          }
        },
      )

    def updateSvAmuletPrice(sv: String, price: BigDecimal): Unit = {
      getSvStatusMetrics(sv).updateValue(price.doubleValue)
    }
    def updateLatestRoundPrice(price: BigDecimal): Unit = {
      latestOpenRoundPrice.updateValue(price.doubleValue)
    }

    def closeAllOffboardedSvMetrics(svs: Seq[String]): Unit = {
      val svIdsToClose = svAmuletPrices.keySet.toSet -- svs
      svAmuletPrices.view.filterKeys(svIdsToClose.contains).foreach(_._2.close())
      blocking {
        synchronized {
          svAmuletPrices --= svIdsToClose: Unit
        }
      }
    }

    override def close(): Unit = {
      svAmuletPrices.values.foreach(_.close())
      latestOpenRoundPrice.close()
    }
  }
}

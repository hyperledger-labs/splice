package com.daml.network.metrics

import com.codahale.metrics
import com.daml.network.svc.metrics.SvcAppMetrics
import com.daml.network.validator.metrics.ValidatorAppMetrics
import com.digitalasset.canton.metrics.MetricsFactory.registerReporter
import com.digitalasset.canton.metrics.{MetricsConfig, MetricsFactory, MetricsFactoryBase}

import scala.collection.concurrent.TrieMap

case class CoinMetricsFactory(
    reporters: Seq[metrics.Reporter],
    registry: metrics.MetricRegistry,
    reportJVMMetrics: Boolean,
) extends AutoCloseable
    with MetricsFactoryBase {
  private val validators = TrieMap[String, ValidatorAppMetrics]()
  private val svcs = TrieMap[String, SvcAppMetrics]()

  override protected def allNodeMetrics: Seq[TrieMap[String, _]] = Seq(validators)

  def forValidator(name: String): ValidatorAppMetrics = {
    validators.getOrElseUpdate(
      name, {
        val metricName = deduplicateName(name, "validator", validators)
        new ValidatorAppMetrics(MetricsFactory.prefix, newRegistry(metricName))
      },
    )
  }

  def forSvc(name: String): SvcAppMetrics = {
    svcs.getOrElseUpdate(
      name, {
        val metricName = deduplicateName(name, "SVC", svcs)
        new SvcAppMetrics(MetricsFactory.prefix, newRegistry(metricName))
      },
    )
  }
}

object CoinMetricsFactory {
  def forConfig(config: MetricsConfig): CoinMetricsFactory = {
    val registry = new metrics.MetricRegistry()
    val reporter = registerReporter(config, registry)
    new CoinMetricsFactory(reporter, registry, config.reportJvmMetrics)
  }
}

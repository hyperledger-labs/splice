package com.daml.network.metrics

import com.codahale.metrics
import com.daml.network.directory.provider.metrics.DirectoryProviderAppMetrics
import com.daml.network.scan.metrics.ScanAppMetrics
import com.daml.network.splitwise.metrics.SplitwiseAppMetrics
import com.daml.network.svc.metrics.SvcAppMetrics
import com.daml.network.validator.metrics.ValidatorAppMetrics
import com.daml.network.wallet.metrics.WalletAppMetrics
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
  private val scans = TrieMap[String, ScanAppMetrics]()
  private val wallets = TrieMap[String, WalletAppMetrics]()
  private val directoryProviders = TrieMap[String, DirectoryProviderAppMetrics]()
  private val splitwises = TrieMap[String, SplitwiseAppMetrics]()

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

  def forScan(name: String): ScanAppMetrics = {
    scans.getOrElseUpdate(
      name, {
        val metricName = deduplicateName(name, "Scan", scans)
        new ScanAppMetrics(MetricsFactory.prefix, newRegistry(metricName))
      },
    )
  }

  def forWallet(name: String): WalletAppMetrics = {
    wallets.getOrElseUpdate(
      name, {
        val metricName = deduplicateName(name, "Wallet", wallets)
        new WalletAppMetrics(MetricsFactory.prefix, newRegistry(metricName))
      },
    )
  }

  def forDirectoryProvider(name: String): DirectoryProviderAppMetrics = {
    directoryProviders.getOrElseUpdate(
      name, {
        val metricName = deduplicateName(name, "DirectoryProvider", directoryProviders)
        new DirectoryProviderAppMetrics(MetricsFactory.prefix, newRegistry(metricName))
      },
    )
  }

  def forSplitwise(name: String): SplitwiseAppMetrics = {
    splitwises.getOrElseUpdate(
      name, {
        val metricName = deduplicateName(name, "Splitwise", splitwises)
        new SplitwiseAppMetrics(MetricsFactory.prefix, newRegistry(metricName))
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

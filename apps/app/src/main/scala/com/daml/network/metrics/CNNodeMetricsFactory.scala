package com.daml.network.metrics

import com.codahale.metrics
import com.daml.metrics.grpc.DamlGrpcServerMetrics
import com.daml.network.directory.metrics.DirectoryAppMetrics
import com.daml.network.scan.metrics.ScanAppMetrics
import com.daml.network.splitwell.metrics.SplitwellAppMetrics
import com.daml.network.sv.metrics.SvAppMetrics
import com.daml.network.svc.metrics.SvcAppMetrics
import com.daml.network.validator.metrics.ValidatorAppMetrics
import com.daml.network.wallet.metrics.WalletAppMetrics
import com.digitalasset.canton.metrics.MetricHandle.CantonDropwizardMetricsFactory
import com.digitalasset.canton.metrics.MetricsFactory.registerReporter
import com.digitalasset.canton.metrics.{MetricsConfig, MetricsFactory}
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.sdk.metrics.SdkMeterProvider

import scala.collection.concurrent.TrieMap

case class CNNodeMetricsFactory(
    reporters: Seq[metrics.Reporter],
    registry: metrics.MetricRegistry,
    reportJVMMetrics: Boolean,
    meter: Meter,
) extends MetricsFactory(
      reporters,
      registry,
      reportJVMMetrics,
      meter,
    ) {
  private val validators = TrieMap[String, ValidatorAppMetrics]()
  private val svcs = TrieMap[String, SvcAppMetrics]()
  private val svs = TrieMap[String, SvAppMetrics]()
  private val scans = TrieMap[String, ScanAppMetrics]()
  private val wallets = TrieMap[String, WalletAppMetrics]()
  private val directories = TrieMap[String, DirectoryAppMetrics]()
  private val splitwells = TrieMap[String, SplitwellAppMetrics]()

  override protected def allNodeMetrics: Seq[TrieMap[String, _]] =
    Seq(validators, svcs, svs, scans, wallets, directories, splitwells)

  def forValidator(name: String): ValidatorAppMetrics = {
    validators.getOrElseUpdate(
      name, {
        val metricName = deduplicateName(name, "Validator", validators)
        new ValidatorAppMetrics(
          MetricsFactory.prefix,
          new CantonDropwizardMetricsFactory(newRegistry(metricName)),
          new DamlGrpcServerMetrics(openTelemetryFactory, "Validator"),
          healthMetrics,
        )
      },
    )
  }

  def forSvc(name: String): SvcAppMetrics = {
    svcs.getOrElseUpdate(
      name, {
        val metricName = deduplicateName(name, "SVC", svcs)
        new SvcAppMetrics(
          MetricsFactory.prefix,
          new CantonDropwizardMetricsFactory(newRegistry(metricName)),
          new DamlGrpcServerMetrics(openTelemetryFactory, "SVC"),
          healthMetrics,
        )
      },
    )
  }

  def forSv(name: String): SvAppMetrics = {
    svs.getOrElseUpdate(
      name, {
        val metricName = deduplicateName(name, "SV", svcs)
        new SvAppMetrics(
          MetricsFactory.prefix,
          new CantonDropwizardMetricsFactory(newRegistry(metricName)),
          new DamlGrpcServerMetrics(openTelemetryFactory, "SV"),
          healthMetrics,
        )
      },
    )
  }

  def forScan(name: String): ScanAppMetrics = {
    scans.getOrElseUpdate(
      name, {
        val metricName = deduplicateName(name, "Scan", scans)
        new ScanAppMetrics(
          MetricsFactory.prefix,
          new CantonDropwizardMetricsFactory(newRegistry(metricName)),
          new DamlGrpcServerMetrics(openTelemetryFactory, "Scan"),
          healthMetrics,
        )
      },
    )
  }

  def forWallet(name: String): WalletAppMetrics = {
    wallets.getOrElseUpdate(
      name, {
        val metricName = deduplicateName(name, "Wallet", wallets)
        new WalletAppMetrics(
          MetricsFactory.prefix,
          new CantonDropwizardMetricsFactory(newRegistry(metricName)),
          new DamlGrpcServerMetrics(openTelemetryFactory, "Wallet"),
          healthMetrics,
        )
      },
    )
  }

  def forDirectory(name: String): DirectoryAppMetrics = {
    directories.getOrElseUpdate(
      name, {
        val metricName = deduplicateName(name, "Directory", directories)
        new DirectoryAppMetrics(
          MetricsFactory.prefix,
          new CantonDropwizardMetricsFactory(newRegistry(metricName)),
          new DamlGrpcServerMetrics(openTelemetryFactory, "Directory"),
          healthMetrics,
        )
      },
    )
  }

  def forSplitwell(name: String): SplitwellAppMetrics = {
    splitwells.getOrElseUpdate(
      name, {
        val metricName = deduplicateName(name, "Splitwell", splitwells)
        new SplitwellAppMetrics(
          MetricsFactory.prefix,
          new CantonDropwizardMetricsFactory(newRegistry(metricName)),
          new DamlGrpcServerMetrics(openTelemetryFactory, "Splitwell"),
          healthMetrics,
        )
      },
    )
  }
}

object CNNodeMetricsFactory {
  def forConfig(config: MetricsConfig): CNNodeMetricsFactory = {
    val registry = new metrics.MetricRegistry()
    val meterProviderBuilder = SdkMeterProvider.builder()
    val reporter = registerReporter(config, registry)
    val meterProvider = meterProviderBuilder.build()
    new CNNodeMetricsFactory(
      reporter,
      registry,
      config.reportJvmMetrics,
      meterProvider.meterBuilder("daml").build(),
    )
  }
}

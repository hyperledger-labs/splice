package com.daml.network.metrics

import com.codahale.metrics
import com.daml.metrics.{HealthMetrics as DMHealth}
import com.daml.metrics.api.MetricsContext
import com.daml.metrics.grpc.DamlGrpcServerMetrics
import com.daml.network.directory.metrics.DirectoryAppMetrics
import com.daml.network.scan.metrics.ScanAppMetrics
import com.daml.network.splitwell.metrics.SplitwellAppMetrics
import com.daml.network.sv.metrics.SvAppMetrics
import com.daml.network.svc.metrics.SvcAppMetrics
import com.daml.network.validator.metrics.ValidatorAppMetrics
import com.daml.network.wallet.metrics.WalletAppMetrics
import com.digitalasset.canton.metrics.{MetricsConfig, MetricsFactory, MetricsFactoryType}
import com.digitalasset.canton.metrics.MetricHandle.CantonDropwizardMetricsFactory
import com.digitalasset.canton.metrics.MetricsFactory.registerReporter
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.sdk.metrics.SdkMeterProvider

import scala.collection.concurrent.TrieMap

case class CNNodeMetricsFactory(
    override val reporters: Seq[metrics.Reporter],
    override val registry: metrics.MetricRegistry,
    override val reportJVMMetrics: Boolean,
    override val meter: Meter,
    override val factoryType: MetricsFactoryType,
) extends MetricsFactory(
      reporters,
      registry,
      reportJVMMetrics,
      meter,
      factoryType,
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
        val metricsContext = MetricsContext("validator" -> name, "component" -> "validator")
        val labeledMetricsFactory =
          createLabeledMetricsFactory(metricsContext)
        new ValidatorAppMetrics(
          MetricsFactory.prefix,
          new CantonDropwizardMetricsFactory(newRegistry(metricName)),
          new DamlGrpcServerMetrics(labeledMetricsFactory, "Validator"),
          new DMHealth(labeledMetricsFactory),
        )
      },
    )
  }

  def forSvc(name: String): SvcAppMetrics = {
    svcs.getOrElseUpdate(
      name, {
        val metricName = deduplicateName(name, "SVC", svcs)
        val metricsContext = MetricsContext("svc" -> name, "component" -> "svc")
        val labeledMetricsFactory =
          createLabeledMetricsFactory(metricsContext)
        new SvcAppMetrics(
          MetricsFactory.prefix,
          new CantonDropwizardMetricsFactory(newRegistry(metricName)),
          new DamlGrpcServerMetrics(labeledMetricsFactory, "SVC"),
          new DMHealth(labeledMetricsFactory),
        )
      },
    )
  }

  def forSv(name: String): SvAppMetrics = {
    svs.getOrElseUpdate(
      name, {
        val metricName = deduplicateName(name, "SV", svcs)
        val metricsContext = MetricsContext("sv" -> name, "component" -> "sv")
        val labeledMetricsFactory =
          createLabeledMetricsFactory(metricsContext)
        new SvAppMetrics(
          MetricsFactory.prefix,
          new CantonDropwizardMetricsFactory(newRegistry(metricName)),
          new DamlGrpcServerMetrics(labeledMetricsFactory, "SV"),
          new DMHealth(labeledMetricsFactory),
        )
      },
    )
  }

  def forScan(name: String): ScanAppMetrics = {
    scans.getOrElseUpdate(
      name, {
        val metricName = deduplicateName(name, "Scan", scans)
        val metricsContext = MetricsContext("scan" -> name, "component" -> "scan")
        val labeledMetricsFactory =
          createLabeledMetricsFactory(metricsContext)
        new ScanAppMetrics(
          MetricsFactory.prefix,
          new CantonDropwizardMetricsFactory(newRegistry(metricName)),
          new DamlGrpcServerMetrics(labeledMetricsFactory, "Scan"),
          new DMHealth(labeledMetricsFactory),
        )
      },
    )
  }

  def forDirectory(name: String): DirectoryAppMetrics = {
    directories.getOrElseUpdate(
      name, {
        val metricName = deduplicateName(name, "Directory", directories)
        val metricsContext = MetricsContext("directory" -> name, "component" -> "directory")
        val labeledMetricsFactory =
          createLabeledMetricsFactory(metricsContext)
        new DirectoryAppMetrics(
          MetricsFactory.prefix,
          new CantonDropwizardMetricsFactory(newRegistry(metricName)),
          new DamlGrpcServerMetrics(labeledMetricsFactory, "Directory"),
          new DMHealth(labeledMetricsFactory),
        )
      },
    )
  }

  def forSplitwell(name: String): SplitwellAppMetrics = {
    splitwells.getOrElseUpdate(
      name, {
        val metricName = deduplicateName(name, "Splitwell", splitwells)
        val metricsContext = MetricsContext("splitwell" -> name, "component" -> "splitwell")
        val labeledMetricsFactory =
          createLabeledMetricsFactory(metricsContext)
        new SplitwellAppMetrics(
          MetricsFactory.prefix,
          new CantonDropwizardMetricsFactory(newRegistry(metricName)),
          new DamlGrpcServerMetrics(labeledMetricsFactory, "Splitwell"),
          new DMHealth(labeledMetricsFactory),
        )
      },
    )
  }
}

object CNNodeMetricsFactory {
  def forConfig(
      config: MetricsConfig,
      metricsFactoryType: MetricsFactoryType,
  ): CNNodeMetricsFactory = {
    val registry = new metrics.MetricRegistry()
    val meterProviderBuilder = SdkMeterProvider.builder()
    val reporter = registerReporter(config, registry)
    val meterProvider = meterProviderBuilder.build()
    new CNNodeMetricsFactory(
      reporter,
      registry,
      config.reportJvmMetrics,
      meterProvider.meterBuilder("daml").build(),
      metricsFactoryType,
    )
  }
}

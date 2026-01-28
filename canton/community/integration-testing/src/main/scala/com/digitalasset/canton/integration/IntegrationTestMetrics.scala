// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.integration

import com.daml.metrics.api.{MetricHandle, MetricInfo, MetricName, MetricsContext}
import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.daml.metrics.api.MetricQualification.Debug
import org.scalatest.Suite

import scala.collection.concurrent.TrieMap

/**
  */
trait IntegrationTestMetrics {
  this: Suite =>

  /** Override this to enable collecting metrics.
    *
    * Metric factories are normally created as part of the environment in EnvironmentSetup,
    * and are used to monitor running Canton applications.
    * Here we want to collect metrics about the test infrastructure, i.e., code that runs before
    * EnvironmentSetup starts the application. We therefore can't reuse any existing metric factories.
    */
  protected def testInfrastructureMetricsFactory: LabeledMetricsFactory = NoOpMetricsFactory

  protected def testInfrastructureSuiteMetrics: IntegrationTestMetrics.SuiteMetrics =
    IntegrationTestMetrics.suiteMetrics.getOrElseUpdate(
      suiteName, {
        new IntegrationTestMetrics.SuiteMetrics(
          suiteName,
          testInfrastructureMetricsFactory,
        )
      },
    )
  protected def testInfrastructureTestMetrics(
      testName: String
  ): IntegrationTestMetrics.TestMetrics =
    IntegrationTestMetrics.testMetrics.getOrElseUpdate(
      (suiteName, testName), {
        new IntegrationTestMetrics.TestMetrics(
          suiteName,
          testName,
          testInfrastructureMetricsFactory,
        )
      },
    )

  protected def testInfrastructureEnvironmentMetrics(
      testName: Option[String]
  ): IntegrationTestMetrics.EnvironmentMetrics =
    testName.fold[IntegrationTestMetrics.EnvironmentMetrics](testInfrastructureSuiteMetrics)(
      testInfrastructureTestMetrics
    )
}

object IntegrationTestMetrics {
  private val metricsPrefix = MetricName.Daml :+ "integration_test"
  private val suiteMetrics = TrieMap[String, SuiteMetrics]()
  private val testMetrics = TrieMap[(String, String), TestMetrics]()

  sealed trait EnvironmentMetrics {
    def environmentWait: MetricHandle.Timer
    def environmentCreate: MetricHandle.Timer
    def environmentDestroy: MetricHandle.Timer
    def environmentCreatePluginsBefore: MetricHandle.Timer
    def environmentCreatePluginsAfter: MetricHandle.Timer
    def environmentCreateFixture: MetricHandle.Timer
  }

  class SuiteMetrics(suiteName: String, metricsFactory: LabeledMetricsFactory)
      extends EnvironmentMetrics {
    val context = MetricsContext(
      "suite_name" -> this.suiteName
    )
    override val environmentCreate = metricsFactory.timer(
      MetricInfo(
        metricsPrefix :+ "environment_create",
        "Time it takes to create the environment",
        Debug,
      )
    )(context)
    override val environmentWait = metricsFactory.timer(
      MetricInfo(
        metricsPrefix :+ "environment_wait",
        "Time the suite spends waiting in ConcurrentEnvironmentLimiter",
        Debug,
      )
    )(context)
    override val environmentDestroy = metricsFactory.timer(
      MetricInfo(
        metricsPrefix :+ "environment_destroy",
        "Time it takes to clean up the environment",
        Debug,
      )
    )(context)
    override val environmentCreatePluginsBefore = metricsFactory.timer(
      MetricInfo(
        metricsPrefix :+ "environment_create_plugins_before",
        "Time it takes to run beforeEnvironmentCreated for all plugins",
        Debug,
      )
    )(context)
    override val environmentCreatePluginsAfter = metricsFactory.timer(
      MetricInfo(
        metricsPrefix :+ "environment_create_plugins_after",
        "Time it takes to run afterEnvironmentCreated for all plugins",
        Debug,
      )
    )(context)
    override val environmentCreateFixture = metricsFactory.timer(
      MetricInfo(
        metricsPrefix :+ "environment_create_fixture",
        "Time it takes to run EnvironmentFactory.create",
        Debug,
      )
    )(context)

    val pluginsBeforeTests = metricsFactory.timer(
      MetricInfo(
        metricsPrefix :+ "plugins_before_tests",
        "Time it takes plugins to initialize before all tests",
        Debug,
      )
    )(context)
    val pluginsAfterTests = metricsFactory.timer(
      MetricInfo(
        metricsPrefix :+ "plugins_after_tests",
        "Time it takes plugins to clean up after all tests",
        Debug,
      )
    )(context)
  }

  class TestMetrics(
      suiteName: String,
      testName: String,
      metricsFactory: LabeledMetricsFactory,
  ) extends EnvironmentMetrics {
    val context = MetricsContext(
      "suite_name" -> this.suiteName,
      "test_name" -> this.testName,
    )
    override val environmentCreate = metricsFactory.timer(
      MetricInfo(
        metricsPrefix :+ "environment_create",
        "Time it takes to create the environment",
        Debug,
      )
    )(context)
    override val environmentWait = metricsFactory.timer(
      MetricInfo(
        metricsPrefix :+ "environment_wait",
        "Time the suite spends waiting in ConcurrentEnvironmentLimiter",
        Debug,
      )
    )(context)
    override val environmentDestroy = metricsFactory.timer(
      MetricInfo(
        metricsPrefix :+ "environment_destroy",
        "Time it takes to clean up the environment",
        Debug,
      )
    )(context)
    override val environmentCreatePluginsBefore = metricsFactory.timer(
      MetricInfo(
        metricsPrefix :+ "environment_create_plugins_before",
        "Time it takes to run beforeEnvironmentCreated for all plugins",
        Debug,
      )
    )(context)
    override val environmentCreatePluginsAfter = metricsFactory.timer(
      MetricInfo(
        metricsPrefix :+ "environment_create_plugins_after",
        "Time it takes to run afterEnvironmentCreated for all plugins",
        Debug,
      )
    )(context)
    override val environmentCreateFixture = metricsFactory.timer(
      MetricInfo(
        metricsPrefix :+ "environment_create_fixture",
        "Time it takes to run EnvironmentFactory.create",
        Debug,
      )
    )(context)

    val testExecution = metricsFactory.timer(
      MetricInfo(
        metricsPrefix :+ "test_execution",
        "Time it takes to run the test body",
        Debug,
      )
    )(context)
    val testProvideEnvironment = metricsFactory.timer(
      MetricInfo(
        metricsPrefix :+ "test_provide_environment",
        "Time it takes to run provideEnvironment()",
        Debug,
      )
    )(context)
    val testFinished = metricsFactory.timer(
      MetricInfo(
        metricsPrefix :+ "test_finished",
        "Time it takes to run testFinished()",
        Debug,
      )
    )(context)
  }
}

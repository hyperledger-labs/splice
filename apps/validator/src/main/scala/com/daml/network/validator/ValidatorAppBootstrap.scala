// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.validator

import org.apache.pekko.actor.ActorSystem
import cats.data.EitherT
import cats.implicits.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.http.AdminRoutes
import com.daml.network.config.{ANStorageFactory, SharedSpliceAppParameters}
import com.daml.network.environment.NodeBootstrapBase
import com.daml.network.validator.config.ValidatorAppBackendConfig
import com.daml.network.validator.metrics.ValidatorAppMetrics
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  FutureSupervisor,
}
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.*
import com.digitalasset.canton.telemetry.ConfiguredOpenTelemetry
import com.digitalasset.canton.time.*

import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.Future

/** Class used to orchester the starting/initialization of Validator node.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class ValidatorAppBootstrap(
    override val name: InstanceName,
    val config: ValidatorAppBackendConfig,
    val validatorAppParameters: SharedSpliceAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    metrics: ValidatorAppMetrics,
    storageFactory: StorageFactory,
    loggerFactory: NamedLoggerFactory,
    futureSupervisor: FutureSupervisor,
    configuredOpenTelemetry: ConfiguredOpenTelemetry,
)(implicit
    executionContext: ExecutionContextIdlenessExecutorService,
    scheduler: ScheduledExecutorService,
    actorSystem: ActorSystem,
    executionSequencerFactory: ExecutionSequencerFactory,
) extends NodeBootstrapBase[
      ValidatorApp,
      ValidatorAppBackendConfig,
      SharedSpliceAppParameters,
    ](
      config,
      name,
      validatorAppParameters,
      clock,
      metrics,
      storageFactory,
      loggerFactory,
      configuredOpenTelemetry,
    ) {

  override def initialize(adminRoutes: AdminRoutes): EitherT[Future, String, Unit] =
    startInstanceUnlessClosing {
      EitherT.fromEither(
        Right(
          new ValidatorApp(
            name,
            config,
            validatorAppParameters,
            storage,
            clock,
            loggerFactory,
            tracerProvider,
            futureSupervisor,
            metrics,
            adminRoutes,
          )
        )
      )
    }

  override def isActive: Boolean = storage.isActive
}

object ValidatorAppBootstrap {
  val LoggerFactoryKeyName: String = "validator"

  def apply(
      name: String,
      validatorConfig: ValidatorAppBackendConfig,
      validatorAppParameters: SharedSpliceAppParameters,
      clock: Clock,
      validatorMetrics: ValidatorAppMetrics,
      testingConfigInternal: TestingConfigInternal,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
      configuredOpenTelemetry: ConfiguredOpenTelemetry,
  )(implicit
      executionContext: ExecutionContextIdlenessExecutorService,
      scheduler: ScheduledExecutorService,
      actorSystem: ActorSystem,
      executionSequencerFactory: ExecutionSequencerFactory,
  ): Either[String, ValidatorAppBootstrap] =
    InstanceName
      .create(name)
      .map(
        new ValidatorAppBootstrap(
          _,
          validatorConfig,
          validatorAppParameters,
          testingConfigInternal,
          clock,
          validatorMetrics,
          new ANStorageFactory(validatorConfig.storage),
          loggerFactory,
          futureSupervisor,
          configuredOpenTelemetry,
        )
      )
      .leftMap(_.toString)
}

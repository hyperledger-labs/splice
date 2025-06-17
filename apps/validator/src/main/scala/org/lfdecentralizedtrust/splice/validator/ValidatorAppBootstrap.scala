// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator

import org.apache.pekko.actor.ActorSystem
import cats.data.EitherT
import cats.implicits.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import org.lfdecentralizedtrust.splice.admin.http.AdminRoutes
import org.lfdecentralizedtrust.splice.config.SharedSpliceAppParameters
import org.lfdecentralizedtrust.splice.environment.NodeBootstrapBase
import org.lfdecentralizedtrust.splice.validator.config.ValidatorAppBackendConfig
import org.lfdecentralizedtrust.splice.validator.metrics.ValidatorAppMetrics
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
    override val metrics: ValidatorAppMetrics,
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
          new CommunityStorageFactory(validatorConfig.storage),
          loggerFactory,
          futureSupervisor,
          configuredOpenTelemetry,
        )
      )
      .leftMap(_.toString)
}

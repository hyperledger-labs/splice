package com.daml.network.environment

import com.daml.network.validator.ValidatorNodeBootstrap
import com.daml.network.validator.config.{LocalValidatorConfig, ValidatorNodeParameters}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.environment.{StartFailed, StartupError}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbMigrationsFactory
import com.digitalasset.canton.tracing.TraceContext

import scala.Function.tupled
import scala.util.Try
import cats.syntax.either._
import cats.instances.either._
import com.digitalasset.canton.lifecycle.{FlagCloseable, HasCloseContext}

/** Validator app instances. */
case class ValidatorApps(
    create: (String, LocalValidatorConfig) => ValidatorNodeBootstrap,
    migrationsFactory: DbMigrationsFactory,
    timeouts: ProcessingTimeout,
    configs: Map[String, LocalValidatorConfig],
    parametersFor: String => ValidatorNodeParameters,
    loggerFactory: NamedLoggerFactory,
) extends NamedLogging
    with HasCloseContext
    with FlagCloseable {

  // TODO(Arne): This is essentially just copied from `ManagedNodes`.
  // Is there a better way to reuse this?
  def start(
      name: String,
      nodeConfig: LocalValidatorConfig,
  )(implicit traceContext: TraceContext): Either[StartupError, ValidatorNodeBootstrap] = {
    val params = parametersFor(name)
    val instance = create(name, nodeConfig)
    val res = Try(
      params.processingTimeouts.unbounded.await(s"Starting node $name")(
        instance.start().value
      )
    ).fold(
      ex => throw ex,
      _.leftMap { error =>
        instance.close() // clean up resources allocated during instance creation (e.g., db)
        StartFailed(name, error)
      },
    )
    res.map(_ => instance)
  }

  def startAll(implicit traceContext: TraceContext): Either[Seq[StartupError], Unit] = {
    configs
      .map { tupled(start(_, _)) }
      .toList
      .foldLeft[Either[Seq[StartupError], Unit]](Right(())) {
        case (results, Right(_)) => results
        case (Left(errors), Left(error)) => Left(errors :+ error)
        case (Right(_), Left(error)) => Left(Seq(error))
      }
  }
}

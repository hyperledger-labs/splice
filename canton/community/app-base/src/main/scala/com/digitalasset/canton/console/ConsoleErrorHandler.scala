// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.console

import com.digitalasset.canton.console.CommandErrors.{
  CantonCommandError,
  CommandError,
  GenericCommandError,
}

import scala.annotation.unused
import scala.util.control.NoStackTrace

/** Handle an error from a console.
  * We expect this implementation will either throw or exit, hence the [[scala.Nothing]] return type.
  */
trait ConsoleErrorHandler {
  def handleCommandFailure(cause: Option[String] = None, err: CommandError): Nothing

  def handleInternalError(err: CantonCommandError): Nothing
}

sealed trait CommandFailure extends Throwable
sealed trait CantonInternalError extends Throwable

final class InteractiveCommandFailure(cause: Option[String] = None)
    extends Throwable(s"Command execution failed. ${cause.getOrElse("")}")
    with CommandFailure
    with NoStackTrace

final class InteractiveCantonInternalError()
    extends Throwable(
      "Command execution failed due to an internal error. Please file a bug report."
    )
    with CantonInternalError
    with NoStackTrace

final class CommandFailureWithDetails(details: String, cause: Throwable)
    extends Throwable(s"Command execution failed: $details", cause)
    with CommandFailure

final class CantonInternalErrorWithDetails(details: String, cause: Throwable)
    extends Throwable(
      s"Command execution failed due to an internal error: $details",
      cause,
    )
    with CantonInternalError

/** Throws a [[InteractiveCommandFailure]] or [[InteractiveCantonInternalError]] when a command fails.
  * The throwables do not have a stacktraces, to avoid noise in the interactive console.
  */
object ThrowErrorHandler extends ConsoleErrorHandler {
  override def handleCommandFailure(
      cause: Option[String],
      @unused err: CommandError,
  ): Nothing =
    throw new InteractiveCommandFailure(cause)

  override def handleInternalError(@unused err: CantonCommandError): Nothing =
    throw new InteractiveCantonInternalError()
}

/** Throws a [[CommandFailureWithDetails]] or [[CantonInternalErrorWithDetails]] when a command fails.
  * The throwables have stack traces and causes, to help debugging.
  */
object ThrowWithDetailsErrorHandler extends ConsoleErrorHandler {
  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  override def handleCommandFailure(
      @unused cause: Option[String],
      @unused err: CommandError,
  ): Nothing = err match {
    case err: CantonCommandError =>
      throw new CommandFailureWithDetails(cause.getOrElse(err.cause), err.throwableO.orNull)
    case err: GenericCommandError =>
      throw new CommandFailureWithDetails(cause.getOrElse(err.cause), null)
  }

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  override def handleInternalError(err: CantonCommandError): Nothing =
    throw new CantonInternalErrorWithDetails(err.cause, err.throwableO.orNull)
}

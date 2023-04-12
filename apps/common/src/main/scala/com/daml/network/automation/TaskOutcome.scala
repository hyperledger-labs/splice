package com.daml.network.automation

import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}

sealed trait TaskOutcome extends Product with Serializable

/** Helper class for modelling the outcome of a task handled by a trigger.
  *
  * @param description in most cases a short description of how the task was completed, sometimes also a return value.
  *                Should be a Left-value if the task failed in some way.
  */
case class TaskSuccess(
    description: String
) extends TaskOutcome

case object TaskStale extends TaskOutcome with PrettyPrinting {
  override def pretty: Pretty[this.type] = {
    prettyOfString(_ => "skipped, as the task has become stale")
  }
}

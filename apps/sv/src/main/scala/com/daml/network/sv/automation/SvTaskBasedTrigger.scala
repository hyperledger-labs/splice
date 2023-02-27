package com.daml.network.sv.automation

import com.daml.network.automation.*
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

trait SvTaskBasedTrigger[T] {
  protected implicit def ec: ExecutionContext

  protected def completeTask(
      task: T
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    isLeader()
      .flatMap(if (_) {
        completeTaskAsLeader(task)
      } else {
        completeTaskAsFollower(task)
      })
  }

  protected def completeTaskAsLeader(
      task: T
  )(implicit tc: TraceContext): Future[TaskOutcome]

  protected def completeTaskAsFollower(
      task: T
  )(implicit tc: TraceContext): Future[TaskOutcome]

  protected def isLeader(): Future[Boolean]
}

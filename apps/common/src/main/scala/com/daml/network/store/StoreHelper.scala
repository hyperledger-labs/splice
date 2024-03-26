package com.daml.network.store

import com.daml.network.codegen.java.cn.dsorules.VoteRequestOutcome as VRO
import com.daml.network.codegen.java.cn.dsorules.voterequestoutcome.VRO_Accepted

import java.time.Instant

sealed trait VoteRequestOutcome {
  val effectiveAt: Option[Instant]
}
case class Executed(effectiveAt: Option[Instant]) extends VoteRequestOutcome
case class NotExecuted(effectiveAt: Option[Instant]) extends VoteRequestOutcome
case class Rejected(effectiveAt: Option[Instant]) extends VoteRequestOutcome

object VoteRequestOutcome {
  def parse(outcome: VRO): VoteRequestOutcome = {
    outcome match {
      case request: VRO_Accepted =>
        if (request.effectiveAt.isBefore(Instant.now())) {
          Executed(Some(request.effectiveAt))
        } else {
          NotExecuted(Some(request.effectiveAt))
        }
      case _ => Rejected(None)
    }
  }
}

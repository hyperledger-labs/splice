package com.daml.network.store

import com.daml.network.codegen.java.cn.svcrules.VoteRequestOutcome2
import com.daml.network.codegen.java.cn.svcrules.voterequestoutcome2.VRO_Accepted
import java.time.Instant

sealed trait VoteRequestOutcome {
  val effectiveAt: Option[Instant]
}
case class Executed(effectiveAt: Option[Instant]) extends VoteRequestOutcome
case class NotExecuted(effectiveAt: Option[Instant]) extends VoteRequestOutcome
case class Rejected(effectiveAt: Option[Instant]) extends VoteRequestOutcome

object VoteRequestOutcome {
  def parse(outcome: VoteRequestOutcome2): VoteRequestOutcome = {
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

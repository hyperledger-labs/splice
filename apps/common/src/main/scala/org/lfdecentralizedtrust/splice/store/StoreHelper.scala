// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.VoteRequestOutcome as VRO
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.voterequestoutcome.VRO_Accepted

import java.time.Instant

sealed trait VoteRequestOutcome {
  val effectiveAt: Option[Instant]
}
case class Accepted(effectiveAt: Option[Instant]) extends VoteRequestOutcome
case class Rejected(effectiveAt: Option[Instant]) extends VoteRequestOutcome

object VoteRequestOutcome {
  def parse(outcome: VRO): VoteRequestOutcome = {
    outcome match {
      case request: VRO_Accepted =>
        Accepted(Some(request.effectiveAt))
      case _ => Rejected(None)
    }
  }
}

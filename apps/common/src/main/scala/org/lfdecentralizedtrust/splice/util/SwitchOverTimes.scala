// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.time.Clock
import java.time.Instant
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules

object SwitchOverTimes {

  private def shouldSwitchOver(
      clock: Clock,
      times: java.util.Optional[java.util.Map[String, Instant]],
      key: String,
  ): Boolean = {
    val timeO = Option(times.orElse(java.util.Map.of()).get(key))
    timeO.fold(false)(time => clock.now >= CantonTimestamp.assertFromInstant(time))
  }

  private def shouldSwitchOver(clock: Clock, dsoRules: DsoRules, key: String): Boolean =
    shouldSwitchOver(clock, dsoRules.config.svOperationsSwitchOverTimes, key)

  def omitFeaturedAppRightInChoiceContext(clock: Clock, dsoRules: DsoRules): Boolean =
    shouldSwitchOver(clock, dsoRules, NoFeaturedAppChoiceContext)

  def alwaysServeVerdictRoundNumber(clock: Clock, dsoRules: DsoRules): Boolean =
    shouldSwitchOver(clock, dsoRules, AlwaysServeVerdictRoundNumber)

  val NoFeaturedAppChoiceContext = "no-featured-app-choice-context"
  val AlwaysServeVerdictRoundNumber = "always-serve-verdict-round-number"
}

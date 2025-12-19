// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import AutomationServiceCompanion.TriggerClass

abstract class AutomationServiceCompanion {

  /** If empty, expected triggers are unknown; otherwise,
    * [[AutomationService#registerTrigger]] will warn if a trigger isn't present
    * in this list.  This list should be exhaustive if either the automation
    * service is dynamically created (e.g. UserWallet), or registered triggers
    * may happen on a delay (e.g. SvDso offboarding).
    */
  protected[this] def expectedTriggerClasses: Seq[TriggerClass]

  lazy val expectedTriggers: Set[String] =
    expectedTriggerClasses.view.map(AutomationService.identifyTriggerClassByName).toSet
}

object AutomationServiceCompanion {
  type TriggerClass = Class[?]

  def aTrigger[T <: Trigger](implicit tag: reflect.ClassTag[T]): TriggerClass =
    tag.runtimeClass
}

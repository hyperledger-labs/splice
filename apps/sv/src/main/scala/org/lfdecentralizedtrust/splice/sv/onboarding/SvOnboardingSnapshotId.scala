// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding

import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}

final case class SvOnboardingSnapshotId(value: String) extends PrettyPrinting {
  override def pretty: Pretty[this.type] = prettyOfString(_.value)
}

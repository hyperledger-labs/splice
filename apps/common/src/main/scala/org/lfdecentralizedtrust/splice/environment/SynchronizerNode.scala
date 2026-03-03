// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import org.lfdecentralizedtrust.splice.environment.*

abstract class SynchronizerNode(
    val sequencerAdminConnection: SequencerAdminConnection,
    val mediatorAdminConnection: MediatorAdminConnection,
    val serial: NonNegativeInt,
) {}

object SynchronizerNode {

  case class LocalSynchronizerNodes[T](
      current: T,
      successor: Option[T],
  )
}

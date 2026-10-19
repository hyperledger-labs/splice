// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.config

import pureconfig.{ConfigReader, ConfigWriter}

object PositiveFiniteDurationAccess {
  implicit val positiveFiniteDurationReader: ConfigReader[PositiveFiniteDuration] =
    PositiveFiniteDuration.positiveFiniteDurationReader

  implicit val positiveFiniteDurationWriter: ConfigWriter[PositiveFiniteDuration] =
    PositiveFiniteDuration.positiveFiniteDurationWriter
}

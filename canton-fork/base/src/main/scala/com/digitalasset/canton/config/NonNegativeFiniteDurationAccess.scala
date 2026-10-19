// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.config

import pureconfig.{ConfigReader, ConfigWriter}

object NonNegativeFiniteDurationAccess {
  implicit val nonNegativeFiniteDurationReader: ConfigReader[NonNegativeFiniteDuration] =
    NonNegativeFiniteDuration.nonNegativeFiniteDurationReader

  implicit val nonNegativeFiniteDurationWriter: ConfigWriter[NonNegativeFiniteDuration] =
    NonNegativeFiniteDuration.nonNegativeFiniteDurationWriter
}

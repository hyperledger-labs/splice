// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.config

import com.digitalasset.canton.config.ConfigErrors.CantonConfigError
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.nonempty.NonEmpty
import com.typesafe.config.{Config, ConfigFactory}

import java.io.File

object CantonConfigAccess {
  implicit class `CantonConfig access`(private val self: CantonConfig.type) extends AnyVal {

    /** Same as the private `CantonConfig.parseAndMergeConfigs`. */
    def parseAndMergeConfigs(
        files: NonEmpty[Seq[File]]
    )(implicit elc: ErrorLoggingContext): Either[CantonConfigError, Config] =
      self.parseAndMergeJustCLIConfigs(files).map(_.withFallback(ConfigFactory.load()))
  }
}

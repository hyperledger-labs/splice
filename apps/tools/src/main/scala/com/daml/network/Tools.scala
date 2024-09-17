// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.tools

import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.NoTracing

object Tools extends NoTracing {
  def main(args: Array[String]) = {
    val loggerFactory = NamedLoggerFactory.root

    Auth0TestUserCleaner.run(
      "https://canton-network-dev.us.auth0.com",
      Tools.readMandatoryEnvVar("AUTH0_CN_MANAGEMENT_API_CLIENT_ID"),
      Tools.readMandatoryEnvVar("AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET"),
      loggerFactory,
    )

    Auth0TestUserCleaner.run(
      "https://canton-network-test.us.auth0.com",
      Tools.readMandatoryEnvVar("AUTH0_TESTS_MANAGEMENT_API_CLIENT_ID"),
      Tools.readMandatoryEnvVar("AUTH0_TESTS_MANAGEMENT_API_CLIENT_SECRET"),
      loggerFactory,
    )
  }

  def readMandatoryEnvVar(name: String): String = {
    sys.env.get(name) match {
      case None => sys.error(s"Environment variable $name must be set")
      case Some(s) if s.isEmpty => sys.error(s"Environment variable $name must be non-empty")
      case Some(s) => s
    }
  }
}

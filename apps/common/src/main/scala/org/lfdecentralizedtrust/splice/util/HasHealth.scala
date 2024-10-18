// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

/** Trait for services that can report their health. */
trait HasHealth {

  /** True if the service is running in healthy state */
  def isHealthy: Boolean
}

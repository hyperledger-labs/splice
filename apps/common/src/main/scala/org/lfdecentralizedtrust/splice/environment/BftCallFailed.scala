// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

sealed abstract class BftCallFailed(message: String) extends RuntimeException(message)

object BftCallFailed {
  final class NotYetAvailable(message: String) extends BftCallFailed(message)
  final class NeverAvailable(message: String) extends BftCallFailed(message)
  final class Disagreement(message: String) extends BftCallFailed(message)
  final class NotEnoughScans(message: String) extends BftCallFailed(message)
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

/** A BFT call across Scans that did not produce an agreed result. */
sealed abstract class BftCallFailed(message: String) extends RuntimeException(message)

object BftCallFailed {

  /** Too few Scans have the requested data yet, but more of them will; retrying later can succeed. */
  final class NotYetAvailable(message: String) extends BftCallFailed(message)

  /** No Scan will ever have the requested data, e.g. it lies before the history of every Scan; retrying cannot succeed. */
  final class NeverAvailable(message: String) extends BftCallFailed(message)

  /** Enough Scans answered, but not enough of them returned the same result. */
  final class Disagreement(message: String) extends BftCallFailed(message)

  /** Fewer Scans can be used than a BFT result requires. */
  final class NotEnoughScans(message: String) extends BftCallFailed(message)
}

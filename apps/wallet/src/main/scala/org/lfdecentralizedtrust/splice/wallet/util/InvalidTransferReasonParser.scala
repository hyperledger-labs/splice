// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.util

import io.grpc.{Status, StatusRuntimeException}

import scala.util.matching.Regex

object InvalidTransferReasonParser {

  sealed trait ParsedInvalidTransferReason

  object ParsedInvalidTransferReason {
    final case class InsufficientFunds(missingAmount: BigDecimal)
        extends ParsedInvalidTransferReason

    final case class UnknownSynchronizer(synchronizerId: String) extends ParsedInvalidTransferReason

    final case class InsufficientTopupAmount(
        requestedTopupAmount: Long,
        minTopupAmount: Long,
    ) extends ParsedInvalidTransferReason

    final case class Other(description: String) extends ParsedInvalidTransferReason
  }

  private val InvalidTransferMarker = "Splice.AmuletRules:InvalidTransfer"

  def isInvalidTransferException(ex: StatusRuntimeException): Boolean =
    ex.getStatus.getCode == Status.Code.FAILED_PRECONDITION &&
      ex.getStatus.getDescription.contains(InvalidTransferMarker)

  import ParsedInvalidTransferReason.InsufficientFunds
  import ParsedInvalidTransferReason.InsufficientTopupAmount
  import ParsedInvalidTransferReason.Other
  import ParsedInvalidTransferReason.UnknownSynchronizer

  private val InsufficientFundsPattern: Regex =
    """ITR_InsufficientFunds\s*\{\s*missingAmount\s*=\s*([0-9.]+)\s*\}""".r

  private val UnknownSynchronizerPattern: Regex =
    """ITR_UnknownSynchronizer\s*\{\s*synchronizerId\s*=\s*([^}]+)\s*\}""".r

  private val InsufficientTopupAmountPattern: Regex =
    """ITR_InsufficientTopupAmount\s*\{\s*requestedTopupAmount\s*=\s*([0-9]+)\s*,\s*minTopupAmount\s*=\s*([0-9]+)\s*\}""".r

  private val OtherPattern: Regex =
    """ITR_Other\s*\{\s*description\s*=\s*([^}]+)\s*\}""".r

  private val parsers: List[String => Option[ParsedInvalidTransferReason]] =
    List(
      parseInsufficientFunds,
      parseUnknownSynchronizer,
      parseInsufficientTopupAmount,
      parseOther,
    )

  def parse(text: String): Option[ParsedInvalidTransferReason] =
    parsers.iterator.map(parser => parser(text)).collectFirst { case Some(value) =>
      value
    }

  private def parseInsufficientFunds(
      text: String
  ): Option[ParsedInvalidTransferReason] =
    InsufficientFundsPattern
      .findFirstMatchIn(text)
      .map(m => InsufficientFunds(BigDecimal(m.group(1))))

  private def parseUnknownSynchronizer(
      text: String
  ): Option[ParsedInvalidTransferReason] =
    UnknownSynchronizerPattern
      .findFirstMatchIn(text)
      .map(m => UnknownSynchronizer(m.group(1).trim))

  private def parseInsufficientTopupAmount(
      text: String
  ): Option[ParsedInvalidTransferReason] =
    InsufficientTopupAmountPattern
      .findFirstMatchIn(text)
      .map { m =>
        InsufficientTopupAmount(
          requestedTopupAmount = m.group(1).toLong,
          minTopupAmount = m.group(2).toLong,
        )
      }

  private def parseOther(
      text: String
  ): Option[ParsedInvalidTransferReason] =
    OtherPattern
      .findFirstMatchIn(text)
      .map(m => Other(m.group(1).trim))
}

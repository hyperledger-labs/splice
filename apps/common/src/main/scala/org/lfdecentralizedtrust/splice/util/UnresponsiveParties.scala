// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.base.error.utils.ErrorDetails
import com.digitalasset.canton.error.MediatorError
import com.digitalasset.canton.topology.PartyId
import io.grpc.StatusRuntimeException
import io.grpc.protobuf.StatusProto

object UnresponsiveParties {

  def fromThrowable(t: Throwable): Option[Set[PartyId]] = t match {
    case ex: StatusRuntimeException =>
      ErrorDetails.from(StatusProto.fromThrowable(ex)).collectFirst {
        case UnresponsiveParties(parties) => parties
      }
    case _ => None
  }

  def unapply(errorDetail: ErrorDetails.ErrorDetail): Option[Set[PartyId]] =
    errorDetail match {
      case ErrorDetails.ErrorInfoDetail(MediatorError.Timeout.id, metadata) =>
        Some(
          metadata
            .get("unresponsiveParties")
            .toList
            .flatMap(
              _.split(',').filter(_.nonEmpty).flatMap { partyStr =>
                PartyId.fromProtoPrimitive(partyStr, "unresponsiveParties").toOption
              }
            )
            .toSet
        )
      case _ => None
    }
}

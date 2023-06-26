// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.traffic

import com.digitalasset.canton.config.RequireTypes.{NonNegativeLong, PositiveLong}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.traffic.v0.MemberTrafficStatus.TopUpEvent as TopUpEventP
import com.digitalasset.canton.{ProtoDeserializationError, SequencerCounter}
import slick.jdbc.GetResult

object TopUpEvent {
  implicit val ordering: Ordering[TopUpEvent] = {
    // Order first by timestamp then by sequencer counter to differentiate if necessary
    (x: TopUpEvent, y: TopUpEvent) =>
      {
        x.validFromInclusive compare y.validFromInclusive match {
          case 0 => x.sequencerCounter compare y.sequencerCounter
          case c => c
        }
      }
  }

  import com.digitalasset.canton.store.db.RequiredTypesCodec.positiveLongGetResult

  implicit val topUpEventGetResult: GetResult[TopUpEvent] =
    GetResult.createGetTuple3[CantonTimestamp, PositiveLong, SequencerCounter].andThen {
      case (ts, limit, sc) =>
        TopUpEvent(limit, ts, sc)
    }

  implicit class EnhancedOption(val limitOpt: Option[TopUpEvent]) extends AnyVal {
    def asNonNegative: NonNegativeLong =
      limitOpt.map(_.limit.toNonNegative).getOrElse(NonNegativeLong.zero)
  }

  def fromProtoV0(
      topUp: TopUpEventP
  ): Either[ProtoDeserializationError, TopUpEvent] = {
    for {
      limit <- ProtoConverter.parsePositiveLong(topUp.extraTrafficLimit)
      validFrom <- ProtoConverter.parseRequired(
        CantonTimestamp.fromProtoPrimitive,
        "effective_at",
        topUp.effectiveAt,
      )
      sequencerCounter = SequencerCounter(topUp.sequencerCounter)
    } yield TopUpEvent(
      limit,
      validFrom,
      sequencerCounter,
    )
  }
}

final case class TopUpEvent(
    limit: PositiveLong,
    validFromInclusive: CantonTimestamp,
    sequencerCounter: SequencerCounter,
) {
  def toProtoV0: TopUpEventP = {
    TopUpEventP(
      Some(validFromInclusive.toProtoPrimitive),
      sequencerCounter = sequencerCounter.toProtoPrimitive,
      extraTrafficLimit = limit.value,
    )
  }
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.lsu

import cats.implicits.toBifunctorOps
import com.digitalasset.canton.topology.{MediatorId, SequencerId}
import com.google.protobuf.ByteString
import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump

import java.time.Instant
import java.util.Base64
import scala.util.Try

case class SynchronizerNodeIdentities(
    sequencer: NodeIdentitiesDump,
    mediator: NodeIdentitiesDump,
)

object SynchronizerNodeIdentities {

  implicit val encoder: Encoder[SynchronizerNodeIdentities] = {
    implicit val nodeIdentitiesDumpEncoder: Encoder[NodeIdentitiesDump] =
      (a: NodeIdentitiesDump) => a.toJson
    deriveEncoder[SynchronizerNodeIdentities]
  }

  implicit val decoder: Decoder[SynchronizerNodeIdentities] = {
    import definitions.NodeIdentitiesDump.decodeNodeIdentitiesDump
    Decoder.instance { cursor =>
      for {
        sequencerHttp <- cursor
          .downField("sequencer")
          .as[definitions.NodeIdentitiesDump]
        sequencer <- NodeIdentitiesDump
          .fromHttp(
            SequencerId
              .fromProtoPrimitive(_, "sequencerId")
              .getOrElse(throw new Exception("Invalid sequencer ID in JSON")),
            sequencerHttp,
          )
          .leftMap(failure => DecodingFailure(failure, List.empty))
        mediatorHttp <- cursor
          .downField("mediator")
          .as[definitions.NodeIdentitiesDump]
        mediator <- NodeIdentitiesDump
          .fromHttp(
            MediatorId
              .fromProtoPrimitive(_, "mediatorId")
              .getOrElse(throw new Exception("Invalid mediator ID in JSON")),
            mediatorHttp,
          )
          .leftMap(failure => DecodingFailure(failure, List.empty))
      } yield SynchronizerNodeIdentities(sequencer, mediator)
    }
  }
}

case class LsuState(
    upgradesAt: Instant,
    nodeIdentities: SynchronizerNodeIdentities,
    synchronizerState: ByteString,
)

object LsuState {

  implicit val encodeInstant: Encoder[Instant] =
    Encoder.encodeString.contramap[Instant](_.toString)

  implicit val decodeInstant: Decoder[Instant] =
    Decoder.decodeString.emapTry(str => Try(Instant.parse(str)))

  implicit val encodeByteString: Encoder[ByteString] =
    Encoder.instance(bs => Json.fromString(Base64.getEncoder.encodeToString(bs.toByteArray)))

  implicit val decodeByteString: Decoder[ByteString] =
    Decoder.decodeString.emap { str =>
      Try(ByteString.copyFrom(Base64.getDecoder.decode(str))).toEither.leftMap(t =>
        s"Failed to decode Base64 ByteString: ${t.getMessage}"
      )
    }

  implicit val encoder: Encoder[LsuState] = deriveEncoder[LsuState]
  implicit val decoder: Decoder[LsuState] = deriveDecoder[LsuState]
}

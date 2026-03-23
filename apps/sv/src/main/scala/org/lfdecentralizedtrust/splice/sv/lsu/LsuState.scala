// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.lsu

import cats.implicits.toBifunctorOps
import com.digitalasset.canton.topology.{MediatorId, SequencerId}
import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump

import java.nio.file.{Path, Paths}
import java.time.Instant
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
    synchronizerStatePath: Path,
)

object LsuState {

  implicit val encodeInstant: Encoder[Instant] =
    Encoder.encodeString.contramap[Instant](_.toString)

  implicit val decodeInstant: Decoder[Instant] =
    Decoder.decodeString.emapTry(str => Try(Instant.parse(str)))

  implicit val encodePath: Encoder[Path] =
    Encoder.instance(p => Json.fromString(p.toString))

  implicit val decodePath: Decoder[Path] =
    Decoder.decodeString.emap { str => Right(Paths.get(str)) }

  implicit val encoder: Encoder[LsuState] = deriveEncoder[LsuState]
  implicit val decoder: Decoder[LsuState] = deriveDecoder[LsuState]
}

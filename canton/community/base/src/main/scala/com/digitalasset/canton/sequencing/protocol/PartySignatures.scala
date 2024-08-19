// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.sequencing.protocol

import cats.data.EitherT
import cats.syntax.apply.*
import cats.syntax.either.*
import cats.syntax.parallel.*
import cats.syntax.traverse.*
import com.digitalasset.canton.LfPartyId
import com.digitalasset.canton.crypto.{DomainSnapshotSyncCryptoApi, Hash, Signature}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.protocol.v30
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.version.*

import scala.concurrent.{ExecutionContext, Future}

/** Signatures provided by non-hosted parties
  */
final case class PartySignatures private (
    signatures: Map[PartyId, Seq[Signature]]
)(
    override val representativeProtocolVersion: RepresentativeProtocolVersion[
      PartySignatures.type
    ]
) extends HasProtocolVersionedWrapper[PartySignatures]
    with PrettyPrinting {
  @transient override protected lazy val companionObj: PartySignatures.type =
    PartySignatures

  private[canton] def toProtoV30: v30.PartySignatures = v30.PartySignatures(
    signatures = signatures.map { case (party, signatures) =>
      v30.SinglePartySignatures(party.toProtoPrimitive, signatures.map(_.toProtoV30))
    }.toSeq
  )

  override def pretty: Pretty[this.type] = prettyOfClass(
    param("signatures", _.signatures)
  )

  def copy(
      signatures: Map[PartyId, Seq[Signature]]
  ): PartySignatures =
    PartySignatures(signatures)(representativeProtocolVersion)

  def verifySignatures(hash: Hash, cryptoSnapshot: DomainSnapshotSyncCryptoApi)(implicit
      traceContext: TraceContext,
      executionContext: ExecutionContext,
  ): EitherT[Future, String, Set[LfPartyId]] =
    signatures.toList
      .parTraverse { case (party, signatures) =>
        for {
          validKeys <- EitherT(
            cryptoSnapshot.ipsSnapshot
              .signingKeys(party)
              .onShutdown(None)
              .map(
                _.toRight(s"Could not find party signing keys for $party.")
              )
          )
          signaturesWithKeys <- EitherT.fromEither[Future](signatures.traverse { signature =>
            validKeys.signingKeys
              .find(_.fingerprint == signature.signedBy)
              .toRight(s"Signing key ${signature.signedBy} is not a valid key for $party")
              .map(key => (signature, key))
          })
          validSignatures <- EitherT
            .fromEither[Future](
              signaturesWithKeys
                .traverse { case (signature, key) =>
                  cryptoSnapshot.pureCrypto
                    .verifySignature(hash, key, signature)
                    .map { _ =>
                      key.fingerprint
                    }
                }
                .leftMap(_.toString)
            )
          validSignaturesSet = validSignatures.toSet
          _ <- EitherT.cond[Future](
            validSignaturesSet.size == validSignatures.size,
            (),
            s"The following signatures were provided one or more times for $party, all signatures must be unique: ${validSignatures
                .diff(validSignaturesSet.toList)}",
          )
          _ <- EitherT.cond[Future](
            validSignaturesSet.size >= validKeys.threshold.unwrap,
            (),
            s"Received ${validSignatures.size} signatures, but expected ${validKeys.threshold} for $party",
          )
        } yield party.toLf
      }
      .map(_.toSet)
}

object PartySignatures
    extends HasProtocolVersionedCompanion[PartySignatures]
    with ProtocolVersionedCompanionDbHelpers[PartySignatures] {

  def apply(
      signatures: Map[PartyId, Seq[Signature]],
      protocolVersion: ProtocolVersion,
  ): PartySignatures =
    PartySignatures(signatures)(protocolVersionRepresentativeFor(protocolVersion))

  override def name: String = "PartySignatures"

  override def supportedProtoVersions: SupportedProtoVersions = SupportedProtoVersions(
    ProtoVersion(-1) -> UnsupportedProtoCodec(ProtocolVersion.minimum),
    ProtoVersion(30) -> VersionedProtoConverter(ProtocolVersion.dev)(v30.PartySignatures)(
      supportedProtoVersion(_)(fromProtoV30),
      _.toProtoV30.toByteString,
    ),
  )

  private[canton] def fromProtoV30(
      proto: v30.PartySignatures
  ): ParsingResult[PartySignatures] = {
    val v30.PartySignatures(signaturesP) = proto
    for {
      signatures <- signaturesP.traverse { case v30.SinglePartySignatures(party, signatures) =>
        (
          PartyId.fromProtoPrimitive(party, "party"),
          signatures.traverse(Signature.fromProtoV30),
        ).tupled
      }
      rpv <- protocolVersionRepresentativeFor(ProtoVersion(30))
    } yield PartySignatures(signatures.toMap, rpv.representative)
  }
}

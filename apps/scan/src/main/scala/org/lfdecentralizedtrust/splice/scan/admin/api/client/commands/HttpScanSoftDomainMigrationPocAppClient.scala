// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse, StatusCodes}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import cats.syntax.traverse.*
import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.{definitions, scan_soft_domain_migration_poc as http}
import org.lfdecentralizedtrust.splice.http.v0.scan as scanHttp
import org.lfdecentralizedtrust.splice.util.{Codec, TemplateJsonDecoder}
import com.digitalasset.canton.topology.{MediatorId, SequencerId}
import com.digitalasset.canton.topology.transaction.SignedTopologyTransaction
import com.digitalasset.canton.topology.transaction.SignedTopologyTransaction.GenericSignedTopologyTransaction
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

object HttpScanSoftDomainMigrationPocAppClient {

  abstract class InternalBaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.ScanSoftDomainMigrationPocClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.ScanSoftDomainMigrationPocClient.httpClient(
        HttpClientBuilder().buildClient(Set(StatusCodes.NotFound)),
        host,
      )
  }

  abstract class ExternalBaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = scanHttp.ScanClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      scanHttp.ScanClient.httpClient(HttpClientBuilder().buildClient(), host)
  }

  final case class SynchronizerIdentities(
      sequencerId: SequencerId,
      sequencerIdentityTransactions: Seq[GenericSignedTopologyTransaction],
      mediatorId: MediatorId,
      mediatorIdentityTransactions: Seq[GenericSignedTopologyTransaction],
  )

  object SynchronizerIdentities {
    def fromHttp(
        identities: definitions.SynchronizerIdentities
    ): Either[String, SynchronizerIdentities] =
      for {
        sequencerId <- Codec.decode(Codec.Sequencer)(identities.sequencerId)
        sequencerIdentityTransactions <- identities.sequencerIdentityTransactions
          .traverse(tx =>
            // TODO(#13301) switch to safe version
            SignedTopologyTransaction.fromTrustedByteString(
              ByteString.copyFrom(Base64.getDecoder.decode(tx))
            )
          )
          .left
          .map(_.toString)
        mediatorId <- Codec.decode(Codec.Mediator)(identities.mediatorId)
        mediatorIdentityTransactions <- identities.mediatorIdentityTransactions
          .traverse(tx =>
            // TODO(#13301) switch to safe version
            SignedTopologyTransaction.fromTrustedByteString(
              ByteString.copyFrom(Base64.getDecoder.decode(tx))
            )
          )
          .left
          .map(_.toString)
      } yield SynchronizerIdentities(
        sequencerId,
        sequencerIdentityTransactions,
        mediatorId,
        mediatorIdentityTransactions,
      )
  }

  final case class GetSynchronizerIdentities(domainIdPrefix: String)
      extends InternalBaseCommand[http.GetSynchronizerIdentitiesResponse, SynchronizerIdentities] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetSynchronizerIdentitiesResponse] =
      client.getSynchronizerIdentities(
        domainIdPrefix
      )

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetSynchronizerIdentitiesResponse.OK(identities) =>
        SynchronizerIdentities.fromHttp(identities)
    }
  }

  final case class SynchronizerBootstrappingTransactions(
      domainParameters: GenericSignedTopologyTransaction,
      sequencerDomainState: GenericSignedTopologyTransaction,
      mediatorDomainState: GenericSignedTopologyTransaction,
  )

  object SynchronizerBootstrappingTransactions {
    def fromHttp(
        state: definitions.SynchronizerBootstrappingTransactions
    ): Either[String, SynchronizerBootstrappingTransactions] =
      (for {
        // TODO(#13301) switch to safe version
        domainParameters <- SignedTopologyTransaction.fromTrustedByteString(
          ByteString.copyFrom(Base64.getDecoder.decode(state.domainParameters))
        )
        // TODO(#13301) switch to safe version
        sequencerDomainState <- SignedTopologyTransaction.fromTrustedByteString(
          ByteString.copyFrom(Base64.getDecoder.decode(state.sequencerDomainState))
        )
        // TODO(#13301) switch to safe version
        mediatorDomainState <- SignedTopologyTransaction.fromTrustedByteString(
          ByteString.copyFrom(Base64.getDecoder.decode(state.mediatorDomainState))
        )
      } yield SynchronizerBootstrappingTransactions(
        domainParameters,
        sequencerDomainState,
        mediatorDomainState,
      )).left.map(_.toString)
  }

  final case class GetSynchronizerBootstrappingTransactions(domainIdPrefix: String)
      extends InternalBaseCommand[
        http.GetSynchronizerBootstrappingTransactionsResponse,
        SynchronizerBootstrappingTransactions,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetSynchronizerBootstrappingTransactionsResponse] =
      client.getSynchronizerBootstrappingTransactions(
        domainIdPrefix
      )

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetSynchronizerBootstrappingTransactionsResponse.OK(transactions) =>
        SynchronizerBootstrappingTransactions.fromHttp(transactions)
    }
  }
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.admin.api.client.commands

import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.admin.participant.v30
import com.digitalasset.canton.admin.participant.v30.ParticipantRepairServiceGrpc.ParticipantRepairServiceStub
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.participant.admin.data.{
  ContractImportMode,
  RepresentativePackageIdOverride,
}
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.util.GrpcStreamingUtils
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel

import java.io.SequenceInputStream
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** Splice additions to
  * [[com.digitalasset.canton.admin.api.client.commands.ParticipantAdminCommands.ParticipantRepairManagement]].
  */
object ParticipantRepairManagementExtensions {

  final case class ImportAcsBytes(
      acsChunk: Seq[ByteString],
      workflowIdPrefix: String,
      contractImportMode: ContractImportMode,
      representativePackageIdOverride: RepresentativePackageIdOverride,
      excludedStakeholders: Set[PartyId],
      synchronizerId: SynchronizerId,
      forceRepairWhenTopologyTransactionAtLedgerEnd: Boolean,
  )(implicit ec: ExecutionContext, loggingContext: ErrorLoggingContext)
      extends GrpcAdminCommand[
        Unit,
        v30.ImportAcsResponse,
        Unit,
      ] {

    override type Svc = ParticipantRepairServiceStub

    override def createService(channel: ManagedChannel): ParticipantRepairServiceStub =
      v30.ParticipantRepairServiceGrpc.stub(channel)

    override protected def createRequest(): Either[String, Unit] =
      Right(())

    override protected def submitRequest(
        service: ParticipantRepairServiceStub,
        request: Unit,
    ): Future[v30.ImportAcsResponse] = {
      GrpcStreamingUtils.streamToServer(
        service.importAcs,
        (chunk: ByteString) =>
          v30.ImportAcsRequest(
            chunk,
            Some(workflowIdPrefix),
            Some(contractImportMode.toProtoV30),
            excludedStakeholders.map(_.toProtoPrimitive).toSeq,
            Some(representativePackageIdOverride.toProtoV30),
            Some(synchronizerId.toProtoPrimitive),
            forceRepairWhenTopologyTransactionAtLedgerEnd,
          ),
        new SequenceInputStream(acsChunk.iterator.map(_.newInput()).asJavaEnumeration),
      )
    }

    override protected def handleResponse(
        response: v30.ImportAcsResponse
    ): Either[String, Unit] = Right(())
  }
}

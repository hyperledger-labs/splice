// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.http

import org.lfdecentralizedtrust.splice.auth.AuthExtractor.TracedUser
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.tokenstandard.{
  allocation,
  allocationinstruction,
  metadata,
  transferinstruction,
}
import scala.concurrent.{ExecutionContext, Future}

class HttpTokenStandardScanProxyHandler(
    scanConnection: BftScanConnection,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends allocation.v1.Handler[TracedUser]
    with allocationinstruction.v1.Handler[TracedUser]
    with metadata.v1.Handler[TracedUser]
    with transferinstruction.v1.Handler[TracedUser]
    with Spanning
    with NamedLogging {

  private val workflowId = this.getClass.getSimpleName

  def getInstrument(
      respond: metadata.v1.Resource.GetInstrumentResponse.type
  )(instrumentId: String)(user: TracedUser): Future[metadata.v1.Resource.GetInstrumentResponse] = {
    implicit val TracedUser(_, tc) = user
    withSpan(s"$workflowId.getInstrument") { implicit tc => _ =>
      scanConnection.lookupInstrument(instrumentId).map { instrumentO =>
        instrumentO.fold(
          metadata.v1.Resource.GetInstrumentResponse.NotFound(
            metadata.v1.definitions.ErrorResponse(s"No instrument with id $instrumentId found")
          )
        )(
          metadata.v1.Resource.GetInstrumentResponse.OK(_)
        )
      }
    }
  }

  def getRegistryInfo(
      respond: metadata.v1.Resource.GetRegistryInfoResponse.type
  )()(user: TracedUser): Future[metadata.v1.Resource.GetRegistryInfoResponse] = {
    implicit val TracedUser(_, tc) = user
    withSpan(s"$workflowId.getRegistryInfo") { implicit tc => _ =>
      scanConnection.getRegistryInfo().map(metadata.v1.Resource.GetRegistryInfoResponse.OK(_))
    }
  }

  def listInstruments(
      respond: metadata.v1.Resource.ListInstrumentsResponse.type
  )(pageSize: Option[Int], pageToken: Option[String])(
      user: TracedUser
  ): Future[metadata.v1.Resource.ListInstrumentsResponse] = {
    implicit val TracedUser(_, tc) = user
    withSpan(s"$workflowId.listInstruments") { implicit tc => _ =>
      scanConnection
        .listInstruments(pageSize, pageToken)
        .map(instruments =>
          metadata.v1.Resource.ListInstrumentsResponse
            .OK(metadata.v1.definitions.ListInstrumentsResponse(instruments.toVector))
        )
    }
  }

  def getAllocationFactory(
      respond: allocationinstruction.v1.Resource.GetAllocationFactoryResponse.type
  )(body: allocationinstruction.v1.definitions.GetFactoryRequest)(
      user: TracedUser
  ): Future[allocationinstruction.v1.Resource.GetAllocationFactoryResponse] = {
    implicit val TracedUser(_, tc) = user
    withSpan(s"$workflowId.getAllocationFactory") { implicit tc => _ =>
      scanConnection
        .getAllocationFactoryRaw(
          body
        )
        .map(allocationinstruction.v1.Resource.GetAllocationFactoryResponse.OK(_))
    }
  }

  def getAllocationCancelContext(
      respond: allocation.v1.Resource.GetAllocationCancelContextResponse.type
  )(allocationId: String, body: allocation.v1.definitions.GetChoiceContextRequest)(
      user: TracedUser
  ): Future[allocation.v1.Resource.GetAllocationCancelContextResponse] = {
    implicit val TracedUser(_, tc) = user
    withSpan(s"$workflowId.getAllocationCancelContext") { implicit tc => _ =>
      scanConnection
        .getAllocationCancelContextRaw(
          allocationId,
          body,
        )
        .map(allocation.v1.Resource.GetAllocationCancelContextResponse.OK(_))
    }
  }

  def getAllocationTransferContext(
      respond: allocation.v1.Resource.GetAllocationTransferContextResponse.type
  )(allocationId: String, body: allocation.v1.definitions.GetChoiceContextRequest)(
      user: TracedUser
  ): Future[allocation.v1.Resource.GetAllocationTransferContextResponse] = {
    implicit val TracedUser(_, tc) = user
    withSpan(s"$workflowId.getAllocationTransferContext") { implicit tc => _ =>
      scanConnection
        .getAllocationTransferContextRaw(
          allocationId,
          body,
        )
        .map(allocation.v1.Resource.GetAllocationTransferContextResponse.OK(_))
    }
  }

  def getAllocationWithdrawContext(
      respond: allocation.v1.Resource.GetAllocationWithdrawContextResponse.type
  )(allocationId: String, body: allocation.v1.definitions.GetChoiceContextRequest)(
      user: TracedUser
  ): Future[allocation.v1.Resource.GetAllocationWithdrawContextResponse] = {
    implicit val TracedUser(_, tc) = user
    withSpan(s"$workflowId.getAllocationWithdrawContext") { implicit tc => _ =>
      scanConnection
        .getAllocationWithdrawContextRaw(
          allocationId,
          body,
        )
        .map(allocation.v1.Resource.GetAllocationWithdrawContextResponse.OK(_))
    }
  }

  def getTransferFactory(respond: transferinstruction.v1.Resource.GetTransferFactoryResponse.type)(
      body: transferinstruction.v1.definitions.GetFactoryRequest
  )(user: TracedUser): Future[transferinstruction.v1.Resource.GetTransferFactoryResponse] = {
    implicit val TracedUser(_, tc) = user
    withSpan(s"$workflowId.getTransferFactory") { implicit tc => _ =>
      scanConnection
        .getTransferFactoryRaw(
          body
        )
        .map(transferinstruction.v1.Resource.GetTransferFactoryResponse.OK(_))
    }
  }

  def getTransferInstructionAcceptContext(
      respond: transferinstruction.v1.Resource.GetTransferInstructionAcceptContextResponse.type
  )(
      transferInstructionId: String,
      body: transferinstruction.v1.definitions.GetChoiceContextRequest,
  )(
      user: TracedUser
  ): Future[transferinstruction.v1.Resource.GetTransferInstructionAcceptContextResponse] = {
    implicit val TracedUser(_, tc) = user
    withSpan(s"$workflowId.getTransferInstructionAcceptContext") { implicit tc => _ =>
      scanConnection
        .getTransferInstructionAcceptContextRaw(
          transferInstructionId,
          body,
        )
        .map(transferinstruction.v1.Resource.GetTransferInstructionAcceptContextResponse.OK(_))
    }
  }

  def getTransferInstructionRejectContext(
      respond: transferinstruction.v1.Resource.GetTransferInstructionRejectContextResponse.type
  )(
      transferInstructionId: String,
      body: transferinstruction.v1.definitions.GetChoiceContextRequest,
  )(
      user: TracedUser
  ): Future[transferinstruction.v1.Resource.GetTransferInstructionRejectContextResponse] = {
    implicit val TracedUser(_, tc) = user
    withSpan(s"$workflowId.getTransferInstructionRejectContext") { implicit tc => _ =>
      scanConnection
        .getTransferInstructionRejectContextRaw(
          transferInstructionId,
          body,
        )
        .map(transferinstruction.v1.Resource.GetTransferInstructionRejectContextResponse.OK(_))
    }
  }

  def getTransferInstructionWithdrawContext(
      respond: transferinstruction.v1.Resource.GetTransferInstructionWithdrawContextResponse.type
  )(
      transferInstructionId: String,
      body: transferinstruction.v1.definitions.GetChoiceContextRequest,
  )(
      user: TracedUser
  ): Future[transferinstruction.v1.Resource.GetTransferInstructionWithdrawContextResponse] = {
    implicit val TracedUser(_, tc) = user
    withSpan(s"$workflowId.getTransferInstructionWithdrawContext") { implicit tc => _ =>
      scanConnection
        .getTransferInstructionWithdrawContextRaw(
          transferInstructionId,
          body,
        )
        .map(transferinstruction.v1.Resource.GetTransferInstructionWithdrawContextResponse.OK(_))
    }
  }
}

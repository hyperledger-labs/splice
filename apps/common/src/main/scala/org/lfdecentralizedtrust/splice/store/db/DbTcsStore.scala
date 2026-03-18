// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractCompanion
import org.lfdecentralizedtrust.splice.store.TcsStore
import org.lfdecentralizedtrust.splice.util.{ContractWithState, TemplateJsonDecoder}
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

class DbTcsStore(
    val acsStore: DbMultiDomainAcsStore[?]
)(implicit
    ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends TcsStore
    with AcsTables
    with AcsQueries
    with TcsQueries {

  private val archiveTableName = acsStore.acsArchiveConfigOpt
    .getOrElse(
      throw new IllegalArgumentException(
        "DbTcsStore requires an AcsArchiveConfig on the underlying acsStore"
      )
    )
    .archiveTableName
  private val storage = acsStore.tcsStorage
  private val acsTableName = acsStore.tcsAcsTableName

  override def lookupContractByIdAsOf[C, TCid <: ContractId[?], T](companion: C)(
      id: ContractId[?],
      asOf: CantonTimestamp,
      synchronizerId: SynchronizerId,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Option[ContractWithState[TCid, T]]] = {
    acsStore.waitUntilRecordTimeReached(synchronizerId, asOf).flatMap { _ =>
      storage
        .querySingle(
          selectFromTcsTableWithStateAsOf(
            acsTableName,
            archiveTableName,
            acsStore.acsStoreId,
            acsStore.domainMigrationId,
            companion,
            asOf,
            additionalWhere = sql"""and acs.contract_id = ${lengthLimited(id.contractId)}""",
          ).headOption,
          "lookupContractByIdAsOf",
        )
        .map(result => contractWithStateFromRow(companion)(result))
        .value
    }
  }

  override def listAllContractsAsOf[C, TCid <: ContractId[?], T](
      companion: C,
      asOf: CantonTimestamp,
      synchronizerId: SynchronizerId,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[ContractWithState[TCid, T]]] = {
    acsStore.waitUntilRecordTimeReached(synchronizerId, asOf).flatMap { _ =>
      val templateId = companionClass.typeId(companion)
      val opName = s"listAllContractsAsOf:${templateId.getEntityName}"
      for {
        result <- storage.query(
          selectFromTcsTableWithStateAsOf(
            acsTableName,
            archiveTableName,
            acsStore.acsStoreId,
            acsStore.domainMigrationId,
            companion,
            asOf,
          ),
          opName,
        )
        withState = result.map(contractWithStateFromRow(companion)(_))
      } yield withState
    }
  }

  override def listAllContractsActiveWithin[C, TCid <: ContractId[?], T](
      companion: C,
      lowerBoundIncl: CantonTimestamp,
      upperBoundIncl: CantonTimestamp,
      synchronizerId: SynchronizerId,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[TcsStore.TemporalContractWithState[TCid, T]]] = {
    acsStore.waitUntilRecordTimeReached(synchronizerId, upperBoundIncl).flatMap { _ =>
      val templateId = companionClass.typeId(companion)
      val opName = s"listAllContractsActiveWithin:${templateId.getEntityName}"
      for {
        result <- storage.query(
          selectFromTcsTableWithStateActiveWithin(
            acsTableName,
            archiveTableName,
            acsStore.acsStoreId,
            acsStore.domainMigrationId,
            companion,
            lowerBoundIncl,
            upperBoundIncl,
          ),
          opName,
        )
        withState = result.map { row =>
          TcsStore.TemporalContractWithState(
            contractWithState = contractWithStateFromRow(companion)(row.withStateRow),
            createdAt = CantonTimestamp.assertFromLong(row.withStateRow.acsRow.createdAt.micros),
            archivedAt = row.archivedAt,
          )
        }
      } yield withState
    }
  }
}

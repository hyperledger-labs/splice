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

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

// The DbTcsStore is currently implemented with support for single synchronizer only.
// Specifically the lookup APIs have wait mechanism ensuring record_time
// reached for a single synchronizer only.
// But since the SQL queries do not perform filtering of contracts on
// 'assigned_domain' due to performance constraints.
// It is expected that the DbMultiDomainAcsStore only has ingestion happening
// for a single synchronizer, and that the Store is itself keyed on the SynchronizerId.
// Here we make use of its StoreDescriptor to derive SynchronizerId to make this requirement explicit.
class DbTcsStore(
    val acsStore: DbMultiDomainAcsStore[?],
    synchronizerIdFromDescriptor: StoreDescriptor => SynchronizerId,
)(implicit
    ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends TcsStore
    with AcsTables
    with AcsQueries
    with TcsQueries {

  private val synchronizerId = synchronizerIdFromDescriptor(acsStore.acsStoreDescriptor)

  private val archiveTableName = acsStore.acsArchiveConfigOpt
    .getOrElse(
      throw new IllegalArgumentException(
        "DbTcsStore requires an AcsArchiveConfig on the underlying acsStore"
      )
    )
    .archiveTableName
  private val storage = acsStore.storage
  private val acsTableName = acsStore.acsTableName

  override def lookupContractByIdAsOf[C, TCid <: ContractId[?], T](companion: C)(
      id: ContractId[?],
      asOf: CantonTimestamp,
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

  private val earliestArchivedAtCache = new AtomicReference[Option[CantonTimestamp]](None)

  /** Returns the earliest archived_at timestamp which serves as an
    * approximation for ingestion start.
    */
  def getEarliestArchivedAt()(implicit
      _traceContext: TraceContext
  ): Future[Option[CantonTimestamp]] = {
    earliestArchivedAtCache.get() match {
      case some @ Some(_) => Future.successful(some)
      case None =>
        val storeId = acsStore.acsStoreId
        val migrationId = acsStore.domainMigrationId
        storage
          .query(
            sql"""SELECT MIN(archived_at) FROM #$archiveTableName
                  WHERE store_id = $storeId
                    AND migration_id = $migrationId
               """.as[Option[Long]].head,
            "getEarliestArchivedAt",
          )
          .map { minArchivedAtO =>
            minArchivedAtO.map { micros =>
              val ts = CantonTimestamp.assertFromLong(micros)
              earliestArchivedAtCache.compareAndSet(None, Some(ts))
              ts
            }
          }
    }
  }
}

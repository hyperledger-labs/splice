// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{Member, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.MemberTraffic
import org.lfdecentralizedtrust.splice.store.LimitHelpers
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.AcsStoreId
import org.lfdecentralizedtrust.splice.util.QualifiedName
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

/** Shared `MemberTraffic` queries for stores that ingest it with the standard index columns. */
trait MemberTrafficQueries extends AcsJdbcTypes with LimitHelpers { this: NamedLogging =>

  protected def sumPurchasedMemberTraffic(
      storage: DbStorage,
      acsTableName: String,
      acsStoreId: AcsStoreId,
      migrationId: Long,
      memberId: Member,
      synchronizerId: SynchronizerId,
  )(implicit ec: ExecutionContext, tc: TraceContext, closeContext: CloseContext): Future[Long] =
    for {
      sum <- storage
        .querySingle(
          sql"""
               select sum(total_traffic_purchased)
               from #$acsTableName
               where store_id = $acsStoreId
                and migration_id = $migrationId
                and package_name = ${MemberTraffic.PACKAGE_NAME}
                and template_id_qualified_name = ${QualifiedName(
              MemberTraffic.TEMPLATE_ID_WITH_PACKAGE_ID
            )}
                and member_traffic_member = ${lengthLimited(memberId.toProtoPrimitive)}
                and member_traffic_domain = $synchronizerId
             """.as[Long].headOption,
          // the callers' method name, which is what the DB retry logs report
          "getTotalPurchasedMemberTraffic",
        )
        .value
    } yield sum.getOrElse(0L)

  /** Total traffic purchased on `synchronizerId`, for every member with a purchase on record. */
  protected def sumPurchasedMemberTrafficPerMember(
      storage: DbStorage,
      acsTableName: String,
      acsStoreId: AcsStoreId,
      migrationId: Long,
      synchronizerId: SynchronizerId,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
      closeContext: CloseContext,
  ): Future[Map[Member, Long]] = {
    // Throws on a member that does not parse: ingestion stores a member only once it parses, so
    // that would be a corrupt row.
    import Member.DbStorageImplicits.getResultMember
    for {
      rows <- storage
        .query(
          sql"""
               select member_traffic_member, sum(total_traffic_purchased)
               from #$acsTableName
               where store_id = $acsStoreId
                and migration_id = $migrationId
                and package_name = ${MemberTraffic.PACKAGE_NAME}
                and template_id_qualified_name = ${QualifiedName(
              MemberTraffic.TEMPLATE_ID_WITH_PACKAGE_ID
            )}
                and member_traffic_domain = $synchronizerId
                and member_traffic_member is not null
               group by member_traffic_member
             """.as[(Member, Long)],
          // the callers' method name, which is what the DB retry logs report
          "listTotalPurchasedMemberTraffic",
        )
    } yield rows.toMap
  }
}

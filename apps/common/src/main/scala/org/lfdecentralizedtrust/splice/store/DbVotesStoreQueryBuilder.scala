// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import cats.data.NonEmptyList
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.nonempty.NonEmpty
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.VoteRequest
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.AcsStoreId
import org.lfdecentralizedtrust.splice.store.db.TxLogQueries.TxLogStoreId
import org.lfdecentralizedtrust.splice.store.db.{AcsQueries, TxLogQueries}
import slick.dbio.{Effect, NoStream}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.sql.SqlStreamingAction

/** All column names will be unsafely interpolated, as they're expected to be constant strings.
  */
trait DbVotesTxLogStoreQueryBuilder[TXE]
    extends TxLogQueries[TXE]
    with LimitHelpers
    with NamedLogging {

  private def voteRequestResultsConditions(
      dbType: String3,
      actionNameColumnName: String,
      acceptedColumnName: String,
      requesterNameColumnName: String,
      filters: VoteResultsFilters,
  ) = {
    val actionNameCondition = filters.actionName match {
      case Some(actionName) =>
        Some(sql"""#$actionNameColumnName like ${lengthLimited(
            s"%${lengthLimited(actionName)}%"
          )}""")
      case None => None
    }
    val executedCondition = filters.accepted match {
      case Some(accepted) => Some(sql"""#$acceptedColumnName = ${accepted}""")
      case None => None
    }
    val effectivenessCondition = (filters.effectiveFrom, filters.effectiveTo) match {
      case (Some(effectiveFrom), Some(effectiveTo)) =>
        Some(sql"""vote_effective_at between ${lengthLimited(
            effectiveFrom
          )} and ${lengthLimited(
            effectiveTo
          )}""")
      case (Some(effectiveFrom), None) =>
        Some(sql"""vote_effective_at > ${lengthLimited(effectiveFrom)}""")
      case (None, Some(effectiveTo)) =>
        Some(sql"""vote_effective_at < ${lengthLimited(effectiveTo)}""")
      case (None, None) => None
    }
    val requesterCondition = filters.requester match {
      case Some(requester) =>
        Some(sql"""#$requesterNameColumnName like ${lengthLimited(
            s"%${lengthLimited(requester)}%"
          )}""")
      case None => None
    }
    NonEmptyList(
      sql"""entry_type = ${dbType}""",
      List(
        actionNameCondition,
        executedCondition,
        requesterCondition,
        effectivenessCondition,
      ).flatten,
    )
  }

  def listVoteRequestResultsQuery(
      txLogTableName: String,
      txLogStoreId: TxLogStoreId,
      dbType: String3,
      actionNameColumnName: String,
      acceptedColumnName: String,
      requesterNameColumnName: String,
      filters: VoteResultsFilters,
      limit: Limit,
      after: Option[Long] = None,
  ): SqlStreamingAction[Vector[
    TxLogQueries.SelectFromTxLogTableResult
  ], TxLogQueries.SelectFromTxLogTableResult, Effect.Read] = {
    // Sort key: the vote's effective date, falling back to the result's completedAt for non-accepted votes that have none.
    val effectiveAtSortKey =
      "coalesce(vote_effective_at, entry_data->'result'->>'completedAt')"
    val afterCondition = after match {
      case Some(a) =>
        Some(
          // Keyset pagination past the previous page's last entry_number `a`. Lexicographical row comparison,
          // expands to: effectiveAt < cursorEffectiveAt OR (effectiveAt = cursorEffectiveAt AND entry_number < a).
          sql"""(#$effectiveAtSortKey, entry_number) < ((select #$effectiveAtSortKey from #$txLogTableName where store_id = $txLogStoreId and entry_number = $a), $a)"""
        )
      case None => None
    }
    val conditions = voteRequestResultsConditions(
      dbType,
      actionNameColumnName,
      acceptedColumnName,
      requesterNameColumnName,
      filters,
    ) ++ afterCondition.toList
    val whereClause = conditions.reduceLeft((a, b) => (a ++ sql""" and """ ++ b).toActionBuilder)

    selectFromTxLogTable(
      txLogTableName,
      txLogStoreId,
      where = whereClause.toActionBuilder,
      orderLimit =
        sql"""order by #$effectiveAtSortKey desc, entry_number desc limit ${sqlLimit(limit)}""",
    )
  }

  def countVoteRequestResultsQuery(
      txLogTableName: String,
      txLogStoreId: TxLogStoreId,
      dbType: String3,
      actionNameColumnName: String,
      acceptedColumnName: String,
      requesterNameColumnName: String,
      filters: VoteResultsFilters,
  ): SqlStreamingAction[Vector[Long], Long, Effect.Read] = {
    val whereClause = voteRequestResultsConditions(
      dbType,
      actionNameColumnName,
      acceptedColumnName,
      requesterNameColumnName,
      filters,
    ).reduceLeft((a, b) => (a ++ sql""" and """ ++ b).toActionBuilder)
    (sql"""select count(*) from #$txLogTableName where store_id = $txLogStoreId and """ ++ whereClause).toActionBuilder
      .as[Long]
  }
}

/** All column names will be unsafely interpolated, as they're expected to be constant strings.
  */
trait DbVotesAcsStoreQueryBuilder extends AcsQueries with LimitHelpers with NamedLogging {

  implicit protected def dbProfile: DbStorage.Profile

  def listVoteRequestsByTrackingCidQuery(
      acsTableName: String,
      acsStoreId: AcsStoreId,
      domainMigrationId: Long,
      trackingCidColumnName: String,
      trackingCids: NonEmpty[Seq[VoteRequest.ContractId]],
      limit: Limit,
  ): SqlStreamingAction[Vector[
    AcsQueries.SelectFromAcsTableResult
  ], AcsQueries.SelectFromAcsTableResult, Effect.Read] = {
    val cids: NonEmpty[Seq[ContractId[?]]] = trackingCids
    val voteRequestTrackingCidsSql =
      DbStorage.toInClause(trackingCidColumnName, cids)
    selectFromAcsTable(
      acsTableName,
      acsStoreId,
      domainMigrationId,
      VoteRequest.COMPANION,
      where = voteRequestTrackingCidsSql,
      orderLimit = sql"""limit ${sqlLimit(limit)}""",
    )
  }

  def lookupVoteRequestQuery(
      acsTableName: String,
      acsStoreId: AcsStoreId,
      domainMigrationId: Long,
      trackingCidColumnName: String,
      voteRequestCid: VoteRequest.ContractId,
  ): SqlStreamingAction[Vector[
    AcsQueries.SelectFromAcsTableResult
  ], AcsQueries.SelectFromAcsTableResult, Effect.Read]#ResultAction[Option[
    AcsQueries.SelectFromAcsTableResult
  ], NoStream, Effect.Read] = {
    selectFromAcsTable(
      acsTableName,
      acsStoreId,
      domainMigrationId,
      VoteRequest.COMPANION,
      where = (sql""" #$trackingCidColumnName = $voteRequestCid """).toActionBuilder,
    ).headOption
  }

}

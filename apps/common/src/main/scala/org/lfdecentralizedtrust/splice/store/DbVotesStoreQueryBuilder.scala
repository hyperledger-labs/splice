// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import cats.data.NonEmptyList
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.VoteRequest
import org.lfdecentralizedtrust.splice.store.db.{AcsQueries, TxLogQueries}
import org.lfdecentralizedtrust.splice.util.QualifiedName
import slick.dbio.{Effect, NoStream}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.sql.SqlStreamingAction

/** All column names will be unsafely interpolated, as they're expected to be constant strings.
  */
trait DbVotesStoreQueryBuilder extends AcsQueries with LimitHelpers with NamedLogging {

  def listVoteRequestResultsQuery(
      txLogTableName: String,
      storeId: Int,
      dbType: String3,
      actionNameColumnName: String,
      acceptedColumnName: String,
      effectiveAtColumnName: String,
      requesterNameColumnName: String,
      actionName: Option[String],
      accepted: Option[Boolean],
      requester: Option[String],
      effectiveFrom: Option[String],
      effectiveTo: Option[String],
      limit: Limit,
  ): SqlStreamingAction[Vector[
    TxLogQueries.SelectFromTxLogTableResult
  ], TxLogQueries.SelectFromTxLogTableResult, Effect.Read] = {
    val actionNameCondition = actionName match {
      case Some(actionName) =>
        Some(sql"""#$actionNameColumnName like ${lengthLimited(
            s"%${lengthLimited(actionName)}%"
          )}""")
      case None => None
    }
    val executedCondition = accepted match {
      case Some(accepted) => Some(sql"""#$acceptedColumnName = ${accepted}""")
      case None => None
    }
    val effectivenessCondition = (effectiveFrom, effectiveTo) match {
      case (Some(effectiveFrom), Some(effectiveTo)) =>
        Some(sql"""#$effectiveAtColumnName between ${lengthLimited(
            effectiveFrom
          )} and ${lengthLimited(
            effectiveTo
          )}""")
      case (Some(effectiveFrom), None) =>
        Some(sql"""#$effectiveAtColumnName > ${lengthLimited(effectiveFrom)}""")
      case (None, Some(effectiveTo)) =>
        Some(sql"""#$effectiveAtColumnName < ${lengthLimited(effectiveTo)}""")
      case (None, None) => None
    }
    val requesterCondition = requester match {
      case Some(requester) =>
        Some(sql"""#$requesterNameColumnName like ${lengthLimited(
            s"%${lengthLimited(requester)}%"
          )}""")
      case None => None
    }
    val conditions = NonEmptyList(
      sql"""entry_type = ${dbType}""",
      List(
        actionNameCondition,
        executedCondition,
        requesterCondition,
        effectivenessCondition,
      ).flatten,
    )
    val whereClause = conditions.reduceLeft((a, b) => (a ++ sql""" and """ ++ b).toActionBuilder)

    TxLogQueries.selectFromTxLogTable(
      txLogTableName,
      storeId,
      where = whereClause.toActionBuilder,
      orderLimit = sql"""order by #$effectiveAtColumnName desc limit ${sqlLimit(limit)}""",
    )
  }

  def listVoteRequestsByTrackingCidQuery(
      acsTableName: String,
      storeId: Int,
      domainMigrationId: Long,
      trackingCidColumnName: String,
      trackingCids: Seq[VoteRequest.ContractId],
      limit: Limit,
  ): SqlStreamingAction[Vector[
    AcsQueries.SelectFromAcsTableResult
  ], AcsQueries.SelectFromAcsTableResult, Effect.Read] = {
    val voteRequestTrackingCidsSql = inClause(trackingCids)
    selectFromAcsTable(
      acsTableName,
      storeId,
      domainMigrationId,
      where = (sql""" template_id_qualified_name = ${QualifiedName(
          VoteRequest.TEMPLATE_ID_WITH_PACKAGE_ID
        )}
                          and #$trackingCidColumnName in """ ++ voteRequestTrackingCidsSql).toActionBuilder,
      orderLimit = sql"""limit ${sqlLimit(limit)}""",
    )
  }

  def lookupVoteRequestQuery(
      acsTableName: String,
      storeId: Int,
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
      storeId,
      domainMigrationId,
      where = (sql""" template_id_qualified_name = ${QualifiedName(
          VoteRequest.TEMPLATE_ID_WITH_PACKAGE_ID
        )}
                       and #$trackingCidColumnName = $voteRequestCid """).toActionBuilder,
    ).headOption
  }

}

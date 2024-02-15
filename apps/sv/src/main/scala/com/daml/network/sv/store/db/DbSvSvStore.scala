package com.daml.network.sv.store.db

import com.daml.network.codegen.java.cn.svlocal.approvedsvidentity.ApprovedSvIdentity
import com.daml.network.codegen.java.cn.validatoronboarding.{UsedSecret, ValidatorOnboarding}
import com.daml.network.environment.RetryProvider
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.store.db.{AcsQueries, AcsTables, DbCNNodeAppStoreWithoutHistory}
import com.daml.network.store.{MultiDomainAcsStore, StoreErrors}
import com.daml.network.sv.store.{SvStore, SvSvStore}
import com.daml.network.util.{Contract, QualifiedName, TemplateJsonDecoder}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

class DbSvSvStore(
    override val key: SvStore.Key,
    storage: DbStorage,
    override protected val outerLoggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    // TODO(#9731): get migration id from sponsor sv / scan instead of configuring here
    domainMigrationId: Long,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStoreWithoutHistory(
      storage,
      DbSvSvStore.tableName,
      // TODO (#5544): change this to something better
      storeDescriptor = Json.obj(
        "name" -> Json.fromString("DbSvSvStore"),
        "version" -> Json.fromInt(1),
        "svParty" -> Json.fromString(key.svParty.toProtoPrimitive),
        "svcParty" -> Json.fromString(key.svcParty.toProtoPrimitive),
      ),
      domainMigrationId,
    )
    with SvSvStore
    with AcsTables
    with AcsQueries
    with StoreErrors
    with NamedLogging {

  import multiDomainAcsStore.waitUntilAcsIngested

  def storeId: Int = multiDomainAcsStore.storeId

  override def lookupValidatorOnboardingBySecretWithOffset(
      secret: String
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[ValidatorOnboarding.ContractId, ValidatorOnboarding]]
  ]] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DbSvSvStore.tableName,
            storeId,
            domainMigrationId,
            sql"""
            template_id_qualified_name = ${QualifiedName(ValidatorOnboarding.TEMPLATE_ID)}
              and onboarding_secret = ${lengthLimited(secret)}
          """,
          ).headOption,
          "lookupValidatorOnboardingBySecretWithOffset",
        )
    } yield QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(ValidatorOnboarding.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def lookupUsedSecretWithOffset(secret: String)(implicit
      tc: TraceContext
  ): Future[MultiDomainAcsStore.QueryResult[Option[Contract[UsedSecret.ContractId, UsedSecret]]]] =
    waitUntilAcsIngested {
      (for {
        resultWithOffset <- storage
          .querySingle(
            selectFromAcsTableWithOffset(
              DbSvSvStore.tableName,
              storeId,
              domainMigrationId,
              sql"""
                  template_id_qualified_name = ${QualifiedName(UsedSecret.TEMPLATE_ID)}
                    and onboarding_secret = ${lengthLimited(secret)}
                """,
            ).headOption,
            "lookupUsedSecretWithOffset",
          )
      } yield QueryResult(
        resultWithOffset.offset,
        resultWithOffset.row.map(contractFromRow(UsedSecret.COMPANION)(_)),
      )).getOrRaise(offsetExpectedError())
    }

  override def lookupApprovedSvIdentityByNameWithOffset(
      name: String
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[ApprovedSvIdentity.ContractId, ApprovedSvIdentity]]
  ]] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DbSvSvStore.tableName,
            storeId,
            domainMigrationId,
            sql"""
              template_id_qualified_name = ${QualifiedName(ApprovedSvIdentity.TEMPLATE_ID)}
                and sv_candidate_name = ${lengthLimited(name)}
            """,
          ).headOption,
          "lookupApprovedSvIdentityByNameWithOffset",
        )
    } yield QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(ApprovedSvIdentity.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }
}

object DbSvSvStore {

  val tableName = "sv_acs_store"

}

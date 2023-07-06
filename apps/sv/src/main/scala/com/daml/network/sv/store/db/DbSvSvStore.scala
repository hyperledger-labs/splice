package com.daml.network.sv.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.network.codegen.java.cn.svonboarding.ApprovedSvIdentity
import com.daml.network.codegen.java.cn.validatoronboarding.{UsedSecret, ValidatorOnboarding}
import com.daml.network.environment.RetryProvider
import com.daml.network.store.MultiDomainAcsStore
import com.daml.network.store.db.{AcsQueries, AcsTables, DbCNNodeAppStoreWithoutHistory}
import com.daml.network.sv.config.SvDomainConfig
import com.daml.network.sv.store.{SvStore, SvSvStore}
import com.daml.network.util.{Contract, TemplateJsonDecoder}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import slick.dbio.DBIO

import scala.concurrent.{ExecutionContext, Future}

class DbSvSvStore(
    override val key: SvStore.Key,
    storage: DbStorage,
    override protected[this] val domainConfig: SvDomainConfig,
    override protected val outerLoggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStoreWithoutHistory(
      storage,
      DbSvSvStore.tableName,
      // TODO (#5544): change this to something better
      storeDescriptor = Json.obj(
        "version" -> Json.fromInt(1),
        "svParty" -> Json.fromString(key.svParty.toProtoPrimitive),
        "svcParty" -> Json.fromString(key.svcParty.toProtoPrimitive),
      ),
    )
    with SvSvStore
    with AcsTables
    with AcsQueries
    with NamedLogging {

  // TODO (#6444): implement acs ingestion
  override def ingestionAcsInsert(createdEvent: CreatedEvent)(implicit
      tc: TraceContext
  ): Either[String, DBIO[_]] = ???

  // TODO (#6443): implement queries
  override def lookupValidatorOnboardingBySecretWithOffset(
      secret: String
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[ValidatorOnboarding.ContractId, ValidatorOnboarding]]
  ]] = ???

  override def lookupUsedSecretWithOffset(secret: String)(implicit
      tc: TraceContext
  ): Future[MultiDomainAcsStore.QueryResult[Option[Contract[UsedSecret.ContractId, UsedSecret]]]] =
    ???

  override def lookupApprovedSvIdentityByNameWithOffset(
      name: String
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[ApprovedSvIdentity.ContractId, ApprovedSvIdentity]]
  ]] = ???
}

object DbSvSvStore {

  val tableName = "sv_acs_store"

}

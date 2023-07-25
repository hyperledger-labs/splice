package com.daml.network.sv.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cn.svonboarding.ApprovedSvIdentity
import com.daml.network.codegen.java.cn.validatoronboarding.{UsedSecret, ValidatorOnboarding}
import com.daml.network.environment.RetryProvider
import com.daml.network.store.{MultiDomainAcsStore, StoreErrors}
import MultiDomainAcsStore.{ContractCompanion, QueryResult}
import com.daml.network.store.db.{AcsQueries, AcsTables, DbCNNodeAppStoreWithoutHistory}
import com.daml.network.sv.config.SvDomainConfig
import com.daml.network.sv.store.db.SvTables.SvAcsStoreRowData
import com.daml.network.sv.store.{SvStore, SvSvStore}
import com.daml.network.util.{Contract, AssignedContract, TemplateJsonDecoder}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
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
    with StoreErrors
    with NamedLogging {

  import storage.DbStorageConverters.setParameterByteArray
  import multiDomainAcsStore.waitUntilAcsIngested

  def storeId: Int = multiDomainAcsStore.storeId

  override def ingestionAcsInsert(createdEvent: CreatedEvent)(implicit
      tc: TraceContext
  ): Either[String, DBIO[_]] = {
    SvAcsStoreRowData.fromCreatedEvent(createdEvent).map {
      case SvAcsStoreRowData(contract, contractExpiresAt, onboardingSecret, svCandidateName) =>
        val safeSecret = onboardingSecret.map(lengthLimited)
        val safeCandidateName = svCandidateName.map(lengthLimited)
        val contractId = contract.contractId.asInstanceOf[ContractId[Any]]
        val templateId = contract.identifier
        val createArguments = payloadJsonFromContract(contract.payload)
        val contractMetadataCreatedAt = Timestamp.assertFromInstant(contract.metadata.createdAt)
        val contractMetadataContractKeyHash =
          lengthLimited(contract.metadata.contractKeyHash.toStringUtf8)
        val contractMetadataDriverInternal = contract.metadata.driverMetadata.toByteArray
        sqlu"""
              insert into sv_acs_store(store_id, contract_id, template_id, create_arguments, contract_metadata_created_at,
                                       contract_metadata_contract_key_hash, contract_metadata_driver_internal,
                                       contract_expires_at, onboarding_secret, sv_candidate_name)
              values ($storeId, $contractId, $templateId, $createArguments, $contractMetadataCreatedAt,
                      $contractMetadataContractKeyHash, $contractMetadataDriverInternal,
                      $contractExpiresAt, $safeSecret, $safeCandidateName)
              on conflict do nothing
            """
    }
  }

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
            sql"""
            template_id = ${ValidatorOnboarding.TEMPLATE_ID}
              and onboarding_secret = ${lengthLimited(secret)}
          """,
          ).as[AcsStoreRowTemplateWithOffset].headOption,
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
              sql"""
                  template_id = ${UsedSecret.TEMPLATE_ID}
                    and onboarding_secret = ${lengthLimited(secret)}
                """,
            ).as[AcsStoreRowTemplateWithOffset].headOption,
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
            sql"""
              template_id = ${ApprovedSvIdentity.TEMPLATE_ID}
                and sv_candidate_name = ${lengthLimited(name)}
            """,
          ).as[AcsStoreRowTemplateWithOffset].headOption,
          "lookupApprovedSvIdentityByNameWithOffset",
        )
    } yield QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(ApprovedSvIdentity.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  // TODO (#5314): this depends on contracts being in domains, which we don't currently track in the DB
  // also: consider moving it to MultiDomainAcsStore (not SvSvStore), as this is generic
  protected[this] override def listAssignedContractsNotOnDomain[C, I <: ContractId[?], P](
      excludedDomain: DomainId,
      c: C,
  )(implicit
      tc: TraceContext,
      companion: ContractCompanion[C, I, P],
  ): Future[Seq[AssignedContract[I, P]]] = ???
}

object DbSvSvStore {

  val tableName = "sv_acs_store"

}

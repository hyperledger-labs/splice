package com.daml.network.sv.store

import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import com.daml.network.codegen.java.cc.validatorlicense as vl
import com.daml.network.codegen.java.cn.validatoronboarding.ValidatorOnboarding
import com.daml.network.codegen.java.cn.{svonboarding as so, validatoronboarding as vo}
import com.daml.network.environment.RetryProvider
import com.daml.network.store.{
  CNNodeAppStoreWithoutHistory,
  ConfiguredDefaultDomain,
  InMemoryMultiDomainAcsStore,
  MultiDomainAcsStore,
  TxLogStore,
}
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.config.SvDomainConfig
import com.daml.network.sv.store.memory.InMemorySvSvStore
import com.daml.network.util.Contract
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.{ExecutionContext, Future}

/* Store used by the SV app for filtering contracts visible to the SV party. */
trait SvSvStore extends CNNodeAppStoreWithoutHistory with ConfiguredDefaultDomain {

  protected[this] def domainConfig: SvDomainConfig

  override final def defaultAcsDomain = domainConfig.global.alias

  override def multiDomainAcsStore: InMemoryMultiDomainAcsStore[
    TxLogStore.IndexRecord,
    TxLogStore.Entry[TxLogStore.IndexRecord],
  ]

  def lookupValidatorOnboardingBySecretWithOffset(
      secret: String
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[vo.ValidatorOnboarding.ContractId, vo.ValidatorOnboarding]]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(vo.ValidatorOnboarding.COMPANION)(
        _,
        co => co.payload.candidateSecret == secret,
      )
    )

  def lookupValidatorOnboardingBySecret(
      secret: String
  )(implicit tc: TraceContext): Future[
    Option[Contract[vo.ValidatorOnboarding.ContractId, vo.ValidatorOnboarding]]
  ] =
    lookupValidatorOnboardingBySecretWithOffset(secret).map(_.value)

  def lookupUsedSecretWithOffset(
      secret: String
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[vo.UsedSecret.ContractId, vo.UsedSecret]]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(vo.UsedSecret.COMPANION)(
        _,
        co => co.payload.secret == secret,
      )
    )

  def lookupUsedSecret(
      secret: String
  )(implicit tc: TraceContext): Future[
    Option[Contract[vo.UsedSecret.ContractId, vo.UsedSecret]]
  ] =
    lookupUsedSecretWithOffset(secret).map(_.value)

  def listValidatorOnboardings()(implicit
      tc: TraceContext
  ): Future[Seq[Contract[vo.ValidatorOnboarding.ContractId, vo.ValidatorOnboarding]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.listContractsOnDomain(vo.ValidatorOnboarding.COMPANION, _)
    )

  def listExpiredValidatorOnboardings()
      : ListExpiredContracts[ValidatorOnboarding.ContractId, ValidatorOnboarding] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(ValidatorOnboarding.COMPANION)(_.expiresAt)

  def lookupApprovedSvIdentityByNameWithOffset(
      name: String
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[so.ApprovedSvIdentity.ContractId, so.ApprovedSvIdentity]]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(so.ApprovedSvIdentity.COMPANION)(
        _,
        co => co.payload.candidateName == name,
      )
    )

  def lookupApprovedSvIdentityByName(
      name: String
  )(implicit
      tc: TraceContext
  ): Future[Option[Contract[so.ApprovedSvIdentity.ContractId, so.ApprovedSvIdentity]]] =
    lookupApprovedSvIdentityByNameWithOffset(name).map(_.value)

  def lookupSvOnboardingConfirmedWithOffset()(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[so.SvOnboardingConfirmed.ContractId, so.SvOnboardingConfirmed]]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(so.SvOnboardingConfirmed.COMPANION)(
        _,
        co => co.payload.svParty == key.svParty.toProtoPrimitive,
      )
    )

  def lookupSvOnboardingConfirmed()(implicit tc: TraceContext): Future[
    Option[Contract[so.SvOnboardingConfirmed.ContractId, so.SvOnboardingConfirmed]]
  ] =
    lookupSvOnboardingConfirmedWithOffset().map(_.value)

  def lookupValidatorLicense()(implicit
      tc: TraceContext
  ): Future[Option[Contract[vl.ValidatorLicense.ContractId, vl.ValidatorLicense]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomain(vl.ValidatorLicense.COMPANION)(_, (_: Any) => true)
    )

  def getValidatorLicense()(implicit
      tc: TraceContext
  ): Future[Contract[vl.ValidatorLicense.ContractId, vl.ValidatorLicense]] =
    lookupValidatorLicense().map(
      _.getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("No active ValidatorLicense contract")
        )
      )
    )

  def key: SvStore.Key
}

object SvSvStore {
  def apply(
      key: SvStore.Key,
      storage: Storage,
      domains: SvDomainConfig,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
  )(implicit ec: ExecutionContext): SvSvStore =
    storage match {
      case _: MemoryStorage =>
        new InMemorySvSvStore(key, domains, loggerFactory, retryProvider)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  /** Contract filter of an sv acs store for a specific acs party. */
  def contractFilter(key: SvStore.Key): MultiDomainAcsStore.ContractFilter = {
    import MultiDomainAcsStore.mkFilter
    val sv = key.svParty.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter(
      key.svParty,
      Map(
        mkFilter(vl.ValidatorLicense.COMPANION)(co => co.payload.validator == sv),
        mkFilter(vo.ValidatorOnboarding.COMPANION)(co => co.payload.sv == sv),
        mkFilter(vo.UsedSecret.COMPANION)(co => co.payload.sv == sv),
        mkFilter(so.ApprovedSvIdentity.COMPANION)(co => co.payload.approvingSv == sv),
        mkFilter(so.SvOnboardingConfirmed.COMPANION)(co => co.payload.svParty == sv),
      ),
    )
  }
}

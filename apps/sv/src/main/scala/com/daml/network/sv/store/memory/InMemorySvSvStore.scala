package com.daml.network.sv.store.memory

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.codegen.java.cn.svonboarding.ApprovedSvIdentity
import com.daml.network.codegen.java.cn.validatoronboarding.{UsedSecret, ValidatorOnboarding}
import com.daml.network.environment.RetryProvider
import com.daml.network.store.{InMemoryCNNodeAppStoreWithoutHistory, MultiDomainAcsStore}
import com.daml.network.store.MultiDomainAcsStore.ContractCompanion
import com.daml.network.sv.config.SvDomainConfig
import com.daml.network.sv.store.{SvStore, SvSvStore}
import com.daml.network.util.{Contract, AssignedContract}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.daml.network.codegen.java.cn.{svonboarding as so, validatoronboarding as vo}

import scala.concurrent.*

class InMemorySvSvStore(
    override val key: SvStore.Key,
    override protected[this] val domainConfig: SvDomainConfig,
    override protected val outerLoggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit
    override protected val
    ec: ExecutionContext
) extends InMemoryCNNodeAppStoreWithoutHistory
    with SvSvStore {
  override def lookupValidatorOnboardingBySecretWithOffset(
      secret: String
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[Option[
    Contract[ValidatorOnboarding.ContractId, ValidatorOnboarding]
  ]]] = defaultAcsDomainIdF.flatMap(
    multiDomainAcsStore.findContractOnDomainWithOffset(vo.ValidatorOnboarding.COMPANION)(
      _,
      co => co.payload.candidateSecret == secret,
    )
  )

  override def lookupUsedSecretWithOffset(secret: String)(implicit
      tc: TraceContext
  ): Future[MultiDomainAcsStore.QueryResult[Option[Contract[UsedSecret.ContractId, UsedSecret]]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(vo.UsedSecret.COMPANION)(
        _,
        co => co.payload.secret == secret,
      )
    )

  override def lookupApprovedSvIdentityByNameWithOffset(
      name: String
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[Option[
    Contract[ApprovedSvIdentity.ContractId, ApprovedSvIdentity]
  ]]] = defaultAcsDomainIdF.flatMap(
    multiDomainAcsStore.findContractOnDomainWithOffset(so.ApprovedSvIdentity.COMPANION)(
      _,
      co => co.payload.candidateName == name,
    )
  )

  protected[this] override def listAssignedContractsNotOnDomain[C, I <: ContractId[?], P](
      excludedDomain: DomainId,
      c: C,
  )(implicit
      tc: TraceContext,
      companion: ContractCompanion[C, I, P],
  ): Future[Seq[AssignedContract[I, P]]] =
    multiDomainAcsStore
      .listAssignedContracts(c)
      .map(_.filterNot(_.domain == excludedDomain))
}

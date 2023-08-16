package com.daml.network.sv.store.memory

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.codegen.java.cn.svonboarding.ApprovedSvIdentity
import com.daml.network.codegen.java.cn.validatoronboarding.{UsedSecret, ValidatorOnboarding}
import com.daml.network.environment.RetryProvider
import com.daml.network.store.{InMemoryCNNodeAppStoreWithoutHistory, MultiDomainAcsStore}
import com.daml.network.store.MultiDomainAcsStore.{ContractCompanion, QueryResult}
import com.daml.network.sv.store.{SvStore, SvSvStore}
import com.daml.network.util.{Contract, ContractWithState, AssignedContract}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.daml.network.codegen.java.cn.{svonboarding as so, validatoronboarding as vo}

import scala.concurrent.*

class InMemorySvSvStore(
    override val key: SvStore.Key,
    override protected val outerLoggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit
    override protected val
    ec: ExecutionContext
) extends InMemoryCNNodeAppStoreWithoutHistory
    with SvSvStore {
  import InMemorySvSvStore.*

  override def lookupValidatorOnboardingBySecretWithOffset(
      secret: String
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[Option[
    Contract[ValidatorOnboarding.ContractId, ValidatorOnboarding]
  ]]] =
    multiDomainAcsStore
      .findContractWithOffset(vo.ValidatorOnboarding.COMPANION)(
        (_: Contract[?, vo.ValidatorOnboarding]).payload.candidateSecret == secret
      )
      .map(onlyContractResult)

  override def lookupUsedSecretWithOffset(secret: String)(implicit
      tc: TraceContext
  ): Future[MultiDomainAcsStore.QueryResult[Option[Contract[UsedSecret.ContractId, UsedSecret]]]] =
    multiDomainAcsStore
      .findContractWithOffset(vo.UsedSecret.COMPANION)(
        (_: Contract[?, vo.UsedSecret]).payload.secret == secret
      )
      .map(onlyContractResult)

  override def lookupApprovedSvIdentityByNameWithOffset(
      name: String
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[Option[
    Contract[ApprovedSvIdentity.ContractId, ApprovedSvIdentity]
  ]]] =
    multiDomainAcsStore
      .findContractWithOffset(so.ApprovedSvIdentity.COMPANION)(
        (_: Contract[?, so.ApprovedSvIdentity]).payload.candidateName == name
      )
      .map(onlyContractResult)

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

object InMemorySvSvStore {
  private def onlyContractResult[TCid, T](
      q: QueryResult[Option[ContractWithState[TCid, T]]]
  ): QueryResult[Option[Contract[TCid, T]]] =
    q map (_ map (_.contract))
}

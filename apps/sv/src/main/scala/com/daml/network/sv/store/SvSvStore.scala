package com.daml.network.sv.store

import com.daml.network.codegen.java.cn.svonboarding as so
import com.daml.network.codegen.java.cn.validatoronboarding as vo
import com.daml.network.environment.CoinRetries
import com.daml.network.store.{AcsStore, CoinAppStoreWithoutHistory}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.sv.config.SvDomainConfig
import com.daml.network.sv.store.memory.InMemorySvSvStore
import com.daml.network.util.Contract
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}

import scala.concurrent.{ExecutionContext, Future}

/* Store used by the SV app for filtering contracts visible to the SV party. */
trait SvSvStore extends CoinAppStoreWithoutHistory {

  protected[this] def domainConfig: SvDomainConfig

  override final def defaultAcsDomain = domainConfig.global

  def lookupValidatorOnboardingBySecretWithOffset(
      secret: String
  ): Future[
    QueryResult[Option[Contract[vo.ValidatorOnboarding.ContractId, vo.ValidatorOnboarding]]]
  ] =
    defaultAcs.flatMap(
      _.findContractWithOffset(vo.ValidatorOnboarding.COMPANION)(co =>
        co.payload.candidateSecret == secret
      )
    )

  def lookupUsedSecret(
      secret: String
  ): Future[
    QueryResult[Option[Contract[vo.UsedSecret.ContractId, vo.UsedSecret]]]
  ] =
    defaultAcs.flatMap(
      _.findContractWithOffset(vo.UsedSecret.COMPANION)(co => co.payload.secret == secret)
    )

  def listValidatorOnboardings()
      : Future[Seq[Contract[vo.ValidatorOnboarding.ContractId, vo.ValidatorOnboarding]]] =
    defaultAcs.flatMap(_.listContracts(vo.ValidatorOnboarding.COMPANION))

  def lookupApprovedSvIdentityByKeyWithOffset(
      secret: String
  ): Future[
    QueryResult[Option[Contract[so.ApprovedSvIdentity.ContractId, so.ApprovedSvIdentity]]]
  ] =
    defaultAcs.flatMap(
      _.findContractWithOffset(so.ApprovedSvIdentity.COMPANION)(co =>
        co.payload.candidateKey == key
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
      futureSupervisor: FutureSupervisor,
      retryProvider: CoinRetries,
  )(implicit ec: ExecutionContext): SvSvStore =
    storage match {
      case _: MemoryStorage =>
        new InMemorySvSvStore(key, domains, loggerFactory, futureSupervisor, retryProvider)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  /** Contract filter of an sv acs store for a specific acs party. */
  def contractFilter(key: SvStore.Key): AcsStore.ContractFilter = {
    import AcsStore.mkFilter
    val sv = key.svParty.toProtoPrimitive

    AcsStore.SimpleContractFilter(
      key.svParty,
      Map(
        mkFilter(vo.ValidatorOnboarding.COMPANION)(co => co.payload.sv == sv),
        mkFilter(vo.UsedSecret.COMPANION)(co => co.payload.sv == sv),
        mkFilter(so.ApprovedSvIdentity.COMPANION)(co => co.payload.approvingSv == sv),
      ),
    )
  }
}

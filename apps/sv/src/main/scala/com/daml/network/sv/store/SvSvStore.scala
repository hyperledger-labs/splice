package com.daml.network.sv.store

import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import com.daml.network.automation.TransferFollowTrigger.Task as FollowTask
import com.daml.network.codegen.java.cn.validatoronboarding.ValidatorOnboarding
import com.daml.network.codegen.java.cn.{svonboarding as so, validatoronboarding as vo}
import com.daml.network.environment.RetryProvider
import com.daml.network.store.{CNNodeAppStoreWithoutHistory, Limit, MultiDomainAcsStore, PageLimit}
import com.daml.network.store.MultiDomainAcsStore.{ConstrainedTemplate, QueryResult}
import com.daml.network.sv.store.db.DbSvSvStore
import com.daml.network.sv.store.memory.InMemorySvSvStore
import com.daml.network.util.{AssignedContract, Contract, TemplateJsonDecoder}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

/* Store used by the SV app for filtering contracts visible to the SV party. */
trait SvSvStore extends CNNodeAppStoreWithoutHistory {
  import SvSvStore.templatesMovedByMyAutomation

  protected val outerLoggerFactory: NamedLoggerFactory

  override protected lazy val loggerFactory: NamedLoggerFactory =
    outerLoggerFactory.append("store", "svParty")

  override lazy val acsContractFilter = SvSvStore.contractFilter(key)

  def lookupValidatorOnboardingBySecretWithOffset(
      secret: String
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[vo.ValidatorOnboarding.ContractId, vo.ValidatorOnboarding]]]
  ]

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
  ]

  def lookupUsedSecret(
      secret: String
  )(implicit tc: TraceContext): Future[
    Option[Contract[vo.UsedSecret.ContractId, vo.UsedSecret]]
  ] =
    lookupUsedSecretWithOffset(secret).map(_.value)

  def listValidatorOnboardings(limit: Limit = Limit.DefaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[?, vo.ValidatorOnboarding]]] =
    multiDomainAcsStore
      .listContracts(vo.ValidatorOnboarding.COMPANION, limit)
      .map(_ map (_.contract))

  def listExpiredValidatorOnboardings()
      : ListExpiredContracts[ValidatorOnboarding.ContractId, ValidatorOnboarding] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(ValidatorOnboarding.COMPANION)(_.expiresAt)

  def lookupApprovedSvIdentityByNameWithOffset(
      name: String
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[so.ApprovedSvIdentity.ContractId, so.ApprovedSvIdentity]]]
  ]

  def lookupApprovedSvIdentityByName(
      name: String
  )(implicit
      tc: TraceContext
  ): Future[Option[Contract[so.ApprovedSvIdentity.ContractId, so.ApprovedSvIdentity]]] =
    lookupApprovedSvIdentityByNameWithOffset(name).map(_.value)

  def lookupSvOnboardingConfirmed()(implicit tc: TraceContext): Future[
    Option[Contract[so.SvOnboardingConfirmed.ContractId, so.SvOnboardingConfirmed]]
  ] =
    multiDomainAcsStore
      .listContracts(
        so.SvOnboardingConfirmed.COMPANION,
        PageLimit.tryCreate(1),
      )
      .map(_.headOption map (_.contract))

  private[this] def listLaggingSvcRulesFollowers(targetDomain: DomainId)(implicit
      tc: TraceContext
  ): Future[Seq[AssignedContract[?, ?]]] =
    multiDomainAcsStore.listAssignedContractsNotOnDomainN(
      targetDomain,
      templatesMovedByMyAutomation: _*
    )

  final def listSvcRulesTransferFollowers[SrCid, Sr](svcRules: AssignedContract[SrCid, Sr])(implicit
      tc: TraceContext
  ): Future[Seq[FollowTask[SrCid, Sr, ?, ?]]] =
    listLaggingSvcRulesFollowers(svcRules.domain)
      .map(_ map (FollowTask(svcRules, _)))

  def key: SvStore.Key
}

object SvSvStore {
  def apply(
      key: SvStore.Key,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
  ): SvSvStore =
    storage match {
      case _: MemoryStorage =>
        new InMemorySvSvStore(key, loggerFactory, retryProvider)
      case db: DbStorage =>
        new DbSvSvStore(key, db, loggerFactory, retryProvider)
    }

  private[network] val templatesMovedByMyAutomation: Seq[ConstrainedTemplate] =
    Seq[ConstrainedTemplate](
      vo.UsedSecret.COMPANION,
      vo.ValidatorOnboarding.COMPANION,
      so.ApprovedSvIdentity.COMPANION,
    )

  /** Contract filter of an sv acs store for a specific acs party. */
  def contractFilter(key: SvStore.Key): MultiDomainAcsStore.ContractFilter = {
    import MultiDomainAcsStore.mkFilter
    val sv = key.svParty.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter(
      key.svParty,
      Map(
        mkFilter(vo.ValidatorOnboarding.COMPANION)(co => co.payload.sv == sv),
        mkFilter(vo.UsedSecret.COMPANION)(co => co.payload.sv == sv),
        mkFilter(so.ApprovedSvIdentity.COMPANION)(co => co.payload.approvingSv == sv),
        mkFilter(so.SvOnboardingConfirmed.COMPANION)(co => co.payload.svParty == sv),
      ),
    )
  }
}

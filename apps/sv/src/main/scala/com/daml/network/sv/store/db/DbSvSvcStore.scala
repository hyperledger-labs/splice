package com.daml.network.sv.store.db

import cats.data.OptionT
import cats.implicits.*
import com.daml.ledger.javaapi.data as javab
import com.daml.ledger.javaapi.data.Identifier
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coin.*
import com.daml.network.codegen.java.cc.coinimport.ImportCrate
import com.daml.network.codegen.java.cc.globaldomain.MemberTraffic
import com.daml.network.codegen.java.cc.round.ClosedMiningRound
import com.daml.network.codegen.java.cc.validatorlicense.{ValidatorFaucetCoupon, ValidatorLicense}
import com.daml.network.codegen.java.cn.cns.{CnsEntry, CnsEntryContext}
import com.daml.network.codegen.java.cn.svc.coinprice.CoinPriceVote
import com.daml.network.codegen.java.cn.svcrules.*
import com.daml.network.codegen.java.cn.svonboarding.{SvOnboardingConfirmed, SvOnboardingRequest}
import com.daml.network.codegen.java.cn.wallet.subscriptions.{
  SubscriptionIdleState,
  SubscriptionInitialPayment,
  SubscriptionRequest,
}
import com.daml.network.environment.RetryProvider
import com.daml.network.store.MultiDomainAcsStore.ContractCompanion
import com.daml.network.store.db.AcsQueries.SelectFromAcsTableResult
import com.daml.network.store.db.{AcsQueries, AcsTables, DbCNNodeAppStore, TxLogQueries}
import com.daml.network.store.{AcsStoreDump, Limit, LimitHelpers, MultiDomainAcsStore}
import com.daml.network.sv.store.TxLogEntry.EntryType
import com.daml.network.sv.store.{
  AppRewardCouponsSum,
  DefiniteVoteTxLogEntry,
  SvStore,
  SvSvcStore,
  TxLogEntry,
}
import com.daml.network.util.*
import com.daml.network.util.Contract.Companion.Template
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.digitalasset.canton.topology.{DomainId, Member, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import slick.jdbc.GetResult
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.canton.SQLActionBuilder

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

class DbSvSvcStore(
    override val key: SvStore.Key,
    storage: DbStorage,
    override protected val outerLoggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit
    override protected val ec: ExecutionContext,
    override protected val templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStore[TxLogEntry](
      storage,
      SvcTables.acsTableName,
      SvcTables.txLogTableName,
      // TODO (#5544): change this to something better
      storeDescriptor = Json.obj(
        "name" -> Json.fromString("DbSvSvcStore"),
        "version" -> Json.fromInt(1),
        "svParty" -> Json.fromString(key.svParty.toProtoPrimitive),
        "svcParty" -> Json.fromString(key.svcParty.toProtoPrimitive),
      ),
    )
    with SvSvcStore
    with AcsTables
    with AcsQueries
    with TxLogQueries[TxLogEntry]
    with LimitHelpers
    with NamedLogging {

  import multiDomainAcsStore.waitUntilAcsIngested

  def storeId: Int = multiDomainAcsStore.storeId

  override def listExpiredCnsSubscriptions(
      now: CantonTimestamp,
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[SvSvcStore.IdleCnsSubscription]] = waitUntilAcsIngested {
    for {
      joinedRows <- storage
        .query(
          sql"""
              select
                       idle.store_id,
                       idle.event_number,
                       idle.contract_id,
                       idle.template_id_package_id,
                       idle.template_id_qualified_name,
                       idle.create_arguments,
                       idle.created_event_blob,
                       idle.created_at,
                       idle.contract_expires_at,
                       ctx.store_id,
                       ctx.event_number,
                       ctx.contract_id,
                       ctx.template_id_package_id,
                       ctx.template_id_qualified_name,
                       ctx.create_arguments,
                       ctx.created_event_blob,
                       ctx.created_at,
                       ctx.contract_expires_at
              from     svc_acs_store idle
              join     svc_acs_store ctx
              on       idle.subscription_reference_contract_id = ctx.subscription_reference_contract_id
                and      ctx.store_id = idle.store_id
              where    idle.store_id = $storeId
                and      idle.template_id_qualified_name = ${QualifiedName(
              SubscriptionIdleState.COMPANION.TEMPLATE_ID
            )}
                and      ctx.template_id_qualified_name = ${QualifiedName(
              CnsEntryContext.COMPANION.TEMPLATE_ID
            )}
                and      idle.subscription_next_payment_due_at < $now
              order by idle.subscription_next_payment_due_at
              limit    ${sqlLimit(limit)}
          """.as[(SelectFromAcsTableResult, SelectFromAcsTableResult)],
          "listExpiredCnsSubscriptions",
        )
    } yield applyLimit("listExpiredCnsSubscriptions", limit, joinedRows).map {
      case (idleRow, ctxRow) =>
        val idleContract = contractFromRow(SubscriptionIdleState.COMPANION)(idleRow)
        val ctxContract = contractFromRow(CnsEntryContext.COMPANION)(ctxRow)
        SvSvcStore.IdleCnsSubscription(idleContract, ctxContract)
    }
  }

  override def listSvOnboardingConfirmed(limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]]] =
    waitUntilAcsIngested {
      for {
        result <- storage
          .query(
            (selectFromAcsTable(SvcTables.acsTableName) ++
              sql"""
            where store_id = $storeId
              and template_id_qualified_name = ${QualifiedName(
                  SvOnboardingConfirmed.TEMPLATE_ID
                )}""").toActionBuilder.as[SelectFromAcsTableResult],
            "listSvOnboardingConfirmed",
          )
        limited = applyLimit("listSvOnboardingConfirmed", limit, result)
      } yield limited.map(contractFromRow(SvOnboardingConfirmed.COMPANION)(_))
    }

  override def lookupSvOnboardingConfirmedByParty(svParty: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]]] =
    waitUntilAcsIngested {
      for {
        result <- storage
          .querySingle(
            (selectFromAcsTable(SvcTables.acsTableName) ++
              sql"""
                where store_id = $storeId
                  and template_id_qualified_name = ${QualifiedName(
                  SvOnboardingConfirmed.TEMPLATE_ID
                )}
                  and sv_candidate_party = $svParty
                limit 1
              """).toActionBuilder.as[SelectFromAcsTableResult].headOption,
            "lookupSvOnboardingConfirmedByParty",
          )
          .value
      } yield result.map(contractFromRow(SvOnboardingConfirmed.COMPANION)(_))
    }

  override def listConfirmations(action: ActionRequiringConfirmation, limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[Confirmation.ContractId, Confirmation]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          (selectFromAcsTable(SvcTables.acsTableName) ++
            sql"""
                 where store_id = $storeId
                   and template_id_qualified_name = ${QualifiedName(Confirmation.TEMPLATE_ID)}
                   and action_requiring_confirmation = ${payloadJsonFromDefinedDataType(action)}
                 limit ${sqlLimit(limit)}
               """).toActionBuilder.as[SelectFromAcsTableResult],
          "listConfirmations",
        )
      limited = applyLimit("listConfirmations", limit, result)
    } yield limited.map(contractFromRow(Confirmation.COMPANION)(_))
  }

  override def listAppRewardCouponsOnDomain(round: Long, domainId: DomainId, limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[AppRewardCoupon.ContractId, AppRewardCoupon]]] =
    listRewardCouponsOnDomain(AppRewardCoupon.COMPANION, round, domainId, limit)

  override def sumAppRewardCouponsOnDomain(round: Long, domainId: DomainId)(implicit
      tc: TraceContext
  ): Future[AppRewardCouponsSum] = for {
    sums <- selectFromRewardCouponsOnDomain[(Option[BigDecimal], Option[BigDecimal])](
      sql"""select
              sum(case app_reward_is_featured when true then reward_amount else 0 end),
              sum(case app_reward_is_featured when true then 0 else reward_amount end)""",
      AppRewardCoupon.COMPANION.TEMPLATE_ID,
      round,
      domainId,
    )
  } yield sums.headOption
    .map { case (featured, unfeatured) =>
      AppRewardCouponsSum(featured = featured.getOrElse(0L), unfeatured = unfeatured.getOrElse(0L))
    }
    .getOrElse(AppRewardCouponsSum(0L, 0L))

  override def listValidatorRewardCouponsOnDomain(round: Long, domainId: DomainId, limit: Limit)(
      implicit tc: TraceContext
  ): Future[Seq[Contract[ValidatorRewardCoupon.ContractId, ValidatorRewardCoupon]]] =
    listRewardCouponsOnDomain(ValidatorRewardCoupon.COMPANION, round, domainId, limit)

  override def sumValidatorRewardCouponsOnDomain(round: Long, domainId: DomainId)(implicit
      tc: TraceContext
  ): Future[BigDecimal] =
    selectFromRewardCouponsOnDomain[Option[BigDecimal]](
      sql"select sum(reward_amount)",
      ValidatorRewardCoupon.COMPANION.TEMPLATE_ID,
      round,
      domainId,
    ).map(_.headOption.flatten.getOrElse(BigDecimal(0)))

  override def listValidatorFaucetCouponsOnDomain(round: Long, domainId: DomainId, limit: Limit)(
      implicit tc: TraceContext
  ): Future[Seq[Contract[ValidatorFaucetCoupon.ContractId, ValidatorFaucetCoupon]]] =
    listRewardCouponsOnDomain(ValidatorFaucetCoupon.COMPANION, round, domainId, limit)

  override def countValidatorFaucetCouponsOnDomain(round: Long, domainId: DomainId)(implicit
      tc: TraceContext
  ): Future[Long] = selectFromRewardCouponsOnDomain[Option[Long]](
    sql"select count(*)",
    ValidatorFaucetCoupon.COMPANION.TEMPLATE_ID,
    round,
    domainId,
  ).map(_.headOption.flatten.getOrElse(0L))

  private def listRewardCouponsOnDomain[C, TCId <: ContractId[_], T](
      companion: C,
      round: Long,
      domainId: DomainId,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCId, T],
      tc: TraceContext,
  ): Future[Seq[Contract[TCId, T]]] = {
    val templateId = companionClass.typeId(companion)
    selectFromRewardCouponsOnDomain[SelectFromAcsTableResult](
      sql"select #${SelectFromAcsTableResult.sqlColumnsCommaSeparated()}",
      templateId,
      round,
      domainId,
      limit = limit,
    ).map(_.map(contractFromRow(companion)(_)))
  }

  private def selectFromRewardCouponsOnDomain[R: GetResult](
      selectClause: SQLActionBuilder,
      templateId: Identifier,
      round: Long,
      domainId: DomainId,
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[R]] = {
    val opName = s"selectFrom${templateId.getEntityName}OnDomain"
    waitUntilAcsIngested {
      for {
        result <- storage
          .query(
            (selectClause ++
              sql"""
                   from #${SvcTables.acsTableName}
                   where store_id = $storeId
                     and template_id_qualified_name = ${QualifiedName(templateId)}
                     and assigned_domain = $domainId
                     and reward_round = $round
                     and reward_party is not null -- otherwise index is not used
                   limit ${sqlLimit(limit)}
                 """).toActionBuilder.as[R],
            opName,
          )
        limited = applyLimit(opName, limit, result)
      } yield limited
    }
  }

  override def listAppRewardCouponsGroupedByCounterparty(
      roundNumber: Long,
      roundDomain: DomainId,
      totalCouponsLimit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Seq[AppRewardCoupon.ContractId]]] =
    listCouponsGroupedByCounterparty(
      AppRewardCoupon.COMPANION,
      roundNumber,
      roundDomain,
      totalCouponsLimit,
    )

  override def listValidatorRewardCouponsGroupedByCounterparty(
      roundNumber: Long,
      roundDomain: DomainId,
      totalCouponsLimit: Limit,
  )(implicit tc: TraceContext): Future[Seq[Seq[ValidatorRewardCoupon.ContractId]]] =
    listCouponsGroupedByCounterparty(
      ValidatorRewardCoupon.COMPANION,
      roundNumber,
      roundDomain,
      totalCouponsLimit,
    )

  override def listValidatorFaucetCouponsGroupedByCounterparty(
      roundNumber: Long,
      roundDomain: DomainId,
      totalCouponsLimit: Limit,
  )(implicit tc: TraceContext): Future[Seq[Seq[ValidatorFaucetCoupon.ContractId]]] =
    listCouponsGroupedByCounterparty(
      ValidatorFaucetCoupon.COMPANION,
      roundNumber,
      roundDomain,
      totalCouponsLimit,
    )

  private def listCouponsGroupedByCounterparty[C, TCId <: ContractId[_]: ClassTag, T](
      companion: C,
      roundNumber: Long,
      roundDomain: DomainId,
      totalCouponsLimit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCId, T],
      tc: TraceContext,
  ): Future[Seq[Seq[TCId]]] = {
    val templateId = companionClass.typeId(companion)
    val opName = s"list${templateId.getEntityName}GroupedByCounterparty"
    waitUntilAcsIngested {
      for {
        result <- storage
          .query(
            sql"""
                select reward_party, array_agg(contract_id)
                from svc_acs_store
                where store_id = $storeId
                  and template_id_qualified_name = ${QualifiedName(templateId)}
                  and assigned_domain = $roundDomain
                  and reward_round = $roundNumber
                  and reward_party is not null -- otherwise index is not used
                group by reward_party
                limit ${sqlLimit(totalCouponsLimit)}
               """.as[(PartyId, Array[ContractId[ValidatorRewardCoupon]])],
            opName,
          )
      } yield applyLimit(opName, totalCouponsLimit, result).map(
        _._2.map(cid => companionClass.toContractId(companion, cid.contractId)).toSeq
      )
    }
  }

  override protected def lookupOldestClosedMiningRound()(implicit
      tc: TraceContext
  ): Future[Option[AssignedContract[ClosedMiningRound.ContractId, ClosedMiningRound]]] =
    waitUntilAcsIngested {
      (for {
        svcRules <- OptionT(lookupSvcRules())
        result <- storage.querySingle(
          selectFromAcsTableWithState(
            SvcTables.acsTableName,
            storeId,
            where =
              sql"""template_id_qualified_name = ${QualifiedName(ClosedMiningRound.TEMPLATE_ID)}
              and assigned_domain = ${svcRules.domain}
              and mining_round is not null""",
            orderLimit = sql"""order by mining_round limit 1""",
          ).headOption,
          "lookupOldestClosedMiningRound",
        )
      } yield assignedContractFromRow(ClosedMiningRound.COMPANION)(result)).value
    }

  override def lookupConfirmationByActionWithOffset(
      confirmer: PartyId,
      action: ActionRequiringConfirmation,
  )(implicit tc: TraceContext): Future[
    MultiDomainAcsStore.QueryResult[Option[Contract[Confirmation.ContractId, Confirmation]]]
  ] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            SvcTables.acsTableName,
            storeId,
            where = sql"""
                    template_id_qualified_name = ${QualifiedName(Confirmation.TEMPLATE_ID)}
                and confirmer = $confirmer
                and action_requiring_confirmation = ${payloadJsonFromDefinedDataType(action)}
                  """,
            orderLimit = sql" limit 1",
          ).headOption,
          "lookupConfirmationByActionWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(Confirmation.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def lookupCnsAcceptedInitialPaymentConfirmationByPaymentIdWithOffset(
      confirmer: PartyId,
      paymentId: SubscriptionInitialPayment.ContractId,
  )(implicit
      tc: TraceContext
  ): Future[
    MultiDomainAcsStore.QueryResult[Option[
      Contract[Confirmation.ContractId, Confirmation]
    ]]
  ] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            SvcTables.acsTableName,
            storeId,
            where = sql"""
                        template_id_qualified_name = ${QualifiedName(Confirmation.TEMPLATE_ID)}
                    and confirmer = $confirmer
                    and action_cns_entry_context_payment_id = $paymentId
                    and action_cns_entry_context_arc_type = ${lengthLimited(
                SvcTables.CnsActionTypeCollectInitialEntryPayment
              )}
                      """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupCnsAcceptedInitialPaymentConfirmationByPaymentIdWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(Confirmation.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def lookupCnsRejectedInitialPaymentConfirmationByPaymentIdWithOffset(
      confirmer: PartyId,
      paymentId: SubscriptionInitialPayment.ContractId,
  )(implicit
      tc: TraceContext
  ): Future[
    MultiDomainAcsStore.QueryResult[Option[
      Contract[Confirmation.ContractId, Confirmation]
    ]]
  ] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            SvcTables.acsTableName,
            storeId,
            where = sql"""
                        template_id_qualified_name = ${QualifiedName(Confirmation.TEMPLATE_ID)}
                    and confirmer = $confirmer
                    and action_cns_entry_context_payment_id = $paymentId
                    and action_cns_entry_context_arc_type = ${lengthLimited(
                SvcTables.CnsActionTypeRejectEntryInitialPayment
              )}
                      """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupCnsRejectedInitialPaymentConfirmationByPaymentIdWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(Confirmation.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def lookupCnsInitialPaymentConfirmationByPaymentIdWithOffset(
      confirmer: PartyId,
      paymentId: SubscriptionInitialPayment.ContractId,
  )(implicit
      tc: TraceContext
  ): Future[
    MultiDomainAcsStore.QueryResult[Option[
      Contract[Confirmation.ContractId, Confirmation]
    ]]
  ] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            SvcTables.acsTableName,
            storeId,
            where = sql"""
                        template_id_qualified_name = ${QualifiedName(Confirmation.TEMPLATE_ID)}
                    and confirmer = $confirmer
                    and action_cns_entry_context_payment_id = $paymentId
                      """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupCnsInitialPaymentConfirmationByPaymentIdWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(Confirmation.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def listInitialPaymentConfirmationByCnsName(
      confirmer: PartyId,
      name: String,
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[Contract[Confirmation.ContractId, Confirmation]]] =
    waitUntilAcsIngested {
      for {
        result <- storage
          .query(
            (selectFromAcsTable(SvcTables.acsTableName) ++
              sql"""
                    where template_id_qualified_name = ${QualifiedName(Confirmation.TEMPLATE_ID)}
                      and confirmer = $confirmer
                      and action_cns_entry_context_cid IN (
                        select contract_id
                        from #${SvcTables.acsTableName}
                        where store_id = $storeId
                          and template_id_qualified_name = ${QualifiedName(
                  CnsEntryContext.TEMPLATE_ID
                )}
                          and cns_entry_name = ${lengthLimited(name)}
                      )
                    limit ${sqlLimit(limit)};
                        """).toActionBuilder.as[SelectFromAcsTableResult],
            "listInitialPaymentConfirmationByCnsName",
          )
        limited = applyLimit("listInitialPaymentConfirmationByCnsName", limit, result)
      } yield limited.map(contractFromRow(Confirmation.COMPANION)(_))
    }

  override def lookupSvOnboardingRequestByTokenWithOffset(
      token: String
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]
  ]] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            SvcTables.acsTableName,
            storeId,
            where = sql"""
                      template_id_qualified_name = ${QualifiedName(SvOnboardingRequest.TEMPLATE_ID)}
                  and sv_onboarding_token = ${lengthLimited(token)}
                    """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupSvOnboardingRequestByTokenWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(SvOnboardingRequest.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def listSvOnboardingRequestsBySvcMembers(
      svcRules: Contract.Has[SvcRules.ContractId, SvcRules],
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]] =
    waitUntilAcsIngested {
      import scala.jdk.CollectionConverters.*
      val svCandidates = svcRules.payload.members.asScala
        .map { case (party, member) =>
          sql"(${lengthLimited(party)}, ${lengthLimited(member.name)})"
        }
        .reduceOption { (acc, next) =>
          (acc ++ sql"," ++ next).toActionBuilder
        }
        .getOrElse(
          throw new IllegalArgumentException("SvcRules is supposed to have at least one member")
        )
      for {
        result <- storage
          .query(
            (selectFromAcsTable(SvcTables.acsTableName) ++
              sql"""
                 where store_id = $storeId
                   and template_id_qualified_name = ${QualifiedName(
                  SvOnboardingRequest.TEMPLATE_ID
                )}
                   and (sv_candidate_party, sv_candidate_name) in (""" ++ svCandidates ++ sql""")
                 limit ${sqlLimit(limit)}
               """).toActionBuilder.as[SelectFromAcsTableResult],
            "listSvOnboardingRequestsBySvcMembers",
          )
        limited = applyLimit("listSvOnboardingRequestsBySvcMembers", limit, result)
      } yield limited.map(contractFromRow(SvOnboardingRequest.COMPANION)(_))
    }

  override protected def listExpiredRoundBased[Id <: ContractId[T], T <: javab.Template](
      companion: Template[Id, T]
  )(coin: T => Coin): ListExpiredContracts[Id, T] = (_, limit) =>
    implicit tc =>
      waitUntilAcsIngested {
        for {
          domainId <- getSvcRules().map(_.domain)
          rows <- storage.query(
            selectFromAcsTableWithState(
              SvcTables.acsTableName,
              storeId,
              where = sql"""
                template_id_qualified_name = ${QualifiedName(companion.TEMPLATE_ID)}
                and assigned_domain = $domainId
                and acs.coin_round_of_expiry <= (
                  select mining_round - 2
                  from svc_acs_store
                  where store_id = $storeId
                    and template_id_qualified_name = ${QualifiedName(
                  cc.round.OpenMiningRound.TEMPLATE_ID
                )}
                    and mining_round is not null
                  order by mining_round desc limit 1)""",
              orderLimit = sql"""order by mining_round desc limit ${sqlLimit(limit)}""",
            ),
            "listExpiredRoundBased",
          )
          assigned = rows.map(assignedContractFromRow(companion)(_))
        } yield assigned
      }

  override def listMemberTrafficContracts(memberId: Member, domainId: DomainId, limit: Limit)(
      implicit tc: TraceContext
  ): Future[Seq[Contract[MemberTraffic.ContractId, MemberTraffic]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          (selectFromAcsTable(SvcTables.acsTableName) ++
            sql"""
                 where store_id = $storeId
                   and template_id_qualified_name = ${QualifiedName(MemberTraffic.TEMPLATE_ID)}
                   and member_traffic_member = $memberId
                 limit ${sqlLimit(limit)}
               """).toActionBuilder.as[SelectFromAcsTableResult],
          "listMemberTrafficContracts",
        )
    } yield applyLimit("listMemberTrafficContracts", limit, result).map(
      contractFromRow(MemberTraffic.COMPANION)(_)
    )
  }

  override def listMemberCoinPriceVotes(limit: Limit = Limit.DefaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[CoinPriceVote.ContractId, CoinPriceVote]]] = waitUntilAcsIngested {
    import scala.jdk.CollectionConverters.*
    for {
      svcRules <- getSvcRules()
      voterParties = inClause(
        svcRules.payload.members.asScala
          .map { case (party, _) =>
            lengthLimited(party)
          }
      )
      result <- storage
        .query(
          (selectFromAcsTable(SvcTables.acsTableName) ++
            sql"""
               where store_id = $storeId
                 and template_id_qualified_name = ${QualifiedName(CoinPriceVote.TEMPLATE_ID)}
                 and voter in """ ++ voterParties ++ sql"""
               limit ${sqlLimit(limit)}
             """).toActionBuilder.as[SelectFromAcsTableResult],
          "listMemberCoinPriceVotes",
        )
      limited = applyLimit("listMemberCoinPriceVotes", limit, result)
    } yield limited.map(contractFromRow(CoinPriceVote.COMPANION)(_)).distinctBy(_.payload.sv)
  }

  override protected def lookupSvOnboardingRequestByCandidatePartyWithOffset(
      candidateParty: PartyId
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]
  ]] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            SvcTables.acsTableName,
            storeId,
            where = sql"""
                        template_id_qualified_name = ${QualifiedName(
                SvOnboardingRequest.TEMPLATE_ID
              )}
                    and sv_candidate_party = $candidateParty
                      """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupSvOnboardingRequestByCandidatePartyWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(SvOnboardingRequest.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def lookupValidatorLicenseWithOffset(
      validator: PartyId
  )(implicit tc: TraceContext): Future[
    MultiDomainAcsStore.QueryResult[Option[Contract[ValidatorLicense.ContractId, ValidatorLicense]]]
  ] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            SvcTables.acsTableName,
            storeId,
            where = sql"""
                          template_id_qualified_name = ${QualifiedName(
                ValidatorLicense.TEMPLATE_ID
              )}
                      and validator = $validator
                        """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupValidatorLicenseWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(ValidatorLicense.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def getTotalPurchasedMemberTraffic(memberId: Member, domainId: DomainId)(implicit
      tc: TraceContext
  ): Future[Long] = waitUntilAcsIngested {
    for {
      sum <- storage
        .querySingle(
          sql"""
               select sum(total_traffic_purchased)
               from #${SvcTables.acsTableName}
               where store_id = $storeId
                and template_id_qualified_name = ${QualifiedName(MemberTraffic.TEMPLATE_ID)}
                and member_traffic_member = ${lengthLimited(memberId.toProtoPrimitive)}
             """.as[Long].headOption,
          "getTotalPurchasedMemberTraffic",
        )
        .value
    } yield sum.getOrElse(0L)
  }

  override def listVotesByVoteRequests(
      voteRequestCids: Seq[VoteRequest.ContractId],
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[Vote.ContractId, Vote]]] = waitUntilAcsIngested {
    val voteRequestCidsSql = inClause(voteRequestCids)
    for {
      result <- storage
        .query(
          (selectFromAcsTable(SvcTables.acsTableName) ++
            sql"""
                 where store_id = $storeId
                   and template_id_qualified_name = ${QualifiedName(Vote.TEMPLATE_ID)}
                   and vote_request_cid in """ ++ voteRequestCidsSql ++ sql"""
                 limit ${sqlLimit(limit)}
               """).toActionBuilder.as[SelectFromAcsTableResult],
          "listVotesByVoteRequests",
        )
      limited = applyLimit("listVotesByVoteRequests", limit, result)
    } yield limited.map(contractFromRow(Vote.COMPANION)(_))
  }

  override def lookupVoteByThisSvAndVoteRequestWithOffset(voteRequestCid: VoteRequest.ContractId)(
      implicit tc: TraceContext
  ): Future[MultiDomainAcsStore.QueryResult[Option[Contract[Vote.ContractId, Vote]]]] =
    waitUntilAcsIngested {
      (for {
        resultWithOffset <- storage
          .querySingle(
            selectFromAcsTableWithOffset(
              SvcTables.acsTableName,
              storeId,
              where = sql"""
                            template_id_qualified_name = ${QualifiedName(Vote.TEMPLATE_ID)}
                        and vote_request_cid = $voteRequestCid
                        and voter = ${key.svParty}
                          """,
              orderLimit = sql"limit 1",
            ).headOption,
            "lookupVoteByThisSvAndVoteRequestWithOffset",
          )
      } yield MultiDomainAcsStore.QueryResult(
        resultWithOffset.offset,
        resultWithOffset.row.map(contractFromRow(Vote.COMPANION)(_)),
      )).getOrRaise(offsetExpectedError())
    }

  override def lookupVoteRequestByThisSvAndActionWithOffset(
      action: ActionRequiringConfirmation
  )(implicit tc: TraceContext): Future[
    MultiDomainAcsStore.QueryResult[Option[Contract[VoteRequest.ContractId, VoteRequest]]]
  ] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            SvcTables.acsTableName,
            storeId,
            where = sql"""
                          template_id_qualified_name = ${QualifiedName(VoteRequest.TEMPLATE_ID)}
                      and action_requiring_confirmation = ${payloadJsonFromDefinedDataType(action)}
                      and requester = ${key.svParty}
                        """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupVoteRequestByThisSvAndActionWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(VoteRequest.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def listEligibleVotes(
      voteRequestId: VoteRequest.ContractId,
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[Vote.ContractId, Vote]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          (selectFromAcsTable(SvcTables.acsTableName) ++
            sql"""
                   where store_id = $storeId
                     and template_id_qualified_name = ${QualifiedName(Vote.TEMPLATE_ID)}
                     and vote_request_cid = $voteRequestId
                   limit ${sqlLimit(limit)}
                 """).toActionBuilder.as[SelectFromAcsTableResult],
          "listEligibleVotes",
        )
      limited = applyLimit("listEligibleVotes", limit, result)
    } yield limited.map(contractFromRow(Vote.COMPANION)(_))
  }

  override def lookupCoinPriceVoteByThisSv()(implicit
      tc: TraceContext
  ): Future[Option[Contract[CoinPriceVote.ContractId, CoinPriceVote]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .querySingle(
          (selectFromAcsTable(SvcTables.acsTableName) ++
            sql"""
                     where store_id = $storeId
                       and template_id_qualified_name = ${QualifiedName(CoinPriceVote.TEMPLATE_ID)}
                       and voter = ${key.svParty}
                     limit 1
                   """).toActionBuilder.as[SelectFromAcsTableResult].headOption,
          "lookupCoinPriceVoteByThisSv",
        )
        .value
    } yield result.map(contractFromRow(CoinPriceVote.COMPANION)(_))
  }

  override protected def lookupSvOnboardingRequestByCandidateNameWithOffset(
      candidateName: String
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]
  ]] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            SvcTables.acsTableName,
            storeId,
            where = sql"""
                            template_id_qualified_name = ${QualifiedName(
                SvOnboardingRequest.TEMPLATE_ID
              )}
                        and sv_candidate_name = ${lengthLimited(candidateName)}
                          """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupSvOnboardingRequestByCandidateNameWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(SvOnboardingRequest.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def lookupSvOnboardingConfirmedByNameWithOffset(
      svName: String
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]]
  ]] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            SvcTables.acsTableName,
            storeId,
            where = sql"""
                              template_id_qualified_name = ${QualifiedName(
                SvOnboardingConfirmed.TEMPLATE_ID
              )}
                          and sv_candidate_name = ${lengthLimited(svName)}
                            """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupSvOnboardingConfirmedByNameWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(SvOnboardingConfirmed.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def listElectionRequests(
      svcRules: AssignedContract[SvcRules.ContractId, SvcRules],
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[ElectionRequest.ContractId, ElectionRequest]]] = waitUntilAcsIngested {
    import scala.jdk.CollectionConverters.*
    val requesters = inClause(svcRules.payload.members.keySet().asScala.map(lengthLimited))
    val electionRequestEpoch = svcRules.payload.epoch.longValue()
    for {
      result <- storage
        .query(
          (selectFromAcsTable(SvcTables.acsTableName) ++
            sql"""
                     where store_id = $storeId
                       and template_id_qualified_name = ${QualifiedName(
                ElectionRequest.TEMPLATE_ID
              )}
                       and requester IN """ ++ requesters ++ sql"""
                       and election_request_epoch = $electionRequestEpoch
                     limit ${sqlLimit(limit)}
                   """).toActionBuilder.as[SelectFromAcsTableResult],
          "listElectionRequests",
        )
      limited = applyLimit("listElectionRequests", limit, result)
    } yield limited.map(contractFromRow(ElectionRequest.COMPANION)(_))
  }

  override def lookupElectionRequestByRequesterWithOffset(requester: PartyId, epoch: Long)(implicit
      tc: TraceContext
  ): Future[
    MultiDomainAcsStore.QueryResult[Option[Contract[ElectionRequest.ContractId, ElectionRequest]]]
  ] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            SvcTables.acsTableName,
            storeId,
            where = sql"""
                              template_id_qualified_name = ${QualifiedName(
                ElectionRequest.TEMPLATE_ID
              )}
                          and requester = $requester
                          and election_request_epoch = $epoch
                            """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupElectionRequestByRequesterWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(ElectionRequest.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def listExpiredElectionRequests(
      epoch: Long,
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[Contract[
    ElectionRequest.ContractId,
    ElectionRequest,
  ]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          (selectFromAcsTable(SvcTables.acsTableName) ++
            sql"""
                       where store_id = $storeId
                         and template_id_qualified_name = ${QualifiedName(
                ElectionRequest.TEMPLATE_ID
              )}
                         and election_request_epoch < $epoch
                       limit ${sqlLimit(limit)}
                     """).toActionBuilder.as[SelectFromAcsTableResult],
          "listExpiredElectionRequests",
        )
      limited = applyLimit("listExpiredElectionRequests", limit, result)
    } yield limited.map(contractFromRow(ElectionRequest.COMPANION)(_))
  }

  override def getImportShipmentFor(receiver: PartyId)(implicit
      tc: TraceContext
  ): Future[AcsStoreDump.ImportShipment] = waitUntilAcsIngested {
    for {
      openRound <- getLatestActiveOpenMiningRound()
      result <- storage
        .query(
          selectFromAcsTableWithState(
            SvcTables.acsTableName,
            storeId,
            where = sql"""template_id_qualified_name = ${QualifiedName(ImportCrate.TEMPLATE_ID)}
                           and import_crate_receiver = $receiver""",
            orderLimit = sql"""limit ${sqlLimit(Limit.DefaultLimit)}""",
          ),
          "getImportShipmentFor",
        )
      limited = applyLimit("getImportShipmentFor", Limit.DefaultLimit, result)
      withState = limited.map(
        contractWithStateFromRow(ImportCrate.COMPANION)(_)
      )
    } yield AcsStoreDump.ImportShipment(openRound, withState)
  }

  override def lookupCnsEntryByNameWithOffset(
      name: String
  )(implicit tc: TraceContext): Future[
    MultiDomainAcsStore.QueryResult[Option[AssignedContract[CnsEntry.ContractId, CnsEntry]]]
  ] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithStateAndOffset(
            SvcTables.acsTableName,
            storeId,
            where = sql"""template_id_qualified_name = ${QualifiedName(CnsEntry.TEMPLATE_ID)}
                    and cns_entry_name = ${lengthLimited(name)}
                    and assigned_domain is not null""",
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupCnsEntryByNameWithOffset",
        )
        .getOrRaise(offsetExpectedError())
      assigned = resultWithOffset.row.map(
        assignedContractFromRow(CnsEntry.COMPANION)(_)
      )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      assigned,
    )
  }

  override def lookupSubscriptionInitialPaymentWithOffset(
      paymentCid: SubscriptionInitialPayment.ContractId
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[AssignedContract[SubscriptionInitialPayment.ContractId, SubscriptionInitialPayment]]
  ]] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithStateAndOffset(
            SvcTables.acsTableName,
            storeId,
            where = sql"""template_id_qualified_name = ${QualifiedName(
                SubscriptionInitialPayment.TEMPLATE_ID
              )}
                        and contract_id = $paymentCid
                        and assigned_domain is not null""",
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupSubscriptionInitialPaymentWithOffset",
        )
        .getOrRaise(offsetExpectedError())
      assigned = resultWithOffset.row.map(
        assignedContractFromRow(SubscriptionInitialPayment.COMPANION)(_)
      )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      assigned,
    )
  }

  override def lookupFeaturedAppRightWithOffset(
      providerPartyId: PartyId
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[AssignedContract[FeaturedAppRight.ContractId, FeaturedAppRight]]
  ]] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithStateAndOffset(
            SvcTables.acsTableName,
            storeId,
            where =
              sql"""template_id_qualified_name = ${QualifiedName(FeaturedAppRight.TEMPLATE_ID)}
                      and featured_app_right_provider = $providerPartyId
                      and assigned_domain is not null""",
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupFeaturedAppRightWithOffset",
        )
        .getOrRaise(offsetExpectedError())
      assigned = resultWithOffset.row.map(
        assignedContractFromRow(FeaturedAppRight.COMPANION)(_)
      )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      assigned,
    )
  }

  override def listVoteResults(
      actionName: Option[String],
      executed: Option[Boolean],
      _requester: Option[String],
      effectiveFrom: Option[String],
      effectiveTo: Option[String],
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[VoteResult]] = {
    val dbType = EntryType.DefiniteVoteTxLogEntry
    val actionNameCondition = actionName match {
      case Some(actionName) =>
        sql"""and action_name like ${lengthLimited(s"%${lengthLimited(actionName)}%")}"""
      case None => sql""""""
    }
    val executedCondition = executed match {
      case Some(executed) => sql"""and executed = ${executed}"""
      case None => sql""""""
    }
    val effectivenessCondition = (effectiveFrom, effectiveTo) match {
      case (Some(effectiveFrom), Some(effectiveTo)) =>
        sql"""and effective_at between ${lengthLimited(effectiveFrom)} and ${lengthLimited(
            effectiveTo
          )}"""
      case (Some(effectiveFrom), None) =>
        sql"""and effective_at > ${lengthLimited(effectiveFrom)}"""
      case (None, Some(effectiveTo)) => sql"""and effective_at < ${lengthLimited(effectiveTo)}"""
      case (None, None) => sql""""""
    }
    val requesterCondition = _requester match {
      case Some(_requester) =>
        sql"""and requester like ${lengthLimited(s"%${lengthLimited(_requester)}%")}"""
      case None => sql""""""
    }
    for {
      rows <- storage.query(
        selectFromTxLogTable(
          SvcTables.txLogTableName,
          storeId,
          where = (sql"""entry_type = ${dbType} """
            ++ actionNameCondition
            ++ executedCondition
            ++ requesterCondition
            ++ effectivenessCondition).toActionBuilder,
          orderLimit = sql"""limit ${sqlLimit(limit)}""",
        ),
        "listVoteResults",
      )
      recentVoteResults = applyLimit("listVoteResults", limit, rows)
        .map(
          txLogEntryFromRow[DefiniteVoteTxLogEntry](txLogConfig)
        )
        .map(_.result.getOrElse(throw txMissingField()))
    } yield recentVoteResults
  }

  override def lookupCnsEntryContext(reference: SubscriptionRequest.ContractId)(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[CnsEntryContext.ContractId, CnsEntryContext]]] =
    waitUntilAcsIngested {
      for {
        row <- storage
          .querySingle(
            selectFromAcsTableWithState(
              SvcTables.acsTableName,
              storeId,
              where = sql"""
               template_id_qualified_name = ${QualifiedName(CnsEntryContext.COMPANION.TEMPLATE_ID)}
           and subscription_reference_contract_id = $reference""",
              orderLimit = sql"""limit 1""",
            ).headOption,
            "lookupCnsEntryContext",
          )
          .value
      } yield row.map(contractWithStateFromRow(CnsEntryContext.COMPANION)(_))
    }
}

package com.daml.network.sv.store.db

import cats.data.OptionT
import cats.implicits.*
import com.daml.ledger.javaapi.data as javab
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coin.*
import com.daml.network.codegen.java.cc.coinimport.ImportCrate
import com.daml.network.codegen.java.cc.globaldomain.MemberTraffic
import com.daml.network.codegen.java.cc.round.ClosedMiningRound
import com.daml.network.codegen.java.cc.validatorlicense.ValidatorLicense
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
import com.daml.network.store.TxLogStore.TransactionTreeSource
import com.daml.network.store.db.AcsQueries.SelectFromAcsTableResult
import com.daml.network.store.db.AcsTables.ContractStateRowData
import com.daml.network.store.db.{AcsQueries, AcsTables, DbCNNodeAppStoreWithHistory}
import com.daml.network.store.{AcsStoreDump, Limit, LimitHelpers, MultiDomainAcsStore}
import com.daml.network.sv.store.db.SvcTables.{SvcAcsStoreRowData, SvcTxLogRowData}
import com.daml.network.sv.store.{SvStore, SvSvcStore, SvcTxLogParser}
import com.daml.network.util.Contract.Companion.Template
import com.daml.network.util.{
  AssignedContract,
  Contract,
  ContractWithState,
  QualifiedName,
  TemplateJsonDecoder,
}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.digitalasset.canton.topology.{DomainId, Member, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

class DbSvSvcStore(
    override val key: SvStore.Key,
    storage: DbStorage,
    override protected val outerLoggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    override protected val transactionTreeSource: TransactionTreeSource,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStoreWithHistory[SvcTxLogParser.TxLogIndexRecord, SvcTxLogParser.TxLogEntry](
      storage,
      DbSvSvcStore.acsTableName,
      DbSvSvcStore.txLogTableName,
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
    with LimitHelpers
    with NamedLogging {

  import storage.DbStorageConverters.setParameterByteArray
  import multiDomainAcsStore.waitUntilAcsIngested

  def storeId: Int = multiDomainAcsStore.storeId

  override def ingestionAcsInsert(
      createdEvent: javab.CreatedEvent,
      contractState: ContractStateRowData,
  )(implicit
      tc: TraceContext
  ) = {
    SvcAcsStoreRowData.fromCreatedEvent(createdEvent).map {
      case SvcAcsStoreRowData(
            contract,
            contractExpiresAt,
            coinRoundOfExpiry,
            rewardRound,
            rewardParty,
            miningRound,
            actionRequiringConfirmation,
            confirmer,
            svOnboardingToken,
            svCandidateParty,
            svCandidateName,
            validator,
            totalTrafficPurchased,
            voter,
            voteRequestCid,
            requester,
            electionRequestEpoch,
            importCrateReceiver,
            memberTrafficMember,
            cnsEntryName,
            actionCnsEntryContextCid,
            actionCnsEntryContextPaymentId,
            actionCnsEntryContextArcType,
            subscriptionReferenceContractId,
            subscriptionNextPaymentDueAt,
            featuredAppRightProvider,
          ) =>
        val contractId = contract.contractId.asInstanceOf[ContractId[Any]]
        val templateId = contract.identifier
        val templateIdPackageId = lengthLimited(contract.identifier.getPackageId)
        val createArguments = payloadJsonFromContract(contract.payload)
        val createArgumentsValue = payloadValueJsonStringFromRecord(contract.mandatoryPayloadValue)
        val contractMetadataCreatedAt = Timestamp.assertFromInstant(contract.metadata.createdAt)
        val contractMetadataContractKeyHash =
          lengthLimited(contract.metadata.contractKeyHash.toStringUtf8)
        val contractMetadataDriverInternal = contract.metadata.driverMetadata.toByteArray
        val safeSvOnboardingToken = svOnboardingToken.map(lengthLimited)
        val safeSvCandidateName = svCandidateName.map(lengthLimited)
        val safeCnsEntryName = cnsEntryName.map(lengthLimited)
        val safeActionCnsEntryContextArcType = actionCnsEntryContextArcType.map(lengthLimited)
        sqlu"""
              insert into svc_acs_store(store_id, contract_id, template_id_package_id, template_id_qualified_name, create_arguments, create_arguments_value, contract_metadata_created_at,
                                        contract_metadata_contract_key_hash, contract_metadata_driver_internal, contract_expires_at,
                                        assigned_domain, reassignment_counter, reassignment_target_domain,
                                        reassignment_source_domain, reassignment_submitter, reassignment_unassign_id,
                                        coin_round_of_expiry, reward_round, reward_party, mining_round, action_requiring_confirmation,
                                        confirmer, sv_onboarding_token, sv_candidate_party, sv_candidate_name, validator,
                                        total_traffic_purchased, voter, vote_request_cid, requester, election_request_epoch,
                                        import_crate_receiver, member_traffic_member, cns_entry_name, action_cns_entry_context_cid,
                                        action_cns_entry_context_payment_id, action_cns_entry_context_arc_type, subscription_reference_contract_id,
                                        subscription_next_payment_due_at, featured_app_right_provider)
              values ($storeId, $contractId, $templateIdPackageId, ${QualifiedName(
            templateId
          )}, $createArguments, $createArgumentsValue, $contractMetadataCreatedAt,
                      $contractMetadataContractKeyHash, $contractMetadataDriverInternal, $contractExpiresAt,
                      ${contractState.assignedDomain}, ${contractState.reassignmentCounter}, ${contractState.reassignmentTargetDomain},
                      ${contractState.reassignmentSourceDomain}, ${contractState.reassignmentSubmitter}, ${contractState.reassignmentUnassignId},
                      $coinRoundOfExpiry, $rewardRound, $rewardParty, $miningRound, $actionRequiringConfirmation,
                      $confirmer, $safeSvOnboardingToken, $svCandidateParty, $safeSvCandidateName, $validator,
                      $totalTrafficPurchased, $voter, $voteRequestCid, $requester, $electionRequestEpoch,
                      $importCrateReceiver, $memberTrafficMember, $safeCnsEntryName, $actionCnsEntryContextCid,
                      $actionCnsEntryContextPaymentId, $safeActionCnsEntryContextArcType, $subscriptionReferenceContractId,
                      $subscriptionNextPaymentDueAt, $featuredAppRightProvider)
              on conflict do nothing
            """
    }
  }

  override def ingestionTxLogInsert(record: SvcTxLogParser.TxLogIndexRecord)(implicit
      tc: TraceContext
  ) = {
    val SvcTxLogRowData(
      eventId,
      offset,
      domainId,
      indexRecordType,
      actionName,
      executed,
      requester,
      effectiveAt,
      votedAt,
    ) = SvcTxLogRowData.fromTxLogIndexRecord(record)
    val safeEventId = lengthLimited(eventId)
    val safeOffset = offset.map(lengthLimited)
    val safeActionName = actionName.map(lengthLimited)
    val safeRequester = requester.map(lengthLimited)
    val safeEffectiveAt = effectiveAt.map(lengthLimited)
    val safeVotedAt = votedAt.map(lengthLimited)
    Right(sqlu"""
          insert into svc_txlog_store(store_id, event_id, index_record_type, "offset", domain_id,
          action_name, executed, requester, effective_at, voted_at)
          values ($storeId, $safeEventId, $indexRecordType, $safeOffset, $domainId,
                  $safeActionName, $executed, $safeRequester, $safeEffectiveAt, $safeVotedAt)
          on conflict do nothing
        """)
  }

  override def listExpiredCnsSubscriptions(
      now: CantonTimestamp,
      limit: Int,
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
                       idle.create_arguments_value,
                       idle.contract_metadata_created_at,
                       idle.contract_metadata_contract_key_hash,
                       idle.contract_metadata_driver_internal,
                       idle.contract_expires_at,
                       ctx.store_id,
                       ctx.event_number,
                       ctx.contract_id,
                       ctx.template_id_package_id,
                       ctx.template_id_qualified_name,
                       ctx.create_arguments,
                       ctx.create_arguments_value,
                       ctx.contract_metadata_created_at,
                       ctx.contract_metadata_contract_key_hash,
                       ctx.contract_metadata_driver_internal,
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
              limit    $limit
          """.as[(SelectFromAcsTableResult, SelectFromAcsTableResult)],
          "listExpiredCnsSubscriptions",
        )
    } yield joinedRows.map { case (idleRow, ctxRow) =>
      val idleContract = contractFromRow(SubscriptionIdleState.COMPANION)(idleRow)
      val ctxContract = contractFromRow(CnsEntryContext.COMPANION)(ctxRow)
      SvSvcStore.IdleCnsSubscription(idleContract, ctxContract)
    }
  }

  override def lookupSvOnboardingConfirmedByParty(svParty: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]]] =
    waitUntilAcsIngested {
      for {
        result <- storage
          .querySingle(
            (selectFromAcsTable(DbSvSvcStore.acsTableName) ++
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
          (selectFromAcsTable(DbSvSvcStore.acsTableName) ++
            sql"""
                 where store_id = $storeId
                   and template_id_qualified_name = ${QualifiedName(Confirmation.TEMPLATE_ID)}
                   and action_requiring_confirmation = ${payloadJsonFromValue(action.toValue)}
                 limit ${sqlLimit(limit)}
               """).toActionBuilder.as[SelectFromAcsTableResult],
          "listConfirmations",
        )
      limited = applyLimit(limit, result)
    } yield limited.map(contractFromRow(Confirmation.COMPANION)(_))
  }

  override def listAppRewardCouponsOnDomain(round: Long, domainId: DomainId, limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[AppRewardCoupon.ContractId, AppRewardCoupon]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          (selectFromAcsTable(DbSvSvcStore.acsTableName) ++
            sql"""
                 where store_id = $storeId
                   and template_id_qualified_name = ${QualifiedName(AppRewardCoupon.TEMPLATE_ID)}
                   and reward_round = $round
                   and assigned_domain = $domainId
                   and reward_party is not null -- otherwise index is not used
                 limit ${sqlLimit(limit)}
               """).toActionBuilder.as[SelectFromAcsTableResult],
          "listAppRewardCouponsOnDomain",
        )
      limited = applyLimit(limit, result)
    } yield limited.map(contractFromRow(AppRewardCoupon.COMPANION)(_))
  }

  override def listValidatorRewardCouponsOnDomain(round: Long, domainId: DomainId, limit: Limit)(
      implicit tc: TraceContext
  ): Future[Seq[Contract[ValidatorRewardCoupon.ContractId, ValidatorRewardCoupon]]] =
    waitUntilAcsIngested {
      for {
        result <- storage
          .query(
            (selectFromAcsTable(DbSvSvcStore.acsTableName) ++
              sql"""
                   where store_id = $storeId
                     and template_id_qualified_name = ${QualifiedName(
                  ValidatorRewardCoupon.TEMPLATE_ID
                )}
                     and assigned_domain = $domainId
                     and reward_round = $round
                     and reward_party is not null -- otherwise index is not used
                   limit ${sqlLimit(limit)}
                 """).toActionBuilder.as[SelectFromAcsTableResult],
            "listValidatorRewardCouponsOnDomain",
          )
        limited = applyLimit(limit, result)
      } yield limited.map(contractFromRow(ValidatorRewardCoupon.COMPANION)(_))
    }

  override def listAppRewardCouponsGroupedByCounterparty(
      roundNumber: Long,
      roundDomain: DomainId,
      totalCouponsLimit: Long,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Seq[AppRewardCoupon.ContractId]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          sql"""
              select reward_party, array_agg(contract_id)
              from svc_acs_store
              where store_id = $storeId
                and template_id_qualified_name = ${QualifiedName(AppRewardCoupon.TEMPLATE_ID)}
                and assigned_domain = $roundDomain
                and reward_round = $roundNumber
                and reward_party is not null -- otherwise index is not used
              group by reward_party
              limit $totalCouponsLimit
             """.as[(PartyId, Array[ContractId[AppRewardCoupon]])],
          "listAppRewardCouponsGroupedByCounterparty",
        )
    } yield result.map(_._2.map(cid => new AppRewardCoupon.ContractId(cid.contractId)).toSeq)
  }

  override def listValidatorRewardCouponsGroupedByCounterparty(
      roundNumber: Long,
      roundDomain: DomainId,
      totalCouponsLimit: Long,
  )(implicit tc: TraceContext): Future[Seq[Seq[ValidatorRewardCoupon.ContractId]]] =
    waitUntilAcsIngested {
      for {
        result <- storage
          .query(
            sql"""
                select reward_party, array_agg(contract_id)
                from svc_acs_store
                where store_id = $storeId
                  and template_id_qualified_name = ${QualifiedName(
                ValidatorRewardCoupon.TEMPLATE_ID
              )}
                  and assigned_domain = $roundDomain
                  and reward_round = $roundNumber
                  and reward_party is not null -- otherwise index is not used
                group by reward_party
                limit $totalCouponsLimit
               """.as[(PartyId, Array[ContractId[ValidatorRewardCoupon]])],
            "listValidatorRewardCouponsGroupedByCounterparty",
          )
      } yield result.map(
        _._2.map(cid => new ValidatorRewardCoupon.ContractId(cid.contractId)).toSeq
      )
    }

  override protected def lookupOldestClosedMiningRound()(implicit
      tc: TraceContext
  ): Future[Option[AssignedContract[ClosedMiningRound.ContractId, ClosedMiningRound]]] =
    waitUntilAcsIngested {
      (for {
        svcRules <- OptionT(lookupSvcRules())
        result <- storage.querySingle(
          selectFromAcsTableWithState(
            DbSvSvcStore.acsTableName,
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
            DbSvSvcStore.acsTableName,
            storeId,
            where = sql"""
                    template_id_qualified_name = ${QualifiedName(Confirmation.TEMPLATE_ID)}
                and confirmer = $confirmer
                and action_requiring_confirmation = ${payloadJsonFromValue(action.toValue)}
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
            DbSvSvcStore.acsTableName,
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
            DbSvSvcStore.acsTableName,
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
            DbSvSvcStore.acsTableName,
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
  )(implicit tc: TraceContext): Future[Seq[Contract[Confirmation.ContractId, Confirmation]]] =
    waitUntilAcsIngested {
      for {
        result <- storage
          .query(
            (selectFromAcsTable(DbSvSvcStore.acsTableName) ++
              sql"""
                    where template_id_qualified_name = ${QualifiedName(Confirmation.TEMPLATE_ID)}
                      and confirmer = $confirmer
                      and action_cns_entry_context_cid IN (
                        select contract_id
                        from #${DbSvSvcStore.acsTableName}
                        where store_id = $storeId
                          and template_id_qualified_name = ${QualifiedName(
                  CnsEntryContext.TEMPLATE_ID
                )}
                          and cns_entry_name = ${lengthLimited(name)}
                      )
                    limit ${sqlLimit(Limit.DefaultLimit)};
                        """).toActionBuilder.as[SelectFromAcsTableResult],
            "listInitialPaymentConfirmationByCnsName",
          )
        limited = applyLimit(Limit.DefaultLimit, result)
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
            DbSvSvcStore.acsTableName,
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
      svcRules: Contract.Has[SvcRules.ContractId, SvcRules]
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]] =
    waitUntilAcsIngested {
      import scala.jdk.CollectionConverters.*
      val svCandidates = svcRules.payload.members.asScala
        .map { case (party, member) =>
          s"('$party', '${member.name}')"
        }
        .mkString("(", ",", ")")
      for {
        result <- storage
          .query(
            (selectFromAcsTable(DbSvSvcStore.acsTableName) ++
              sql"""
                 where store_id = $storeId
                   and template_id_qualified_name = ${QualifiedName(
                  SvOnboardingRequest.TEMPLATE_ID
                )}
                   and (sv_candidate_party, sv_candidate_name) in #$svCandidates
                 limit ${sqlLimit(Limit.DefaultLimit)}
               """).toActionBuilder.as[SelectFromAcsTableResult],
            "listSvOnboardingRequestsBySvcMembers",
          )
        limited = applyLimit(Limit.DefaultLimit, result)
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
              DbSvSvcStore.acsTableName,
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
              orderLimit = sql"""order by mining_round desc limit $limit""",
            ),
            "listExpiredRoundBased",
          )
          assigned = rows.map(assignedContractFromRow(companion)(_))
        } yield assigned
      }

  override def listMemberTrafficContracts(memberId: Member, domainId: DomainId, limit: Long)(
      implicit tc: TraceContext
  ): Future[Seq[Contract[MemberTraffic.ContractId, MemberTraffic]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          (selectFromAcsTable(DbSvSvcStore.acsTableName) ++
            sql"""
                 where store_id = $storeId
                   and template_id_qualified_name = ${QualifiedName(MemberTraffic.TEMPLATE_ID)}
                   and member_traffic_member = $memberId
                 limit $limit
               """).toActionBuilder.as[SelectFromAcsTableResult],
          "listMemberTrafficContracts",
        )
    } yield result.map(contractFromRow(MemberTraffic.COMPANION)(_))
  }

  override def listMemberCoinPriceVotes()(implicit
      tc: TraceContext
  ): Future[Seq[Contract[CoinPriceVote.ContractId, CoinPriceVote]]] = waitUntilAcsIngested {
    import scala.jdk.CollectionConverters.*
    for {
      svcRules <- getSvcRules()
      voterParties = svcRules.payload.members.asScala
        .map { case (party, _) =>
          party
        }
        .mkString("('", "','", "')")
      result <- storage
        .query(
          (selectFromAcsTable(DbSvSvcStore.acsTableName) ++
            sql"""
               where store_id = $storeId
                 and template_id_qualified_name = ${QualifiedName(CoinPriceVote.TEMPLATE_ID)}
                 and voter in #$voterParties
               limit ${sqlLimit(Limit.DefaultLimit)}
             """).toActionBuilder.as[SelectFromAcsTableResult],
          "listMemberCoinPriceVotes",
        )
      limited = applyLimit(Limit.DefaultLimit, result)
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
            DbSvSvcStore.acsTableName,
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
            DbSvSvcStore.acsTableName,
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
               from #${DbSvSvcStore.acsTableName}
               where store_id = $storeId
                and template_id_qualified_name = ${QualifiedName(MemberTraffic.TEMPLATE_ID)}
                and member_traffic_member = ${lengthLimited(memberId.toProtoPrimitive)}
             """.as[Long].headOption,
          "getTotalPurchasedMemberTraffic",
        )
        .value
    } yield sum.getOrElse(0L)
  }

  override def listVotesByVoteRequests(voteRequestCids: Seq[VoteRequest.ContractId])(implicit
      tc: TraceContext
  ): Future[Seq[Contract[Vote.ContractId, Vote]]] = waitUntilAcsIngested {
    val voteRequestCidsSql = voteRequestCids
      .map(_.contractId)
      .mkString("('", "','", "')")
    for {
      result <- storage
        .query(
          (selectFromAcsTable(DbSvSvcStore.acsTableName) ++
            sql"""
                 where store_id = $storeId
                   and template_id_qualified_name = ${QualifiedName(Vote.TEMPLATE_ID)}
                   and vote_request_cid in #$voteRequestCidsSql
                 limit ${sqlLimit(Limit.DefaultLimit)}
               """).toActionBuilder.as[SelectFromAcsTableResult],
          "listVotesByVoteRequests",
        )
      limited = applyLimit(Limit.DefaultLimit, result)
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
              DbSvSvcStore.acsTableName,
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
            DbSvSvcStore.acsTableName,
            storeId,
            where = sql"""
                          template_id_qualified_name = ${QualifiedName(VoteRequest.TEMPLATE_ID)}
                      and action_requiring_confirmation = ${payloadJsonFromValue(action.toValue)}
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

  override def listEligibleVotes(voteRequestId: VoteRequest.ContractId)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[Vote.ContractId, Vote]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          (selectFromAcsTable(DbSvSvcStore.acsTableName) ++
            sql"""
                   where store_id = $storeId
                     and template_id_qualified_name = ${QualifiedName(Vote.TEMPLATE_ID)}
                     and vote_request_cid = $voteRequestId
                   limit ${sqlLimit(Limit.DefaultLimit)}
                 """).toActionBuilder.as[SelectFromAcsTableResult],
          "listEligibleVotes",
        )
      limited = applyLimit(Limit.DefaultLimit, result)
    } yield limited.map(contractFromRow(Vote.COMPANION)(_))
  }

  override def lookupCoinPriceVoteByThisSv()(implicit
      tc: TraceContext
  ): Future[Option[Contract[CoinPriceVote.ContractId, CoinPriceVote]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .querySingle(
          (selectFromAcsTable(DbSvSvcStore.acsTableName) ++
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
            DbSvSvcStore.acsTableName,
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
            DbSvSvcStore.acsTableName,
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

  override def listElectionRequests(svcRules: AssignedContract[SvcRules.ContractId, SvcRules])(
      implicit tc: TraceContext
  ): Future[Seq[Contract[ElectionRequest.ContractId, ElectionRequest]]] = waitUntilAcsIngested {
    import scala.jdk.CollectionConverters.*
    val requesters = svcRules.payload.members.keySet().asScala.mkString("('", "','", "')")
    val electionRequestEpoch = svcRules.payload.epoch.longValue()
    for {
      result <- storage
        .query(
          (selectFromAcsTable(DbSvSvcStore.acsTableName) ++
            sql"""
                     where store_id = $storeId
                       and template_id_qualified_name = ${QualifiedName(
                ElectionRequest.TEMPLATE_ID
              )}
                       and requester IN #$requesters
                       and election_request_epoch = $electionRequestEpoch
                     limit ${sqlLimit(Limit.DefaultLimit)}
                   """).toActionBuilder.as[SelectFromAcsTableResult],
          "listElectionRequests",
        )
      limited = applyLimit(Limit.DefaultLimit, result)
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
            DbSvSvcStore.acsTableName,
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
      epoch: Long
  )(implicit tc: TraceContext): Future[Seq[Contract[
    ElectionRequest.ContractId,
    ElectionRequest,
  ]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          (selectFromAcsTable(DbSvSvcStore.acsTableName) ++
            sql"""
                       where store_id = $storeId
                         and template_id_qualified_name = ${QualifiedName(
                ElectionRequest.TEMPLATE_ID
              )}
                         and election_request_epoch < $epoch
                       limit ${sqlLimit(Limit.DefaultLimit)}
                     """).toActionBuilder.as[SelectFromAcsTableResult],
          "listExpiredElectionRequests",
        )
      limited = applyLimit(Limit.DefaultLimit, result)
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
            DbSvSvcStore.acsTableName,
            storeId,
            where = sql"""template_id_qualified_name = ${QualifiedName(ImportCrate.TEMPLATE_ID)}
                           and import_crate_receiver = $receiver""",
            orderLimit = sql"""limit ${sqlLimit(Limit.DefaultLimit)}""",
          ),
          "getImportShipmentFor",
        )
      limited = applyLimit(Limit.DefaultLimit, result)
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
            DbSvSvcStore.acsTableName,
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
            DbSvSvcStore.acsTableName,
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
            DbSvSvcStore.acsTableName,
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

  def listVoteResults(
      actionName: Option[String],
      executed: Option[Boolean],
      _requester: Option[String],
      effectiveFrom: Option[String],
      effectiveTo: Option[String],
      limit: Int,
  )(implicit
      tc: TraceContext
  ): Future[Seq[SvcTxLogParser.TxLogEntry.DefiniteVoteTxLogEntry]] = {
    val dbType = SvcTxLogParser.TxLogIndexRecord.DefiniteVoteIndexRecord.dbType
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
        (sql"""
             select event_id, domain_id
             from svc_txlog_store
             where store_id = $storeId
                and index_record_type = ${dbType}
                """ ++ actionNameCondition ++ executedCondition ++ requesterCondition ++ effectivenessCondition ++ sql"""
             limit $limit;
           """).toActionBuilder.as[(String, String)],
        "listVoteResults",
      )
      entries <- rows.traverse { case (eventId, domainId) =>
        loadTxLogEntry(txLogReader, eventId, DomainId.tryFromString(domainId), None, dbType)
      }: Future[Seq[SvcTxLogParser.TxLogEntry]]
      recentVoteResults = entries.collect {
        case e: SvcTxLogParser.TxLogEntry.DefiniteVoteTxLogEntry => e
      }
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
              DbSvSvcStore.acsTableName,
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

object DbSvSvcStore {

  val acsTableName = "svc_acs_store"
  val txLogTableName = "svc_txlog_store"

}

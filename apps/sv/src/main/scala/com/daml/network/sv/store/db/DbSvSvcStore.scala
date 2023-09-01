package com.daml.network.sv.store.db

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
import com.daml.network.codegen.java.cn.wallet.subscriptions.SubscriptionInitialPayment
import com.daml.network.environment.RetryProvider
import com.daml.network.store.db.AcsTables.AcsStoreRowTemplate
import com.daml.network.store.db.{AcsQueries, AcsTables, DbCNNodeAppStoreWithoutHistory}
import com.daml.network.store.{AcsStoreDump, Limit, LimitHelpers, MultiDomainAcsStore}
import com.daml.network.sv.config.SvDomainConfig
import com.daml.network.sv.store.db.SvcTables.SvcAcsStoreRowData
import com.daml.network.sv.store.{SvStore, SvSvcStore}
import com.daml.network.util.Contract.Companion.Template
import com.daml.network.util.{AssignedContract, Contract, ContractWithState, TemplateJsonDecoder}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.digitalasset.canton.topology.{DomainId, Member, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import slick.dbio.DBIO
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

class DbSvSvcStore(
    override val key: SvStore.Key,
    storage: DbStorage,
    override protected[this] val domainConfig: SvDomainConfig,
    override protected[this] val enableCoinRulesUpgrade: Boolean,
    override protected val outerLoggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStoreWithoutHistory(
      storage,
      DbSvSvcStore.tableName,
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

  import multiDomainAcsStore.waitUntilAcsIngested
  import storage.DbStorageConverters.setParameterByteArray

  def storeId: Int = multiDomainAcsStore.storeId

  override def ingestionAcsInsert(createdEvent: javab.CreatedEvent)(implicit
      tc: TraceContext
  ): Either[String, DBIO[_]] = {
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
            featuredAppRightProvider,
          ) =>
        val contractId = contract.contractId.asInstanceOf[ContractId[Any]]
        val templateId = contract.identifier
        val createArguments = payloadJsonFromContract(contract.payload)
        val contractMetadataCreatedAt = Timestamp.assertFromInstant(contract.metadata.createdAt)
        val contractMetadataContractKeyHash =
          lengthLimited(contract.metadata.contractKeyHash.toStringUtf8)
        val contractMetadataDriverInternal = contract.metadata.driverMetadata.toByteArray
        val safeSvOnboardingToken = svOnboardingToken.map(lengthLimited)
        val safeSvCandidateName = svCandidateName.map(lengthLimited)
        val safeCnsEntryName = cnsEntryName.map(lengthLimited)
        val safeActionCnsEntryContextArcType = actionCnsEntryContextArcType.map(lengthLimited)
        sqlu"""
              insert into svc_acs_store(store_id, contract_id, template_id, create_arguments, contract_metadata_created_at,
                                        contract_metadata_contract_key_hash, contract_metadata_driver_internal, contract_expires_at,
                                        coin_round_of_expiry, reward_round, reward_party, mining_round, action_requiring_confirmation,
                                        confirmer, sv_onboarding_token, sv_candidate_party, sv_candidate_name, validator,
                                        total_traffic_purchased, voter, vote_request_cid, requester, election_request_epoch,
                                        import_crate_receiver, member_traffic_member, cns_entry_name, action_cns_entry_context_cid,
                                        action_cns_entry_context_payment_id, action_cns_entry_context_arc_type, featured_app_right_provider)
              values ($storeId, $contractId, $templateId, $createArguments, $contractMetadataCreatedAt,
                      $contractMetadataContractKeyHash, $contractMetadataDriverInternal, $contractExpiresAt,
                      $coinRoundOfExpiry, $rewardRound, $rewardParty, $miningRound, $actionRequiringConfirmation,
                      $confirmer, $safeSvOnboardingToken, $svCandidateParty, $safeSvCandidateName, $validator,
                      $totalTrafficPurchased, $voter, $voteRequestCid, $requester, $electionRequestEpoch,
                      $importCrateReceiver, $memberTrafficMember, $safeCnsEntryName, $actionCnsEntryContextCid,
                      $actionCnsEntryContextPaymentId, $safeActionCnsEntryContextArcType, $featuredAppRightProvider)
              on conflict do nothing
            """
    }
  }

  // TODO (#5314): this is not checking for domain
  override def lookupSvOnboardingConfirmedByPartyOnDomain(svParty: PartyId, domainId: DomainId)(
      implicit tc: TraceContext
  ): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]]
  ]] = waitUntilAcsIngested {
    for {
      result <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DbSvSvcStore.tableName,
            storeId,
            where = sql"""
              template_id = ${SvOnboardingConfirmed.TEMPLATE_ID}
                and sv_candidate_party = $svParty
              """,
            orderLimit = sql"limit 1",
          ).toActionBuilder.as[AcsStoreRowTemplateWithOffset].headOption,
          "lookupSvOnboardingConfirmedByPartyOnDomain",
        )
        .getOrRaise(offsetExpectedError())
    } yield MultiDomainAcsStore.QueryResult(
      result.offset,
      result.row.map(contractFromRow(SvOnboardingConfirmed.COMPANION)(_)),
    )
  }

  override def listConfirmations(action: ActionRequiringConfirmation, limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[Confirmation.ContractId, Confirmation]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          (selectFromAcsTable(DbSvSvcStore.tableName) ++
            sql"""
                 where store_id = $storeId
                   and template_id = ${Confirmation.TEMPLATE_ID}
                   and action_requiring_confirmation = ${payloadJsonFromValue(action.toValue)}
                 limit ${sqlLimit(limit)}
               """).toActionBuilder.as[AcsStoreRowTemplate],
          "listConfirmations",
        )
      limited = applyLimit(limit, result)
    } yield limited.map(contractFromRow(Confirmation.COMPANION)(_))
  }

  // TODO (#5314): this is not checking for domain
  override def listAppRewardCouponsOnDomain(round: Long, domainId: DomainId, limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[AppRewardCoupon.ContractId, AppRewardCoupon]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          (selectFromAcsTable(DbSvSvcStore.tableName) ++
            sql"""
                 where store_id = $storeId
                   and template_id = ${AppRewardCoupon.TEMPLATE_ID}
                   and reward_round = $round
                   and reward_party is not null -- otherwise index is not used
                 limit ${sqlLimit(limit)}
               """).toActionBuilder.as[AcsStoreRowTemplate],
          "listAppRewardCouponsOnDomain",
        )
      limited = applyLimit(limit, result)
    } yield limited.map(contractFromRow(AppRewardCoupon.COMPANION)(_))
  }

  // TODO (#5314): this is not checking for domain
  override def listValidatorRewardCouponsOnDomain(round: Long, domainId: DomainId, limit: Limit)(
      implicit tc: TraceContext
  ): Future[Seq[Contract[ValidatorRewardCoupon.ContractId, ValidatorRewardCoupon]]] =
    waitUntilAcsIngested {
      for {
        result <- storage
          .query(
            (selectFromAcsTable(DbSvSvcStore.tableName) ++
              sql"""
                   where store_id = $storeId
                     and template_id = ${ValidatorRewardCoupon.TEMPLATE_ID}
                     and reward_round = $round
                     and reward_party is not null -- otherwise index is not used
                   limit ${sqlLimit(limit)}
                 """).toActionBuilder.as[AcsStoreRowTemplate],
            "listValidatorRewardCouponsOnDomain",
          )
        limited = applyLimit(limit, result)
      } yield limited.map(contractFromRow(ValidatorRewardCoupon.COMPANION)(_))
    }

  override def listAppRewardCouponsGroupedByCounterparty(round: Long, totalCouponsLimit: Long)(
      implicit tc: TraceContext
  ): Future[Seq[Seq[AppRewardCoupon.ContractId]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          sql"""
              select reward_party, array_agg(contract_id)
              from svc_acs_store
              where store_id = $storeId
                and template_id = ${AppRewardCoupon.TEMPLATE_ID}
                and reward_round = $round
                and reward_party is not null -- otherwise index is not used
              group by reward_party
              limit $totalCouponsLimit
             """.as[(PartyId, Array[ContractId[AppRewardCoupon]])],
          "listAppRewardCouponsGroupedByCounterparty",
        )
    } yield result.map(_._2.map(cid => new AppRewardCoupon.ContractId(cid.contractId)).toSeq)
  }

  override def listValidatorRewardCouponsGroupedByCounterparty(
      round: Long,
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
                  and template_id = ${ValidatorRewardCoupon.TEMPLATE_ID}
                  and reward_round = $round
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
  ): Future[Option[Contract[ClosedMiningRound.ContractId, ClosedMiningRound]]] =
    waitUntilAcsIngested {
      (for {
        result <- storage.querySingle(
          (selectFromAcsTable(DbSvSvcStore.tableName) ++
            sql"""
            where store_id = $storeId
              and template_id = ${ClosedMiningRound.TEMPLATE_ID}
              and mining_round is not null
            order by mining_round
            limit 1
           """).toActionBuilder.as[AcsStoreRowTemplate].headOption,
          "lookupOldestClosedMiningRound",
        )
      } yield contractFromRow(ClosedMiningRound.COMPANION)(result)).value
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
            DbSvSvcStore.tableName,
            storeId,
            where = sql"""
                    template_id = ${Confirmation.TEMPLATE_ID}
                and confirmer = $confirmer
                and action_requiring_confirmation = ${payloadJsonFromValue(action.toValue)}
                  """,
            orderLimit = sql" limit 1",
          ).as[AcsStoreRowTemplateWithOffset].headOption,
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
            DbSvSvcStore.tableName,
            storeId,
            where = sql"""
                        template_id = ${Confirmation.TEMPLATE_ID}
                    and confirmer = $confirmer
                    and action_cns_entry_context_payment_id = $paymentId
                    and action_cns_entry_context_arc_type = ${lengthLimited(
                SvcTables.CnsActionTypeCollectInitialEntryPayment
              )}
                      """,
            orderLimit = sql"limit 1",
          ).as[AcsStoreRowTemplateWithOffset].headOption,
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
            DbSvSvcStore.tableName,
            storeId,
            where = sql"""
                        template_id = ${Confirmation.TEMPLATE_ID}
                    and confirmer = $confirmer
                    and action_cns_entry_context_payment_id = $paymentId
                    and action_cns_entry_context_arc_type = ${lengthLimited(
                SvcTables.CnsActionTypeRejectEntryInitialPayment
              )}
                      """,
            orderLimit = sql"limit 1",
          ).as[AcsStoreRowTemplateWithOffset].headOption,
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
            DbSvSvcStore.tableName,
            storeId,
            where = sql"""
                        template_id = ${Confirmation.TEMPLATE_ID}
                    and confirmer = $confirmer
                    and action_cns_entry_context_payment_id = $paymentId
                      """,
            orderLimit = sql"limit 1",
          ).as[AcsStoreRowTemplateWithOffset].headOption,
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
            (selectFromAcsTable(DbSvSvcStore.tableName) ++
              sql"""
                    where template_id = ${Confirmation.TEMPLATE_ID}
                      and confirmer = $confirmer
                      and action_cns_entry_context_cid IN (
                        select contract_id
                        from #${DbSvSvcStore.tableName}
                        where store_id = $storeId
                          and template_id = ${CnsEntryContext.TEMPLATE_ID}
                          and cns_entry_name = ${lengthLimited(name)}
                      )
                    limit ${sqlLimit(Limit.DefaultLimit)};
                        """).toActionBuilder.as[AcsStoreRowTemplate],
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
            DbSvSvcStore.tableName,
            storeId,
            where = sql"""
                      template_id = ${SvOnboardingRequest.TEMPLATE_ID}
                  and sv_onboarding_token = ${lengthLimited(token)}
                    """,
            orderLimit = sql"limit 1",
          ).as[AcsStoreRowTemplateWithOffset].headOption,
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
            (selectFromAcsTable(DbSvSvcStore.tableName) ++
              sql"""
                 where store_id = $storeId
                   and template_id = ${SvOnboardingRequest.TEMPLATE_ID}
                   and (sv_candidate_party, sv_candidate_name) in #$svCandidates
                 limit ${sqlLimit(Limit.DefaultLimit)}
               """).toActionBuilder.as[AcsStoreRowTemplate],
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
          rows <- storage.query(
            (selectFromAcsTable(DbSvSvcStore.tableName) ++
              sql"""
              where store_id = $storeId
                and template_id = ${companion.TEMPLATE_ID}
                and coin_round_of_expiry <= (
                  select mining_round - 2
                  from svc_acs_store
                  where store_id = $storeId
                    and template_id = ${cc.round.OpenMiningRound.TEMPLATE_ID}
                    and mining_round is not null
                  order by mining_round desc
                  limit 1
                )
                order by mining_round desc
                limit $limit
               """).toActionBuilder.as[AcsStoreRowTemplate],
            "listExpiredRoundBased",
          )
          contracts <- rows.traverse(multiDomainAcsStore.contractWithStateFromRow(companion)(_))
        } yield contracts.flatMap(_.toAssignedContract)
      }

  override def listMemberTrafficContracts(memberId: Member, domainId: DomainId, limit: Long)(
      implicit tc: TraceContext
  ): Future[Seq[Contract[MemberTraffic.ContractId, MemberTraffic]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          (selectFromAcsTable(DbSvSvcStore.tableName) ++
            sql"""
                 where store_id = $storeId
                   and template_id = ${MemberTraffic.TEMPLATE_ID}
                   and member_traffic_member = $memberId
                 limit $limit
               """).toActionBuilder.as[AcsStoreRowTemplate],
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
          (selectFromAcsTable(DbSvSvcStore.tableName) ++
            sql"""
               where store_id = $storeId
                 and template_id = ${CoinPriceVote.TEMPLATE_ID}
                 and voter in #$voterParties
               limit ${sqlLimit(Limit.DefaultLimit)}
             """).toActionBuilder.as[AcsStoreRowTemplate],
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
            DbSvSvcStore.tableName,
            storeId,
            where = sql"""
                        template_id = ${SvOnboardingRequest.TEMPLATE_ID}
                    and sv_candidate_party = $candidateParty
                      """,
            orderLimit = sql"limit 1",
          ).as[AcsStoreRowTemplateWithOffset].headOption,
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
            DbSvSvcStore.tableName,
            storeId,
            sql"""
                          template_id = ${ValidatorLicense.TEMPLATE_ID}
                      and validator = $validator
                    limit 1;
                        """,
          ).as[AcsStoreRowTemplateWithOffset].headOption,
          "lookupSvOnboardingRequestByCandidatePartyWithOffset",
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
               from #${DbSvSvcStore.tableName}
               where store_id = $storeId
                and template_id = ${MemberTraffic.TEMPLATE_ID}
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
          (selectFromAcsTable(DbSvSvcStore.tableName) ++
            sql"""
                 where store_id = $storeId
                   and template_id = ${CoinPriceVote.TEMPLATE_ID}
                   and vote_request_cid in #$voteRequestCidsSql
                 limit ${sqlLimit(Limit.DefaultLimit)}
               """).toActionBuilder.as[AcsStoreRowTemplate],
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
              DbSvSvcStore.tableName,
              storeId,
              sql"""
                            template_id = ${Vote.TEMPLATE_ID}
                            vote_request_cid = $voteRequestCid
                        and voter = ${key.svParty}
                      limit 1;
                          """,
            ).as[AcsStoreRowTemplateWithOffset].headOption,
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
            DbSvSvcStore.tableName,
            storeId,
            sql"""
                          template_id = ${VoteRequest.TEMPLATE_ID}
                          action_requiring_confirmation = ${payloadJsonFromValue(action.toValue)}
                      and requester = ${key.svParty}
                    limit 1;
                        """,
          ).as[AcsStoreRowTemplateWithOffset].headOption,
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
          (selectFromAcsTable(DbSvSvcStore.tableName) ++
            sql"""
                   where store_id = $storeId
                     and template_id = ${Vote.TEMPLATE_ID}
                     and vote_request_cid = $voteRequestId
                   limit ${sqlLimit(Limit.DefaultLimit)}
                 """).toActionBuilder.as[AcsStoreRowTemplate],
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
          (selectFromAcsTable(DbSvSvcStore.tableName) ++
            sql"""
                     where store_id = $storeId
                       and template_id = ${CoinPriceVote.TEMPLATE_ID}
                       and voter = ${key.svParty}
                     limit 1
                   """).toActionBuilder.as[AcsStoreRowTemplate].headOption,
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
            DbSvSvcStore.tableName,
            storeId,
            where = sql"""
                            template_id = ${SvOnboardingRequest.TEMPLATE_ID}
                        and sv_candidate_name = ${lengthLimited(candidateName)}
                          """,
            orderLimit = sql"limit 1",
          ).as[AcsStoreRowTemplateWithOffset].headOption,
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
            DbSvSvcStore.tableName,
            storeId,
            where = sql"""
                              template_id = ${SvOnboardingConfirmed.TEMPLATE_ID}
                          and sv_candidate_name = ${lengthLimited(svName)}
                            """,
            orderLimit = sql"limit 1",
          ).as[AcsStoreRowTemplateWithOffset].headOption,
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
          (selectFromAcsTable(DbSvSvcStore.tableName) ++
            sql"""
                     where store_id = $storeId
                       and template_id = ${ElectionRequest.TEMPLATE_ID}
                       and requester IN #$requesters
                       and election_request_epoch = $electionRequestEpoch
                     limit ${sqlLimit(Limit.DefaultLimit)}
                   """).toActionBuilder.as[AcsStoreRowTemplate],
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
            DbSvSvcStore.tableName,
            storeId,
            where = sql"""
                              template_id = ${ElectionRequest.TEMPLATE_ID}
                          and requester = $requester
                          and election_request_epoch = $epoch
                            """,
            orderLimit = sql"limit 1",
          ).as[AcsStoreRowTemplateWithOffset].headOption,
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
          (selectFromAcsTable(DbSvSvcStore.tableName) ++
            sql"""
                       where store_id = $storeId
                         and template_id = ${ElectionRequest.TEMPLATE_ID}
                         and election_request_epoch < $epoch
                       limit ${sqlLimit(Limit.DefaultLimit)}
                     """).toActionBuilder.as[AcsStoreRowTemplate],
          "listExpiredElectionRequests",
        )
      limited = applyLimit(Limit.DefaultLimit, result)
    } yield limited.map(contractFromRow(ElectionRequest.COMPANION)(_))
  }

  override def getImportShipmentFor(receiver: PartyId)(implicit
      tc: TraceContext
  ): Future[AcsStoreDump.ImportShipment] = waitUntilAcsIngested {
    for {
      domainId <- defaultAcsDomainIdF
      openRound <- getLatestActiveOpenMiningRound()
      result <- storage
        .query(
          (selectFromAcsTable(DbSvSvcStore.tableName) ++
            sql"""
                         where store_id = $storeId
                           and template_id = ${ImportCrate.TEMPLATE_ID}
                           and import_crate_receiver = $receiver
                         limit ${sqlLimit(Limit.DefaultLimit)}
                       """).toActionBuilder.as[AcsStoreRowTemplate],
          "listExpiredElectionRequests",
        )
      limited = applyLimit(Limit.DefaultLimit, result)
      withState <- Future.traverse(limited)(
        multiDomainAcsStore.contractWithStateFromRow(ImportCrate.COMPANION)(_)
      )
    } yield AcsStoreDump
      .ImportShipment( // TODO (#5314): revisit this comment (also in the in-mem version)
        // It would be better if we received the ContractWithState directly from the underlying store.
        // This is OK though, as eventually the defaulAcsDomainIdF and the domain of the openRound should align.
        ContractWithState(openRound, MultiDomainAcsStore.ContractState.Assigned(domainId)),
        domainId,
        withState,
      )
  }

  override def lookupCnsEntryByNameWithOffset(
      name: String
  )(implicit tc: TraceContext): Future[
    MultiDomainAcsStore.QueryResult[Option[AssignedContract[CnsEntry.ContractId, CnsEntry]]]
  ] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DbSvSvcStore.tableName,
            storeId,
            where = sql"""
                        template_id = ${CnsEntry.TEMPLATE_ID}
                    and cns_entry_name = ${lengthLimited(name)}
                      """,
            orderLimit = sql"limit 1",
          ).as[AcsStoreRowTemplateWithOffset].headOption,
          "lookupCnsEntryByNameWithOffset",
        )
        .getOrRaise(offsetExpectedError())
      withState <- resultWithOffset.row.traverse(
        multiDomainAcsStore.contractWithStateFromRow(CnsEntry.COMPANION)(_)
      )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      withState.flatMap(_.toAssignedContract),
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
          selectFromAcsTableWithOffset(
            DbSvSvcStore.tableName,
            storeId,
            where = sql"""
                            template_id = ${SubscriptionInitialPayment.TEMPLATE_ID}
                        and contract_id = $paymentCid
                          """,
            orderLimit = sql"limit 1",
          ).as[AcsStoreRowTemplateWithOffset].headOption,
          "lookupSubscriptionInitialPaymentWithOffset",
        )
        .getOrRaise(offsetExpectedError())
      withState <- resultWithOffset.row.traverse(
        multiDomainAcsStore.contractWithStateFromRow(SubscriptionInitialPayment.COMPANION)(_)
      )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      withState.flatMap(_.toAssignedContract),
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
          selectFromAcsTableWithOffset(
            DbSvSvcStore.tableName,
            storeId,
            where = sql"""
                          template_id = ${FeaturedAppRight.TEMPLATE_ID}
                      and featured_app_right_provider = $providerPartyId
                        """,
            orderLimit = sql"limit 1",
          ).as[AcsStoreRowTemplateWithOffset].headOption,
          "lookupFeaturedAppRightWithOffset",
        )
        .getOrRaise(offsetExpectedError())
      withState <- resultWithOffset.row.traverse(
        multiDomainAcsStore.contractWithStateFromRow(FeaturedAppRight.COMPANION)(_)
      )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      withState.flatMap(_.toAssignedContract),
    )
  }
}

object DbSvSvcStore {
  val tableName = "svc_acs_store"
}

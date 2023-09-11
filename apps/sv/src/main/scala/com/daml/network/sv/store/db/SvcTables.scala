package com.daml.network.sv.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc.validatorlicense as vl
import com.daml.network.codegen.java.cn.svonboarding as so
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.codegen.java.cc.v1test as ccV1Test
import com.daml.network.codegen.java.cn.wallet.subscriptions as sub
import com.daml.network.store.db.AcsTables
import com.daml.network.sv.store.SvcTxLogParser
import com.daml.network.util.{CNNodeUtil, Contract}
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{DomainId, Member, PartyId}
import spray.json.JsValue

object SvcTables extends AcsTables with NamedLogging {

  final val CnsActionTypeCollectInitialEntryPayment = "CNSRARC_CollectInitialEntryPayment"
  final val CnsActionTypeRejectEntryInitialPayment = "CNSRARC_RejectEntryInitialPayment"

  override protected def loggerFactory: NamedLoggerFactory = NamedLoggerFactory.root

  case class SvcAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp] = None,
      coinRoundOfExpiry: Option[Long] = None,
      rewardRound: Option[Long] = None,
      rewardParty: Option[PartyId] = None,
      miningRound: Option[Long] = None,
      actionRequiringConfirmation: Option[JsValue] = None,
      confirmer: Option[PartyId] = None,
      svOnboardingToken: Option[String] = None,
      svCandidateParty: Option[PartyId] = None,
      svCandidateName: Option[String] = None,
      validator: Option[PartyId] = None,
      totalTrafficPurchased: Option[Long] = None,
      voter: Option[PartyId] = None,
      voteRequestCid: Option[cn.svcrules.VoteRequest.ContractId] = None,
      requester: Option[PartyId] = None,
      electionRequestEpoch: Option[Long] = None,
      importCrateReceiver: Option[PartyId] = None,
      memberTrafficMember: Option[Member] = None,
      cnsEntryName: Option[String] = None,
      actionCnsEntryContextCid: Option[cn.cns.CnsEntryContext.ContractId] = None,
      actionCnsEntryContextPaymentId: Option[sub.SubscriptionInitialPayment.ContractId] = None,
      actionCnsEntryContextArcType: Option[String] = None,
      subscriptionContextContractId: Option[sub.SubscriptionContext.ContractId] = None,
      subscriptionNextPaymentDueAt: Option[Timestamp] = None,
      featuredAppRightProvider: Option[PartyId] = None,
  )

  object SvcAcsStoreRowData {
    def fromCreatedEvent(
        createdEvent: CreatedEvent
    )(implicit elc: ErrorLoggingContext): Either[String, SvcAcsStoreRowData] = {
      createdEvent.getTemplateId match {
        case cn.svc.coinprice.CoinPriceVote.TEMPLATE_ID =>
          tryToDecode(cn.svc.coinprice.CoinPriceVote.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              voter = Some(PartyId.tryFromProtoPrimitive(contract.payload.sv)),
            )
          }
        case cn.svcrules.Confirmation.TEMPLATE_ID =>
          tryToDecode(cn.svcrules.Confirmation.COMPANION, createdEvent) { contract =>
            val (
              actionCnsEntryContextCid,
              actionCnsEntryContextPaymentId,
              actionCnsEntryContextArcType,
            ) =
              contract.payload.action match {
                case arcCnsEntryContext: cn.svcrules.actionrequiringconfirmation.ARC_CnsEntryContext =>
                  arcCnsEntryContext.cnsEntryContextAction match {
                    case action: cn.svcrules.cnsentrycontext_actionrequiringconfirmation.CNSRARC_CollectInitialEntryPayment =>
                      (
                        Some(arcCnsEntryContext.cnsEntryContextCid),
                        Some(action.cnsEntryContext_CollectInitialEntryPaymentValue.paymentCid),
                        Some("CNSRARC_CollectInitialEntryPayment"),
                      )
                    case action: cn.svcrules.cnsentrycontext_actionrequiringconfirmation.CNSRARC_RejectEntryInitialPayment =>
                      (
                        Some(arcCnsEntryContext.cnsEntryContextCid),
                        Some(action.cnsEntryContext_RejectEntryInitialPaymentValue.paymentCid),
                        Some("CNSRARC_RejectEntryInitialPayment"),
                      )
                    case _ =>
                      (None, None, None)
                  }
                case _ => (None, None, None)
              }
            SvcAcsStoreRowData(
              contract,
              contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
              actionRequiringConfirmation =
                Some(payloadJsonFromValue(contract.payload.action.toValue)),
              confirmer = Some(PartyId.tryFromProtoPrimitive(contract.payload.confirmer)),
              actionCnsEntryContextCid = actionCnsEntryContextCid,
              actionCnsEntryContextPaymentId = actionCnsEntryContextPaymentId,
              actionCnsEntryContextArcType = actionCnsEntryContextArcType,
            )
          }
        case cn.svcrules.ElectionRequest.TEMPLATE_ID =>
          tryToDecode(cn.svcrules.ElectionRequest.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              requester = Some(PartyId.tryFromProtoPrimitive(contract.payload.requester)),
              electionRequestEpoch = Some(contract.payload.epoch),
            )
          }
        case cn.svcrules.VoteRequest.TEMPLATE_ID =>
          tryToDecode(cn.svcrules.VoteRequest.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
              actionRequiringConfirmation =
                Some(payloadJsonFromValue(contract.payload.action.toValue)),
              requester = Some(PartyId.tryFromProtoPrimitive(contract.payload.requester)),
            )
          }
        case cn.svcrules.Vote.TEMPLATE_ID =>
          tryToDecode(cn.svcrules.Vote.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              voter = Some(PartyId.tryFromProtoPrimitive(contract.payload.voter)),
              voteRequestCid = Some(contract.payload.requestCid),
            )
          }
        case cn.svcrules.SvcRules.TEMPLATE_ID =>
          tryToDecode(cn.svcrules.SvcRules.COMPANION, createdEvent)(SvcAcsStoreRowData(_))
        case cn.svcrules.SvReward.TEMPLATE_ID =>
          tryToDecode(cn.svcrules.SvReward.COMPANION, createdEvent)(SvcAcsStoreRowData(_))
        case so.SvOnboardingRequest.TEMPLATE_ID =>
          tryToDecode(so.SvOnboardingRequest.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
              svOnboardingToken = Some(contract.payload.token),
              svCandidateParty =
                Some(PartyId.tryFromProtoPrimitive(contract.payload.candidateParty)),
              svCandidateName = Some(contract.payload.candidateName),
            )
          }
        case so.SvOnboardingConfirmed.TEMPLATE_ID =>
          tryToDecode(so.SvOnboardingConfirmed.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
              svCandidateParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.svParty)),
              svCandidateName = Some(contract.payload.svName),
            )
          }
        case cc.coin.CoinRules.TEMPLATE_ID =>
          tryToDecode(cc.coin.CoinRules.COMPANION, createdEvent)(SvcAcsStoreRowData(_))
        case ccV1Test.coin.CoinRulesV1Test.TEMPLATE_ID =>
          tryToDecode(ccV1Test.coin.CoinRulesV1Test.COMPANION, createdEvent)(SvcAcsStoreRowData(_))
        case cc.coin.Coin.TEMPLATE_ID =>
          tryToDecode(cc.coin.Coin.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              coinRoundOfExpiry = Some(CNNodeUtil.coinExpiresAt(contract.payload).number),
            )
          }
        case cc.coin.FeaturedAppRight.TEMPLATE_ID =>
          tryToDecode(cc.coin.FeaturedAppRight.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              featuredAppRightProvider =
                Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
            )
          }
        case cc.coin.LockedCoin.TEMPLATE_ID =>
          tryToDecode(cc.coin.LockedCoin.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              coinRoundOfExpiry = Some(CNNodeUtil.coinExpiresAt(contract.payload.coin).number),
            )
          }
        case cc.coinimport.ImportCrate.TEMPLATE_ID =>
          tryToDecode(cc.coinimport.ImportCrate.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              importCrateReceiver = Some(PartyId.tryFromProtoPrimitive(contract.payload.receiver)),
            )
          }
        case cc.coin.SvcReward.TEMPLATE_ID =>
          tryToDecode(cc.coin.SvcReward.COMPANION, createdEvent)(SvcAcsStoreRowData(_))
        case cc.coin.AppRewardCoupon.TEMPLATE_ID =>
          tryToDecode(cc.coin.AppRewardCoupon.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              rewardRound = Some(contract.payload.round.number),
              rewardParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
            )
          }
        case cc.coin.ValidatorRewardCoupon.TEMPLATE_ID =>
          tryToDecode(cc.coin.ValidatorRewardCoupon.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              rewardRound = Some(contract.payload.round.number),
              rewardParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
            )
          }
        case cc.round.OpenMiningRound.TEMPLATE_ID =>
          tryToDecode(cc.round.OpenMiningRound.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              miningRound = Some(contract.payload.round.number),
            )
          }
        case cc.round.IssuingMiningRound.TEMPLATE_ID =>
          tryToDecode(cc.round.IssuingMiningRound.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              contractExpiresAt =
                Some(Timestamp.assertFromInstant(contract.payload.targetClosesAt)),
              miningRound = Some(contract.payload.round.number),
            )
          }
        case cc.round.SummarizingMiningRound.TEMPLATE_ID =>
          tryToDecode(cc.round.SummarizingMiningRound.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              miningRound = Some(contract.payload.round.number),
            )
          }
        case cc.round.ClosedMiningRound.TEMPLATE_ID =>
          tryToDecode(cc.round.ClosedMiningRound.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              miningRound = Some(contract.payload.round.number),
            )
          }
        case cc.coin.UnclaimedReward.TEMPLATE_ID =>
          tryToDecode(cc.coin.UnclaimedReward.COMPANION, createdEvent)(SvcAcsStoreRowData(_))
        case vl.ValidatorLicense.TEMPLATE_ID =>
          tryToDecode(vl.ValidatorLicense.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              validator = Some(PartyId.tryFromProtoPrimitive(contract.payload.validator)),
            )
          }
        case cc.globaldomain.MemberTraffic.TEMPLATE_ID =>
          tryToDecode(cc.globaldomain.MemberTraffic.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              memberTrafficMember = Some(Member.tryFromProtoPrimitive(contract.payload.memberId)),
            )
          }
        case cn.cns.CnsRules.TEMPLATE_ID =>
          tryToDecode(cn.cns.CnsRules.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(contract)
          }
        case cn.cns.CnsEntry.TEMPLATE_ID =>
          tryToDecode(cn.cns.CnsEntry.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              cnsEntryName = Some(contract.payload.name),
            )
          }
        case cn.cns.CnsEntryContext.TEMPLATE_ID =>
          tryToDecode(cn.cns.CnsEntryContext.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              cnsEntryName = Some(contract.payload.name),
            )
          }
        case sub.SubscriptionInitialPayment.TEMPLATE_ID =>
          tryToDecode(sub.SubscriptionInitialPayment.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(contract)
          }
        case sub.SubscriptionPayment.TEMPLATE_ID =>
          tryToDecode(sub.SubscriptionPayment.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(contract)
          }
        case sub.SubscriptionIdleState.TEMPLATE_ID =>
          tryToDecode(sub.SubscriptionIdleState.COMPANION, createdEvent) { contract =>
            SvcAcsStoreRowData(
              contract,
              subscriptionContextContractId = Some(contract.payload.subscriptionData.context),
              subscriptionNextPaymentDueAt =
                Some(Timestamp.assertFromInstant(contract.payload.nextPaymentDueAt)),
            )
          }
        case t =>
          Left(s"Template $t cannot be decoded as an entry for the SVC store.")
      }
    }
  }

  case class SvcTxLogRowData(
      eventId: String,
      offset: Option[String],
      domainId: DomainId,
      indexRecordType: String3,
      actionName: Option[String],
      executed: Option[Boolean],
      requester: Option[String],
      effectiveAt: Option[String],
  )

  object SvcTxLogRowData {

    def fromTxLogIndexRecord(record: SvcTxLogParser.TxLogIndexRecord): SvcTxLogRowData = {
      record match {
        case err @ SvcTxLogParser.TxLogIndexRecord.ErrorIndexRecord(offset, eventId, domainId) =>
          SvcTxLogRowData(
            eventId = eventId,
            offset = Some(offset),
            domainId = domainId,
            indexRecordType = err.companion.dbType,
            actionName = None,
            executed = None,
            requester = None,
            effectiveAt = None,
          )
        case dv @ SvcTxLogParser.TxLogIndexRecord.DefiniteVoteIndexRecord(
              offset,
              eventId,
              domainId,
              actionName,
              executed,
              requester,
              effectiveAt,
            ) =>
          SvcTxLogRowData(
            eventId = eventId,
            offset = Some(offset),
            domainId = domainId,
            indexRecordType = dv.companion.dbType,
            actionName = Some(actionName),
            executed = Some(executed),
            requester = Some(requester),
            effectiveAt = Some(effectiveAt),
          )
      }
    }
  }

}

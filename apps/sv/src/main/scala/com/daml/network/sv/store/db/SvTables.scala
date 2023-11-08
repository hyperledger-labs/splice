package com.daml.network.sv.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cn.{svonboarding as so, validatoronboarding as vo}
import com.daml.network.store.db.{AcsRowData, AcsTables, IndexColumnValue}
import com.daml.network.util.{Contract, QualifiedName}
import com.google.protobuf.ByteString

object SvTables extends AcsTables {

  case class SvAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp],
      onboardingSecret: Option[String],
      svCandidateName: Option[String],
  ) extends AcsRowData {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      "onboarding_secret" -> onboardingSecret.map(lengthLimited),
      "sv_candidate_name" -> svCandidateName.map(lengthLimited),
    )
  }

  object SvAcsStoreRowData {
    def fromCreatedEvent(
        createdEvent: CreatedEvent,
        createdEventBlob: ByteString,
    ): Either[String, SvAcsStoreRowData] = {
      // TODO(#8125) Switch to map lookups instead
      QualifiedName(createdEvent.getTemplateId) match {
        case t if t == QualifiedName(vo.ValidatorOnboarding.TEMPLATE_ID) =>
          tryToDecode(vo.ValidatorOnboarding.COMPANION, createdEvent, createdEventBlob) {
            contract =>
              SvAcsStoreRowData(
                contract,
                contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
                onboardingSecret = Some(contract.payload.candidateSecret),
                svCandidateName = None,
              )
          }
        case t if t == QualifiedName(vo.UsedSecret.TEMPLATE_ID) =>
          tryToDecode(vo.UsedSecret.COMPANION, createdEvent, createdEventBlob) { contract =>
            SvAcsStoreRowData(
              contract,
              contractExpiresAt = None,
              onboardingSecret = Some(contract.payload.secret),
              svCandidateName = None,
            )
          }
        case t if t == QualifiedName(so.ApprovedSvIdentity.TEMPLATE_ID) =>
          tryToDecode(so.ApprovedSvIdentity.COMPANION, createdEvent, createdEventBlob) { contract =>
            SvAcsStoreRowData(
              contract,
              contractExpiresAt = None,
              onboardingSecret = None,
              svCandidateName = Some(contract.payload.candidateName),
            )
          }
        case t if t == QualifiedName(so.SvOnboardingConfirmed.TEMPLATE_ID) =>
          tryToDecode(so.SvOnboardingConfirmed.COMPANION, createdEvent, createdEventBlob) {
            contract =>
              SvAcsStoreRowData(
                contract,
                contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
                onboardingSecret = None,
                svCandidateName = Some(contract.payload.svName),
              )
          }
        case t =>
          Left(s"Template $t cannot be decoded as an entry for the SV store.")
      }
    }
  }

}

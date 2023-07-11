package com.daml.network.sv.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cn.{svonboarding as so, validatoronboarding as vo}
import com.daml.network.store.db.AcsTables
import com.daml.network.util.Contract

object SvTables extends AcsTables {

  case class SvAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp],
      onboardingSecret: Option[String],
      svCandidateName: Option[String],
  )

  object SvAcsStoreRowData {
    def fromCreatedEvent(createdEvent: CreatedEvent): Either[String, SvAcsStoreRowData] = {
      createdEvent.getTemplateId match {
        case vo.ValidatorOnboarding.TEMPLATE_ID =>
          tryToDecode(vo.ValidatorOnboarding.COMPANION, createdEvent) { contract =>
            SvAcsStoreRowData(
              contract,
              contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
              onboardingSecret = Some(contract.payload.candidateSecret),
              svCandidateName = None,
            )
          }
        case vo.UsedSecret.TEMPLATE_ID =>
          tryToDecode(vo.UsedSecret.COMPANION, createdEvent) { contract =>
            SvAcsStoreRowData(
              contract,
              contractExpiresAt = None,
              onboardingSecret = Some(contract.payload.secret),
              svCandidateName = None,
            )
          }
        case so.ApprovedSvIdentity.TEMPLATE_ID =>
          tryToDecode(so.ApprovedSvIdentity.COMPANION, createdEvent) { contract =>
            SvAcsStoreRowData(
              contract,
              contractExpiresAt = None,
              onboardingSecret = None,
              svCandidateName = Some(contract.payload.candidateName),
            )
          }
        case so.SvOnboardingConfirmed.TEMPLATE_ID =>
          tryToDecode(so.SvOnboardingConfirmed.COMPANION, createdEvent) { contract =>
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

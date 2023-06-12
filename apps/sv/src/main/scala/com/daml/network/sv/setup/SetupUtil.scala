package com.daml.network.sv.setup

import com.daml.ledger.javaapi.data.User
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.sv.config.SvAppBackendConfig
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}

private[setup] object SetupUtil {

  def setupSvParty(connection: CNLedgerConnection, config: SvAppBackendConfig)(implicit
      ec: ExecutionContext
  ): Future[PartyId] =
    for {
      svParty <- connection.ensureUserPrimaryPartyIsAllocated(
        config.ledgerApiUser,
        config.svPartyHint.getOrElse(config.onboarding.name),
      )
      // We share the SV party between the validator user and the SV user. Therefore, we allocate the validator user here with the SV
      // party as the primary one. We allocate the user here and don't just tweak the primary party of an externally allocated user.
      // That ensures the validator app won't try to allocate its own primary party because it waits first for the user to be created
      // and then checks if it has a primary party already.
      _ <- connection.createUserWithPrimaryParty(
        config.validatorLedgerApiUser,
        svParty,
        Seq(User.Right.ParticipantAdmin.INSTANCE),
      )
    } yield svParty
}

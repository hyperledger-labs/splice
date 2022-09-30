package com.daml.network.validator.util

import com.daml.network.codegen.CC.{Coin => coinCodegen}
import com.daml.network.codegen.CN.{Wallet => walletCodegen}
import com.daml.network.environment.CoinLedgerConnection
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

private[validator] object ValidatorUtil {
  def createValidatorRight(
      user: PartyId,
      validator: PartyId,
      svc: PartyId,
      connection: CoinLedgerConnection,
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    connection.ignoreDuplicateKeyErrors(
      connection.submitCommand(
        actAs = Seq(user, validator),
        readAs = Seq.empty,
        Seq(
          coinCodegen
            .ValidatorRight(
              svc = svc.toPrim,
              user = user.toPrim,
              validator = validator.toPrim,
            )
            .create
            .command
        ),
      ),
      s"ValidatorRight($svc, $user, $validator)",
    )
  }

  def installWalletForUser(
      validatorServiceParty: PartyId,
      walletServiceUser: String,
      walletServiceParty: PartyId,
      endUserParty: PartyId,
      svcParty: PartyId,
      connection: CoinLedgerConnection,
      logger: TracedLogger,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Unit] = {
    logger.debug(
      s"Installing wallet for endUserParty=$endUserParty, walletServiceParty=$walletServiceParty, validatorServiceParty=$validatorServiceParty, svcParty=$svcParty"
    )
    for {
      // TODO (i713): remove this workaround for missing `read-as-any-party` rights
      _ <- connection.grantUserRights(walletServiceUser, Seq.empty, Seq(endUserParty))
      _ <- connection.ignoreDuplicateKeyErrors(
        connection
          .submitCommand(
            Seq(validatorServiceParty, walletServiceParty, endUserParty),
            Seq(),
            Seq(
              walletCodegen
                .WalletAppInstall(
                  serviceUser = walletServiceParty.toPrim,
                  endUser = endUserParty.toPrim,
                  svcUser = svcParty.toPrim,
                  validatorUser = validatorServiceParty.toPrim,
                )
                .create
                .command
            ),
          ),
        s"WalletAppInstall($walletServiceParty, $endUserParty, $svcParty, $validatorServiceParty)",
      )
    } yield ()
  }
}

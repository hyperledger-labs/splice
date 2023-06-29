package com.daml.network.validator.store

import cats.syntax.traverse.*
import com.daml.network.config.BackupDumpConfig
import com.daml.network.environment.{BuildInfo, CNLedgerConnection, ParticipantAdminConnection}
import com.daml.network.http.v0.definitions as http
import com.daml.network.util.BackupDump
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.circe.syntax.*

import java.nio.file.Path
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.jdk.OptionConverters.*

/** A store for accessing the participant identities. */
class ParticipantIdentitiesStore(
    participantAdminConnection: ParticipantAdminConnection,
    connection: CNLedgerConnection,
    backupDumpConfig: Option[BackupDumpConfig],
    clock: Clock,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  def getParticipantIdentitiesDump()(implicit
      tc: TraceContext
  ): Future[http.DumpParticipantIdentitiesResponse] =
    for {
      id <- participantAdminConnection.getParticipantId()
      keysMetadata <- participantAdminConnection.listMyKeys()
      keys <- keysMetadata.traverse(keyM =>
        participantAdminConnection
          .exportKeyPair(keyM.publicKeyWithName.publicKey.id)
          .map(keyBytes =>
            http.ParticipantKeyPair(
              Base64.getEncoder().encodeToString(keyBytes.toByteArray),
              keyM.publicKeyWithName.name.map(_.toString),
            )
          )
      )
      bootstrapTxs <- participantAdminConnection
        .getIdentityBootstrapTransactions(id.uid)
        .map(txes => txes.map(tx => Base64.getEncoder().encodeToString(tx.toByteArray)))
      users <- connection
        .listUsers()
        .map(users =>
          users.map(user =>
            http.ParticipantUser(
              user.getId(),
              user.getPrimaryParty().toScala,
            )
          )
        )
    } yield http.DumpParticipantIdentitiesResponse(
      id.toProtoPrimitive,
      keys.toVector,
      bootstrapTxs.toVector,
      users.toVector,
    )

  /** Write a dump of the participant identities to the configured backup location.
    *
    * Fails if no backup location is configured.
    *
    * @return: the name of the file to which the backup was written
    */
  def backupParticipantIdentities()(implicit traceContext: TraceContext): Future[Path] = for {
    dumpConfig <- Future {
      backupDumpConfig.getOrElse(
        throw new IllegalStateException(
          "cannot backup identities as no backup location is configured"
        )
      )
    }
    dump <- getParticipantIdentitiesDump()
    path <- Future {
      blocking {
        import java.nio.file.Paths

        // determine target file
        val now = clock.now.toInstant
        // Deliberately not using better files here
        // because it turns this into an absolute path which
        // then makes all the logging stuff below very confusing.
        val filename = Paths.get(
          BuildInfo.compiledVersion,
          dump.id,
          s"participant_identities_${dump.id}_${now}.json",
        )
        // TODO(#6073): compress output file
        val fileDesc =
          s"participant identities dump containing ${dump.keys.size} keys and ${dump.users.size} users to ${dumpConfig.locationDescription} at path: $filename"
        logger.debug(s"Attempting to write $fileDesc")
        val path = BackupDump.write(
          dumpConfig,
          filename,
          dump.asJson.noSpaces,
          loggerFactory,
        )
        logger.info(s"Wrote $fileDesc")
        path
      }
    }
  } yield path
}

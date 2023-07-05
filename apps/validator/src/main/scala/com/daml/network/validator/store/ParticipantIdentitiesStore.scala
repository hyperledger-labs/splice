package com.daml.network.validator.store

import cats.syntax.traverse.*
import com.daml.network.config.BackupDumpConfig
import com.daml.network.environment.{BuildInfo, CNLedgerConnection, ParticipantAdminConnection}
import com.daml.network.util.{BackupDump, ParticipantIdentitiesDump}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import java.nio.file.{Path, Paths}
import java.time.Instant
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
  ): Future[ParticipantIdentitiesDump] =
    for {
      id <- participantAdminConnection.getParticipantId()
      keysMetadata <- participantAdminConnection.listMyKeys()
      keys <- keysMetadata.traverse(keyM =>
        participantAdminConnection
          .exportKeyPair(keyM.publicKeyWithName.publicKey.id)
          .map(keyBytes =>
            ParticipantIdentitiesDump.ParticipantKey(
              keyBytes.toByteArray,
              keyM.publicKeyWithName.name.map(_.toString),
            )
          )
      )
      bootstrapTxs <- participantAdminConnection.getIdentityBootstrapTransactions(id.uid)
      users <- connection
        .listUsers()
        .map(users =>
          users.map(user =>
            ParticipantIdentitiesDump.ParticipantUser(
              user.getId(),
              user.getPrimaryParty().toScala.map(PartyId.tryFromProtoPrimitive),
            )
          )
        )
    } yield ParticipantIdentitiesDump(
      id,
      keys,
      bootstrapTxs,
      users,
      Some(BuildInfo.compiledVersion),
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
        // determine target file
        val now = clock.now.toInstant
        // Deliberately not using better files here
        // because it turns this into an absolute path which
        // then makes all the logging stuff below very confusing.
        val filename = ParticipantIdentitiesStore.dumpFilename(now)
        // TODO(#6073): compress output file
        val fileDesc =
          s"participant identities dump containing ${dump.keys.size} keys and ${dump.users.size} users to ${dumpConfig.locationDescription} at path: $filename"
        logger.debug(s"Attempting to write $fileDesc")
        val path = BackupDump.write(
          dumpConfig,
          filename,
          dump.toJson.noSpaces,
          loggerFactory,
        )
        logger.info(s"Wrote $fileDesc")
        path
      }
    }
  } yield path
}

object ParticipantIdentitiesStore {
  def dumpFilename(now: Instant) =
    Paths.get(
      s"participant_identities_${now}.json"
    )
}

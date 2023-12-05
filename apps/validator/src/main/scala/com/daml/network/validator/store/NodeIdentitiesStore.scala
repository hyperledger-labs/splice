package com.daml.network.validator.store

import cats.syntax.traverse.*
import com.daml.network.config.BackupDumpConfig
import com.daml.network.environment.{BuildInfo, TopologyAdminConnection}
import com.daml.network.util.{BackupDump, NodeIdentitiesDump}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext

import java.nio.file.{Path, Paths}
import java.time.Instant
import scala.concurrent.{blocking, ExecutionContext, Future}

/** A store for accessing the node identities. */
class NodeIdentitiesStore(
    adminConnection: TopologyAdminConnection,
    backupDumpConfig: Option[BackupDumpConfig],
    clock: Clock,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  def getNodeIdentitiesDump()(implicit
      tc: TraceContext
  ): Future[NodeIdentitiesDump] =
    for {
      id <- adminConnection.identity()
      keysMetadata <- adminConnection.listMyKeys()
      keys <- keysMetadata.traverse(keyM =>
        adminConnection
          .exportKeyPair(keyM.publicKeyWithName.publicKey.id)
          .map(keyBytes =>
            NodeIdentitiesDump.NodeKey(
              keyBytes.toByteArray,
              keyM.publicKeyWithName.name.map(_.unwrap),
            )
          )
      )
      bootstrapTxs <- adminConnection.getIdentityBootstrapTransactions(None, id.uid)
    } yield NodeIdentitiesDump(
      id,
      keys,
      bootstrapTxs,
      Some(BuildInfo.compiledVersion),
    )

  /** Write a dump of the node identities to the configured backup location.
    *
    * Fails if no backup location is configured.
    *
    * @return: the name of the file to which the backup was written
    */
  def backupNodeIdentities()(implicit traceContext: TraceContext): Future[Path] = for {
    dumpConfig <- Future {
      backupDumpConfig.getOrElse(
        throw new IllegalStateException(
          "cannot backup identities as no backup location is configured"
        )
      )
    }
    dump <- getNodeIdentitiesDump()
    path <- Future {
      blocking {
        // determine target file
        val now = clock.now.toInstant
        // Deliberately not using better files here
        // because it turns this into an absolute path which
        // then makes all the logging stuff below very confusing.
        val filename = NodeIdentitiesStore.dumpFilename(now)
        val fileDesc =
          s"node identities dump containing ${dump.keys.size} keys to ${dumpConfig.locationDescription} at path: $filename"
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

object NodeIdentitiesStore {
  def dumpFilename(now: Instant) =
    Paths.get(
      s"participant_identities_${now}.json"
    )
}

import com.digitalasset.canton.topology.transaction.TopologyMapping
import com.digitalasset.canton.topology.store.TimeQuery
import java.util.Base64

val id = participant.id.toProtoPrimitive

// This line needs to be adapted if your participant stores keys in an external KMS
val keys = "[" + participant.keys.secret
  .list()
  .filter(k => k.name.get.unwrap != "cometbft-governance-keys")
  .map(key =>
    s"{\"keyPair\": \"${Base64.getEncoder.encodeToString(
        participant.keys.secret.download(key.publicKey.fingerprint).toByteArray
      )}\", \"name\": \"${key.name.get.unwrap}\"}"
  )
  .mkString(",") + "]"

val authorizedStoreSnapshot = Base64.getEncoder.encodeToString(
  participant.topology.transactions
    .export_topology_snapshot(
      timeQuery = TimeQuery.Range(from = None, until = None),
      filterMappings = Seq(
        TopologyMapping.Code.NamespaceDelegation,
        TopologyMapping.Code.OwnerToKeyMapping,
        TopologyMapping.Code.VettedPackages,
      ),
      filterNamespace = participant.id.namespace.toProtoPrimitive,
    )
    .toByteArray
)

val combinedJson =
  s"""{ "id" : "$id", "keys" : $keys, "authorizedStoreSnapshot" : "$authorizedStoreSnapshot" }"""

// Write to file
import java.nio.file.{Files, Paths}
val dumpPath = Paths.get("identities-dump.json")
Files.writeString(dumpPath, combinedJson)

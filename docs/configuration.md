# owl4agents Configuration

System properties (set via `-D` on the JVM command line or `OWL4AGENTS_OPTS`
environment variable) configure the v0.9.1 write-tools-expansion subsystem.

## Write Transaction TTL

| Property | Default | Description |
|----------|---------|-------------|
| `owl4agents.write.transaction.ttl.seconds` | `300` | Idle TTL for write transactions. Transactions whose `lastActivityAt` is older than this value are auto-rolled-back by the daemon sweep (runs every 60 s). Set to a larger value for long-running interactive sessions. |

## Version History

| Property | Default | Description |
|----------|---------|-------------|
| `owl4agents.write.version.maxSnapshots` | `100` | Maximum number of version snapshots retained per ontology. When exceeded, the oldest dedup-shareable snapshot is pruned first. The initial import baseline (`parentVersionId=null`) is **never** pruned. |

## Audit Log

| Property | Default | Description |
|----------|---------|-------------|
| `owl4agents.write.audit.enabled` | `true` | When `false`, `AuditLog.append` is a no-op (testing only). The write tools still succeed; no audit entries are persisted. |
| `owl4agents.write.audit.maxBytes` | `104857600` (100 MB) | Size threshold for audit log rotation. When the current `audit.jsonl` exceeds this size, it is rotated to `audit.<timestamp>.jsonl`. |
| `owl4agents.write.audit.maxFiles` | `10` | Maximum number of rotated audit files retained per ontology. Oldest is deleted first when exceeded. |

## Examples

```bash
# Disable audit log for a test run
java -Dowl4agents.write.audit.enabled=false -jar owl4agents.jar mcp --port 8091

# Increase transaction TTL to 30 minutes and keep 200 snapshots
java -Dowl4agents.write.transaction.ttl.seconds=1800 \
     -Dowl4agents.write.version.maxSnapshots=200 \
     -jar owl4agents.jar mcp --port 8091
```

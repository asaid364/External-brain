# Protocol 1 JSON contract

All request and response bodies are UTF-8 JSON with `Content-Type: application/json`. Unknown fields, unknown snapshot item types, unknown entity kinds, and unknown schemas are rejected. Except for the unauthenticated short-lived pairing request, every endpoint requires the already TLS-protected bearer token and pair identity. Tokens and pairing codes are never placed in URLs or response bodies after pairing.

## Endpoints

| Method and path | Request | Success response |
|---|---|---|
| `POST /pair/v1/request` | pairing request described by the pairing UI | `202` while pending; paired credentials only after Windows confirmation |
| `GET /sync/v1/status` | none | `SyncStatus` |
| `POST /sync/v1/fences` | `FenceSet` | peer `FenceSet` or transport-specific success envelope |
| `POST /sync/v1/push/begin` | `ExportManifest` | `IncomingDisposition` |
| `PUT /sync/v1/push/{exportId}/parts/{partNo}` | `ExportPart` | empty success |
| `POST /sync/v1/push/{exportId}/commit` | none | `Ack` |
| `POST /sync/v1/pull/begin` | peer's persisted received sequence and `full` request | `ExportManifest` |
| `GET /sync/v1/pull/{exportId}/parts/{partNo}` | none | `ExportPart` |
| `POST /sync/v1/pull/{exportId}/ack` | `Ack` | empty success |
| `POST /goal-ai/v1/status` | `{goalId,fromDate,throughDate}` | state/run/holder/inputHash/expiry, never token |
| `POST /goal-ai/v1/claim` | `{goalId,fromDate,throughDate,runId,inputHash,takeover}` | current authorized lease |
| `POST /goal-ai/v1/heartbeat` | `{goalId,fromDate,throughDate,runId,token}` | renewed lease |
| `POST /goal-ai/v1/finish` | `{goalId,fromDate,throughDate,runId,token}` | finished lease |

Goal AI routes additionally require `X-LifeRecord-Data-Schema: 4` and complete bilateral content consent. Lease identity uses authenticated database holder, exact window/run/hash and current token. RUNNING lease lasts 180 seconds with 30-second heartbeat; expiry/restart becomes UNKNOWN, never automatic paid takeover. Conflict codes are `GOAL_INPUT_MISMATCH`, `GOAL_GENERATION_RUNNING`, `GOAL_GENERATION_UNKNOWN`, and `GOAL_LEASE_INVALID`. Keys/configuration/lease tokens are not synchronized business entities.

The HTTP layer must reject redirects for authenticated calls and must not enable CORS. Pair and bearer authentication is outside the JSON DTOs below.

## Manifest and parts

```json
{
  "protocol": 1,
  "dataSchema": 4,
  "spaceId": "shared-random-space-id",
  "senderActor": "persistent-random-actor-epoch",
  "senderDatabaseId": "persistent-random-sender-db-id",
  "receiverDatabaseId": "pinned-receiver-db-id",
  "exportId": "random-export-id",
  "baseSeq": 12,
  "throughSeq": 18,
  "full": false,
  "partCount": 2,
  "partHashes": ["64-lowercase-hex", "64-lowercase-hex"],
  "partsSha256": "64-lowercase-hex",
  "contentEpoch": 4,
  "manifestHash": "64-lowercase-hex"
}
```

`full=false` means an incremental fixed snapshot for `(baseSeq, throughSeq]`; `baseSeq` must equal the receiver cursor already persisted by the sender. `full=true` carries all current entities and retained causal metadata and is required for first merge or a new database identity. A full export remains meaningful when `throughSeq` is zero.

`partsSha256` is SHA-256 over the ordered part-hash strings using the canonical field encoding below. `manifestHash` covers, in order: protocol, schema, space, sender actor, sender database, receiver database, export ID, base, through, full, part count, parts digest, content epoch, then every ordered part hash. For each string field, canonical bytes are ASCII decimal UTF-8-byte-length, `:`, then the exact UTF-8 bytes. JSON member order is never part of a digest.

```json
{
  "exportId": "random-export-id",
  "partNo": 0,
  "dataBase64": "base64-of-at-most-262144-raw-bytes",
  "partHash": "sha256-of-decoded-raw-bytes"
}
```

Decoded parts concatenate in `partNo` order to one UTF-8 NDJSON stream. A split may occur between any bytes, including inside a multibyte UTF-8 code point or one logical item. Receivers reassemble NDJSON frames before UTF-8 decoding and JSON parsing. There is no arbitrary record/body size cap; staging and transfer operate one 256 KiB part at a time.

## Snapshot items

Each NDJSON line is one `WireSnapshotItem`. Exactly the property named by `type` is non-null. Allowed types are:

- `purge`: `{recordId,purgeId}`
- `draftTerminal`: `{draftId,terminalId,mode,targetRecordId,consumedHeads,valueHash}`, where mode is `SAVED` or `DISCARDED`
- `entity`: `{kind,key,heads,retired,recordRefs}`
- `knownHead`: `{id,kind,key,payloadHash}`
- `lifeEvent`: `{recordId,eventId}`
- `history`: `{table,syncId,payloadHash,payload}`
- `sourceAlias`: `{originId,recordId,legacyToken,newToken}`

Network entity kinds are `record`, `draft-slot`, `report`, `answer`, `goal`, `goal-confirmation`, and `goal-review` (data schema 4). The only history tables are `revision` and `report_answer_history`. Unknown values return `UNSUPPORTED_SCHEMA`; schema3 peers must upgrade, not silently drop goal content or re-pair.

An entity head is:

```json
{
  "id": "actorEpoch/counter/part",
  "payloadHash": "sha256-of-canonical-payload-json-utf8",
  "payload": {}
}
```

`payload` is typed by entity kind and is the complete payload generated by the shared core codecs. `retired` retains observed removed head IDs. `recordRefs` identifies records referenced by a logical entity when applicable. A known head with the same ID but different kind, key, or payload hash is `CONTENT_MISMATCH`. A purge fence may be referenced by retained report evidence; this is not a missing foreign key.

Snapshot items are staged in dependency-safe order: purge fences, draft terminals, entities, known heads, life events, stable histories, and source aliases. Import is atomic as a batch even though frames are decoded incrementally.

Goal payloads use the unique strict `GoalCodec`. Goal definitions, explicit user milestone declarations (including their criterion snapshot), and completed whole-window reviews synchronize through observed-head registers. AI suggestions never create confirmations. Review evidence retains source IDs, versions and transitive dependencies, not separate raw-record/answer/report snapshots; published historical analysis survives source deletion with an unavailable/stale warning. Device-local send/automatic permission, keys, pending results, run state and coordination leases never enter snapshots. Dependency validity is derived from final current state, not cached as missing during per-frame arrival.

Schema4 status uses nullable `entityCount`, `purgeCount`, and typed `counts` (`records,drafts,reports,goals,reviews`). All are null until bilateral send/receive consent is complete; null never means zero. Existing pairing identities and cursors survive upgrade, but expanded scope requires explicit local re-consent on both peers. Windows authorization and status use the same persisted per-pair consent source.

## ACK and duplicate handling

```json
{
  "exportId": "random-export-id",
  "throughSeq": 18,
  "manifestHash": "exact-manifest-hash"
}
```

ACK is valid only after all parts and hashes were checked and the merge, receiver cursor, and `sync_applied_exports` credential committed in one transaction. Repeating an applied `exportId` with the same manifest returns the stored ACK. Repeating it with another manifest is `CONTENT_MISMATCH`. An ACK with a mismatching export ID, sequence, receiver database, or manifest hash never advances the sender cursor.

## Status, consent, and fences

```json
{
  "protocol": 1,
  "dataSchema": 3,
  "spaceId": "shared-space-or-null",
  "localDatabaseId": "local-db-id",
  "peerDatabaseId": "pinned-peer-db-id-or-null",
  "localCommitSeq": 18,
  "ackedLocalSeq": 12,
  "receivedRemoteSeq": 9,
  "contentEpoch": 4,
  "sendConsent": true,
  "receiveConsent": true,
  "entityCount": 42,
  "purgeCount": 3
}
```

`spaceId` and both database identities are checked for every exchange. A database identity change requires explicit re-pair/full merge; old cursors are not reused. Local send and receive consent both must be true before fences or content transfer. Remote status is informative and cannot grant local consent.

```json
{
  "protocol": 1,
  "dataSchema": 3,
  "spaceId": "shared-space",
  "senderDatabaseId": "sender-db-id",
  "contentEpoch": 4,
  "fences": [{"recordId":"record-id","purgeId":"stable-purge-id"}]
}
```

Peers exchange fences before full content. Fences contain no body. Applying a new fence increments the receiver's local content epoch and permanently removes related record/draft/revision payloads. Sender and receiver epochs are local invalidation counters and are never compared to each other. Before serving every staged part and accepting ACK, the sender compares the export's captured epoch with its current epoch. The receiver captures its own epoch at begin; if it changes before commit, it returns `EXPORT_INVALIDATED`.

## Incoming disposition

```json
{"state":"READY","ack":null}
```

or, for an already durably applied export:

```json
{"state":"APPLIED","ack":{"exportId":"...","throughSeq":18,"manifestHash":"..."}}
```

## Errors

Errors contain only a stable code and a non-sensitive message:

```json
{"code":"CURSOR_GAP","message":"接收游标不连续"}
```

Defined codes are `CONSENT_REQUIRED`, `CURSOR_GAP`, `IDENTITY_MISMATCH`, `SPACE_MISMATCH`, `UNSUPPORTED_PROTOCOL`, `UNSUPPORTED_SCHEMA`, `CONTENT_MISMATCH`, `INCOMPLETE_EXPORT`, `EXPORT_INVALIDATED`, and `NOT_FOUND`. Error bodies never echo payloads, tokens, pairing codes, or certificate material.

## Cleanup and durability

`sync_exports` and `sync_incoming` contain only unacknowledged temporary payloads. ACK, explicit cancellation, or startup recovery removes them; recovery reforms an export from the retained no-body change index rather than retaining historical body logs. `sync_applied_exports`, peer cursors, identities, known heads, retired heads, purge fences, and draft terminals survive cleanup. Storage or validation failure leaves existing business data and the previous cursor unchanged.

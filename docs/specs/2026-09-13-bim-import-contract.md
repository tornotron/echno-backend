# BIM import contract: backend, worker and object store

**Date:** 2026-09-13
**Status:** Binding for `echno-backend` (`modules/bim`) and `echno-bim-worker`. Changes land in
both repos together.
**Design record:** `echno-roadmap/bim/bim-ingestion-viewer-element-identity.md`.

The backend registers an uploaded IFC as a model version and inserts one row in
`bim_import_jobs`. The worker (Python, IfcOpenShell) polls that table, reads the source IFC from
the object store, writes its outputs under the prefix the row names, and closes the row. The
backend then reads the outputs into `bim_elements` and marks the version READY. The worker never
writes any table but `bim_import_jobs` and never touches a `bim_model_versions` or
`bim_elements` row.

## 1. The job row

Table `bim_import_jobs`. Columns the worker reads are marked R, columns it writes W.

| Column | Type | Who | Meaning |
|---|---|---|---|
| `id` | uuid | R | Job id. |
| `organization_id` | bigint | R | Tenant. Carried into log lines only. |
| `model_id` | uuid | R | `bim_models.id`. |
| `version_id` | uuid | R | `bim_model_versions.id`. |
| `status` | varchar(20) | R W | `QUEUED`, `RUNNING`, `DONE`, `FAILED`. |
| `source_key` | varchar(500) | R | Object key of the IFC, `bim/<modelId>/<versionId>/source.ifc`. |
| `output_prefix` | varchar(500) | R | `bim/<modelId>/<versionId>/`, trailing slash included. Every output goes under it. |
| `worker_id` | varchar(100) | W | Hostname or pod name of the worker that claimed the row. |
| `attempt` | int | W | Incremented on every claim. |
| `max_attempts` | int | R | Set by the backend, default 3. |
| `lease_expires_at` | timestamp | W | Pushed forward while the worker is alive. |
| `element_count` | int | W | Lines written to `elements.jsonl`. |
| `storey_count` | int | W | Storey tiles written. |
| `worker_version` | varchar(50) | W | Worker image version. |
| `error` | text | W | Set on FAILED. One line, human readable, no stack trace. |
| `queued_at` | timestamp | R | Queue order. |
| `started_at` | timestamp | W | Set on claim. |
| `finished_at` | timestamp | W | Set on DONE or FAILED. |
| `ingested_at` | timestamp | backend | Set when the backend has read the outputs. The worker never writes it. |
| `created_at`, `updated_at` | timestamp | W (`updated_at`) | Set `updated_at = now()` on every write. |

Timestamps are UTC, column type `TIMESTAMP` without zone.

## 2. Status transitions

```
QUEUED  --claim (worker)-------------------> RUNNING
RUNNING --outputs written (worker)---------> DONE
RUNNING --exception (worker)---------------> FAILED   error set
RUNNING --lease expired, attempts left-----> QUEUED   (backend poller)
RUNNING --lease expired, none left---------> FAILED   (backend poller)
QUEUED  --version deleted / module dark----> FAILED   (backend)
```

DONE and FAILED are terminal. A retry is a new row, queued by the backend; the worker never
reopens a terminal row. The backend mirrors the job on the version: `QUEUED` and `RUNNING`
show as version `QUEUED` and `PROCESSING`, a DONE job takes the version through `INGESTING` to
`READY`, a FAILED job sets the version `FAILED` with the same error.

## 3. Claiming, heartbeating and closing

Claim (one statement, compare-and-set on `status`, works on CockroachDB and Postgres):

```sql
UPDATE bim_import_jobs
   SET status = 'RUNNING', worker_id = :worker, attempt = attempt + 1,
       started_at = now(), lease_expires_at = now() + interval '30 minutes',
       updated_at = now()
 WHERE id = (SELECT id FROM bim_import_jobs
              WHERE status = 'QUEUED' ORDER BY queued_at LIMIT 1)
   AND status = 'QUEUED'
RETURNING id, organization_id, model_id, version_id, source_key, output_prefix, attempt, max_attempts;
```

Zero rows means nothing to do; sleep and poll again (suggested 5 s). Two workers racing the same
row is expected; the `AND status = 'QUEUED'` makes one of them lose cleanly.

Heartbeat, at least every 10 minutes while parsing or tiling:

```sql
UPDATE bim_import_jobs SET lease_expires_at = now() + interval '30 minutes', updated_at = now()
 WHERE id = :id AND status = 'RUNNING' AND worker_id = :worker;
```

Close:

```sql
UPDATE bim_import_jobs
   SET status = 'DONE', finished_at = now(), updated_at = now(),
       element_count = :n, storey_count = :s, worker_version = :v
 WHERE id = :id AND status = 'RUNNING' AND worker_id = :worker;

UPDATE bim_import_jobs
   SET status = 'FAILED', finished_at = now(), updated_at = now(), error = :msg, worker_version = :v
 WHERE id = :id AND status = 'RUNNING' AND worker_id = :worker;
```

A close that updates zero rows means the lease expired and the row moved on; discard the outputs
(the prefix is overwritten by the next attempt anyway) and log it.

Only write DONE after every file in section 4 is fully uploaded. The backend reads them the moment
it sees DONE.

## 4. Object-store layout

One prefix per version, from `output_prefix`. The bucket and endpoint come from the environment
(MinIO on IITM, Spaces on DigitalOcean); nothing in the layout names either.

```
bim/<modelId>/<versionId>/source.ifc          written by the browser through a presigned PUT
bim/<modelId>/<versionId>/elements.jsonl      worker
bim/<modelId>/<versionId>/structure.json      worker
bim/<modelId>/<versionId>/model-meta.json     worker
bim/<modelId>/<versionId>/tiles/coarse.glb    worker: whole model, decimated, first paint
bim/<modelId>/<versionId>/tiles/<storeyGlobalId>.glb   worker: one per IfcBuildingStorey
bim/<modelId>/<versionId>/tiles/unassigned.glb         worker: products with no storey, if any
```

Content types: `application/x-ndjson`, `application/json`, `model/gltf-binary`. Tiles are
Draco-compressed glTF binary; every mesh carries a string attribute `globalId` (custom attribute
`_GLOBALID` on the primitive, plus `extras.globalId` on the node) so the viewer picks by id.

## 5. JSON shapes

### `elements.jsonl`

One object per line, one line per `IfcProduct` that has a GlobalId. Order is free. Keys:

```json
{"globalId": "2O2Fr$t4X7Zf8NOew3FLKI",
 "ifcType": "IfcWallStandardCase",
 "name": "Basic Wall:Interior 200mm:345678",
 "storeyGlobalId": "1xS3BCk291UvhgP2a6eflL",
 "spaceGlobalId": "0BTBFw6f90Nfh9rP1dlXrb",
 "bbox": {"min": [0.0, 0.0, 0.0], "max": [4.2, 0.2, 3.0]},
 "properties": {"Pset_WallCommon": {"IsExternal": false, "FireRating": "REI60"}}}
```

`storeyGlobalId`, `spaceGlobalId` and `bbox` may be null. `properties` is an object of property
set name to an object of property name to a JSON scalar (string, number, boolean, null); quantity
sets are included under their `Qto_` name. Values that IfcOpenShell cannot express as a scalar are
stringified. `bbox` is in model coordinates, metres, after unit conversion.

### `structure.json`

The spatial containment tree used to propose the QA/QC hierarchy.

```json
{"project": {"globalId": "...", "name": "Tower A"},
 "sites": [{"globalId": "...", "name": "Site",
   "buildings": [{"globalId": "...", "name": "Block A",
     "storeys": [{"globalId": "...", "name": "Level 03", "elevation": 9.0,
       "spaces": [{"globalId": "...", "name": "L03-Z1", "longName": "Lobby"}]}]}]}]}
```

Storeys are in ascending elevation. `elevation` is metres relative to the building placement and
may be null. A storey with no spaces has `"spaces": []`.

### `model-meta.json`

```json
{"ifcSchema": "IFC4",
 "units": {"length": "METRE"},
 "sitePlacement": {"origin": [0.0, 0.0, 0.0], "refLatitude": null, "refLongitude": null, "refElevation": null},
 "trueNorth": [0.0, 1.0],
 "elementCount": 18342,
 "storeyCount": 12,
 "storeys": [{"globalId": "...", "name": "Level 03", "elevation": 9.0,
              "tile": "tiles/1xS3BCk291UvhgP2a6eflL.glb", "elementCount": 1502}],
 "coarseTile": "tiles/coarse.glb",
 "unassignedTile": "tiles/unassigned.glb",
 "workerVersion": "0.1.0",
 "ifcOpenShellVersion": "0.8.1"}
```

`storeys[].tile` paths are relative to `output_prefix`. `unassignedTile` is null when every product
sat in a storey. The backend copies `elementCount`, `storeyCount` and `ifcSchema` onto the version
and stores the whole document in `bim_model_versions.meta`.

## 6. What the backend does with DONE

1. Reads `model-meta.json`, `structure.json` and streams `elements.jsonl`.
2. Upserts `bim_elements` by (`model_id`, `global_id`): matched rows are updated in place with
   `last_seen_version_id` set to this version, new rows inserted with both seen columns set,
   rows of this model absent from the file flagged `retired = true`. Nothing is deleted.
3. Builds the hierarchy proposal from `structure.json` and stores it on the version for a user to
   confirm; confirmation creates or matches `SpatialNode` rows by `bim_element_guid`.
4. Sets `ingested_at`, the version READY, and `bim_models.current_version_id` to this version.

Any failure in steps 1 to 4 sets the version FAILED with the message and leaves the job DONE with
`ingested_at` null, so the backend retries ingestion on its next pass without re-running the
worker.

## 7. Limits

- Source IFC up to 1 GB. The backend refuses a larger presign; the worker still guards with the
  same cap and fails the job with a clear message if the object exceeds it.
- IFC2x3 and IFC4 are supported; IFC4.3 is accepted if IfcOpenShell parses it.
- A job that runs past 30 minutes without a heartbeat is treated as dead.

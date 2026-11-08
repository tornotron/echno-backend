# Vision inference job contract: backend, worker and object store

**Date:** 2026-11-08
**Status:** Binding for `echno-backend` (`modules/vision`) and `echno-vision-worker`. Changes land in
both repos together.
**Design:** `2026-10-26-cv-phase4-design.md`, sections 4.6 and 4.7. The shape follows
`2026-09-13-bim-import-contract.md`; where this note is silent, that one applies with the table name
changed.

The backend inserts one row in `cv_inference_jobs` per image and model version. The worker polls the
table, reads the image and the model weights from the object store, runs the model, writes
`detections.json` under the key the row names, and closes the row. The backend then reads the file and
posts each kept detection through observation intake. The worker writes no table but
`cv_inference_jobs` and no object but its own output.

## 1. The job row

Columns the worker reads are marked R, columns it writes W.

| Column | Type | Who | Meaning |
|---|---|---|---|
| `id` | uuid | R | Job id. |
| `organization_id` | bigint | R | Tenant. Log lines only. |
| `project_id` | bigint | | Copied for ingestion. |
| `attachment_id` | bigint | R | The image's attachment row. Log lines only. |
| `inspection_id`, `observation_id` | uuid null | | The record the image is evidence of. One is set. |
| `source_key` | varchar(500) | R | Object key of the image in the attachments bucket. |
| `content_type` | varchar(100) | R | `image/jpeg`, `image/png` or `image/webp`. Nothing else is queued. |
| `model_version_id` | uuid | | `cv_model_versions.id`. |
| `model_key`, `model_version` | varchar(50) | R | Copied from the registry. |
| `weights_uri` | varchar(500) | R | `s3://<models bucket>/<model>/<version>/model.onnx`. |
| `weights_sha256` | varchar(64) | R | Lower-case hex. A mismatch fails the job. |
| `input_size` | int | R | Copied. |
| `classes` | jsonb | R | `[{"key": "ppe_helmet", "shape": "BOX", "threshold": 0.35}, ...]`, the version's classes when queued. |
| `output_key` | varchar(500) | R | `vision/jobs/<id>/detections.json`. |
| `job_trigger` | varchar(20) | | `AUTO` or `ON_DEMAND`. |
| `requested_by_id` | bigint null | | The user, on demand only. |
| `status` | varchar(20) | R W | `QUEUED`, `RUNNING`, `DONE`, `FAILED`. |
| `worker_id` | varchar(100) | W | Hostname or pod name. |
| `attempt` | int | W | Incremented on every claim. |
| `max_attempts` | int | R | Set by the backend, default 3. |
| `lease_expires_at` | timestamp | W | Pushed forward while the worker is alive. |
| `detection_count` | int | W | Entries in `detections`. |
| `inference_ms` | int | W | Model time only. |
| `worker_version` | varchar(50) | W | Worker image version. |
| `error` | text | W | Set on FAILED. One line, no stack trace. |
| `queued_at` | timestamp | R | Queue order. |
| `started_at`, `finished_at` | timestamp | W | Set on claim, and on DONE or FAILED. |
| `ingested_at`, `observation_count`, `ingest_error` | | backend | Set by ingestion. The worker never writes them. |
| `created_at`, `updated_at` | timestamp | W (`updated_at`) | Set `updated_at = now()` on every write. |

Timestamps are UTC, `TIMESTAMP` without zone. The column is `job_trigger`, not `trigger`, which is a
keyword. A partial unique index on `(attachment_id, model_version_id)` over QUEUED and RUNNING rows
keeps one pending job per image and version.

## 2. Status transitions

As the BIM contract, section 2, without the version mirror. DONE and FAILED are terminal; a rerun is
a new row queued by the backend.

## 3. Claiming, heartbeating and closing

Claim:

```sql
UPDATE cv_inference_jobs
   SET status = 'RUNNING', worker_id = :worker, attempt = attempt + 1,
       started_at = now(), lease_expires_at = now() + interval '5 minutes',
       updated_at = now()
 WHERE id = (SELECT id FROM cv_inference_jobs
              WHERE status = 'QUEUED' ORDER BY queued_at LIMIT 1)
   AND status = 'QUEUED'
RETURNING id, organization_id, attachment_id, source_key, content_type, model_key, model_version,
          weights_uri, weights_sha256, input_size, classes, output_key, attempt, max_attempts;
```

Zero rows: sleep 5 s and poll again. A lost race returns zero rows and is expected.

Heartbeat, every 60 s while a job runs:

```sql
UPDATE cv_inference_jobs SET lease_expires_at = now() + interval '5 minutes', updated_at = now()
 WHERE id = :id AND status = 'RUNNING' AND worker_id = :worker;
```

Close:

```sql
UPDATE cv_inference_jobs
   SET status = 'DONE', finished_at = now(), updated_at = now(),
       detection_count = :n, inference_ms = :ms, worker_version = :v
 WHERE id = :id AND status = 'RUNNING' AND worker_id = :worker;

UPDATE cv_inference_jobs
   SET status = 'FAILED', finished_at = now(), updated_at = now(), error = :msg, worker_version = :v
 WHERE id = :id AND status = 'RUNNING' AND worker_id = :worker;
```

Write DONE only after `detections.json` is fully uploaded. A close that updates zero rows means the
lease expired and the row moved on; log it and drop the result. The backend returns a RUNNING row
whose lease expired to QUEUED while attempts are left, and fails it otherwise.

## 4. Object-store layout

```
<source_key>                          the image, in the attachments bucket, read only
vision/jobs/<jobId>/detections.json   worker, in the attachments bucket, application/json
s3://<models bucket>/<model>/<version>/model.onnx and model.json   read only, cached by sha256
```

The bucket names and endpoint come from the environment; nothing in the row names a bucket except
`weights_uri`.

## 5. `detections.json`

```json
{"jobId": "...", "modelKey": "ppe", "modelVersion": "0.1.0",
 "image": {"width": 4032, "height": 3024, "sha256": "...", "exifOrientationApplied": 6},
 "detections": [
   {"classKey": "ppe_helmet", "score": 0.81, "shape": "box", "box": [0.412, 0.188, 0.471, 0.262]},
   {"classKey": "crack", "score": 0.66, "shape": "polygon", "points": [[0.10, 0.52], [0.18, 0.49], [0.21, 0.61]]}
 ],
 "inferenceMs": 214, "workerVersion": "0.1.0", "runtime": "onnxruntime 1.x"}
```

- Coordinates are fractions of the image as displayed, after EXIF orientation, origin top left, x to
  the right, y down. `width` and `height` are the displayed pixel size.
- `shape` is `box` or `polygon`. A box is `[x1, y1, x2, y2]` with `x1 < x2`, `y1 < y2`. A polygon has
  3 to 200 vertices, open, not crossing itself.
- Write every detection at or above the lowest threshold in `classes`, of a class in `classes` only.
- `jobId` must equal the row's `id`; the backend ignores a file that names another job.

## 6. What the backend does with DONE

Every 10 s, for each DONE row with `ingested_at` null:

1. Reads `detections.json`.
2. Keeps detections at or above their class threshold, best score first, at most
   `echno.vision.max-observations-per-image` (50).
3. Posts one intake item per kept detection, source AI, external ref `cv/<jobId>/<index>` (the
   detection's index in the file), device `echno-vision-worker/<workerVersion>`, model key and version
   as `modelName` and `modelVersion`, the score as confidence, the class as category, title
   "`<Class label>` detected", the inspection the image is evidence of, observed at the image's upload
   time, and one region on the job's image.
4. Sets `ingested_at`, `observation_count` (created plus already present) and `ingest_error` (null, or
   a one-line count of refused items by code, or why the file could not be read).

A crash anywhere re-runs cleanly: the external refs repeat and intake returns the rows already stored.
An unreadable file is stamped with its error and not read again; run the model again on demand to
retry.

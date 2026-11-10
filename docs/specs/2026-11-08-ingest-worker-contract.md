# Ingest worker contract: backend, worker and object store

**Date:** 2026-11-08
**Status:** Binding for `echno-backend` (`modules/documentingestion`) and `echno-ingest-worker`. Changes
land in both repos together.
**Design:** `docs/specs/2026-11-08-document-ingestion-design.md`, sections 6.2, 6.4, 7.4 and 9.

The backend registers an uploaded file as a document and inserts one row in `doc_ingest_parse_job`. The
worker (Python) polls that table, reads the source from the object store, writes its outputs under the
prefix the row names, and closes the row. The backend then reads the outputs. The worker writes no other
table, holds no model key, and needs no tenant context beyond the organization id it logs.

## 1. The job row

Table `doc_ingest_parse_job`. Columns the worker reads are marked R, columns it writes W.

| Column | Type | Who | Meaning |
|---|---|---|---|
| `id` | uuid | R | Job id. |
| `organization_id` | bigint | R | Tenant. Goes in log lines and scopes the same-file lookup in section 5. |
| `package_id` | uuid | R | `doc_ingest_package.id`. |
| `document_id` | uuid | R | `doc_ingest_document.id`. |
| `job_type` | varchar(10) | R | `UNPACK` or `PARSE`. |
| `status` | varchar(20) | R W | `QUEUED`, `RUNNING`, `DONE`, `FAILED`. |
| `source_key` | varchar(500) | R | Object key of the source file. |
| `source_file_name` | varchar(300) | R | Name the person gave the file. A hint for messages only: the file type is read from the content. |
| `output_prefix` | varchar(500) | R | Trailing slash included. Every output goes under it. |
| `worker_id` | varchar(100) | W | Hostname or pod name of the worker that claimed the row. |
| `attempt` | int | W | Incremented on every claim. |
| `max_attempts` | int | R | Set by the backend, default 3. |
| `lease_expires_at` | timestamp | W | Pushed forward while the worker is alive. |
| `source_sha256` | varchar(64) | W | Hex SHA-256 of the source, set before DONE. |
| `source_size_bytes` | bigint | W | The real size read from the object store. |
| `page_count` | int | W | PDF pages. Null for other types. |
| `sheet_count` | int | W | Workbook sheets. Null for other types. |
| `row_count` | int | W | Lines written to `tables.jsonl`. |
| `pages_ocr` | int | W | Pages read through OCR. |
| `pages_failed` | int | W | Pages or sheets that could not be read. |
| `reused_output_prefix` | varchar(500) | W | Set when an earlier parse of the same file in this organization was reused (section 5). |
| `worker_version` | varchar(50) | W | Worker image version. |
| `error` | text | W | Set on FAILED. One plain sentence a person can act on, no stack trace. |
| `queued_at` | timestamp | R | Queue order. |
| `started_at` | timestamp | W | Set on claim. |
| `finished_at` | timestamp | W | Set on DONE or FAILED. |
| `ingested_at` | timestamp | backend | Set when the backend has read the outputs. The worker never writes it. |
| `ingest_claimed_by`, `ingest_claimed_at` | | backend | The replica ingesting the job. The worker never writes them. |
| `created_at`, `updated_at` | timestamp | W (`updated_at`) | Set `updated_at = now()` on every write. |

Timestamps are UTC, column type `TIMESTAMP` without zone.

The worker's database user holds `SELECT` and `UPDATE` on this table and nothing else. Its object-store
policy covers `doc-ingest/` only.

## 2. Status transitions

```
QUEUED  --claim (worker)-------------------> RUNNING
RUNNING --outputs written (worker)---------> DONE
RUNNING --exception, bad file (worker)-----> FAILED   error set
RUNNING --lease expired, attempts left-----> QUEUED   (backend poller)
RUNNING --lease expired, none left---------> FAILED   (backend poller)
QUEUED  --organization off the allow-list--> FAILED   (backend poller)
```

DONE and FAILED are terminal. A retry is a new row, queued by the backend when a person asks for it; the
worker never reopens a terminal row. With the module switched off the backend poller does not run and rows
wait where they are. Replicas of the worker are 0 by default, so a row can wait a long time.

## 3. Claiming, heartbeating and closing

Claim (one statement, compare-and-set on `status`, works on CockroachDB and Postgres):

```sql
UPDATE doc_ingest_parse_job
   SET status = 'RUNNING', worker_id = :worker, attempt = attempt + 1,
       started_at = now(), lease_expires_at = now() + interval '10 minutes',
       updated_at = now()
 WHERE id = (SELECT id FROM doc_ingest_parse_job
              WHERE status = 'QUEUED' ORDER BY queued_at LIMIT 1)
   AND status = 'QUEUED'
RETURNING id, organization_id, package_id, document_id, job_type, source_key, source_file_name,
          output_prefix, attempt, max_attempts;
```

Zero rows means nothing to do or a lost race; sleep and poll again (suggested 5 s).

Heartbeat, every 60 seconds and after each 100 pages:

```sql
UPDATE doc_ingest_parse_job SET lease_expires_at = now() + interval '10 minutes', updated_at = now()
 WHERE id = :id AND status = 'RUNNING' AND worker_id = :worker;
```

Close:

```sql
UPDATE doc_ingest_parse_job
   SET status = 'DONE', finished_at = now(), updated_at = now(), source_sha256 = :sha,
       source_size_bytes = :size, page_count = :pages, sheet_count = :sheets, row_count = :rows,
       pages_ocr = :ocr, pages_failed = :failed, reused_output_prefix = :reused, worker_version = :v
 WHERE id = :id AND status = 'RUNNING' AND worker_id = :worker;

UPDATE doc_ingest_parse_job
   SET status = 'FAILED', finished_at = now(), updated_at = now(), error = :msg, worker_version = :v
 WHERE id = :id AND status = 'RUNNING' AND worker_id = :worker;
```

A close that updates zero rows means the lease expired and the row moved on; discard the outputs and log
it. Only write DONE after every file in section 4 is fully uploaded. Check the lease immediately before
uploading, so an attempt that has lost its lease does not write over a newer attempt's outputs under the
shared prefix. A small window remains between that check and the last upload. The backend reads them the moment it
sees DONE.

On start-up a worker closes any row still `RUNNING` under its own `worker_id` as FAILED, since it cannot
have been running it. One job runs at a time, in a work directory wiped per job, with parsing in a child
process under memory and time limits.

## 4. Object-store layout

The bucket and endpoint come from the environment. `output_prefix` for a document is
`doc-ingest/<orgId>/<packageId>/<documentId>/output/`.

```
doc-ingest/<orgId>/<packageId>/<documentId>/source              written by the browser through a presigned PUT
doc-ingest/<orgId>/<packageId>/<documentId>/output/meta.json    worker, PARSE
doc-ingest/<orgId>/<packageId>/<documentId>/output/pages.jsonl  worker, PARSE of a paged document
doc-ingest/<orgId>/<packageId>/<documentId>/output/tables.jsonl worker, PARSE
doc-ingest/<orgId>/<packageId>/<documentId>/output/page-images/<n>.webp  worker, PARSE of a PDF
doc-ingest/<orgId>/<packageId>/<documentId>/output/titleblock.jsonl      worker 0.3.0, PARSE of a PDF
doc-ingest/<orgId>/<packageId>/<documentId>/output/titleblock/<n>.webp   worker 0.3.0, PARSE of a PDF, drawing sheets
doc-ingest/<orgId>/<packageId>/<documentId>/output/dxf.json             worker 0.3.0, PARSE of a DXF
doc-ingest/<orgId>/<packageId>/members/<sha256>                 worker, UNPACK: one object per distinct member
doc-ingest/<orgId>/<packageId>/<zipDocumentId>/output/manifest.json      worker, UNPACK
```

The worker refuses any `source_key` or `output_prefix` that does not begin with
`doc-ingest/<organization_id>/<package_id>/`, and writes nothing outside that prefix.

Content types: `application/json`, `application/x-ndjson`, `image/webp`, and
`application/octet-stream` for a member.

## 5. The same file again

After hashing the source, a `PARSE` job looks for an earlier parse of the same bytes **in the same
organization**:

```sql
SELECT output_prefix, page_count, sheet_count, row_count, pages_ocr, pages_failed
  FROM doc_ingest_parse_job
 WHERE organization_id = :org AND source_sha256 = :sha AND job_type = 'PARSE' AND status = 'DONE'
   AND reused_output_prefix IS NULL AND id <> :id AND worker_version = :v
 ORDER BY finished_at LIMIT 1;
```

A row found means the worker writes nothing, copies its counts, and closes DONE with
`reused_output_prefix` set to that row's `output_prefix`. The backend then reads the outputs from there.
The organization condition is required: a hash shared across tenants would reveal that another tenant
holds the same tender. The version condition means outputs of an older worker are parsed afresh, so an
improved reader reaches files seen before.

## 6. Limits

Defaults, from section 9 of the design, set by environment variables on the worker. A file over a limit
closes FAILED with the sizes in the message and is not retried. Sizes are checked again on the real object,
because a presigned PUT does not bind the size the browser declared.

| Limit | Default |
|---|---|
| Source file, other than ZIP | 200 MB |
| ZIP | 1 GiB compressed, 4 GiB expanded, 500 members, ratio 100:1, one nesting level |
| PDF | 2,000 pages |
| XLSX, XLS | 50 sheets, 200,000 rows in the whole workbook, 1 GiB expanded |
| Child process | 2 GiB memory, 30 minutes |

Type is read from the content. RAR, 7z, `.doc`, encrypted workbooks and anything unknown close FAILED with
a sentence saying what to upload instead.

## 7. JSON shapes

Keys are camelCase. A reader ignores keys it does not know, so a new key is not a breaking change. Keys
added since the first version are marked with the worker version that writes them; the backend reads all
of them and still accepts outputs without them.

### `meta.json`

```json
{"documentId": "0b0f...", "fileType": "XLSX", "sourceSha256": "ab12...", "sourceSizeBytes": 48211,
 "workerVersion": "0.1.0", "sheets": [{"index": 0, "name": "BOQ", "hidden": false, "rowCount": 412,
 "columnCount": 8, "merged": ["A1:H1"]}], "pageCount": null, "warnings": ["Macros were ignored."]}
```

`fileType` is `PDF`, `XLSX`, `XLS`, `DOCX` or `DXF`. `warnings` are plain sentences for the review screen.

### `tables.jsonl`

One object per non-empty row of a sheet, or per row of a table on a PDF page, in reading order.

```json
{"sheet": "BOQ", "sheetIndex": 0, "page": null, "row": 47, "hidden": false,
 "cells": [{"ref": "B47", "col": 2, "value": 1234.5, "type": "NUMBER", "text": "1,234.50",
            "formula": false, "noCachedValue": false, "mergeOrigin": null}]}
```

- `type` is `TEXT`, `NUMBER`, `DATE`, `BOOL` or `EMPTY`. `value` is a JSON string, number or boolean, a
  date as ISO 8601, or null.
- `text` is what a person sees in the cell as far as the file states it. An Indian-format number held as text
  keeps `type` `TEXT`; deciding that it is a number belongs to the extractor, which flags it.
- `formula` true means the cell holds a formula and `value` is its cached result. XLS files do not
  expose formulas to the reader, so `formula` is always false there and `meta.json` says so in `warnings`. Formulas are never
  calculated. `noCachedValue` true means there is no cached result and `value` is null.
- `mergeOrigin` names the top-left cell of a merged range for a covered cell, and is null otherwise.
- A hidden row or a row on a hidden sheet is written with `hidden` true, never dropped.
- On a row of a table on a PDF page (worker 0.2.0), `sheet` and `sheetIndex` are null, `page` is the page,
  and the additive key `table` is the 1-based index of the table on that page, so two tables on one page do
  not share row numbers. PDF cells are `TEXT` or `EMPTY` (a covered cell of a merged range, with
  `mergeOrigin`), and each carries the additive key `box`: the cell's `[x0, y0, x1, y1]`, normalized as the
  line boxes of `pages.jsonl` are, or null on a covered cell. Sheet cells have no `box`.

### `pages.jsonl`

One object per page: `page`, `method` (`TEXT`, `OCR`, `NONE`), `width`, `height`, `lines` (each `text`,
`box` as `[x0, y0, x1, y1]` normalized 0 to 1, and `ocrConfidence` or null), `status` (`OK` or `FAILED`) and
`reason`. Produced from plan step 11; a worker that does not parse PDF yet closes a PDF job FAILED and says
so, and a person can retry after upgrading.

```json
{"page": 3, "method": "TEXT", "width": 595.28, "height": 841.89,
 "lines": [{"text": "GENERAL CONDITIONS OF CONTRACT", "box": [0.21, 0.08, 0.79, 0.1], "ocrConfidence": null}],
 "status": "OK", "reason": null, "script": "LATIN"}
```

- `width` and `height` are in PDF points. A box has its origin at the top left of the page, so `y0` is
  above `y1`. Lines are in reading order.
- `ocrConfidence` is the mean confidence of the line's words on a 0 to 1 scale, on `OCR` pages only.
- `method` `NONE` with `status` `OK` is a blank page (no text layer and nothing to read by OCR); `reason`
  says so.
- **`script`** (worker 0.2.0, additive): the Unicode script most letters of the page's text layer belong to,
  as the Unicode script name in capitals (`LATIN`, `DEVANAGARI`, `TAMIL`, ...). Null on `OCR` pages (English
  OCR cannot tell) and on pages with fewer than 20 letters. Every page that is not `LATIN` is also named in
  `meta.json` `warnings` as stored and not sent for reading. The backend sends no page whose `script` is not
  `LATIN` to the LLM (design section 7.4, English only); a reader of an older output with no `script` key
  works the script out from the text itself.

### `titleblock.jsonl` (worker 0.3.0, additive)

Plan step 18, for the drawing register (design section 4.3). One object per page of a PDF, in page
order, written beside `pages.jsonl`. Outputs of a worker before 0.3.0 have no such file; the reader
treats that as "title block not read".

```json
{"page": 1, "status": "OK", "reason": null, "method": "TEXT",
 "region": [0.7777, 0.8218, 0.9916, 0.9881], "regionSource": "RULED_BOX",
 "image": "titleblock/1.webp",
 "words": [{"text": "RCB-AR-101", "box": [0.8406, 0.8924, 0.8755, 0.9008], "ocrConfidence": null}],
 "lines": [{"text": "DRAWING NO.", "box": [0.7802, 0.8813, 0.8062, 0.8867], "ocrConfidence": null}]}
```

- `status`: `OK`; `SKIPPED` for a page not shaped like a drawing sheet (portrait and no larger
  than A4 on its short edge), with `reason` saying so; `FAILED` with `reason` when the title block
  could not be read. A page that fails in `pages.jsonl` normally fails here too, with one exception:
  a sheet whose scan is over the page pixel limit fails as a page but has its title block read
  here (design section 9, "A0 drawing scan: title-block crop only"), and `meta.json` `warnings`
  names it.
- `region`: the title-block box, `[x0, y0, x1, y1]` normalized as the line boxes of `pages.jsonl`
  are. `regionSource`: `RULED_BOX` when it is the largest box ruled by the page's vector lines at
  the sheet's bottom-right corner, `DEFAULT` when it is the fixed bottom-right fraction (every
  scanned page, and vector pages with no such box). Both are null on a `SKIPPED` line.
- `method`: `TEXT` (words of the text layer inside the region), `OCR` (only the cropped region
  read by OCR: scanned pages, and text pages whose region has almost no text, as when CAD draws
  title-block text as outlines), or `NONE` (skipped, failed, or a blank region, which `reason`
  states on an `OK` line).
- `words` and `lines` have the shape of a `pages.jsonl` line (`text`, `box`, `ocrConfidence`, the
  last on `OCR` lines only). Their boxes are on the page, not on the crop. `words` are single
  words in reading order. `lines` are the words grouped into lines as `pages.jsonl` does.
- `image`: the crop's key relative to `output_prefix` (`titleblock/<n>.webp`), covering exactly
  `region`, long edge at most 2000 pixels; null when nothing was cropped.

Reading drawing number, title, revision, date and scale out of these words is the backend's job
(plan step 19).

### `dxf.json` (worker 0.3.0, additive)

Written for a DXF (`meta.json` `fileType` `DXF`), with an empty `tables.jsonl`. DWG is still
refused by the worker with "save it as DXF or PDF".

```json
{"dxfVersion": "AC1032", "insUnits": 4,
 "properties": [{"name": "Client", "value": "Example Housing Board"}],
 "layouts": [{"name": "Model", "modelSpace": true, "paperSize": null},
             {"name": "A1 Sheet", "modelSpace": false, "paperSize": "ISO_A1_(841.00_x_594.00_MM)",
              "texts": [{"text": "NOT FOR CONSTRUCTION", "kind": "TEXT", "layer": "0", "position": [20.0, 20.0]}]}],
 "inserts": [{"layout": "A1 Sheet", "block": "TITLE_BLOCK_A1", "path": ["A1_BORDER"], "handle": "A3",
              "titleBlockLike": true,
              "attributes": [{"tag": "DWG_NO", "value": "HRV-AR-101", "constant": false, "invisible": false}],
              "texts": [{"text": "DRAWING NO.", "kind": "TEXT", "layer": "0", "position": [2.0, 50.0]}]}],
 "truncated": false}
```

- `insUnits` is the header's `$INSUNITS` code, or null. `properties` are the drawing properties a
  CAD program keeps as custom header variables.
- `layouts`: every layout in tab order. A paper space layout carries `texts`, its loose TEXT and
  MTEXT; model space carries none.
- `inserts`: every block reference, in a layout or nested in a block definition up to four levels
  (`path` names the enclosing blocks, outermost first), that carries attributes or whose block looks
  like a title block. `block` is a dynamic block's own name, not its anonymous `*U` copy.
  `attributes` are the ATTRIB tag and value pairs, plus the block's constant ATTDEFs with
  `constant` true. `titleBlockLike` is true when the block name says title, border, sheet, stamp,
  cartouche or TB, or when at least two attribute tags name title-block fields; such a block's own
  TEXT and MTEXT are in `texts`.
- Text is plain (MTEXT formatting and `%%` codes resolved). `position` is in the coordinates of the
  layout or block the text sits in.
- `truncated` is true when a cap was reached (5,000 inserts, 200,000 references walked, 500 texts in
  one layout or block); `meta.json` `warnings` then says so, and also when no title-block-like block
  was found.

### `manifest.json` (`UNPACK`)

```json
{"zipDocumentId": "0b0f...", "expandedBytes": 104857600,
 "members": [{"path": "boq/BOQ.xlsx", "fileName": "BOQ.xlsx", "sha256": "ab12...", "sizeBytes": 48211}],
 "skipped": [{"path": "__MACOSX/x", "reason": "Hidden system folder"}]}
```

The backend creates one child document per member with `source_key`
`doc-ingest/<orgId>/<packageId>/members/<sha256>` and queues a `PARSE` job for it.

## 8. Worker health and versions

The worker serves `GET /healthz` on `HEALTH_PORT`. (`echno-bim-worker` has only a heartbeat file; this
worker has an HTTP endpoint so a probe needs no shell.) `worker_version` is written on
every close. A change to a column, a status, a key that readers rely on or the layout is a change to this
file, in both repos, in the same sitting.

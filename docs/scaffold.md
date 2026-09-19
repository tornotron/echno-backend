# Module scaffold

Generates the skeleton of a new backend module from the templates under
`scripts/scaffold/templates/backend/`, so a module starts out obeying the module contract
(`docs/specs/2026-08-26-modular-plugin-architecture.md`) and the architecture rules that
judge it, and the first commit is about the domain rather than the plumbing.

## Command

```sh
./gradlew scaffoldModule -Pid=toolbox-talks -Pname="Toolbox Talks"
```

`id` is required and must match `[a-z][a-z0-9-]*` (the `ModuleManifest` id pattern). `name` is
the human label; it defaults to the id in title case. The generator refuses an id whose package
under `modules/` or changelog directory already exists, and never overwrites a file.

The Gradle task wraps `scripts/scaffold-module.py` (Python 3, standard library only), which
can also be run directly:

```sh
python3 scripts/scaffold-module.py toolbox-talks --name "Toolbox Talks" --dry-run   # list, write nothing
python3 scripts/scaffold-module.py toolbox-talks --delete                            # remove what it generated
```

`--delete` removes exactly the files the same invocation would create, then any directories
left empty, so a throwaway module leaves no trace.

## What it generates

For `-Pid=toolbox-talks`, with the derived names `toolboxtalks` (package), `ToolboxTalks`
(class prefix), `toolbox_talks` (table prefix) and `MODULE_TOOLBOX_TALKS` (feature key):

| Path | What it is |
|---|---|
| `modules/toolboxtalks/ToolboxTalksModule.java` | The manifest bean: id, name, version `0.1.0`, feature key, `enabledByDefault=false`, permissions `toolbox-talks:read` and `toolbox-talks:manage`, one nav descriptor gated on `:read` |
| `modules/toolboxtalks/ToolboxTalksModuleEnabled.java` | The kill switch as a bean condition, `echno.modules.toolbox-talks.enabled`; stack it on scheduled jobs and listeners |
| `modules/toolboxtalks/api/package-info.java` | The module's published surface, the only package other code may import from |
| `modules/toolboxtalks/domain/ToolboxTalksEntry.java` | A sample `TenantScopedEntity` behind `orgFilter`, with audit columns; rename it to the module's real noun |
| `modules/toolboxtalks/repository/ToolboxTalksEntryRepository.java` | Scoped `findByIdScoped` (JPQL so the filter applies) and a paged list; no `findAll()` |
| `modules/toolboxtalks/service/ToolboxTalksService.java` | Create, paged list, get; stamps the tenant and user on writes |
| `modules/toolboxtalks/dto/ToolboxTalksEntryDto.java`, `CreateToolboxTalksEntryRequest.java` | Response and request records with `@Schema` and validation |
| `modules/toolboxtalks/mapper/ToolboxTalksMapper.java` | MapStruct mapper, `componentModel = "spring"` |
| `modules/toolboxtalks/web/ToolboxTalksController.java` | Mobile twin on `/api/v1/toolbox-talks`: reads only |
| `modules/toolboxtalks/web/ToolboxTalksControllerWeb.java` | Web twin on `/api/v1/toolbox-talks/web`: reads plus create. Both twins carry `@RequireSubscription(feature = "MODULE_TOOLBOX_TALKS")` at class level and `@PreAuthorize` on every method, and page through `PageQuery` |
| `db/changelog/modules/toolbox-talks/db.changelog-toolbox-talks.xml` | The directory marker the master `includeAll` picks up |
| `db/changelog/modules/toolbox-talks/001-create-toolbox-talks.xml` | The sample table with its organization foreign key and index |
| `db/changelog/modules/toolbox-talks/002-seed-module-toolbox-talks-feature.xml` | The feature row and its grant on all four plans, each changeSet guarded on its own row (mirrors the BIM seed) |
| `test/.../modules/toolboxtalks/ToolboxTalksModuleTest.java` | Manifest, colon-form permission vocabulary, nav, registry entitlement and kill switch |
| `test/.../modules/toolboxtalks/ToolboxTalksServiceIT.java` | Tenant isolation through the real migration on CockroachDB: another tenant's row reads as absent |
| `test/.../modules/toolboxtalks/ToolboxTalksControllerWebAuthzTest.java` | `@WebMvcTest` slice over both twins: membership on reads, admin role on the write |

Seventeen files. The generated module passes `ModuleBoundaryTest`, `EndpointAuthorizationTest`,
`PaginationParameterBoundTest`, `UnboundedRepositoryReadTest` and `MapperConventionTest` as
generated.

## After generating

1. `./gradlew compileJava`, then the three tests under `src/test/java/.../modules/<pkg>/`.
2. Rename `Entry` to the module's aggregate and grow the table in `001-create-<id>.xml`.
   Keep every entity tenant scoped or an owned child of one.
3. `./gradlew openApiSnapshot -PupdateOpenApiSnapshot` and commit `docs/openapi.json`: the new
   endpoints change the contract, and the pull-request workflow fails on a stale snapshot.
4. Add the module's permission keys to the web nav gate and the core subpath on their side; the
   keys are `<module>:<action>` throughout.

## How the templates stay honest

The templates mirror the SPI by hand, so the `scaffold-check` job in
`.github/workflows/pull-request.yml` generates `ci-probe` on every pull request, runs its three
tests together with the architecture suite that judges every module, and deletes it, failing
if anything else in the tree changed. A change to `EchnoModule`, `ModuleManifest`, the tenant
helpers or a boundary rule that the templates do not follow fails that job before merge.
`ci-probe` is never committed.

Placeholders in template paths and contents are replaced verbatim, no template engine:
`__MODULE_ID__`, `__MODULE_PKG__`, `__MODULE_PASCAL__`, `__MODULE_SNAKE__`, `__MODULE_UPPER__`,
`__FEATURE_KEY__`, `__MODULE_NAME__`.

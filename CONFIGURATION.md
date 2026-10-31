# Backend configuration

All credentials are supplied at runtime. None are stored in tracked files (AGF-S1-01).
If a required value is missing, the application **fails at startup**. The error names the missing placeholder (for example `Could not resolve placeholder 'CLOUDFLARE_R2_SECRET_KEY'`) and never prints secret values.

## Required environment variables

| Variable | Property | Profiles | Secret? | Notes |
|---|---|---|---|---|
| `DB_PASSWORD` | `spring.datasource.password` | all | **Yes** | |
| `APP_JWT_SECRET` | `app.jwt.secret` | all | **Yes** | At least 32 characters (HS256). Changing it logs out every user. |
| `CLOUDFLARE_R2_ACCESS_KEY` | `cloudflare.accessKey` | all | **Yes** | R2 API token access key ID |
| `CLOUDFLARE_R2_SECRET_KEY` | `cloudflare.secretKey` | all | **Yes** | R2 API token secret |
| `CLOUDFLARE_R2_ENDPOINT` | `cloudflare.endpoint` | all | No (contains the account ID) | `https://<account-id>.r2.cloudflarestorage.com` |
| `CLOUDFLARE_R2_BUCKET` | `cloudflare.bucket` | all | No | Use a different bucket per environment |
| `CLOUDFLARE_R2_PUBLIC_BASE_URL` | `cloudflare.publicBaseUrl` | all | No | Public host that serves bucket objects |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME` | `spring.datasource.url` / `username` | `prod` | `DB_USERNAME`: treat as sensitive | The default profile uses `localhost:5432/agafari_menu` with user `postgres` |

## Optional environment variables

| Variable | Default | Profiles |
|---|---|---|
| `PORT` | `8080` | `prod` |
| `APP_JWT_EXPIRESMINUTES` | `120` | `prod` |
| `APP_BASE_DOMAIN` | `agafarimenu.com` | all |
| `SPRING_PROFILES_ACTIVE` | none | Set to `prod` in production |

## Local development

Pick one of these:

- **Option A — local file.** Copy `application-local.example.yml` to `application-local.yml` in the project root and fill in development values. The file is git-ignored and Docker-ignored. `application.yml` loads it automatically when it exists (`spring.config.import: optional:file:./application-local.yml`). Run the app from the project root so the relative path resolves.
- **Option B — environment variables.** Export the variables listed above in your shell or IDE run configuration.

Environment variables override values from the local file.

## Database schema and migrations (Decision A)

The schema comes from **Flyway** migrations in `src/main/resources/db/migration`; Hibernate only validates it (`ddl-auto: validate`).

| Profile | Flyway | Schema |
|---|---|---|
| default (development, PostgreSQL) | on, `baseline-on-migrate: true`, baseline version 1 | Fresh database: runs `V1__baseline.sql`. Existing development database built earlier by `ddl-auto`: recorded at version 1 **without** running V1, so its data stays. `FlywayBaselineGuard` stops startup if a non-empty database is not an Agafari database. |
| test | on, no baseline | Built from the migrations on an empty database (Testcontainers) |
| demo (H2) | off | Built from the entities (`create-drop`); PostgreSQL migrations are not used |
| prod | **off (unchanged)** | `ddl-auto: update` as before. There is no production deployment yet; switching production is part of S1-08B's production scope. |

**Ordering and upgrades:**

- Files are applied in version order: `V1__baseline.sql`, then `V2__…`, `V3__…`. Each runs once per database and is recorded in `flyway_schema_history`.
- **Never edit or delete a migration that a shared database (CI excepted) has applied.** Every schema change is a new, forward-only `V{n+1}__description.sql`, written so it also works on a database that was baselined at version 1 (same columns, see `ExistingDatabaseUpgradeTest`).
- Entity changes must ship with a migration in the same commit; otherwise startup fails validation (by design).
- Current versions: **V1** baseline (Decision A), **V2** menu-type seed (AGF-S1-09: the nine PO-1 types, insert-only, case-insensitive). **V3** restaurant location link (AGF-S2-12: nullable `business.location_url VARCHAR(2048)`, existing rows get NULL); **V4** restaurant country (AGF-S2-12 CR1: `business.country VARCHAR(2) NOT NULL DEFAULT 'ET'`, existing rows become ET); **V5** restaurant time zone (AGF-S2-26: `business.time_zone VARCHAR(64) NOT NULL DEFAULT 'Africa/Addis_Ababa'`); **V6** Amharic translations (AGF-S2-23: nullable `name_am` on menu, category and menu_item, `description_am` on menu_item).
- V1 is **provisional**: it is the schema Hibernate builds from today's entities. Until a shared non-development database exists it may still be corrected; after that it is frozen.
- To start over locally, drop and recreate the development database; Flyway rebuilds it from V1.

## Deployment order

1. Set every required variable on the hosting provider **before** deploying a build that contains this change. Otherwise the service will not start.
2. Deploy.
3. Verify the deployment: health endpoint, login, and an item image upload.

## Frontend note

The frontend (`JemawMenus`) has no secrets. Every variable prefixed with `VITE_` is compiled into the public JavaScript bundle and must never contain a credential.

## Secret scanning (AGF-S1-02)

- CI runs [gitleaks](https://github.com/gitleaks/gitleaks) 8.30.1 over the **full Git history** on every push and pull request (`secret-scan` job). Any new finding fails the build; output is redacted.
- `.gitleaks.toml` extends the default rules. Its allowlist covers only explicit placeholders (`change-me-…`, `test-only-…`, `${ENV_VAR}`).
- `.gitleaksignore` lists the **known historical** R2 credential findings by fingerprint only. They are ignored so CI can block new leaks, but they remain exposed until rotated. Don't add entries without security review.
- Optional local pre-commit check (requires a local gitleaks install):

  ```bash
  printf '#!/bin/sh\nexec gitleaks git --pre-commit --staged --redact --no-banner\n' > .git/hooks/pre-commit
  chmod +x .git/hooks/pre-commit
  ```

If a scan fails: don't push. Remove the secret from the commit (amend or rewrite the unpushed commits) and treat the credential as compromised. If it was already pushed, rotate it immediately.

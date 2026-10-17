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

## Deployment order

1. Set every required variable on the hosting provider **before** deploying a build that contains this change. Otherwise the service will not start.
2. Deploy.
3. Verify the deployment: health endpoint, login, and an item image upload.

## Frontend note

The frontend (`JemawMenus`) has no secrets. Every variable prefixed with `VITE_` is compiled into the public JavaScript bundle and must never contain a credential.

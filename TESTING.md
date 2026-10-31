# Backend testing

## Run the tests

```bash
sh ./mvnw verify
```

Requirements:
- **JDK 21 or newer.** Lombok follows Spring Boot's managed version, which supports current JDKs.
- **Docker.** Integration tests start a throwaway PostgreSQL 16 container through Testcontainers.

No real credentials are needed. The `test` profile (`src/test/resources/application-test.yml`) supplies dummy JWT and R2 values, and the R2 clients are replaced by mocks.

### Without Docker: use an existing PostgreSQL

Point the tests at a running PostgreSQL. **Use an empty, disposable database:** the tests build the schema with Flyway and truncate every table.

```bash
AGAFARI_TEST_EXTERNAL_DB=true \
AGAFARI_TEST_DB_URL=jdbc:postgresql://localhost:5432/agafari_test \
AGAFARI_TEST_DB_USERNAME=postgres \
AGAFARI_TEST_DB_PASSWORD=postgres \
sh ./mvnw verify
```

Without Docker and without these variables, integration tests fail with `Could not find a valid Docker environment`.

## Test types

| Type | How | Examples |
|---|---|---|
| Unit / slice | Plain JUnit or `ApplicationContextRunner`; no database | `config/ConfigurationSecretsTest`, `config/SecretsStartupTest` |
| Integration | Extend `support/AbstractIntegrationTest`: full Spring context, real PostgreSQL, `MockMvc`, mocked `S3Client` and `S3Presigner` | `support/TestInfrastructureSmokeTest`, `AgafariMenuBackendApplicationTests` |

## Writing an integration test

```java
class MyFeatureTest extends AbstractIntegrationTest {

    @Test
    void example() throws Exception {
        var a = data.tenant("alpha");                      // owner + business
        var b = data.tenant("bravo");
        data.item(data.category(data.menu(a, "Lunch"), "Mains"), "Burger");
        data.item(data.category(data.menu(b, "Lunch"), "Mains"), "Pasta");

        mockMvc.perform(get("/api/menus").header("Authorization", data.bearer(a)))
               .andExpect(status().isOk());
    }
}
```

- `TestDataFactory` (`data`) provides `tenant`, `menu` (active/public by default, overloads for inactive or PRIVATE), `category`, `item` (AVAILABLE by default, overload for any status), `menuType`, and `bearer` (Authorization header for a tenant's owner).
- The database is emptied after every test.
- All integration tests share one cached Spring context and one database container. Don't add per-class `@MockitoBean` or `@TestPropertySource` unless it's necessary: each variation starts a new context.
- Stub R2 behavior on the inherited `s3Client` and `s3Presigner` mocks when a test needs it.

## Schema source

Since PO Decision A (2026-10-25), tests build the schema from the Flyway migrations in `src/main/resources/db/migration` and Hibernate starts with `ddl-auto=validate`, so every integration test also proves the migrations match the entities. The test profile never baselines (`spring.flyway.baseline-on-migrate=false`), so an external test database must be empty (or already migrated by an earlier run).

## CI

`.github/workflows/ci.yml` runs `sh ./mvnw -B verify` on JDK 21 for every pull request, every push to `main` or any `feature/**` branch, and on manual dispatch. Test reports are uploaded when the build fails. The Dockerfile still builds with `-DskipTests`, because tests run in CI rather than in the image build.

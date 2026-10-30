# Stalwart SCIM Provisioning Connector

Independent outbound SCIM 2.0 provisioning connector for WSO2 Identity Server 7.3.x and Stalwart Enterprise.

## Project identity

- Connector type: `stalwart-scim`
- Bundle symbolic name: `org.stalwart.identity.provisioning.connector.scim`
- Java package: `org.stalwart.identity.provisioning.connector.scim`
- Version: `1.0.4`
- Runtime baseline: WSO2 Identity Framework 7.10.156.

## Protocol and claim mapping

Uses JDK 21 `HttpClient`, Gson, Bearer authentication and Stalwart's SCIM 2.0 API at `/scim/v2`. Password provisioning is not supported.

| SCIM attribute | Default WSO2 local claim |
|---|---|
| `userName` | `http://wso2.org/claims/emailaddress` |
| `externalId` | `http://wso2.org/claims/username` |
| `name.givenName` | `http://wso2.org/claims/givenname` |
| `name.familyName` | `http://wso2.org/claims/lastname` |
| `active` | Optional Boolean claim; unset by default |
| Additional `emails` aliases | Optional multi-valued claim URI; unset by default |

Stalwart requires `userName` to be a complete email address. Its domain must exist and have SCIM provisioning enabled. Stalwart derives the immutable primary email from `userName`; the connector must not mutate that primary entry through `emails`. Additional addresses can be mapped through the optional multi-valued alias claim. If that claim is absent, the primary email is still derived from `userName`.

Stalwart maps SCIM `active` to the account's `authenticate` permission. `active=false` suspends authentication without deleting the account. For an inverse source claim such as `accountDisabled`, enable **Invert active source value**.

## Reliability and compatibility

- JIT-originated entities are processed by the outbound connector; the JIT marker does not suppress outbound SCIM provisioning.
- Repeated and concurrent user/group create events are reconciled idempotently; a duplicate POST returning 409 is treated as success only after the resource is found on Stalwart.
- Group membership changes are checked against current membership before PATCH, including WSO2 user-store domain prefixes.
- Unchanged replacement-only user PATCH operations are skipped after reading the current resource.
- Bareword equality filter values are normalized to quoted SCIM strings.
- Transient I/O errors and HTTP 408, 429 and 5xx responses are retried up to three attempts with exponential backoff (250 ms, then 500 ms). Duplicate POST responses are reconciled by lookup. This is in-process retry only; it is not a durable queue and cannot guarantee delivery after WSO2 process failure.
- DEBUG logs include resource ID, operation, attempt and response status; credentials and request bodies are not logged.
- The factory returns `List<Property>`; the WSO2 `ProvisionedIdentifier provision(ProvisioningEntity)` signature is retained. The connector inherits the base claim-dialect behavior.

Only explicitly mapped SCIM attributes are sent. Arbitrary unknown attributes are not blindly forwarded; acceptance of unsupported attributes depends on the target schema and implementation.

## Build and installation

GitHub Actions builds with Temurin JDK 21 and Maven. The artifact is named `stalwart-scim-connector-1.0.4`.

Copy the JAR to `<IS_HOME>/repository/components/dropins/`, restart WSO2 IS, and verify OSGi registration of connector type `stalwart-scim`. Compilation and manifest checks do not replace runtime integration tests against the target WSO2 and Stalwart versions.

## License

Apache License 2.0.

# Stalwart SCIM Provisioning Connector

Independent outbound SCIM 2.0 provisioning connector for WSO2 Identity Server 7.3.x and Stalwart Enterprise.

## Project identity

- Connector type: `stalwart-scim`
- Bundle symbolic name: `org.stalwart.identity.provisioning.connector.scim`
- Java package: `org.stalwart.identity.provisioning.connector.scim`
- Version: `1.0.8`
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

## 1.0.8 OSGi activation diagnostics

Version 1.0.8 uses the BND Maven plugin for OSGi metadata and Declarative Services annotation processing. The DS descriptor is generated from the OSGi annotations (no hand-maintained component XML). The build pins the generated descriptor to DS 1.4.0, sets `Bundle-ActivationPolicy: lazy` and `Bundle-StartLevel: 1`, and retains the JavaSE 21 and SCR extender requirements.

The service component has distinct `System.err` markers for static initialization, constructor invocation, activation entry, BundleContext acquisition, factory construction, service registration, activation completion, activation failure and deactivation. This separates the following runtime checkpoints:

1. Bundle installed/resolved/started by Equinox.
2. Component class static initializer entered.
3. DS component instance constructed.
4. DS `activate(ComponentContext)` invoked.
5. WSO2 provisioning factory service registered.

The Maven factory smoke test is a separate executable test with the Maven test classpath. It checks the connector type and all 11 configuration properties, including the default email-to-`userName` and local-username-to-`externalId` mappings. It is deliberately not launched with `java -cp connector.jar`: the distributable bundle does not embed WSO2 API classes, so a standalone invocation without the WSO2 dependency classpath would be an invalid test. The smoke test does not prove runtime DS activation.

GitHub Actions builds with Temurin JDK 21 and Maven, verifies the manifest, DS XML, diagnostic markers, nested Gson JAR, absence of embedded WSO2 classes and absence of JAR signatures. The artifact is named `stalwart-scim-connector-1.0.8`.

Copy the built JAR to `<IS_HOME>/repository/components/dropins/`, restart WSO2 IS, and inspect the WSO2 container logs for the v1.0.8 markers. Compilation, the factory smoke test, and manifest/DS descriptor checks do not replace runtime integration tests against the target WSO2 and Stalwart versions.

## License

Apache License 2.0.

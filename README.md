# Stalwart SCIM Provisioning Connector

Independent outbound provisioning connector for WSO2 Identity Server 7.3.0 and Stalwart Enterprise.

## Project identity

- Connector type: `stalwart-scim`
- Bundle symbolic name: `org.irisadc.identity.provisioning.connector.stalwart.scim`
- Java package: `org.irisadc.identity.provisioning.connector.stalwart.scim`
- Version: `1.0.1`
- WSO2 framework compatibility baseline: identity framework `7.10.156`, matching the application-common and provisioning package exports observed in the target WSO2 IS 7.3.0 runtime.
- The original WSO2 connector's lifecycle dispatch and framework integration were used as a reference. Its legacy SCIM 1.0 HTTP client is **not** used.

## Protocol implementation

The connector implements Stalwart's SCIM 2.0 API directly using JDK 21 `HttpClient` and Gson:

- Base path: `https://<host>/scim/v2`
- Authentication: `Authorization: Bearer <API key>`
- Media type: `application/scim+json`
- User and Group schemas use the RFC 7643 SCIM 2.0 URNs.
- User updates and group membership changes use SCIM PATCH.
- User and group lookup uses SCIM `eq` filters to obtain Stalwart resource IDs before PATCH or DELETE.

The connector does not send passwords. Stalwart's SCIM User schema does not support password provisioning; authentication remains with the identity provider.

## Claim mapping

The connector reads **local WSO2 claim URIs** from the provisioning entity and maps them to the Stalwart SCIM User resource.

| Stalwart SCIM attribute | Default WSO2 local claim |
|---|---|
| `userName` | `http://wso2.org/claims/emailaddress` |
| `externalId` | `http://wso2.org/claims/username` |
| `name.givenName` | `http://wso2.org/claims/givenname` |
| `name.familyName` | `http://wso2.org/claims/lastname` |
| `active` | Optional Boolean claim; unset by default |

Mapping can be disabled. When enabled, the email claim is mandatory; provisioning fails rather than sending the national identifier as Stalwart's `userName`. The active mapping expects a Boolean source claim. If the source is an inverse claim such as `accountDisabled`, enable **Invert active source value** so `accountDisabled=true` becomes SCIM `active=false`.

The WSO2 connector configuration uses text fields for source claim URIs and a Boolean toggle. It does not currently provide a dynamic dropdown populated from the WSO2 claim catalog.

## Build

GitHub Actions builds with Temurin JDK 21 and Maven. The resulting OSGi bundle is uploaded as a workflow artifact named `stalwart-scim-connector-1.0.1`:

`components/org.irisadc.identity.provisioning.connector.stalwart.scim/target/*.jar`

No local Java or Maven installation is required.

## Installation

Do not install until the GitHub Actions build is green and the bundle has been checked against the exact WSO2 IS 7.3.0 runtime. The intended deployment location is:

`<IS_HOME>/repository/components/dropins/`

Restart WSO2 IS and verify that the OSGi service component registers connector type `stalwart-scim`. The official `scim` connector can remain installed; this bundle has a distinct symbolic name, package, and connector type.

## Verification status and limitations

The build validates compilation against the WSO2 identity framework artifacts matching the target runtime package exports. Runtime OSGi resolution, connector registration, and interoperability with a live Stalwart Enterprise server still require integration testing on the target WSO2 IS 7.3.0 instance.

For group-member events supplied as group resources, the connector resolves each member first by `externalId`, then by `userName`. Verify that the source member values correspond to the configured WSO2 username/national-ID or email claims in your deployment. Stalwart group membership is represented by SCIM User resource IDs.

## License

Apache License 2.0. The WSO2 framework integration reference was based on WSO2's SCIM outbound provisioning connector, tag `v5.4.1`.

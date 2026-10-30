# Stalwart SCIM Provisioning Connector

Independent outbound provisioning connector for WSO2 Identity Server 7.3.0 and Stalwart Enterprise.

## Project identity

- Connector type: `stalwart-scim`
- Bundle symbolic name: `org.irisadc.identity.provisioning.connector.stalwart.scim`
- Java package: `org.irisadc.identity.provisioning.connector.stalwart.scim`
- Version: `1.0.0`
- Source baseline: WSO2 SCIM outbound provisioning connector `v5.4.1` (Apache-2.0)

## Claim mapping

The connector reads **local WSO2 claim URIs** from the provisioning entity and maps them to the Stalwart SCIM User resource.

| Stalwart SCIM attribute | Default WSO2 local claim |
|---|---|
| `userName` | `http://wso2.org/claims/emailaddress` |
| `externalId` | `http://wso2.org/claims/username` |
| `name.givenName` | `http://wso2.org/claims/givenname` |
| `name.familyName` | `http://wso2.org/claims/lastname` |
| `active` | Optional; unset by default |

Mapping can be disabled in the connector configuration. The email claim is mandatory when mapping is enabled; provisioning fails clearly if it is absent rather than sending the national identifier as Stalwart's `userName`.

The active mapping expects a Boolean source claim. If your WSO2 account state is represented by an inverse claim such as `accountDisabled`, do not map it directly to `active`; use a non-inverted Boolean claim or implement an explicit inversion rule.

## Build

GitHub Actions builds with Temurin JDK 21 and Maven. The resulting OSGi bundle is uploaded as the workflow artifact:

`components/org.irisadc.identity.provisioning.connector.stalwart.scim/target/*.jar`

No local Java or Maven installation is required to build.

## Installation

Do not install until the GitHub Actions build is green and the generated bundle has been checked against the exact WSO2 IS 7.3.0 runtime. The intended deployment location is:

`<IS_HOME>/repository/components/dropins/`

Restart WSO2 IS and verify that the OSGi service component registers connector type `stalwart-scim`. Keep the official `scim` connector installed; this bundle has a distinct symbolic name, package, and connector type.

## Current scope / verification status

The implementation carries over the upstream user/group provisioning operations, JIT gate, and password handling, with explicit user claim mapping. Runtime interoperability with a live Stalwart server and the WSO2 IS 7.3.0 OSGi container remains to be integration-tested. Group-member events initiated from a group resource still depend on the member identifiers supplied by WSO2; verify that those values are Stalwart `userName` values in your configured outbound group claim mapping.

## License

Apache License 2.0. This derivative includes source from the WSO2 SCIM outbound provisioning connector, tag `v5.4.1`.

# Stalwart SCIM Provisioning Connector

[![Build](https://github.com/hsaeidh/stalwart-scim-connector/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/hsaeidh/stalwart-scim-connector/actions/workflows/build.yml)

An independent outbound SCIM 2.0 provisioning connector for **WSO2 Identity Server 7.3.x** and **Stalwart Enterprise**.

**Current version: 1.0.8**

## Project developers

- **Saeid Hajtalebi** — [saeid.hajtalebi@gmail.com](mailto:saeid.hajtalebi@gmail.com)
- **Ahmad Azizi** — [azizi64a13@gmail.com](mailto:azizi64a13@gmail.com)

## Overview

This connector allows WSO2 Identity Server to provision users and groups to Stalwart through its SCIM 2.0 API. It is designed for deployments where the WSO2 local username is not the user's email address (for example, when the local username is a national identifier), while Stalwart requires an email address as SCIM `userName`.

- Connector type: `stalwart-scim`
- OSGi Bundle-SymbolicName: `org.stalwart.identity.provisioning.connector.scim`
- Java package: `org.stalwart.identity.provisioning.connector.scim`
- Version: `1.0.8`
- Runtime baseline: WSO2 Identity Framework 7.10.156 / WSO2 IS 7.3.x
- Java runtime/build target: Java 21
- SCIM endpoint: `https://<stalwart-host>/scim/v2`
- Authentication: HTTP Bearer token
- Password provisioning: not supported by this connector

## Installation

There are two ways to obtain the connector JAR: build it from source or use the JAR produced by the project's GitHub Actions workflow.

### 1. Prerequisites

For building from source:

- Git
- JDK 21 (the `java` command must use JDK 21)
- Apache Maven
- Network access to the WSO2 public Maven repository and Maven Central

For installation:

- WSO2 Identity Server 7.3.x
- Administrative access to the WSO2 installation or its Docker container
- A reachable Stalwart Enterprise SCIM endpoint
- A Stalwart Bearer token with permission to provision the intended domain

Check the build tools:

```bash
java -version
mvn -version
git --version
```

The Java version reported by both Java and Maven must be 21.

### 2. Build the connector from source

Clone the repository and enter its root directory:

```bash
git clone https://github.com/hsaeidh/stalwart-scim-connector.git
cd stalwart-scim-connector
```

Build the complete project and run its tests:

```bash
mvn -B -ntp clean verify
```

The distributable JAR is generated at:

```text
components/org.stalwart.identity.provisioning.connector.scim/target/org.stalwart.identity.provisioning.connector.scim-1.0.8.jar
```

The Maven build runs the factory/configuration smoke test. GitHub Actions additionally checks the OSGi manifest, Declarative Services descriptor, Java 21 requirement, SCR extender requirement, nested Gson dependency, diagnostic markers, absence of embedded WSO2 API classes and absence of JAR signatures.

A successful Maven build and smoke test do not replace the runtime activation check inside WSO2.

### 3. Prepare Stalwart

Before configuring the WSO2 connection:

1. Make sure the email domain that will be used in SCIM `userName` exists in Stalwart.
2. Enable/configure SCIM provisioning for that domain in Stalwart.
3. Create or obtain a Bearer token authorized to provision the intended domain.
4. Keep the token secret. Do not commit it to source control or place it in logs.
5. Confirm that the WSO2 host/container can reach the Stalwart HTTPS endpoint and that the TLS certificate is trusted.

The Base URL configured in WSO2 must include the SCIM path, for example:

```text
https://mail.example.com/scim/v2
```

Do not append an individual resource path such as `Users` or `Groups` to this Base URL.

### 4. Install into WSO2 Identity Server

The connector is an OSGi bundle JAR. Install it in the WSO2 dropins directory:

```text
<IS_HOME>/repository/components/dropins/
```

#### 4.1 Docker installation

The following example uses the container name `wso2-identity-wso2-1` and the WSO2 home path `/home/wso2carbon/wso2is-7.3.0`. Change these values if your deployment differs.

First, create a backup directory and move older copies of this connector out of `dropins`. Do not leave multiple connector versions in the directory:

```bash
docker exec wso2-identity-wso2-1 sh -c 'D=/home/wso2carbon/wso2is-7.3.0/repository/components/dropins; B=/home/wso2carbon/wso2is-7.3.0/repository/components/backup/stalwart-scim; mkdir -p "$B"; find "$D" -maxdepth 1 -type f -name "org.stalwart.identity.provisioning.connector.scim-*.jar" -exec mv -f {} "$B"/ \\;'
```

Copy the version 1.0.8 JAR from the host into the container. Replace the Windows path with the actual path to the built/downloaded JAR:

```cmd
docker cp "C:\\path\\org.stalwart.identity.provisioning.connector.scim-1.0.8.jar" wso2-identity-wso2-1:/home/wso2carbon/wso2is-7.3.0/repository/components/dropins/
```

Confirm that the JAR exists in the container:

```cmd
docker exec wso2-identity-wso2-1 sh -c "ls -lh /home/wso2carbon/wso2is-7.3.0/repository/components/dropins/org.stalwart.identity.provisioning.connector.scim-1.0.8.jar"
```

Restart WSO2:

```cmd
docker restart wso2-identity-wso2-1
```

#### 4.2 Linux / native installation

On the WSO2 host, back up older versions and copy the new JAR:

```bash
IS_HOME=/path/to/wso2is-7.3.0
DROPINS="$IS_HOME/repository/components/dropins"
BACKUP="$IS_HOME/repository/components/backup/stalwart-scim"

mkdir -p "$BACKUP"
find "$DROPINS" -maxdepth 1 -type f \\
  -name 'org.stalwart.identity.provisioning.connector.scim-*.jar' \\
  -exec mv -f {} "$BACKUP"/ \\;

cp org.stalwart.identity.provisioning.connector.scim-1.0.8.jar "$DROPINS"/
chown wso2carbon:wso2 "$DROPINS/org.stalwart.identity.provisioning.connector.scim-1.0.8.jar"
```

Adjust the owner/group if WSO2 runs under a different operating-system account. Restart WSO2 using the service manager or the deployment's normal startup scripts.

### 5. Verify OSGi bundle activation

Version 1.0.8 prints explicit diagnostic markers to standard error. These markers help identify whether the class was loaded, the Declarative Services component was instantiated, its activation method ran, and the WSO2 provisioning factory was registered.

For Docker:

```cmd
docker logs --since 10m wso2-identity-wso2-1 2>&1 | findstr /I "STALWART-SCIM"
```

For Linux:

```bash
docker logs --since 10m wso2-identity-wso2-1 2>&1 | grep -i 'STALWART-SCIM'
```

For a native WSO2 installation, inspect `<IS_HOME>/repository/logs/wso2carbon.log` and the process standard output/error.

The successful activation sequence should include markers similar to:

```text
>>> STALWART-SCIM STATIC INIT ENTERING v1.0.8
>>> STALWART-SCIM STATIC INIT DONE v1.0.8
>>> STALWART-SCIM CONSTRUCTOR v1.0.8
>>> STALWART-SCIM ACTIVATE START v1.0.8
>>> STALWART-SCIM BundleContext: ...
>>> STALWART-SCIM Factory created: ...
>>> STALWART-SCIM Connector type: stalwart-scim
>>> STALWART-SCIM Service registered: ...
>>> STALWART-SCIM ACTIVATE END v1.0.8 — READY
```

If the sequence stops before the final READY marker, inspect the first missing marker and any accompanying exception. In particular:

- Only `STATIC INIT ENTERING`: class initialization did not complete.
- Constructor marker but no `ACTIVATE START`: investigate Declarative Services component activation.
- `ACTIVATE FAILED`: use the printed stack trace to locate the activation failure.
- `ACTIVATE END` is present but the connector is absent from the WSO2 UI: investigate WSO2 provisioning-factory discovery/registration and the UI's connector listing.

### 6. Configure the outbound provisioning connection in WSO2

After the bundle has activated:

1. Sign in to the WSO2 Identity Server management console.
2. Open **Connections**.
3. Select **New Connection**.
4. Choose the **Outbound Provisioning Connection** flow/type.
5. Select the connector type **Stalwart SCIM** / `stalwart-scim` from the available connector list.
6. Enter the connection properties described below.
7. Save the connection and associate it with the required outbound provisioning configuration/identity provider according to your WSO2 deployment.

The exact labels around the connection wizard can vary slightly with the WSO2 console configuration, but the connector type identifier is `stalwart-scim`.

#### 6.1 Connector properties

| Property name | Required | Default / example | Purpose |
|---|---:|---|---|
| `scim-base-url` | Yes | `https://mail.example.com/scim/v2` | Stalwart SCIM API base URL |
| `scim-bearer-token` | Yes | Secret token | Bearer token used for SCIM requests; confidential field |
| `scim-user-store-domain` | No | Empty | WSO2 user-store domain name, when needed |
| `enable-claim-mapping` | No | `true` | Enables the connector's explicit WSO2-claim to SCIM mapping |
| `username-claim-uri` | Yes | `http://wso2.org/claims/emailaddress` | WSO2 local claim used for SCIM `userName` |
| `external-id-claim-uri` | No | `http://wso2.org/claims/username` | WSO2 local claim used for SCIM `externalId` |
| `given-name-claim-uri` | No | `http://wso2.org/claims/givenname` | Source claim for `name.givenName` |
| `family-name-claim-uri` | No | `http://wso2.org/claims/lastname` | Source claim for `name.familyName` |
| `active-claim-uri` | No | Empty | Optional Boolean source claim for SCIM `active` |
| `email-aliases-claim-uri` | No | Empty | Optional multi-valued claim for additional email aliases |
| `invert-active-claim` | No | `false` | Inverts the Boolean source value when mapping SCIM `active` |

The Bearer token is required. Do not use a user's password as the SCIM token.

#### 6.2 Important username mapping

The connector's default mapping is intentionally different from WSO2's default username mapping:

| WSO2 local claim | SCIM attribute | Default |
|---|---|---|
| `http://wso2.org/claims/emailaddress` | `userName` | Yes |
| `http://wso2.org/claims/username` | `externalId` | Yes |
| `http://wso2.org/claims/givenname` | `name.givenName` | Yes |
| `http://wso2.org/claims/lastname` | `name.familyName` | Yes |

For example, if the WSO2 local username is a national identifier such as `1234567890`, the connector sends the user's email address as SCIM `userName` and the local username as `externalId`.

Before provisioning, make sure:

- The configured `username-claim-uri` exists in the WSO2 local claim dialect.
- The user has a non-empty value for that claim.
- The value is a complete email address, including its domain.
- The corresponding email domain exists in Stalwart and is enabled for SCIM provisioning.
- WSO2 is allowed to read/retrieve the mapped local claims for outbound provisioning.

If you change the claim URIs in the connection, use the exact local claim URI, not only the display name of the claim.

#### 6.3 Optional account status and email aliases

- Leave `active-claim-uri` empty if you do not want the connector to set the SCIM `active` attribute.
- If configured, the source claim must contain Boolean `true` or `false`.
- Stalwart maps SCIM `active=false` to disabling the account's `authenticate` permission; it does not delete the account.
- For an inverse source claim such as `accountDisabled`, set `invert-active-claim=true`.
- Leave `email-aliases-claim-uri` empty unless you have a multi-valued local claim containing additional email aliases.
- Stalwart derives the primary email address from SCIM `userName`; do not attempt to replace that primary address through the additional `emails` aliases mapping.

### 7. Test provisioning

After saving the connection and enabling it in the relevant WSO2 outbound provisioning configuration:

1. Use a test user whose email claim is populated and whose email domain is configured in Stalwart.
2. Trigger the relevant outbound provisioning event from WSO2 (for example, create/update the user or initiate the configured provisioning operation).
3. Check the WSO2 logs for SCIM response status and any provisioning exception.
4. Confirm that the user appears in the intended Stalwart domain and that SCIM `userName` is the email address, while `externalId` contains the WSO2 local username.
5. If group provisioning is configured, test group creation and membership changes separately.

The connector handles user/group provisioning operations supported by its implementation. It does **not** provision or synchronize user passwords.

## Claim mapping reference

| SCIM attribute | Default WSO2 local claim |
|---|---|
| `userName` | `http://wso2.org/claims/emailaddress` |
| `externalId` | `http://wso2.org/claims/username` |
| `name.givenName` | `http://wso2.org/claims/givenname` |
| `name.familyName` | `http://wso2.org/claims/lastname` |
| `active` | Optional Boolean claim; unset by default |
| Additional `emails` aliases | Optional multi-valued claim URI; unset by default |

Stalwart derives the immutable primary email from SCIM `userName`. Additional addresses can be mapped through the optional multi-valued alias claim. Only explicitly mapped attributes are sent; arbitrary unknown attributes are not blindly forwarded.

## Reliability and compatibility

- JIT-originated entities are processed by the outbound connector; the JIT marker does not suppress outbound SCIM provisioning.
- Repeated and concurrent user/group create events are reconciled idempotently; a duplicate POST returning HTTP 409 is treated as success only after the resource is found on Stalwart.
- Group membership changes are checked against current membership before PATCH, including WSO2 user-store domain prefixes.
- Unchanged replacement-only user PATCH operations are skipped after reading the current resource.
- Bareword equality filter values are normalized to quoted SCIM strings.
- Transient I/O errors and HTTP 408, 429 and 5xx responses are retried up to three attempts with exponential backoff (250 ms, then 500 ms). This is in-process retry only; it is not a durable queue and cannot guarantee delivery after WSO2 process failure.
- DEBUG logs include resource ID, operation, attempt and response status; credentials and request bodies are not logged.
- The factory returns `List<Property>`; the WSO2 `ProvisionedIdentifier provision(ProvisioningEntity)` signature is retained. The connector inherits the base claim-dialect behavior.

## Build and CI validation

Build with JDK 21 and Maven:

```bash
mvn -B -ntp clean verify
```

The build runs the factory smoke test, which checks the connector type and all 11 configuration properties, including the default email-to-`userName` and local-username-to-`externalId` mappings. It runs with the Maven test classpath because WSO2 API classes are not embedded in the distributable bundle.

The GitHub Actions workflow additionally verifies:

- Bundle version, symbolic name and JavaSE 21 requirement.
- Exactly one SCR extender requirement and the generated Declarative Services descriptor.
- WSO2 package import version ranges.
- Bundle start level and lazy activation policy.
- Static initialization, constructor and activation diagnostic markers.
- Nested Gson dependency.
- No embedded `org/wso2` classes and no JAR signature files.

These build checks do not replace runtime verification in the target WSO2 installation.

## License

Apache License 2.0.

## Project developers

- **Saeid Hajtalebi** — [saeid.hajtalebi@gmail.com](mailto:saeid.hajtalebi@gmail.com)
- **Ahmad Azizi** — [azizi64a13@gmail.com](mailto:azizi64a13@gmail.com)

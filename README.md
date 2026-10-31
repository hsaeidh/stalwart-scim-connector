# Stalwart SCIM Provisioning Connector

[![Build](https://github.com/hsaeidh/stalwart-scim-connector/actions/workflows/build.yml/badge.svg?branch=release/2.0.0)](https://github.com/hsaeidh/stalwart-scim-connector/actions/workflows/build.yml)

An outbound SCIM 2.0 provisioning connector for WSO2 Identity Server 7.3.x and Stalwart Enterprise.

**Development version: 2.0.0**  
**Branch: release/2.0.0**  
**Connector type: stalwart-scim**

Version 2.0.0 retains the provisioning and activation work from 1.0.9 and introduces an immutable email identity policy. It also makes user-name updates use Stalwart's supported structured-name input and documents the resulting display-name behavior.

## 1. What the connector does

The connector sends user and group lifecycle events from WSO2 Identity Server to Stalwart through the SCIM 2.0 API.

It is intended for deployments where the WSO2 local username is not the email address. For example:

- WSO2 local username: a national identifier such as 1234567890
- WSO2 emailaddress claim: person@example.com
- Stalwart SCIM userName: person@example.com
- Stalwart SCIM externalId: 1234567890

The email address is used only when a Stalwart account is first created. The WSO2 local username, mapped to SCIM externalId, is the stable cross-system identifier used to find the account later.

### Important Stalwart name behavior

Stalwart stores one display name, not separate given-name and family-name fields. It accepts the standard SCIM name object and, when neither displayName nor name.formatted is supplied, joins name.givenName and name.familyName into the stored display name. Stalwart returns the result as displayName and name.formatted; it does not return the original structured name parts.

Accordingly, version 2.0.0 sends a SCIM PATCH of the name object containing both configured name claims. In Stalwart, the observable result is an updated display name such as “Saeed Mehdi”, not separately retrievable givenName and familyName attributes. This is Stalwart's data model, not a connector limitation that can be fixed by changing claim names.

## 2. Version 2.0.0 changes

Version 2.0.0 retains the 1.0.9 implementation, including:

- Idempotent user and group creation.
- Stable resource lookup by externalId before falling back to userName.
- Group membership reconciliation that checks current membership before PATCH.
- Removal from the configured email-access group disables Stalwart authentication through SCIM active=false.
- The email-group removal rule is applied after normal attribute updates, so an active claim in the same event cannot re-enable the account.
- Retry handling for transient I/O errors and HTTP 408, 429 and 5xx responses.
- WSO2 JIT-originated events are not incorrectly suppressed by the outbound connector.
- OSGi Declarative Services registration and activation diagnostics.

New or changed in 2.0.0:

1. **Primary email is immutable after account creation.** The connector sends userName only in POST /Users. It never includes userName in a user PATCH.
2. **Email changes in WSO2 are ignored for existing Stalwart accounts.** They do not rename the Stalwart account or change its primary SMTP address.
3. **Email alias synchronization is disabled.** The connector does not send the emails collection on create or update, and does not add or remove Stalwart aliases based on WSO2 email changes.
4. **Name updates use the SCIM name object.** When both configured name claims are present in a WSO2 update event, the connector sends them together as name.givenName and name.familyName in one name object. Stalwart composes these into displayName/name.formatted.
5. **Partial name events are handled conservatively.** If only one of the configured name claims is present, the connector skips that name update rather than accidentally replacing a complete display name with only a first or last name. Ensure WSO2 supplies both mapped claims in update events.
6. **externalId is required when creating a user.** It must be a stable identifier and must not be changed after account creation. This is necessary so a changed WSO2 email address cannot cause the connector to lose track of the existing Stalwart account.
7. The email-alias claim property has been removed from the connector configuration. Existing Stalwart aliases are left untouched by the connector.

Version 1.0.8 is not modified by this branch. Version 1.0.9 is also not modified by this branch.

## 3. Provisioning behavior and lifecycle scenarios

### 3.1 Create a user

For a WSO2 user creation event, the connector:

1. Reads the configured email claim and validates that it is a full email address.
2. Reads the configured stable externalId claim. If it is empty, creation fails rather than creating an account that cannot be found safely after an email change.
3. Sends POST /Users with:
   - userName = the initial email address;
   - externalId = the stable WSO2 identifier;
   - name.givenName and name.familyName when available;
   - active only when an active claim is configured.
4. Does not send emails aliases.
5. Reconciles the user's configured WSO2 group memberships after the account exists.

If an account with the same externalId already exists, the connector reuses it instead of creating a duplicate and proceeds with the update/membership reconciliation path.

### 3.2 Change first name or last name

For an update event, the connector looks up the existing Stalwart user by externalId and sends a PATCH containing the SCIM name object when both mapped name claims are present.

Example request body:

~~~json
{
  "schemas": [
    "urn:ietf:params:scim:api:messages:2.0:PatchOp"
  ],
  "Operations": [
    {
      "op": "replace",
      "path": "name",
      "value": {
        "givenName": "Saeed",
        "familyName": "Mehdi"
      }
    }
  ]
}
~~~

Stalwart stores the joined display name and returns it as displayName and name.formatted. It does not retain the structured parts as separate readable attributes.

If the WSO2 event contains only one of the two configured claims, the connector skips the name update. This avoids overwriting a full name with an incomplete value. Check WSO2's outbound claim set if this warning appears.

### 3.3 Change the email address in WSO2

The existing Stalwart account is located using externalId. The connector does not PATCH userName, so:

- Stalwart userName remains the original address.
- The primary SMTP address remains the original address.
- The mailbox/account is not renamed by the connector.
- Existing aliases are not changed.
- The new WSO2 email value is not added to Stalwart.

**OIDC/JMAP/Bulwark integration warning:** keeping the Stalwart address fixed is only one part of preserving access. The identity claim that Stalwart uses to resolve an OIDC login must continue to identify the same Stalwart account. If WSO2 changes the email claim used as Stalwart's OIDC account identifier to a new address, authentication/account resolution may no longer match the existing Stalwart account. Keep the OIDC account identifier aligned with the original Stalwart primary address, or implement and verify a stable identifier mapping in the OIDC directory configuration. The SCIM connector does not change OIDC token claims.

### 3.4 Add, remove, or change another email address in WSO2

The connector does not send the SCIM emails collection. Adding, removing, or changing an additional WSO2 email address does not add or remove a Stalwart alias. Existing Stalwart aliases remain under Stalwart administrator control.

### 3.5 Change the WSO2 username / externalId

The externalId is the stable link between the WSO2 user and the Stalwart account. Do not change it after provisioning. If the externalId is changed upstream, the connector may no longer be able to locate the original Stalwart account. A changed email address is deliberately not used as a substitute for the stable identifier.

### 3.6 Remove a user from the WSO2 Email group

When a user is removed from the configured email-access group (default: Email), the connector sends a SCIM PATCH with active=false.

In Stalwart, active=false disables the account's authenticate permission. It does not delete the account or its mailbox data. The account can continue to receive mail unless the relevant mail permissions are separately changed in Stalwart.

This email-group removal rule is applied last. Therefore, if the same WSO2 event contains active=true, the group-removal rule still leaves the Stalwart account unable to authenticate.

Adding a user to the Email group does not automatically reactivate an account that was previously disabled. Reactivation must be sent through the configured active claim or performed administratively.

### 3.7 Change the configured active claim

If active-claim-uri is configured, a Boolean true/false claim is mapped to SCIM active. If invert-active-claim=true, the source Boolean is inverted before it is sent.

An invalid value other than true or false causes a provisioning error. Leave active-claim-uri empty if the connector should not synchronize account activation except for removal from the Email group.

### 3.8 Change membership in other WSO2 groups

For groups other than the configured email-access group:

- Added membership is reconciled in the matching Stalwart SCIM Group.
- Removed membership is removed from the matching Stalwart SCIM Group.
- The connector checks the current membership before sending a PATCH, making repeated events safe.
- User resolution prefers externalId, so membership events do not depend on a mutable email address.

The corresponding Stalwart Group must already exist or be provisioned through the configured group lifecycle.

### 3.9 Create, update, and delete groups

- Group creation sends displayName, externalId and resolvable members.
- If the group already exists, the connector reconciles membership instead of creating a duplicate.
- Group updates reconcile renamed display names and membership changes.
- Group deletion sends SCIM DELETE for the matching group.
- Stalwart places SCIM-created groups in the provisioning account's domain. The provisioning account must therefore belong to the intended tenant/domain and that domain must be enabled for SCIM.

### 3.10 Delete a WSO2 user

A WSO2 user DELETE event results in SCIM DELETE /Users/{id}. This is destructive: Stalwart documents that deleting a user through SCIM removes the account and schedules deletion of its data. It is not equivalent to suspending the account.

If the desired offboarding policy is to preserve mailboxes, do not send user deletion events to this connector. Use the Email-group removal/active=false suspension path instead and manage retention separately.

### 3.11 Passwords and authentication

The connector does not provision passwords. Stalwart SCIM does not store the password attribute; user authentication remains the responsibility of the configured identity provider (for example, WSO2 through OIDC).

### 3.12 Retries and idempotency

- Transient I/O failures and HTTP 408, 429 and 5xx responses are retried up to three attempts with exponential backoff (250 ms, then 500 ms).
- A duplicate POST returning HTTP 409 is treated as success only after the connector confirms that the resource now exists.
- A repeated DELETE for an already absent resource is treated as successful.
- Unchanged replacement-only PATCH operations are skipped after reading the current resource.
- Retries are in-process only. They are not a durable queue and cannot guarantee delivery if WSO2 stops while an event is being processed.

## 4. Requirements

### Build requirements

- JDK 21
- Apache Maven
- Git
- Network access to Maven Central and the WSO2 public Maven repository

### Runtime requirements

- WSO2 Identity Server 7.3.x
- Stalwart Enterprise with SCIM enabled
- HTTPS connectivity from WSO2 to Stalwart
- A Stalwart SCIM provisioning API key (Bearer token)
- The WSO2 local emailaddress and username claims must be available to outbound provisioning
- The configured externalId claim must be stable and non-empty
- Both given-name and family-name claims should be present in user update events

## 5. Prepare Stalwart

Stalwart SCIM is an Enterprise feature. The endpoint is:

~~~text
https://mail.example.com/scim/v2
~~~

Do not append /Users or /Groups to the Base URL configured in WSO2.

Before connecting WSO2:

1. Confirm the Stalwart Enterprise license is active.
2. Create the mail domain in Stalwart.
3. Enable SCIM provisioning on the domain by setting allowScimProvisioning=true.
4. Create a dedicated SCIM provisioning account. Do not reuse a general administrator account.
5. Place the provisioning account in the tenant/domain it will manage. SCIM requests are tenant-scoped.
6. Give the provisioning account the User role and the SCIM/account-management permissions required by Stalwart: scimAccess, sysAccountGet, sysAccountCreate, sysAccountUpdate and sysAccountDestroy. The provisioning account must also hold the permissions of the accounts it manages.
7. Create an API key for the provisioning account and use its secret as the Bearer token in WSO2. Stalwart SCIM advertises Bearer authentication; HTTP Basic authentication is not supported.
8. Ensure the TLS certificate is trusted by the WSO2 JVM.
9. If Stalwart is behind a reverse proxy, ensure its public URL is configured correctly so SCIM resource locations point to the externally reachable host.

The API key should inherit the provisioning account's permissions unless there is a deliberate, validated reason to use a more restrictive permission mode. Do not remove permissions required by managed users: Stalwart checks that the provisioning principal is authorized to grant the permissions of the accounts it manages.

### Verify Stalwart before configuring WSO2

Discovery endpoint (no bearer token required):

~~~bash
curl -i https://mail.example.com/scim/v2/ServiceProviderConfig
~~~

Authenticated resource endpoint:

~~~bash
curl -i \
  -H "Authorization: Bearer YOUR_SCIM_TOKEN" \
  -H "Accept: application/scim+json" \
  "https://mail.example.com/scim/v2/Users?count=1"
~~~

The first request checks endpoint routing, TLS and SCIM availability. The second checks the token and the provisioning account's permissions. A 401 usually indicates a missing/invalid token; a 403 indicates licensing or permission problems. Inspect the SCIM error detail and Stalwart events for the exact cause.

## 6. Connector configuration in WSO2

The connector exposes 11 connection properties. Configure them in the WSO2 management console when creating the outbound provisioning connection of type stalwart-scim.

| Property | Required | Default / example | Description |
|---|---:|---|---|
| scim-base-url | Yes | https://mail.example.com/scim/v2 | Stalwart SCIM Base URL |
| scim-bearer-token | Yes | Secret token | Stalwart provisioning API key; confidential |
| scim-user-store-domain | No | Empty | WSO2 user-store domain name, when required |
| enable-claim-mapping | No | true | Enables explicit WSO2 local-claim mapping |
| username-claim-uri | Yes | http://wso2.org/claims/emailaddress | Initial Stalwart userName source; used on create and fallback lookup only |
| external-id-claim-uri | Yes | http://wso2.org/claims/username | Stable WSO2 identifier sent as externalId; must not change |
| given-name-claim-uri | No | http://wso2.org/claims/givenname | Source for SCIM name.givenName |
| family-name-claim-uri | No | http://wso2.org/claims/lastname | Source for SCIM name.familyName |
| active-claim-uri | No | Empty | Optional Boolean claim for SCIM active |
| invert-active-claim | No | false | Invert the Boolean active claim |
| email-membership-group | No | Email | WSO2 group whose removal disables Stalwart authentication |

The connector uses the following default mapping:

| WSO2 local claim | SCIM attribute | Purpose |
|---|---|---|
| http://wso2.org/claims/emailaddress | userName | Initial primary email address at account creation |
| http://wso2.org/claims/username | externalId | Stable identifier for future lookups |
| http://wso2.org/claims/givenname | name.givenName | First component of the Stalwart display name |
| http://wso2.org/claims/lastname | name.familyName | Second component of the Stalwart display name |

Make sure these are the exact local claim URIs available in your WSO2 user-store claim dialect. The claim display label is not a substitute for the URI. WSO2 must include both configured name claims in user update events for the connector to update the complete Stalwart display name.

### Configure the outbound connection

1. Sign in to the WSO2 management console.
2. Open Connections and create a new outbound provisioning connection.
3. Select Stalwart SCIM (connector type stalwart-scim).
4. Enter the Base URL and Bearer token.
5. Confirm the emailaddress and username claim URIs.
6. Confirm the givenname and lastname claim URIs.
7. Set the Email group name if it differs from the default.
8. Save the connection and associate it with the required outbound provisioning configuration.

The connector factory returns a List<Property> and is registered as an AbstractProvisioningConnectorFactory OSGi service. Do not rename the connector type, factory service interface, bundle symbolic name, Declarative Services component name, or exported package names when customizing the project.

## 7. TOML and deployment configuration

### WSO2 deployment.toml

No connector-specific deployment.toml section is required to make this connector appear in WSO2. The connector is discovered as an OSGi bundle from:

~~~text
<IS_HOME>/repository/components/dropins/
~~~

The SCIM URL, token and claim mappings are connection properties saved through the WSO2 management console. Do not add guessed connector keys to deployment.toml; unsupported keys do not register an OSGi provisioning factory.

If your deployment uses a customized WSO2 configuration that disables OSGi Declarative Services or changes provisioning service loading, review that runtime configuration separately. The connector's bundle/DS metadata must remain intact.

### Stalwart configuration files

Current Stalwart configuration is primarily stored as configuration objects in its data store and managed through the WebUI, CLI or JMAP management API. The local config.json file identifies the data store; SCIM domain authorization is configured on the Domain object with allowScimProvisioning=true. There is no connector-specific Stalwart TOML block to add for SCIM.

Do not put the SCIM Bearer token in a public repository or an example configuration file. Store it only in the confidential WSO2 connection property and protect backups of WSO2 configuration/database data.

## 8. Install the OSGi bundle

### Build from source

Clone the repository and select the 2.0.0 branch:

~~~bash
git clone https://github.com/hsaeidh/stalwart-scim-connector.git
cd stalwart-scim-connector
git checkout release/2.0.0
~~~

Build and run the project verification:

~~~bash
mvn -B -ntp clean verify
~~~

The distributable bundle is:

~~~text
components/org.stalwart.identity.provisioning.connector.scim/target/org.stalwart.identity.provisioning.connector.scim-2.0.0.jar
~~~

### Install in Docker WSO2

The following commands use the container name wso2-identity-wso2-1 and WSO2 home /home/wso2carbon/wso2is-7.3.0. Adjust them for your deployment.

Back up and move older copies of this connector out of dropins so WSO2 does not load multiple versions:

~~~cmd
docker exec wso2-identity-wso2-1 sh -c "D=/home/wso2carbon/wso2is-7.3.0/repository/components/dropins; B=/home/wso2carbon/wso2is-7.3.0/repository/components/backup/stalwart-scim; mkdir -p $B; find $D -maxdepth 1 -type f -name 'org.stalwart.identity.provisioning.connector.scim-*.jar' -exec mv -f {} $B/ ;"
~~~

Copy the built JAR from the Windows host:

~~~cmd
docker cp "C:\path\org.stalwart.identity.provisioning.connector.scim-2.0.0.jar" wso2-identity-wso2-1:/home/wso2carbon/wso2is-7.3.0/repository/components/dropins/
~~~

Check the JAR:

~~~cmd
docker exec wso2-identity-wso2-1 sh -c "ls -lh /home/wso2carbon/wso2is-7.3.0/repository/components/dropins/org.stalwart.identity.provisioning.connector.scim-2.0.0.jar"
~~~

Restart WSO2:

~~~cmd
docker restart wso2-identity-wso2-1
~~~

### Install on Linux / native WSO2

~~~bash
IS_HOME=/path/to/wso2is-7.3.0
DROPINS="$IS_HOME/repository/components/dropins"
BACKUP="$IS_HOME/repository/components/backup/stalwart-scim"

mkdir -p "$BACKUP"
find "$DROPINS" -maxdepth 1 -type f \
  -name 'org.stalwart.identity.provisioning.connector.scim-*.jar' \
  -exec mv -f {} "$BACKUP"/ \;

cp components/org.stalwart.identity.provisioning.connector.scim/target/org.stalwart.identity.provisioning.connector.scim-2.0.0.jar "$DROPINS"/
chown wso2carbon:wso2 "$DROPINS/org.stalwart.identity.provisioning.connector.scim-2.0.0.jar"
~~~

Restart WSO2 using the normal service manager or startup procedure.

## 9. Verify OSGi activation and connector discovery

The OSGi bundle must be loaded and the Declarative Services component must register the factory. Version 2.0.0 keeps the diagnostic markers so the activation sequence can be checked.

Docker:

~~~cmd
docker logs --since 10m wso2-identity-wso2-1 2>&1 | findstr /I "STALWART-SCIM"
~~~

Linux:

~~~bash
grep -i 'STALWART-SCIM' /path/to/wso2is-7.3.0/repository/logs/wso2carbon.log
~~~

The successful sequence should include:

~~~text
>>> STALWART-SCIM STATIC INIT ENTERING v2.0.0
>>> STALWART-SCIM STATIC INIT DONE v2.0.0
>>> STALWART-SCIM CONSTRUCTOR v2.0.0
>>> STALWART-SCIM ACTIVATE START v2.0.0
>>> STALWART-SCIM Factory created: ...
>>> STALWART-SCIM Connector type: stalwart-scim
>>> STALWART-SCIM Service registered: ...
>>> STALWART-SCIM ACTIVATE END v2.0.0 — READY
~~~

Interpretation:

- No STATIC INIT marker: the component class was not loaded; inspect bundle resolution and class-loading errors.
- STATIC INIT ENTERING but no STATIC INIT DONE: inspect the first initialization exception.
- Constructor but no ACTIVATE START: inspect Declarative Services activation and SCR logs.
- ACTIVATE FAILED: use the printed stack trace; do not proceed to connection configuration.
- ACTIVATE END ... READY but connector is not listed: inspect WSO2 provisioning factory discovery, the service registration and the console's connection-type list.

After activation, open Connections, create an outbound provisioning connection and verify that Stalwart SCIM / stalwart-scim appears in the connector type list.

Do not try to fix a missing connector by renaming the service component or adding guessed deployment.toml properties. The OSGi service registration, DS descriptor and WSO2 API package imports are part of the connector's discovery contract.

## 10. Troubleshooting

| Symptom | Checks |
|---|---|
| Connector type is missing in WSO2 | Confirm only one connector JAR is in dropins; check bundle resolution, DS descriptor, SCR extender, Java 21 requirement and the READY marker |
| Base URL validation fails | Use an absolute HTTP(S) URL ending exactly in /scim/v2; do not include /Users, credentials, query or fragment |
| HTTP 401 | Verify the Bearer token; Basic authentication is not supported |
| HTTP 403 | Verify Enterprise licensing, allowScimProvisioning, tenant scope, scimAccess and account-management permissions |
| HTTP 404 on user update | Verify externalId mapping and confirm that the Stalwart account has the same externalId |
| User creation fails because externalId is empty | Ensure the configured WSO2 local username claim is included and has a non-empty value |
| Email change in WSO2 does not change Stalwart | Expected in 2.0.0; primary userName and SMTP address are immutable after creation |
| Name update is skipped | Check logs for the partial-name warning; make sure both givenname and lastname claims are included in the outbound event |
| Name appears as one field in Stalwart | Expected; Stalwart stores displayName/name.formatted rather than separate structured name parts |
| Group membership update fails | Confirm the Stalwart Group exists and that the WSO2 user can be resolved by externalId |
| Email group removal does not work | Verify the configured email-membership-group name and the WSO2 deleted-group event |
| SCIM user deletion removed data | DELETE is destructive; use active=false suspension instead when mailbox retention is required |

## 11. Build and CI validation

Build with JDK 21:

~~~bash
mvn -B -ntp clean verify
~~~

The build runs the factory/configuration smoke test. GitHub Actions additionally verifies:

- Bundle version 2.0.0 and symbolic name.
- JavaSE 21 requirement.
- Exactly one OSGi SCR extender requirement.
- Generated Declarative Services descriptor and immediate activation method.
- WSO2 provisioning package import ranges.
- Bundle start level and lazy activation policy.
- Version 2.0.0 static initialization, constructor and activation diagnostic markers.
- Nested Gson dependency.
- No embedded WSO2 API classes.
- No JAR signature files.

A successful Maven build and metadata checks do not replace runtime activation testing inside the target WSO2 IS 7.3.0 instance. A real provisioning test is still required to verify WSO2's actual outbound claim payload and Stalwart's response.

## 12. License and developers

Apache License 2.0.

- Saeid Hajtalebi
- Ahmad Azizi

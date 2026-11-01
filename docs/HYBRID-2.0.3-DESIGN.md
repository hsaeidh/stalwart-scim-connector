# Hybrid provisioning and SCIM payload contract (2.0.3)

## Status

This document is the implementation contract for the 2.0.3 development line. It is not a claim that Hybrid queueing, event ingestion, or CSV-import coverage is already implemented. Those capabilities remain gated on the persistence and WSO2 runtime checks below.

## Compatibility and modes

- `INDIVIDUAL` remains the default and must preserve the 2.0.1/2.0.2 synchronous behavior.
- `BULK` remains the existing synchronous one-operation Bulk transport mode.
- `HYBRID` is a separate opt-in mode. It is not an alias for `BULK`.
- In `HYBRID`, user creation is synchronous and Individual. Profile changes are coalesced in a durable outbox and flushed in multi-operation SCIM Bulk requests every five minutes. Non-profile mutations (active state, Email entitlement, distribution-group membership, deletion) retain their existing synchronous semantics unless a separately reviewed change explicitly says otherwise.
- A create must not be acknowledged until Stalwart has confirmed it or a safe reconciliation has proven that the same stable `externalId` already exists.

## Event ingress and duplicate prevention

WSO2 event subscriptions of interest are `POST_ADD_USER` and `POST_SET_USER_CLAIMS`. The WSO2 event contract exposes properties including the user name, tenant domain, and user-store manager. After a claims event, the observer/dispatcher should use the supplied user-store context to read the authoritative current FIRSTNAME, LASTNAME, and DISPLAYNAME values; the event payload itself must not be assumed to contain a complete profile. The event handler must initially run in observation mode in a real IS 7.3.0 test environment. It must log event type and a privacy-safe correlation marker, not claim values, passwords, tokens, or full user records.

Do not let the event handler independently call Stalwart while the outbound connector also handles the same mutation. Both ingress paths, if both are eventually required, must call one dispatcher/outbox and share a deterministic idempotency key. Until CSV-import behavior is proven, the existing outbound connector remains authoritative.

The event-handler bundle must be separately versioned and registered through WSO2's supported event-handler service contract. It must not be silently bundled into the provisioning connector unless its API and lifecycle dependencies are verified against WSO2 IS 7.3.0.

## Durable queue requirements

An in-memory queue is prohibited: it loses accepted updates on restart and cannot coordinate multiple IS nodes.

Before enabling `HYBRID`, select and implement a transactional durable store that:
- survives JVM and host restart;
- supports atomic enqueue/coalesce/claim/acknowledge operations;
- prevents two scheduler instances from flushing the same row concurrently;
- scopes records by SCIM tenant/base URL, WSO2 organization, user-store domain, and immutable externalId;
- stores only the latest intended profile state and a stable operation/idempotency key;
- supports retry with bounded backoff and a dead-letter/error state;
- never retries an ambiguous non-idempotent operation blindly;
- is configurable and documented for backup, retention, and multi-node deployment.

A local file queue is not an acceptable default for a clustered WSO2 deployment. Do not enable HYBRID until the selected storage is proven transaction-safe for the target topology.

## SCIM User payload contract

### Create: POST /Users

Send only the attributes needed to create the Stalwart account:
- `schemas`: exactly the SCIM core User schema URI;
- `userName`: the configured primary email claim, validated as a full email address;
- `externalId`: the configured immutable WSO2 identifier, required and non-empty;
- `displayName`: only when a non-empty configured displayName or usable name input is available;
- `active`: only when an explicitly configured Boolean active claim is present and valid.

Do not send `emails`, password, phone numbers, address, photos, locale, timezone, arbitrary WSO2 claims, group names, or empty/null attributes. Do not send `meta`, `id`, or server-managed attributes on create. The primary email is represented by `userName`; alias synchronization is out of scope.

### Profile update: PATCH /Users/{id}

Use the SCIM PatchOp schema and only `replace` operations for supported profile attributes. In the current Stalwart mapping the supported profile field is `displayName`. Resolve it from the explicit displayName claim first, otherwise compose it from both givenName and familyName when both are present.

Never include `userName`, `externalId`, `emails`, `password`, `groups`, `active`, or arbitrary claims in a profile-update payload. Empty values are not sent as replacements; partial name input must not erase or truncate a complete display name. Do not emit a PATCH document with an empty Operations array.

The trigger condition is OR, not AND: a change to FIRSTNAME, LASTNAME, or DISPLAYNAME individually must be detected. Do not infer the changed field merely from whichever values happen to be present in a partial event. The dispatcher must obtain the complete current authoritative name snapshot from WSO2 (or receive an explicitly complete snapshot) and retain change-origin metadata:
- if DISPLAYNAME itself changed, use its current non-empty value;
- if FIRSTNAME or LASTNAME changed, compose the display name from the latest complete FIRSTNAME and LASTNAME values, even if an unchanged/stale DISPLAYNAME claim is also present;
- if a complete safe value cannot be obtained, do not send a destructive partial replacement; keep the item pending with a diagnostic state.

Coalesce repeated profile changes for one account into one latest desired value. Deduplicate operations by SCIM path so one Bulk request never contains multiple conflicting writes to the same user/path. If the existing outbound provisioning callback does not expose which claim changed, the event observer/dispatcher must reconcile the current WSO2 claim snapshot rather than guessing from attribute presence.

### Active state and group membership

- Email entitlement is represented by `User.active`; it is not a SCIM Group and must never be sent as a Stalwart Group membership.
- Other distribution-group membership changes remain explicit Group resource PATCH operations, with stable Stalwart user IDs.
- Do not mix profile updates with active-state or membership operations in the same coalescing bucket.
- Do not send unchanged or duplicate group membership deltas when the connector can establish that no change is needed.

## SCIM Bulk contract

- Use the RFC 7644 BulkRequest schema and one operation object per queued user profile update.
- Each operation must carry method `PATCH`, a properly encoded `/Users/{id}` path, and a PatchOp body.
- No cross-operation `bulkId` references are needed for independent updates to existing users.
- Enforce both the configured payload byte ceiling and the server's documented operation-count ceiling before sending.
- Parse every operation result independently. A successful HTTP envelope does not imply every operation succeeded.
- Acknowledge only successful operations. Retain failed operations with their individual status and response detail; do not replay successful siblings.
- Treat an ambiguous network outcome as unknown. Reconcile the current Stalwart profile before retrying; do not blindly replay a batch whose commit status is unknown.
- Bulk endpoint fallback is permitted only for definitive unsupported-endpoint responses (404/405/501), and fallback state is scoped to the exact SCIM base URL/tenant.

## Identity, tenant isolation, and SCIM correctness

- `externalId` is immutable after creation and is the authoritative cross-system key.
- `userName` is the initial primary email/login and is immutable in this connector's update contract.
- Resolve resource IDs within the configured SCIM endpoint and tenant security context; never share cache or queue identity across organizations or user stores.
- Escape SCIM filter string literals and URL-encode filter query parameters and resource path segments.
- Send SCIM media types and required schema URIs; validate response status and resource IDs before caching.
- At connection validation, discover `/ServiceProviderConfig` and `/Schemas`; verify the server advertises PATCH and (for BULK/HYBRID) Bulk support, and verify the required User attributes are writable. Do not assume capabilities solely from a product/version string.
- Use the server's SCIM ETag/version where available for conditional writes, and handle version conflicts by refreshing the current resource and reconciling the intended state rather than blindly overwriting concurrent changes.
- Do not infer successful provisioning from an HTTP 2xx Bulk envelope alone.
- Keep bearer tokens and claim values out of logs and diagnostics.

## Required verification before HYBRID is enabled

1. Confirm from WSO2 IS 7.3.0 runtime logs which event names fire for UI creation, CSV import, claim updates, and bulk import retries.
2. Confirm whether outbound provisioning callbacks already cover those same paths.
3. Verify the event-handler service API, DS metadata, and OSGi imports against the exact 7.3.0 distribution.
4. Test durable queue restart recovery, concurrent schedulers, tenant isolation, coalescing, payload limits, partial Bulk failure, and ambiguous outcomes.
5. Run live tests against the target Stalwart Enterprise SCIM endpoint, including the actual configured domain/tenant behavior.

Until all five gates pass, `HYBRID` must fail closed or remain unavailable rather than silently reverting to volatile in-memory buffering.

/*
 * Copyright (c) 2014, WSO2 Inc. All Rights Reserved.
 * Modified for Stalwart SCIM 2.0 provisioning.
 * Licensed under the Apache License, Version 2.0.
 */
package org.stalwart.identity.provisioning.connector.scim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.application.common.model.ClaimMapping;
import org.wso2.carbon.identity.application.common.model.Property;
import org.wso2.carbon.identity.provisioning.AbstractOutboundProvisioningConnector;
import org.wso2.carbon.identity.provisioning.IdentityProvisioningConstants;
import org.wso2.carbon.identity.provisioning.IdentityProvisioningException;
import org.wso2.carbon.identity.provisioning.ProvisionedIdentifier;
import org.wso2.carbon.identity.provisioning.ProvisioningEntity;
import org.wso2.carbon.identity.provisioning.ProvisioningEntityType;
import org.wso2.carbon.identity.provisioning.ProvisioningUtil;
import org.wso2.carbon.context.PrivilegedCarbonContext;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * WSO2 outbound provisioning connector that speaks SCIM 2.0 directly to Stalwart.
 * The upstream WSO2 SCIM connector uses Charon's legacy SCIM 1.0 model and Basic
 * authentication; it is deliberately not used here.
 */
public class StalwartSCIMProvisioningConnector extends AbstractOutboundProvisioningConnector {

    private static final long serialVersionUID = 1L;
    private static final Log log = LogFactory.getLog(StalwartSCIMProvisioningConnector.class);

    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    private static final String PATCH_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    private static final String SEARCH_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:SearchRequest";
    private static final String USERNAME_CLAIM = IdentityProvisioningConstants.USERNAME_CLAIM_URI;
    private static final String DEFAULT_EXTERNAL_ID_CLAIM_URI = "http://wso2.org/claims/username";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final long CACHE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final ConcurrentMap<String, CacheEntry> RESOURCE_ID_CACHE = new ConcurrentHashMap<>();
    private static final class CacheEntry {
        private final String id;
        private final long expiresAt;
        private CacheEntry(String id) {
            this.id = id;
            this.expiresAt = System.currentTimeMillis() + CACHE_TTL_MILLIS;
        }
        private boolean isValid() { return System.currentTimeMillis() < expiresAt; }
    }

    private String baseUrl;
    private String bearerToken;
    private String userStoreDomainName;
    private boolean claimMappingEnabled = true;
    private String userNameClaimUri = "http://wso2.org/claims/emailaddress";
    private String externalIdClaimUri = "http://wso2.org/claims/username";
    private String givenNameClaimUri = "http://wso2.org/claims/givenname";
    private String familyNameClaimUri = "http://wso2.org/claims/lastname";
    private String displayNameClaimUri = "http://wso2.org/claims/displayname";
    private String activeClaimUri = "";
    private boolean invertActiveClaim = false;
    private String emailMembershipGroup = "Email";
    private boolean enableProfileUpdates = true;
    private String profileUpdateScimUrl;
    private int maxConcurrentRequests = 8;
    private Semaphore requestSemaphore = new Semaphore(8, true);
    private String provisioningMode = "INDIVIDUAL";
    private boolean bulkFallbackToIndividual = true;
    private int bulkRequestTimeoutMs = 30000;
    private int bulkMaxPayloadBytes = 900000;
    private volatile boolean bulkUnsupported;

    @Override
    public void init(Property[] properties) throws IdentityProvisioningException {
        if (properties != null) {
            for (Property property : properties) {
                if (property == null || property.getName() == null) {
                    continue;
                }
                String value = valueOf(property);
                switch (property.getName()) {
                    case StalwartSCIMProvisioningConnectorConstants.BASE_URL -> baseUrl = trimTrailingSlash(value);
                    case StalwartSCIMProvisioningConnectorConstants.BEARER_TOKEN -> bearerToken = value;
                    case StalwartSCIMProvisioningConnectorConstants.USERSTORE_DOMAIN -> userStoreDomainName = value;
                    case StalwartSCIMProvisioningConnectorConstants.ENABLE_CLAIM_MAPPING ->
                            claimMappingEnabled = Boolean.parseBoolean(value);
                    case StalwartSCIMProvisioningConnectorConstants.USERNAME_CLAIM_URI -> userNameClaimUri = value;
                    case StalwartSCIMProvisioningConnectorConstants.EXTERNAL_ID_CLAIM_URI -> externalIdClaimUri = value;
                    case StalwartSCIMProvisioningConnectorConstants.GIVEN_NAME_CLAIM_URI -> givenNameClaimUri = value;
                    case StalwartSCIMProvisioningConnectorConstants.FAMILY_NAME_CLAIM_URI -> familyNameClaimUri = value;
                    case StalwartSCIMProvisioningConnectorConstants.DISPLAY_NAME_CLAIM_URI -> displayNameClaimUri = value;
                    case StalwartSCIMProvisioningConnectorConstants.ENABLE_PROFILE_UPDATES -> enableProfileUpdates = Boolean.parseBoolean(value);
                    case StalwartSCIMProvisioningConnectorConstants.PROFILE_UPDATE_SCIM_URL -> profileUpdateScimUrl = trimTrailingSlash(value);
                    case StalwartSCIMProvisioningConnectorConstants.MAX_CONCURRENT_REQUESTS -> {
                        try {
                            maxConcurrentRequests = Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            throw new IdentityProvisioningException("max-concurrent-requests must be an integer from 1 to 64.", e);
                        }
                    }
                    case StalwartSCIMProvisioningConnectorConstants.ACTIVE_CLAIM_URI -> activeClaimUri = value;
                    case StalwartSCIMProvisioningConnectorConstants.INVERT_ACTIVE_CLAIM ->
                            invertActiveClaim = Boolean.parseBoolean(value);
                    case StalwartSCIMProvisioningConnectorConstants.EMAIL_MEMBERSHIP_GROUP ->
                            emailMembershipGroup = value;
                    case StalwartSCIMProvisioningConnectorConstants.PROVISIONING_MODE ->
                            provisioningMode = value.toUpperCase(java.util.Locale.ROOT);
                    case StalwartSCIMProvisioningConnectorConstants.BULK_FALLBACK_TO_INDIVIDUAL ->
                            bulkFallbackToIndividual = Boolean.parseBoolean(value);
                    case StalwartSCIMProvisioningConnectorConstants.BULK_REQUEST_TIMEOUT_MS ->
                            bulkRequestTimeoutMs = parseIntegerProperty(value, "bulk-request-timeout-ms");
                    case StalwartSCIMProvisioningConnectorConstants.BULK_MAX_PAYLOAD_BYTES ->
                            bulkMaxPayloadBytes = parseIntegerProperty(value, "bulk-max-payload-bytes");
                    default -> log.debug("Ignoring unsupported Stalwart connector property: " + property.getName());
                }
            }
        }
        applyTomlOverrides();
        if (!"INDIVIDUAL".equals(provisioningMode) && !"BULK".equals(provisioningMode)) {
            throw new IdentityProvisioningException("provisioning-mode must be INDIVIDUAL or BULK.");
        }
        if (bulkRequestTimeoutMs < 1000 || bulkRequestTimeoutMs > 120000) {
            throw new IdentityProvisioningException("bulk-request-timeout-ms must be between 1000 and 120000.");
        }
        if (bulkMaxPayloadBytes < 1024 || bulkMaxPayloadBytes > 1048576) {
            throw new IdentityProvisioningException("bulk-max-payload-bytes must be between 1024 and 1048576.");
        }
        if (maxConcurrentRequests < 1 || maxConcurrentRequests > 64) {
            throw new IdentityProvisioningException("max-concurrent-requests must be between 1 and 64.");
        }
        requestSemaphore = new Semaphore(maxConcurrentRequests, true);
        if (StringUtils.isBlank(baseUrl) || StringUtils.isBlank(bearerToken)) {
            throw new IdentityProvisioningException("Stalwart SCIM base URL and bearer token are required.");
        }
        URI endpoint;
        try {
            endpoint = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IdentityProvisioningException("Stalwart SCIM base URL is not a valid URI.", e);
        }
        String scheme = endpoint.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || ("http".equalsIgnoreCase(scheme)
                && ("localhost".equalsIgnoreCase(endpoint.getHost()) || "127.0.0.1".equals(endpoint.getHost()))))
                || StringUtils.isBlank(endpoint.getHost())
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !endpoint.getPath().endsWith("/scim/v2")) {
            throw new IdentityProvisioningException("Base URL must be an absolute HTTP(S) URL ending with /scim/v2 and must not contain credentials, query, or fragment.");
        }
        if (StringUtils.isNotBlank(profileUpdateScimUrl)) {
            URI profileEndpoint;
            try {
                profileEndpoint = URI.create(profileUpdateScimUrl);
            } catch (IllegalArgumentException e) {
                throw new IdentityProvisioningException("Profile update SCIM URL is not a valid URI.", e);
            }
            String profileScheme = profileEndpoint.getScheme();
            if (!("https".equalsIgnoreCase(profileScheme) || ("http".equalsIgnoreCase(profileScheme)
                    && ("localhost".equalsIgnoreCase(profileEndpoint.getHost()) || "127.0.0.1".equals(profileEndpoint.getHost()))))
                    || StringUtils.isBlank(profileEndpoint.getHost()) || profileEndpoint.getUserInfo() != null
                    || profileEndpoint.getQuery() != null || profileEndpoint.getFragment() != null
                    || !profileEndpoint.getPath().endsWith("/scim/v2")) {
                throw new IdentityProvisioningException(
                        "Profile update SCIM URL must be an absolute HTTP(S) URL ending with /scim/v2.");
            }
            if (!sameOrigin(endpoint, profileEndpoint)) {
                throw new IdentityProvisioningException(
                        "Profile update SCIM URL must use the same origin as the primary SCIM URL; the bearer token cannot be forwarded to another host.");
            }
        }
        if (bearerToken.indexOf('\r') >= 0 || bearerToken.indexOf('\n') >= 0) {
            throw new IdentityProvisioningException("Stalwart SCIM bearer token contains an invalid line break.");
        }
    }

    @Override
    public ProvisionedIdentifier provision(ProvisioningEntity entity) throws IdentityProvisioningException {
        if (entity == null) {
            return null;
        }
        if (entity.getEntityType() == null || entity.getOperation() == null) {
            throw new IdentityProvisioningException("Provisioning entity type and operation are required.");
        }
        if (entity.isJitProvisioning()) {
            // This is an outbound connector: the JIT marker describes the origin of the event,
            // and must not suppress provisioning to the target SCIM service.
            log.debug("JIT-originated event received; continuing outbound SCIM provisioning.");
        }
        if (entity.getEntityType() == ProvisioningEntityType.USER) {
            switch (entity.getOperation()) {
                case POST -> createUser(entity);
                case PUT, PATCH -> updateUser(entity);
                case DELETE -> deleteUser(entity);
                default -> log.warn("Unsupported user provisioning operation: " + entity.getOperation());
            }
        } else if (entity.getEntityType() == ProvisioningEntityType.GROUP) {
            // Ignore WSO2 GROUP lifecycle events. User membership deltas are handled from USER claims.
            log.debug("Ignoring WSO2 GROUP entity event; Stalwart group lifecycle is not driven by WSO2 GROUP events.");
        } else {
            log.warn("Unsupported provisioning entity type: " + entity.getEntityType());
        }
        return null;
    }

    private void createUser(ProvisioningEntity entity) throws IdentityProvisioningException {
        Map<ClaimMapping, List<String>> attrs = entity.getAttributes();
        // Resolve by immutable externalId before requiring the mutable-to-us
        // provisioning input userName claim. WSO2 may invoke POST again for a
        // membership/claim event whose attribute map omits emailaddress.
        String externalId = mappedValue(attrs, externalIdClaimUri);
        // With the default mapping, WSO2 entityName is the local username and
        // therefore the same stable value used as Stalwart externalId. POST
        // retries caused by later membership/profile events may omit claim attrs.
        if (StringUtils.isBlank(externalId) && DEFAULT_EXTERNAL_ID_CLAIM_URI.equals(externalIdClaimUri)
                && StringUtils.isNotBlank(entity.getEntityName())
                && entity.getEntityName().indexOf('@') < 0) {
            externalId = stripUserStoreDomain(entity.getEntityName().trim());
        }
        if (StringUtils.isBlank(externalId)) {
            throw new IdentityProvisioningException(
                    "A non-empty stable WSO2 externalId claim is required to reconcile a Stalwart account.");
        }
        String userName = null;
        String existingId = findResourceId("Users", "externalId", externalId);
        if (existingId == null) {
            userName = targetUserName(attrs, entity.getEntityName());
            existingId = resolveExistingUserIdForCreate(externalId, userName);
        }
        List<String> initialGroups = groupNames(attrs);
        boolean emailEntitled = initialGroups != null
                && initialGroups.stream().anyMatch(this::isEmailMembershipGroup);
        boolean activeClaimAllows = activeClaimAllows(attrs);
        // WSO2 can retry a failed provisioning event. Reuse an existing account so
        // a later group-membership failure does not cause a duplicate-user conflict.
        if (existingId == null) {
            JsonObject user = userPayload(attrs, userName, externalId);
            // Fail closed: account activation requires positive Email-group membership.
            // An optional active claim may further disable access, but can never grant it.
            user.addProperty("active", emailEntitled && activeClaimAllows);
            JsonObject created = request("POST", "/Users", user);
            if (!created.has("id")) {
                // A concurrent create may win between the preflight lookup and POST.
                // Reconcile that race before deciding whether this is a new account.
                existingId = resolveExistingUserIdForCreate(externalId, userName);
                if (existingId == null) {
                    throw new IdentityProvisioningException(
                            "Stalwart accepted no identifiable User resource after POST; provisioning outcome is unresolved.");
                }
                updateUser(entity, initialGroups == null ? null : emailEntitled);
            } else {
                // Initial memberships are applied once, after successful account creation.
                }
        } else {
            log.info("Stalwart user already exists; reconciling attributes and membership deltas.");
            // Reconciliation must not require the current email claim. The stable
            // externalId resolved the existing Stalwart account above.
            updateUser(entity, initialGroups == null ? null : emailEntitled);
        }
    }

    private String resolveExistingUserIdForCreate(String externalId, String userName)
            throws IdentityProvisioningException {
        String byExternalId = findResourceId("Users", "externalId", externalId);
        if (byExternalId != null) return byExternalId;

        String byUserName = findResourceId("Users", "userName", userName);
        if (byUserName == null) return null;

        JsonObject existing = request("GET", "/Users/" + pathSegment(byUserName), null);
        String actualExternalId = existing.has("externalId") && !existing.get("externalId").isJsonNull()
                ? existing.get("externalId").getAsString() : null;
        if (externalId.equals(actualExternalId)) {
            cacheResourceId("Users", "externalId", externalId, byUserName);
            return byUserName;
        }

        throw new IdentityProvisioningException(
                "Stalwart userName already exists but belongs to a different externalId. "
                        + "The connector will not adopt or overwrite that account.");
    }

    private void updateUser(ProvisioningEntity entity) throws IdentityProvisioningException {
        updateUser(entity, null);
    }

    /**
     * A non-null membership snapshot is authoritative (true or false). Null means
     * the event did not carry the complete current group list, so only explicit
     * Email-group deltas may grant/revoke access.
     */
    private void updateUser(ProvisioningEntity entity, Boolean emailMembershipSnapshot)
            throws IdentityProvisioningException {
        Map<ClaimMapping, List<String>> attrs = entity.getAttributes();
        // Email address, aliases and the WSO2 local username are deliberately
        // outside the Stalwart synchronization contract. If an update contains
        // no supported profile, active-state or group-membership delta, return
        // before performing even a SCIM lookup. In particular, never resolve a
        // changed externalId by falling back to a mutable email address.
        if (emailMembershipSnapshot == null && !hasSupportedUpdate(attrs)) {
            log.debug("Ignoring WSO2 update with no supported Stalwart changes "
                    + "(primary email, aliases, local username and unsupported claims are not synchronized).");
            return;
        }
        List<String> newGroups = distinctGroupNames(
                claimValues(attrs, IdentityProvisioningConstants.NEW_GROUP_CLAIM_URI));
        List<String> deletedGroups = distinctGroupNames(
                claimValues(attrs, IdentityProvisioningConstants.DELETED_GROUP_CLAIM_URI));
        boolean emailMembershipRemoved = deletedGroups != null
                && deletedGroups.stream().anyMatch(this::isEmailMembershipGroup);
        boolean emailMembershipAdded = newGroups != null
                && newGroups.stream().anyMatch(this::isEmailMembershipGroup);
        if (emailMembershipAdded && findUserId(attrs, entity.getEntityName()) == null) {
            String stableId = targetUserIdentifier(attrs, entity.getEntityName());
            JsonObject newUser = userPayload(attrs, targetUserName(attrs, entity.getEntityName()), stableId);
            newUser.addProperty("active", activeClaimAllows(attrs));
            request("POST", "/Users", newUser);
        }
        JsonObject profilePatch = userPatch(attrs, enableProfileUpdates, entity.getEntityName());
        if (profilePatch.getAsJsonArray("Operations").size() > 0) {
            String id = findUserId(attrs, entity.getEntityName());
            if (id != null) {
                requestAt(profileUpdateBaseUrl(), "PATCH", "/Users/" + pathSegment(id), profilePatch);
            } else {
                throw new IdentityProvisioningException(
                        "Stalwart user was not found for update; stable externalId lookup failed.");
            }
        }
        // Email membership is the mandatory authorization gate. The optional
        // active claim can only revoke access; it cannot independently grant it.
        Boolean desiredActive = null;
        if (emailMembershipRemoved) {
            desiredActive = false;
        } else if (emailMembershipAdded) {
            desiredActive = activeClaimAllows(attrs);
        } else if (emailMembershipSnapshot != null) {
            desiredActive = emailMembershipSnapshot && activeClaimAllows(attrs);
        } else if (Boolean.FALSE.equals(activeClaimState(attrs))) {
            desiredActive = false;
        }
        if (desiredActive != null) {
            setStalwartActive(attrs, entity.getEntityName(), desiredActive);
        }
    }

    private boolean hasSupportedUpdate(Map<ClaimMapping, List<String>> attrs) {
        if (attrs == null || attrs.isEmpty()) return false;
        return mappedValue(attrs, givenNameClaimUri) != null
                || mappedValue(attrs, familyNameClaimUri) != null
                || mappedValue(attrs, displayNameClaimUri) != null
                || StringUtils.isNotBlank(mappedValue(attrs, activeClaimUri))
                || CollectionUtils.isNotEmpty(claimValues(attrs, IdentityProvisioningConstants.NEW_GROUP_CLAIM_URI))
                || CollectionUtils.isNotEmpty(claimValues(attrs, IdentityProvisioningConstants.DELETED_GROUP_CLAIM_URI));
    }

    private void setStalwartActive(Map<ClaimMapping, List<String>> attrs, String fallback, boolean active)
            throws IdentityProvisioningException {
        String id = findUserId(attrs, fallback);
        if (id == null) throw new IdentityProvisioningException("Cannot change Stalwart email access: user was not found.");
        JsonArray operations = new JsonArray();
        addReplace(operations, "active", active);
        request("PATCH", "/Users/" + pathSegment(id), patchDocument(operations));
    }

    private boolean isEmailMembershipGroup(String groupName) {
        if (StringUtils.isBlank(emailMembershipGroup) || StringUtils.isBlank(groupName)) {
            return false;
        }
        return emailMembershipGroup.trim().equalsIgnoreCase(stripUserStoreDomain(groupName).trim());
    }

    private void deleteUser(ProvisioningEntity entity) throws IdentityProvisioningException {
        String id = findUserId(entity.getAttributes(), entity.getEntityName());
        if (id != null) {
            JsonArray operations = new JsonArray();
            addReplace(operations, "active", false);
            request("PATCH", "/Users/" + pathSegment(id), patchDocument(operations));
            // Invalidate every cached lookup for this resource ID. DELETE events
            // commonly omit emailaddress; cache invalidation must not depend on it.
            invalidateResourceIdById("Users", id);
            invalidateResourceId("Users", "externalId",
                    mappedValue(entity.getAttributes(), externalIdClaimUri));
        }
    }

    private JsonObject userPayload(Map<ClaimMapping, List<String>> attrs, String userName)
            throws IdentityProvisioningException {
        return userPayload(attrs, userName, mappedValue(attrs, externalIdClaimUri));
    }

    private JsonObject userPayload(Map<ClaimMapping, List<String>> attrs, String userName, String stableExternalId)
            throws IdentityProvisioningException {
        JsonObject user = new JsonObject();
        JsonArray schemas = new JsonArray();
        schemas.add(USER_SCHEMA);
        user.add("schemas", schemas);
        user.addProperty("userName", userName);
        String externalId = stableExternalId;
        if (StringUtils.isBlank(externalId)) {
            throw new IdentityProvisioningException("A non-empty stable WSO2 externalId claim is required to create a Stalwart account.");
        }
        user.addProperty("externalId", externalId.trim());
        String given = mappedValue(attrs, givenNameClaimUri);
        String family = mappedValue(attrs, familyNameClaimUri);
        String displayName = mappedValue(attrs, displayNameClaimUri);
        if (StringUtils.isNotBlank(displayName)) {
            user.addProperty("displayName", displayName.trim());
        } else if (StringUtils.isNotBlank(given) && StringUtils.isNotBlank(family)) {
            // Stalwart stores one display name; structured name parts are only
            // fallback inputs when displayName/name.formatted is absent.
            user.addProperty("displayName", joinName(given, family));
        } else if (StringUtils.isNotBlank(given) || StringUtils.isNotBlank(family)) {
            user.addProperty("displayName", StringUtils.defaultIfBlank(given, family).trim());
        }
        // Email aliases are intentionally not provisioned. The primary address is immutable after creation.
        // Creation is always fail-closed. Callers must explicitly prove
        // Email-group membership before changing this to true.
        user.addProperty("active", false);
        return user;
    }

    private JsonObject userPatch(Map<ClaimMapping, List<String>> attrs, boolean includeProfile, String entityName)
            throws IdentityProvisioningException {
        JsonArray operations = new JsonArray();

        // userName and externalId are immutable identity keys after creation.
        // A name-part delta is resolved against the authoritative current WSO2
        // UserStore snapshot; no Event Handler or JVM property is involved.
        String given = mappedValue(attrs, givenNameClaimUri);
        String family = mappedValue(attrs, familyNameClaimUri);
        String display = mappedValue(attrs, displayNameClaimUri);
        if (includeProfile && display != null) {
            // An explicitly supplied blank displayName is a clear operation.
            addReplace(operations, "displayName", StringUtils.defaultString(display).trim());
        } else if (includeProfile && (given != null || family != null)) {
            String lookupName = entityName;
            if (StringUtils.isNotBlank(userStoreDomainName) && StringUtils.isNotBlank(lookupName)
                    && lookupName.indexOf('/') < 0) {
                lookupName = userStoreDomainName.trim() + "/" + lookupName.trim();
            }
            try {
                Map<String, String> snapshot = Wso2UserClaimSnapshot.read(lookupName,
                        new String[]{givenNameClaimUri, familyNameClaimUri});
                // A missing UserStore claim is a legitimate empty value.
                String currentGiven = snapshot.getOrDefault(givenNameClaimUri, "");
                String currentFamily = snapshot.getOrDefault(familyNameClaimUri, "");
                String composed = joinName(currentGiven, currentFamily);
                // Empty composed values intentionally clear Stalwart displayName.
                addReplace(operations, "displayName", composed);
            } catch (IdentityProvisioningException e) {
                throw e;
            } catch (Exception e) {
                throw new IdentityProvisioningException(
                        "Unable to read the complete current FIRSTNAME/LASTNAME snapshot from WSO2 UserStore.", e);
            }
        } else if (includeProfile && StringUtils.isNotBlank(display)) {
            addReplace(operations, "displayName", display.trim());
        }

        return patchDocument(operations);
    }

    private String targetUserIdentifier(Map<ClaimMapping, List<String>> attrs, String fallback) {
        String externalId = mappedValue(attrs, externalIdClaimUri);
        String candidate = StringUtils.isNotBlank(externalId) ? externalId : fallback;
        return StringUtils.isNotBlank(candidate) ? stripUserStoreDomain(candidate.trim()) : candidate;
    }

    private static String joinName(String given, String family) {
        String first = StringUtils.defaultString(given).trim();
        String last = StringUtils.defaultString(family).trim();
        if (first.isEmpty()) return last;
        if (last.isEmpty()) return first;
        return first + " " + last;
    }

    private JsonObject patchDocument(JsonArray operations) {
        JsonObject patch = new JsonObject();
        JsonArray schemas = new JsonArray();
        schemas.add(PATCH_SCHEMA);
        patch.add("schemas", schemas);
        patch.add("Operations", operations);
        return patch;
    }

    private void addReplace(JsonArray operations, String path, String value) {
        addOperation(operations, "replace", path, new com.google.gson.JsonPrimitive(value));
    }

    private void addReplace(JsonArray operations, String path, JsonElement value) {
        addOperation(operations, "replace", path, value);
    }

    private void addReplace(JsonArray operations, String path, boolean value) {
        addOperation(operations, "replace", path, new com.google.gson.JsonPrimitive(value));
    }

    private void addOperation(JsonArray operations, String op, String path, JsonElement value) {
        JsonObject operation = new JsonObject();
        operation.addProperty("op", op);
        operation.addProperty("path", path);
        if (value != null) operation.add("value", value);
        operations.add(operation);
    }

    private String findUserId(Map<ClaimMapping, List<String>> attrs, String fallback)
            throws IdentityProvisioningException {
        String externalId = mappedValue(attrs, externalIdClaimUri);
        // WSO2 DELETE/UPDATE events can omit the mapped claim map while retaining
        // entityName as the local username. In this connector's default mapping,
        // that local username is the immutable Stalwart externalId, not userName.
        if (StringUtils.isBlank(externalId) && StringUtils.isNotBlank(fallback)) {
            String candidate = stripUserStoreDomain(fallback.trim());
            if (StringUtils.isNotBlank(candidate) && candidate.indexOf('@') < 0) {
                String id = findResourceId("Users", "externalId", candidate);
                if (id != null) return id;
                if (!candidate.equals(fallback)) {
                    id = findResourceId("Users", "externalId", fallback.trim());
                    if (id != null) return id;
                }
            }
        }
        // externalId is authoritative whenever supplied. Never adopt a different
        // account by matching its email if this stable identifier fails to match.
        if (StringUtils.isNotBlank(externalId)) {
            return findResourceId("Users", "externalId", externalId);
        }
        // Email lookup is a legacy fallback only when no stable local identifier
        // is available; numeric/bare WSO2 usernames are never treated as email.
        String userName = mappedValue(attrs, userNameClaimUri);
        if (StringUtils.isBlank(userName) && StringUtils.isNotBlank(fallback)
                && fallback.trim().contains("@")) {
            userName = fallback.trim();
        }
        if (StringUtils.isBlank(userName)) return null;
        userName = userName.trim();
        if (!userName.matches("^[^\\s@]+@[^\\s@]+$")) {
            throw new IdentityProvisioningException(
                    "Stalwart SCIM userName fallback must be a full email address.");
        }
        return findResourceId("Users", "userName", userName);
    }

    private String findUserIdByIdentifier(String identifier) throws IdentityProvisioningException {
        if (StringUtils.isBlank(identifier)) return null;
        String candidate = stripUserStoreDomain(identifier);
        String id = findResourceId("Users", "externalId", candidate);
        if (id == null && !candidate.equals(identifier)) {
            id = findResourceId("Users", "externalId", identifier);
        }
        if (id != null) return id;
        // Email identifiers can legitimately be supplied by legacy group claims;
        // numeric/stable identifiers are externalId and need no username fallback.
        if (candidate.indexOf('@') >= 0) {
            id = findResourceId("Users", "userName", candidate);
            return id != null ? id : (candidate.equals(identifier) ? null
                    : findResourceId("Users", "userName", identifier));
        }
        return null;
    }

    private String stripUserStoreDomain(String value) {
        if (StringUtils.isBlank(value)) return value;
        int slash = value.indexOf('/');
        if (slash > 0 && (StringUtils.isBlank(userStoreDomainName)
                || userStoreDomainName.equalsIgnoreCase(value.substring(0, slash)))) {
            return value.substring(slash + 1);
        }
        return value;
    }

    private String findResourceId(String resource, String attribute, String value)
            throws IdentityProvisioningException {
        if (StringUtils.isBlank(value)) return null;
        int tenantId = currentTenantId();
        String cacheKey = cacheKey(resource, attribute, value);
        if (tenantId >= 0) {
            CacheEntry cached = RESOURCE_ID_CACHE.get(cacheKey);
            if (cached != null) {
                if (cached.isValid()) return cached.id;
                RESOURCE_ID_CACHE.remove(cacheKey, cached);
            }
        }
        // POST .search keeps email addresses and external identifiers out of
        // request URLs, reverse-proxy access logs and URI telemetry.
        String filter = normalizeScimFilter(attribute + " Eq " + value);
        JsonObject search = new JsonObject();
        JsonArray schemas = new JsonArray();
        schemas.add(SEARCH_SCHEMA);
        search.add("schemas", schemas);
        search.addProperty("filter", filter);
        search.addProperty("count", 2);
        if ("Groups".equals(resource)) search.addProperty("excludedAttributes", "members");
        JsonObject response = requestAtIndividual(baseUrl, "POST", "/" + resource + "/.search", search);
        JsonArray resources = response.has("Resources") && response.get("Resources").isJsonArray()
                ? response.getAsJsonArray("Resources") : new JsonArray();
        if (resources.isEmpty()) return null;
        if (resources.size() > 1) {
            throw new IdentityProvisioningException("Stalwart SCIM filter returned multiple " + resource
                    + " resources for " + attribute + "; refusing to select an arbitrary match.");
        }
        String id = resources.get(0).getAsJsonObject().has("id")
                ? resources.get(0).getAsJsonObject().get("id").getAsString() : null;
        if (StringUtils.isNotBlank(id)) cacheResourceId(resource, attribute, value, id);
        return id;
    }

    private String tokenFingerprint(String token) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(StringUtils.defaultString(token).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte b : digest) value.append(String.format("%02x", b));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for credential-scoped batch keys.", e);
        }
    }

    private int currentTenantId() {
        try {
            return PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private String cacheKey(String resource, String attribute, String value) {
        return currentTenantId() + "|" + baseUrl + "|" + String.valueOf(userStoreDomainName) + "|"
                + tokenFingerprint(bearerToken) + "|" + resource + "|" + attribute + "|"
                + value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void cacheResourceId(String resource, String attribute, String value, String id) {
        if (currentTenantId() < 0 || StringUtils.isBlank(value) || StringUtils.isBlank(id)) return;
        RESOURCE_ID_CACHE.put(cacheKey(resource, attribute, value), new CacheEntry(id));
        if (RESOURCE_ID_CACHE.size() > 10000) { long now = System.currentTimeMillis(); RESOURCE_ID_CACHE.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now); }
    }

    private void invalidateResourceId(String resource, String attribute, String value) {
        if (StringUtils.isNotBlank(value)) RESOURCE_ID_CACHE.remove(cacheKey(resource, attribute, value));
    }

    private void invalidateResourceIdById(String resource, String id) {
        if (StringUtils.isBlank(resource) || StringUtils.isBlank(id)) return;
        String marker = "|" + resource + "|";
        RESOURCE_ID_CACHE.entrySet().removeIf(entry ->
                entry.getKey().contains(marker) && id.equals(entry.getValue().id));
    }

    private String profileUpdateBaseUrl() {
        return enableProfileUpdates && StringUtils.isNotBlank(profileUpdateScimUrl) ? profileUpdateScimUrl : baseUrl;
    }

    private boolean sameOrigin(URI left, URI right) {
        if (left == null || right == null || left.getHost() == null || right.getHost() == null) return false;
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private Boolean activeClaimState(Map<ClaimMapping, List<String>> attrs)
            throws IdentityProvisioningException {
        String value = mappedValue(attrs, activeClaimUri);
        if (StringUtils.isBlank(value)) return null;
        boolean parsed = parseBooleanClaim(value, activeClaimUri);
        return invertActiveClaim ? !parsed : parsed;
    }

    private boolean activeClaimAllows(Map<ClaimMapping, List<String>> attrs)
            throws IdentityProvisioningException {
        return !Boolean.FALSE.equals(activeClaimState(attrs));
    }

    private JsonObject request(String method, String path, JsonObject body) throws IdentityProvisioningException {
        return requestAt(baseUrl, method, path, body);
    }

    private JsonObject requestAt(String endpointBaseUrl, String method, String path, JsonObject body)
            throws IdentityProvisioningException {
        if ("BULK".equals(provisioningMode) && !"GET".equalsIgnoreCase(method)) {
            return requestBulkAt(endpointBaseUrl, method, path, body);
        }
        return requestAtIndividual(endpointBaseUrl, method, path, body);
    }

    private static final class PendingBulkOperation {
        final StalwartSCIMProvisioningConnector owner;
        final String endpoint, method, path;
        final JsonObject body;
        final CompletableFuture<JsonObject> result = new CompletableFuture<>();
        PendingBulkOperation(StalwartSCIMProvisioningConnector owner, String endpoint, String method,
                             String path, JsonObject body) {
            this.owner = owner; this.endpoint = endpoint; this.method = method; this.path = path;
            this.body = body == null ? null : body.deepCopy();
        }
    }
    private static final class BulkBatch {
        final List<PendingBulkOperation> operations = new ArrayList<>();
        ScheduledFuture<?> scheduled;
    }
    private static final ConcurrentMap<String, BulkBatch> BULK_BATCHES = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService BULK_DISPATCHER = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "stalwart-scim-bulk-dispatcher");
        thread.setDaemon(true);
        return thread;
    });
    private static final int BULK_BATCH_MAX_OPERATIONS = 100;
    private static final int BULK_PENDING_LIMIT = 5000;
    private static final Semaphore BULK_PENDING_PERMITS = new Semaphore(BULK_PENDING_LIMIT, true);
    private static final long BULK_BATCH_WINDOW_MS = 150;

    /** Completes pending in-memory requests before the OSGi bundle is stopped. */
    public static void shutdownBulkDispatcher() {
        BULK_DISPATCHER.shutdown();
        IdentityProvisioningException stopped =
                new IdentityProvisioningException("Connector bundle is stopping; pending in-memory Bulk batch was not sent.");
        for (BulkBatch batch : BULK_BATCHES.values()) {
            synchronized (batch) {
                for (PendingBulkOperation item : batch.operations) item.result.completeExceptionally(stopped);
                batch.operations.clear();
                if (batch.scheduled != null) batch.scheduled.cancel(false);
            }
        }
        BULK_BATCHES.clear();
    }

    /** Short in-memory micro-batching. Every caller waits for its own Stalwart operation result. */
    private JsonObject requestBulkAt(String endpointBaseUrl, String method, String path, JsonObject body)
            throws IdentityProvisioningException {
        if (bulkUnsupported) {
            if (bulkFallbackToIndividual) return requestAtIndividual(endpointBaseUrl, method, path, body);
            throw new IdentityProvisioningException("Stalwart SCIM Bulk endpoint is unavailable.");
        }
        String key = currentTenantId() + "|" + String.valueOf(userStoreDomainName) + "|"
                + endpointBaseUrl + "|" + tokenFingerprint(bearerToken) + "|"
                + bulkMaxPayloadBytes + "|" + bulkRequestTimeoutMs + "|" + bulkFallbackToIndividual;
        if (!BULK_PENDING_PERMITS.tryAcquire()) {
            throw new IdentityProvisioningException("The in-memory SCIM Bulk pending-operation limit has been reached; retry after current requests complete.");
        }
        PendingBulkOperation pending = new PendingBulkOperation(this, endpointBaseUrl, method, path, body);
        pending.result.whenComplete((result, error) -> BULK_PENDING_PERMITS.release());
        BulkBatch batch = BULK_BATCHES.computeIfAbsent(key, ignored -> new BulkBatch());
        synchronized (batch) {
            batch.operations.add(pending);
            if (batch.scheduled == null) {
                batch.scheduled = BULK_DISPATCHER.schedule(() -> flushBulkBatch(key, batch),
                        BULK_BATCH_WINDOW_MS, TimeUnit.MILLISECONDS);
            }
            if (batch.operations.size() >= BULK_BATCH_MAX_OPERATIONS) {
                batch.scheduled.cancel(false);
                batch.scheduled = BULK_DISPATCHER.schedule(() -> flushBulkBatch(key, batch), 0, TimeUnit.MILLISECONDS);
            }
        }
        try {
            return pending.result.get(bulkRequestTimeoutMs + 10000L, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IdentityProvisioningException("Timed out waiting for the synchronous SCIM Bulk operation result.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdentityProvisioningException("Interrupted while waiting for the synchronous SCIM Bulk operation.", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IdentityProvisioningException) throw (IdentityProvisioningException) cause;
            throw new IdentityProvisioningException("SCIM Bulk operation failed.", cause);
        }
    }

    private static void flushBulkBatch(String key, BulkBatch batch) {
        List<PendingBulkOperation> pending;
        synchronized (batch) {
            pending = new ArrayList<>(batch.operations);
            batch.operations.clear();
            batch.scheduled = null;
        }
        if (pending.isEmpty()) { BULK_BATCHES.remove(key, batch); return; }
        StalwartSCIMProvisioningConnector owner = pending.get(0).owner;
        List<PendingBulkOperation> chunk = new ArrayList<>();
        int bytes = 256;
        for (PendingBulkOperation item : pending) {
            int estimate = item.body == null ? 256 : item.body.toString().getBytes(StandardCharsets.UTF_8).length + 256;
            if (!chunk.isEmpty() && (chunk.size() >= BULK_BATCH_MAX_OPERATIONS
                    || bytes + estimate > owner.bulkMaxPayloadBytes)) {
                owner.sendBulkChunk(chunk);
                chunk = new ArrayList<>(); bytes = 256;
            }
            if (estimate + 256 > owner.bulkMaxPayloadBytes) {
                item.result.completeExceptionally(new IdentityProvisioningException("One SCIM operation exceeds bulk-max-payload-bytes."));
            } else {
                chunk.add(item); bytes += estimate;
            }
        }
        if (!chunk.isEmpty()) owner.sendBulkChunk(chunk);
        synchronized (batch) {
            if (!batch.operations.isEmpty() && batch.scheduled == null) {
                batch.scheduled = BULK_DISPATCHER.schedule(() -> flushBulkBatch(key, batch),
                        BULK_BATCH_WINDOW_MS, TimeUnit.MILLISECONDS);
            } else if (batch.operations.isEmpty()) {
                BULK_BATCHES.remove(key, batch);
            }
        }
    }

    private void sendBulkChunk(List<PendingBulkOperation> batch) {
        if (batch.isEmpty()) return;
        if (bulkUnsupported) {
            for (PendingBulkOperation item : batch) completeIndividualFallback(item);
            return;
        }
        JsonArray operations = new JsonArray();
        for (PendingBulkOperation item : batch) {
            JsonObject op = new JsonObject();
            op.addProperty("method", item.method);
            op.addProperty("path", item.path);
            if ("POST".equalsIgnoreCase(item.method)) op.addProperty("bulkId", java.util.UUID.randomUUID().toString());
            if (item.body != null) op.add("data", item.body);
            operations.add(op);
        }
        JsonObject envelope = new JsonObject();
        JsonArray schemas = new JsonArray();
        schemas.add("urn:ietf:params:scim:api:messages:2.0:BulkRequest");
        envelope.add("schemas", schemas); envelope.add("Operations", operations);
        String payload = envelope.toString();
        if (payload.getBytes(StandardCharsets.UTF_8).length > bulkMaxPayloadBytes) {
            if (batch.size() == 1) {
                batch.get(0).result.completeExceptionally(new IdentityProvisioningException("SCIM Bulk request exceeds configured payload limit."));
            } else {
                int mid = batch.size() / 2;
                sendBulkChunk(batch.subList(0, mid)); sendBulkChunk(batch.subList(mid, batch.size()));
            }
            return;
        }
        HttpRequest httpRequest = HttpRequest.newBuilder().uri(URI.create(batch.get(0).endpoint + "/Bulk"))
                .timeout(Duration.ofMillis(bulkRequestTimeoutMs)).header("Authorization", "Bearer " + bearerToken)
                .header("Accept", "application/scim+json").header("Content-Type", "application/scim+json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        try {
            HttpResponse<String> response = sendWithLimit(httpRequest);
            int httpStatus = response.statusCode();
            if ((httpStatus == 404 || httpStatus == 405 || httpStatus == 501) && bulkFallbackToIndividual) {
                bulkUnsupported = true;
                for (PendingBulkOperation item : batch) completeIndividualFallback(item);
                return;
            }
            if (httpStatus < 200 || httpStatus >= 300) {
                IdentityProvisioningException failure = new IdentityProvisioningException("Stalwart SCIM Bulk request failed with HTTP " + httpStatus + ".");
                for (PendingBulkOperation item : batch) item.result.completeExceptionally(failure);
                return;
            }
            JsonObject bulkResponse = StringUtils.isBlank(response.body()) ? new JsonObject()
                    : JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray results = bulkResponse.has("Operations") && bulkResponse.get("Operations").isJsonArray()
                    ? bulkResponse.getAsJsonArray("Operations") : new JsonArray();
            if (results.size() != batch.size()) {
                IdentityProvisioningException failure = new IdentityProvisioningException("Stalwart Bulk response count did not match submitted operations; reconcile before retry.");
                for (PendingBulkOperation item : batch) item.result.completeExceptionally(failure);
                return;
            }
            for (int i = 0; i < batch.size(); i++) {
                PendingBulkOperation item = batch.get(i);
                JsonObject result = results.get(i).getAsJsonObject();
                int status = parseBulkOperationStatus(result);
                if (status >= 200 && status < 300) {
                    JsonObject resource = result.has("response") && result.get("response").isJsonObject()
                            ? result.getAsJsonObject("response") : new JsonObject();
                    cacheBulkCreated(item, resource);
                    item.result.complete(resource);
                } else if (status == 404 && "DELETE".equalsIgnoreCase(item.method)) {
                    item.result.complete(new JsonObject());
                } else if (status == 409 && "POST".equalsIgnoreCase(item.method)) {
                    try {
                        if (postResourceNowExists(item.path, item.body)) item.result.complete(new JsonObject());
                        else item.result.completeExceptionally(new IdentityProvisioningException("Stalwart returned a POST conflict without a matching externalId."));
                    } catch (IdentityProvisioningException reconcileError) {
                        item.result.completeExceptionally(reconcileError);
                    }
                } else {
                    item.result.completeExceptionally(new IdentityProvisioningException(
                            "Stalwart SCIM Bulk operation failed with HTTP " + status + "."));
                }
            }
        } catch (IOException e) {
            for (PendingBulkOperation item : batch) {
                try {
                    if ("POST".equalsIgnoreCase(item.method) && postResourceNowExists(item.path, item.body)) {
                        item.result.complete(new JsonObject());
                    } else {
                        item.result.completeExceptionally(new IdentityProvisioningException(
                                "Ambiguous SCIM Bulk I/O outcome; verify the target resource before retrying.", e));
                    }
                } catch (Exception reconcile) {
                    item.result.completeExceptionally(new IdentityProvisioningException(
                            "Ambiguous SCIM Bulk I/O outcome and reconciliation failed.", reconcile));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            IdentityProvisioningException failure = new IdentityProvisioningException("SCIM Bulk dispatch interrupted.", e);
            for (PendingBulkOperation item : batch) item.result.completeExceptionally(failure);
        } catch (RuntimeException e) {
            IdentityProvisioningException failure = new IdentityProvisioningException("Invalid Stalwart Bulk response.", e);
            for (PendingBulkOperation item : batch) item.result.completeExceptionally(failure);
        }
    }

    private void completeIndividualFallback(PendingBulkOperation item) {
        try { item.result.complete(requestAtIndividual(item.endpoint, item.method, item.path, item.body)); }
        catch (IdentityProvisioningException e) { item.result.completeExceptionally(e); }
    }

    private void cacheBulkCreated(PendingBulkOperation item, JsonObject resource) {
        if (!"POST".equalsIgnoreCase(item.method) || item.body == null || !resource.has("id")) return;
        String id = resource.get("id").getAsString();
        if ("/Users".equals(item.path)) {
            if (item.body.has("userName")) cacheResourceId("Users", "userName", item.body.get("userName").getAsString(), id);
            if (item.body.has("externalId")) cacheResourceId("Users", "externalId", item.body.get("externalId").getAsString(), id);
        }
    }

    private int parseBulkOperationStatus(JsonObject operation) {
        if (operation == null || !operation.has("status") || operation.get("status").isJsonNull()) return 0;
        try { return Integer.parseInt(operation.get("status").getAsString()); }
        catch (NumberFormatException e) { return 0; }
    }

    private int parseIntegerProperty(String value, String name) throws IdentityProvisioningException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IdentityProvisioningException(name + " must be an integer.", e);
        }
    }

    private JsonObject requestAtIndividual(String endpointBaseUrl, String method, String path, JsonObject body)
            throws IdentityProvisioningException {
        final int maxAttempts = 3;
        String resourceId = resourceIdFromPath(path);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpointBaseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Accept", "application/scim+json");
            if (body != null) {
                builder.header("Content-Type", "application/scim+json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
            } else if ("GET".equals(method) || "DELETE".equals(method)) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/scim+json")
                        .method(method, HttpRequest.BodyPublishers.ofString("{}"));
            }
            log.debug("Stalwart SCIM request; userId=" + resourceId + ", operation=" + method
                    + ", attempt=" + attempt + "/" + maxAttempts);
            HttpResponse<String> response = sendWithLimit(builder.build());
            int status = response.statusCode();
            if (status == 404 && "DELETE".equals(method)) {
                log.debug("Stalwart SCIM DELETE target already absent; treating as idempotent success.");
                return new JsonObject();
            }
            log.debug("Stalwart SCIM response; userId=" + resourceId + ", operation=" + method
                    + ", status=" + status + ", attempt=" + attempt);
            if (status == 409 && "POST".equals(method) && postResourceNowExists(path, body)) {
                log.info("Stalwart SCIM duplicate POST resolved as idempotent success; operation=" + method
                        + ", resource=" + path);
                return new JsonObject();
            }
            if (status == 408 || status == 429 || status >= 500) {
                if ("POST".equals(method) && postResourceNowExists(path, body)) return new JsonObject();
                boolean retrySafe = "GET".equals(method) || "DELETE".equals(method) || "POST".equals(method)
                        || ("PATCH".equals(method) && isIdempotentPatch(body));
                if (retrySafe && attempt < maxAttempts) {
                    retryBackoff(attempt, response.headers().firstValue("Retry-After").orElse(null));
                    continue;
                }
            }            if (status < 200 || status >= 300) {
                throw new IdentityProvisioningException("Stalwart SCIM " + method + " request failed with HTTP " + status + ".");
            }
            JsonObject parsed = StringUtils.isBlank(response.body())
                    ? new JsonObject() : JsonParser.parseString(response.body()).getAsJsonObject();
            if ("POST".equals(method) && parsed.has("id") && body != null) {
                String resource = "/Users".equals(path) ? "Users" : "/Groups".equals(path) ? "Groups" : null;
                String attribute = "Users".equals(resource) ? "userName" : "displayName";
                if (resource != null && body.has(attribute)) {
                    cacheResourceId(resource, attribute, body.get(attribute).getAsString(),
                            parsed.get("id").getAsString());
                }
                if ("Users".equals(resource) && body.has("externalId")) {
                    cacheResourceId("Users", "externalId", body.get("externalId").getAsString(),
                            parsed.get("id").getAsString());
                }
            }
            return parsed;
        } catch (IOException e) {
            log.warn("Stalwart SCIM I/O failure; userId=" + resourceId + ", operation=" + method
                    + ", attempt=" + attempt, e);
            if ("POST".equals(method) && postResourceNowExists(path, body)) return new JsonObject();
            if (!"GET".equals(method) && !"DELETE".equals(method) && !"POST".equals(method)
                    && !("PATCH".equals(method) && isIdempotentPatch(body))) {
                throw new IdentityProvisioningException("I/O error during non-idempotent SCIM " + method
                        + " request; it was not retried to avoid duplicate side effects.", e);
            }
            if (attempt == maxAttempts) {
                throw new IdentityProvisioningException("I/O error while calling Stalwart SCIM endpoint after "
                        + maxAttempts + " attempts.", e);
            }
            retryBackoff(attempt, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdentityProvisioningException("Interrupted while calling Stalwart SCIM endpoint.", e);
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException e) {
            throw new IdentityProvisioningException("Invalid Stalwart SCIM response or endpoint.", e);
        }
        }
        throw new IdentityProvisioningException("Stalwart SCIM request failed after retries.");
    }

    private HttpResponse<String> sendWithLimit(HttpRequest request) throws IOException, InterruptedException {
        requestSemaphore.acquire();
        try {
            return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } finally {
            requestSemaphore.release();
        }
    }

    private boolean isIdempotentPatch(JsonObject body) {
        if (body == null || !body.has("Operations") || !body.get("Operations").isJsonArray()) return false;
        for (JsonElement element : body.getAsJsonArray("Operations")) {
            if (!element.isJsonObject() || !element.getAsJsonObject().has("op")) return false;
            String op = element.getAsJsonObject().get("op").getAsString();
            if (!"replace".equalsIgnoreCase(op) && !"remove".equalsIgnoreCase(op)
                    && !"add".equalsIgnoreCase(op)) return false;
        }
        return !body.getAsJsonArray("Operations").isEmpty();
    }

    private boolean postResourceNowExists(String path, JsonObject body) throws IdentityProvisioningException {
        if (body == null || !path.matches("^/(Users|Groups)$")) return false;
        if ("/Users".equals(path)) {
            // A username collision is not proof that this is the same WSO2 user.
            // First reconcile by immutable externalId; only accept a username
            // collision after reading the existing resource and matching externalId.
            if (!body.has("externalId") || StringUtils.isBlank(body.get("externalId").getAsString())) {
                return false;
            }
            String expectedExternalId = body.get("externalId").getAsString();
            String byExternalId = findResourceId("Users", "externalId", expectedExternalId);
            if (byExternalId != null) return true;
            if (!body.has("userName") || StringUtils.isBlank(body.get("userName").getAsString())) {
                return false;
            }
            String byUserName = findResourceId("Users", "userName", body.get("userName").getAsString());
            if (byUserName == null) return false;
            JsonObject existing = request("GET", "/Users/" + pathSegment(byUserName), null);
            String actualExternalId = existing.has("externalId") && !existing.get("externalId").isJsonNull()
                    ? existing.get("externalId").getAsString() : null;
            if (expectedExternalId.equals(actualExternalId)) return true;
            throw new IdentityProvisioningException(
                    "Stalwart userName already exists but belongs to a different externalId. "
                            + "The connector will not adopt or overwrite that account.");
        }
        if (!body.has("displayName")) return false;
        return findResourceId("Groups", "displayName", body.get("displayName").getAsString()) != null;
    }

    private void retryBackoff(int attempt, String retryAfter) throws IdentityProvisioningException {
        try {
            long delay = 250L * (1L << (attempt - 1));
            if (StringUtils.isNotBlank(retryAfter)) {
                try {
                    delay = Math.max(delay, Math.min(60_000L, Long.parseLong(retryAfter.trim()) * 1000L));
                } catch (NumberFormatException ignored) {
                    try {
                        long retryAt = java.time.ZonedDateTime.parse(retryAfter,
                                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                        delay = Math.max(delay, Math.min(60_000L, Math.max(0L, retryAt - System.currentTimeMillis())));
                    } catch (java.time.format.DateTimeParseException ignoredDate) {
                        // Fall back to bounded exponential backoff for invalid headers.
                    }
                }
            }
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdentityProvisioningException("Interrupted during SCIM retry backoff.", e);
        }
    }

    private String resourceIdFromPath(String path) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^/(?:Users|Groups)/([^/?]+)").matcher(path == null ? "" : path);
        return matcher.find() ? matcher.group(1) : "unresolved";
    }

    private static String normalizeScimFilter(String filter) {
        if (filter == null) return null;
        String normalized = filter.replaceAll("(?i)\\beq\\b", "eq").trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)^([A-Za-z][A-Za-z0-9._-]*\\s+eq\\s+)(?!\\\")(.*)$")
                .matcher(normalized);
        if (matcher.matches()) normalized = matcher.group(1) + "\"" + escapeFilter(matcher.group(2).trim()) + "\"";
        return normalized;
    }

    private String targetUserName(Map<ClaimMapping, List<String>> attrs, String fallback)
            throws IdentityProvisioningException {
        String value = claimMappingEnabled ? mappedValue(attrs, userNameClaimUri) : fallback;
        if (StringUtils.isBlank(value)) {
            throw new IdentityProvisioningException("A full email address is required for Stalwart SCIM userName.");
        }
        value = value.trim();
        if (!value.matches("^[^\\s@]+@[^\\s@]+$")) {
            throw new IdentityProvisioningException("Stalwart SCIM userName must be a full email address; bare usernames are not accepted.");
        }
        return value;
    }

    private boolean parseBooleanClaim(String value, String claimUri) throws IdentityProvisioningException {
        String normalized = value == null ? "" : value.trim();
        if ("true".equalsIgnoreCase(normalized)) return true;
        if ("false".equalsIgnoreCase(normalized)) return false;
        throw new IdentityProvisioningException("Claim " + claimUri
                + " must contain Boolean true or false; received an invalid value.");
    }

    private String mappedValue(Map<ClaimMapping, List<String>> attrs, String uri) {
        if (StringUtils.isBlank(uri) || attrs == null) return null;
        List<String> values = ProvisioningUtil.getClaimValues(attrs, uri, null);
        return CollectionUtils.isNotEmpty(values) ? values.get(0) : null;
    }

    private List<String> claimValues(Map<ClaimMapping, List<String>> attrs, String uri) {
        if (StringUtils.isBlank(uri) || attrs == null) return null;
        return ProvisioningUtil.getClaimValues(attrs, uri, getUserStoreDomainName());
    }

    private List<String> groupNames(Map<ClaimMapping, List<String>> attrs) {
        return distinctGroupNames(claimValues(attrs, IdentityProvisioningConstants.GROUP_CLAIM_URI));
    }

    /**
     * Normalize user-store-qualified group names and remove exact duplicates
     * while preserving source order. Duplicate WSO2 claims must not create
     * duplicate SCIM membership PATCH operations.
     */
    private List<String> distinctGroupNames(List<String> names) {
        if (names == null) return null;
        java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
        for (String name : names) {
            if (StringUtils.isNotBlank(name)) {
                String normalized = stripUserStoreDomain(name.trim());
                if (StringUtils.isNotBlank(normalized)) unique.add(normalized);
            }
        }
        return new ArrayList<>(unique);
    }

    /** Read optional local TOML configuration using WSO2's standard carbon.home property. */
    private void applyTomlOverrides() throws IdentityProvisioningException {
        String carbonHome = System.getProperty("carbon.home");
        if (StringUtils.isBlank(carbonHome)) return;
        Path file = Path.of(carbonHome, "repository", "conf", "stalwart-scim-connector.toml");
        if (!Files.isRegularFile(file)) return;
        Map<String, String> values = new java.util.LinkedHashMap<>();
        boolean inSection = false;
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = stripTomlComment(raw).trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    inSection = "[stalwart-scim]".equals(line);
                    continue;
                }
                if (!inSection) continue;
                int equals = line.indexOf('=');
                if (equals <= 0) throw new IdentityProvisioningException("Invalid key/value in connector TOML file: " + file);
                String key = line.substring(0, equals).trim();
                String value = parseTomlScalar(line.substring(equals + 1).trim(), file);
                if (values.putIfAbsent(key, value) != null) {
                    throw new IdentityProvisioningException("Duplicate TOML key in [stalwart-scim]: " + key);
                }
            }
        } catch (IOException e) {
            throw new IdentityProvisioningException("Unable to read connector TOML file: " + file, e);
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue();
            switch (entry.getKey()) {
                case StalwartSCIMProvisioningConnectorConstants.BASE_URL -> baseUrl = trimTrailingSlash(value);
                case StalwartSCIMProvisioningConnectorConstants.BEARER_TOKEN -> bearerToken = value;
                case StalwartSCIMProvisioningConnectorConstants.USERSTORE_DOMAIN -> userStoreDomainName = value;
                case StalwartSCIMProvisioningConnectorConstants.ENABLE_CLAIM_MAPPING -> claimMappingEnabled = parseTomlBoolean(entry);
                case StalwartSCIMProvisioningConnectorConstants.USERNAME_CLAIM_URI -> userNameClaimUri = value;
                case StalwartSCIMProvisioningConnectorConstants.EXTERNAL_ID_CLAIM_URI -> externalIdClaimUri = value;
                case StalwartSCIMProvisioningConnectorConstants.GIVEN_NAME_CLAIM_URI -> givenNameClaimUri = value;
                case StalwartSCIMProvisioningConnectorConstants.FAMILY_NAME_CLAIM_URI -> familyNameClaimUri = value;
                case StalwartSCIMProvisioningConnectorConstants.DISPLAY_NAME_CLAIM_URI -> displayNameClaimUri = value;
                case StalwartSCIMProvisioningConnectorConstants.ENABLE_PROFILE_UPDATES -> enableProfileUpdates = parseTomlBoolean(entry);
                case StalwartSCIMProvisioningConnectorConstants.PROFILE_UPDATE_SCIM_URL -> profileUpdateScimUrl = trimTrailingSlash(value);
                case StalwartSCIMProvisioningConnectorConstants.MAX_CONCURRENT_REQUESTS -> maxConcurrentRequests = parseTomlInteger(entry);
                case StalwartSCIMProvisioningConnectorConstants.ACTIVE_CLAIM_URI -> activeClaimUri = value;
                case StalwartSCIMProvisioningConnectorConstants.INVERT_ACTIVE_CLAIM -> invertActiveClaim = parseTomlBoolean(entry);
                case StalwartSCIMProvisioningConnectorConstants.EMAIL_MEMBERSHIP_GROUP -> emailMembershipGroup = value;
                case StalwartSCIMProvisioningConnectorConstants.PROVISIONING_MODE -> provisioningMode = value.toUpperCase(java.util.Locale.ROOT);
                case StalwartSCIMProvisioningConnectorConstants.BULK_FALLBACK_TO_INDIVIDUAL -> bulkFallbackToIndividual = parseTomlBoolean(entry);
                case StalwartSCIMProvisioningConnectorConstants.BULK_REQUEST_TIMEOUT_MS -> bulkRequestTimeoutMs = parseTomlInteger(entry);
                case StalwartSCIMProvisioningConnectorConstants.BULK_MAX_PAYLOAD_BYTES -> bulkMaxPayloadBytes = parseTomlInteger(entry);
                default -> throw new IdentityProvisioningException(
                        "Unknown key in [stalwart-scim] TOML section: " + entry.getKey()
                                + ". Check the key name; refusing to silently ignore a configuration typo.");
            }
        }
    }

    private String stripTomlComment(String line) {
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quoted && ch == 92) { escaped = true; continue; }
            if (ch == '"') quoted = !quoted;
            if (ch == '#' && !quoted) return line.substring(0, i);
        }
        return line;
    }

    private String parseTomlScalar(String raw, Path file) throws IdentityProvisioningException {
        if (raw.isEmpty()) return "";
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
            String value = raw.substring(1, raw.length() - 1);
            StringBuilder decoded = new StringBuilder();
            boolean escaped = false;
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (escaped) {
                    if (ch == 'n') decoded.append('\n');
                    else if (ch == 'r') decoded.append('\r');
                    else if (ch == 't') decoded.append('\t');
                    else if (ch == '"' || ch == 92) decoded.append(ch);
                    else throw new IdentityProvisioningException("Unsupported TOML escape in " + file);
                    escaped = false;
                } else if (ch == 92) escaped = true;
                else decoded.append(ch);
            }
            if (escaped) throw new IdentityProvisioningException("Incomplete TOML escape in " + file);
            return decoded.toString();
        }
        if ("true".equals(raw) || "false".equals(raw) || raw.matches("-?[0-9]+")) return raw;
        throw new IdentityProvisioningException("TOML scalar must be a quoted string, Boolean or integer in " + file);
    }

    private boolean parseTomlBoolean(Map.Entry<String, String> entry) throws IdentityProvisioningException {
        if ("true".equals(entry.getValue())) return true;
        if ("false".equals(entry.getValue())) return false;
        throw new IdentityProvisioningException("TOML key " + entry.getKey() + " must be true or false.");
    }

    private int parseTomlInteger(Map.Entry<String, String> entry) throws IdentityProvisioningException {
        try { return Integer.parseInt(entry.getValue()); }
        catch (NumberFormatException e) {
            throw new IdentityProvisioningException("TOML key " + entry.getKey() + " must be an integer.", e);
        }
    }

    private String valueOf(Property property) {
        if (StringUtils.isNotBlank(property.getValue())) return property.getValue().trim();
        return property.getDefaultValue() == null ? "" : property.getDefaultValue().trim();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) return null;
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String escapeFilter(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    protected String getUserStoreDomainName() {
        return userStoreDomainName;
    }

}
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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;

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
    private static final String USERNAME_CLAIM = IdentityProvisioningConstants.USERNAME_CLAIM_URI;
    private static final String DEFAULT_EXTERNAL_ID_CLAIM_URI = "http://wso2.org/claims/username";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final long CACHE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final ConcurrentMap<String, CacheEntry> RESOURCE_ID_CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger CACHE_INSERTIONS = new AtomicInteger();
    private static final ConcurrentMap<String, StalwartSCIMProvisioningConnector> HYBRID_WORKERS =
            new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, StalwartSCIMProvisioningConnector> BULK_WORKERS =
            new ConcurrentHashMap<>();
    private static final AtomicBoolean HYBRID_SCHEDULER_STARTED = new AtomicBoolean(false);
    private static volatile ScheduledExecutorService hybridScheduler;
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
    private String hybridOutboxJndiName = HybridProfileOutbox.DEFAULT_JNDI_NAME;
    private String hybridScopeId;
    private HybridProfileOutbox hybridProfileOutbox;
    private ScimOperationOutbox scimOperationOutbox;
    private String scimOperationScope;
    private final ConcurrentMap<String, Boolean> bulkUnsupportedEndpoints = new ConcurrentHashMap<>();
    private final Object scimOutboxFlushLock = new Object();

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
                    case StalwartSCIMProvisioningConnectorConstants.HYBRID_OUTBOX_JNDI_NAME ->
                            hybridOutboxJndiName = value;
                    default -> log.debug("Ignoring unsupported Stalwart connector property: " + property.getName());
                }
            }
        }
        if (!"INDIVIDUAL".equals(provisioningMode) && !"BULK".equals(provisioningMode)
                && !"HYBRID".equals(provisioningMode)) {
            throw new IdentityProvisioningException("provisioning-mode must be INDIVIDUAL, BULK or HYBRID.");
        }
        if ("HYBRID".equals(provisioningMode) && !enableProfileUpdates) {
            throw new IdentityProvisioningException("HYBRID mode requires enable-profile-updates=true.");
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
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
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
            if (!("https".equalsIgnoreCase(profileScheme) || "http".equalsIgnoreCase(profileScheme))
                    || StringUtils.isBlank(profileEndpoint.getHost()) || profileEndpoint.getUserInfo() != null
                    || profileEndpoint.getQuery() != null || profileEndpoint.getFragment() != null
                    || !profileEndpoint.getPath().endsWith("/scim/v2")) {
                throw new IdentityProvisioningException(
                        "Profile update SCIM URL must be an absolute HTTP(S) URL ending with /scim/v2.");
            }
        }
        if (bearerToken.indexOf('\r') >= 0 || bearerToken.indexOf('\n') >= 0) {
            throw new IdentityProvisioningException("Stalwart SCIM bearer token contains an invalid line break.");
        }
        if ("HYBRID".equals(provisioningMode) || "BULK".equals(provisioningMode)) {
            try {
                hybridScopeId = buildOutboxScope(baseUrl, userStoreDomainName, bearerToken);
                if ("BULK".equals(provisioningMode)) {
                    scimOperationOutbox = ScimOperationOutbox.lookup(hybridOutboxJndiName);
                    scimOperationScope = hybridScopeId;
                    registerBulkWorker();
                } else {
                    hybridProfileOutbox = HybridProfileOutbox.lookup(hybridOutboxJndiName);
                    registerHybridWorker();
                }
            } catch (Exception e) {
                throw new IdentityProvisioningException(
                        "Unable to initialize the durable Stalwart SCIM outbox using WSO2 Carbon DataSource "
                                + hybridOutboxJndiName + ".", e);
            }
        }
    }

    private String buildOutboxScope(String endpoint, String userStoreDomain, String token) {
        String scopeMaterial = String.join("|", StringUtils.defaultString(endpoint),
                StringUtils.defaultString(profileUpdateScimUrl),
                StringUtils.defaultIfBlank(userStoreDomain, "PRIMARY"), StringUtils.defaultString(token));
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(scopeMaterial.getBytes(StandardCharsets.UTF_8));
            StringBuilder scope = new StringBuilder("SCIM-");
            for (byte value : digest) scope.append(String.format("%02x", value));
            return scope.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for durable outbox scope isolation.", e);
        }
    }

    private void registerHybridWorker() {
        HYBRID_WORKERS.put(hybridScopeId, this);
        startOutboxScheduler();
    }

    private void registerBulkWorker() {
        BULK_WORKERS.put(scimOperationScope, this);
        startOutboxScheduler();
    }

    private static void startOutboxScheduler() {
        if (!HYBRID_SCHEDULER_STARTED.compareAndSet(false, true)) return;
        hybridScheduler = Executors.newScheduledThreadPool(2, task -> {
            Thread thread = new Thread(task, "stalwart-scim-durable-outbox");
            thread.setDaemon(true);
            return thread;
        });
        hybridScheduler.scheduleAtFixedRate(() -> {
            for (StalwartSCIMProvisioningConnector worker : HYBRID_WORKERS.values()) {
                try {
                    worker.flushHybridProfileOutbox();
                } catch (Throwable error) {
                    log.error("Unexpected failure in HYBRID profile outbox scheduler.", error);
                }
            }
        }, 5, 5, TimeUnit.MINUTES);
        hybridScheduler.scheduleWithFixedDelay(() -> {
            for (StalwartSCIMProvisioningConnector worker : BULK_WORKERS.values()) {
                try {
                    worker.flushScimOperationOutbox();
                } catch (Throwable error) {
                    log.error("Unexpected failure in durable SCIM Bulk outbox scheduler.", error);
                }
            }
        }, 5, 15, TimeUnit.SECONDS);
    }

    /**
     * Called by the OSGi component during bundle shutdown. Pending rows remain
     * durable and are reclaimed by another WSO2 node or after the next restart.
     */
    public static void shutdownHybridScheduler() {
        ScheduledExecutorService scheduler = hybridScheduler;
        hybridScheduler = null;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        HYBRID_WORKERS.clear();
        BULK_WORKERS.clear();
        HYBRID_SCHEDULER_STARTED.set(false);
    }

    private record HybridPatch(HybridProfileOutbox.Entry entry, String userId, String version) { }

    private void flushHybridProfileOutbox() {
        if (hybridProfileOutbox == null || StringUtils.isBlank(hybridScopeId)) return;
        String leaseOwner = java.util.UUID.randomUUID().toString();
        List<HybridProfileOutbox.Entry> claimed;
        try {
            // Keep the batch comfortably below Stalwart's 1000-operation and
            // 1 MiB limits even when every displayName is near its storage cap.
            claimed = hybridProfileOutbox.claimBatch(hybridScopeId, 25, leaseOwner, 600_000L);
        } catch (Exception e) {
            log.error("Unable to claim durable HYBRID profile updates for scope " + hybridScopeId + ".", e);
            return;
        }
        if (claimed.isEmpty()) return;

        List<HybridPatch> pending = new ArrayList<>();
        for (HybridProfileOutbox.Entry entry : claimed) {
            try {
                String id = findResourceId("Users", "externalId", entry.externalId());
                if (id == null) {
                    hybridProfileOutbox.markRetry(entry, leaseOwner,
                            "No Stalwart User was found for the queued immutable externalId.", 10);
                    continue;
                }
                JsonObject current = request("GET", "/Users/" + pathSegment(id), null);
                String currentName = current.has("displayName") && !current.get("displayName").isJsonNull()
                        ? current.get("displayName").getAsString() : null;
                if (entry.displayName().equals(currentName)) {
                    hybridProfileOutbox.markSent(entry.key(), leaseOwner);
                    continue;
                }
                String version = null;
                if (current.has("meta") && current.get("meta").isJsonObject()) {
                    JsonObject meta = current.getAsJsonObject("meta");
                    if (meta.has("version") && !meta.get("version").isJsonNull()) {
                        version = meta.get("version").getAsString();
                    }
                }
                pending.add(new HybridPatch(entry, id, version));
            } catch (Exception e) {
                markHybridRetry(entry, leaseOwner, "Unable to resolve or read the queued Stalwart user: " + e);
            }
        }
        if (pending.isEmpty()) return;

        if (bulkUnsupportedEndpoints.containsKey(profileUpdateBaseUrl())) {
            sendHybridIndividualFallback(pending, leaseOwner);
            return;
        }

        JsonArray operations = new JsonArray();
        for (HybridPatch item : pending) {
            JsonArray patchOperations = new JsonArray();
            addReplace(patchOperations, "displayName", item.entry().displayName());
            JsonObject operation = new JsonObject();
            operation.addProperty("method", "PATCH");
            operation.addProperty("path", "/Users/" + pathSegment(item.userId()));
            operation.add("data", patchDocument(patchOperations));
            // RFC 7644 Bulk Operation version carries the resource ETag when
            // the target exposes one; omit it when Stalwart does not.
            if (StringUtils.isNotBlank(item.version())) operation.addProperty("version", item.version());
            operations.add(operation);
        }
        JsonObject bulkRequest = new JsonObject();
        JsonArray schemas = new JsonArray();
        schemas.add("urn:ietf:params:scim:api:messages:2.0:BulkRequest");
        bulkRequest.add("schemas", schemas);
        bulkRequest.add("Operations", operations);
        String payload = bulkRequest.toString();
        if (payload.getBytes(StandardCharsets.UTF_8).length > bulkMaxPayloadBytes) {
            for (HybridPatch item : pending) {
                markHybridRetry(item.entry(), leaseOwner,
                        "HYBRID Bulk payload exceeds bulk-max-payload-bytes; no request was sent.");
            }
            return;
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(profileUpdateBaseUrl() + "/Bulk"))
                .timeout(Duration.ofMillis(bulkRequestTimeoutMs))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Accept", "application/scim+json")
                .header("Content-Type", "application/scim+json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        try {
            HttpResponse<String> response = sendWithLimit(httpRequest);
            int status = response.statusCode();
            if ((status == 404 || status == 405 || status == 501) && bulkFallbackToIndividual) {
                bulkUnsupportedEndpoints.put(profileUpdateBaseUrl(), Boolean.TRUE);
                log.warn("Stalwart Bulk endpoint is unavailable for HYBRID profile updates; using individual PATCH fallback.");
                sendHybridIndividualFallback(pending, leaseOwner);
                return;
            }
            if (status < 200 || status >= 300) {
                for (HybridPatch item : pending) {
                    markHybridRetry(item.entry(), leaseOwner,
                            "Stalwart HYBRID Bulk endpoint returned HTTP " + status + ": " + abbreviate(response.body()));
                }
                return;
            }
            JsonObject result = StringUtils.isBlank(response.body())
                    ? new JsonObject() : JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray results = result.has("Operations") && result.get("Operations").isJsonArray()
                    ? result.getAsJsonArray("Operations") : new JsonArray();
            if (results.size() != pending.size()) {
                reconcileHybridBatch(pending, leaseOwner,
                        "Bulk response operation count did not match the submitted batch.");
                return;
            }
            for (int i = 0; i < pending.size(); i++) {
                HybridPatch item = pending.get(i);
                JsonObject operationResult = results.get(i).getAsJsonObject();
                int operationStatus = parseBulkOperationStatus(operationResult);
                if (operationStatus >= 200 && operationStatus < 300) {
                    hybridProfileOutbox.markSent(item.entry().key(), leaseOwner);
                } else {
                    markHybridRetry(item.entry(), leaseOwner,
                            "Stalwart Bulk PATCH operation failed with status " + operationStatus
                                    + ": " + abbreviate(operationResult.toString()));
                }
            }
        } catch (IOException e) {
            // The server may have committed some or all PATCH operations. Read
            // each resource before deciding whether an item can be acknowledged.
            reconcileHybridBatch(pending, leaseOwner, "Ambiguous Bulk I/O outcome: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reconcileHybridBatch(pending, leaseOwner, "Bulk flush interrupted; outcome may be unknown.");
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException e) {
            reconcileHybridBatch(pending, leaseOwner, "Invalid Bulk response; outcome may be unknown: " + e);
        } catch (SQLException e) {
            log.error("Unable to update HYBRID outbox state after Stalwart response.", e);
        }
    }

    private void sendHybridIndividualFallback(List<HybridPatch> pending, String leaseOwner) {
        for (HybridPatch item : pending) {
            try {
                JsonArray patchOperations = new JsonArray();
                addReplace(patchOperations, "displayName", item.entry().displayName());
                requestAtIndividual(profileUpdateBaseUrl(), "PATCH",
                        "/Users/" + pathSegment(item.userId()), patchDocument(patchOperations));
                hybridProfileOutbox.markSent(item.entry().key(), leaseOwner);
            } catch (Exception e) {
                markHybridRetry(item.entry(), leaseOwner, "Individual fallback failed: " + e);
            }
        }
    }

    private int parseBulkOperationStatus(JsonObject operation) {
        if (operation == null || !operation.has("status") || operation.get("status").isJsonNull()) return 0;
        try {
            return Integer.parseInt(operation.get("status").getAsString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void reconcileHybridBatch(List<HybridPatch> pending, String leaseOwner, String reason) {
        for (HybridPatch item : pending) {
            try {
                JsonObject current = request("GET", "/Users/" + pathSegment(item.userId()), null);
                String currentName = current.has("displayName") && !current.get("displayName").isJsonNull()
                        ? current.get("displayName").getAsString() : null;
                if (item.entry().displayName().equals(currentName)) {
                    hybridProfileOutbox.markSent(item.entry().key(), leaseOwner);
                } else {
                    markHybridRetry(item.entry(), leaseOwner, reason);
                }
            } catch (Exception e) {
                markHybridRetry(item.entry(), leaseOwner, reason + "; reconciliation read failed: " + e);
            }
        }
    }

    private void markHybridRetry(HybridProfileOutbox.Entry entry, String leaseOwner, String reason) {
        try {
            hybridProfileOutbox.markRetry(entry, leaseOwner, reason, 10);
        } catch (Exception e) {
            log.error("Unable to persist HYBRID retry state for entry " + entry.key() + ".", e);
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
        // WSO2 can retry a failed provisioning event. Reuse an existing account so
        // a later group-membership failure does not cause a duplicate-user conflict.
        if (existingId == null) {
            JsonObject user = userPayload(attrs, userName, externalId);
            // Email entitlement takes precedence over a mapped active claim. Fold it
            // into the create document instead of issuing a redundant follow-up PATCH.
            if (emailEntitled) {
                user.addProperty("active", true);
            }
            JsonObject created = request("POST", "/Users", user);
            if (!created.has("id")) {
                // A concurrent create may win between the preflight lookup and POST.
                // Reconcile that race before deciding whether this is a new account.
                existingId = resolveExistingUserIdForCreate(externalId, userName);
                if (existingId == null) {
                    throw new IdentityProvisioningException(
                            "Stalwart accepted no identifiable User resource after POST; provisioning outcome is unresolved.");
                }
                updateUser(entity, emailEntitled);
            } else {
                // Initial memberships are applied once, after successful account creation.
                updateUserGroupMemberships(entity);
            }
        } else {
            log.info("Stalwart user already exists; reconciling attributes and membership deltas.");
            // Reconciliation must not require the current email claim. The stable
            // externalId resolved the existing Stalwart account above.
            updateUser(entity, emailEntitled);
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
        updateUser(entity, false);
    }

    private void updateUser(ProvisioningEntity entity, boolean forceEmailEntitlement)
            throws IdentityProvisioningException {
        Map<ClaimMapping, List<String>> attrs = entity.getAttributes();
        // Email address, aliases and the WSO2 local username are deliberately
        // outside the Stalwart synchronization contract. If an update contains
        // no supported profile, active-state or group-membership delta, return
        // before performing even a SCIM lookup. In particular, never resolve a
        // changed externalId by falling back to a mutable email address.
        if (!forceEmailEntitlement && !hasSupportedUpdate(attrs)) {
            log.debug("Ignoring WSO2 update with no supported Stalwart changes "
                    + "(primary email, aliases, local username and unsupported claims are not synchronized).");
            return;
        }
        List<String> newGroups = distinctGroupNames(
                claimValues(attrs, IdentityProvisioningConstants.NEW_GROUP_CLAIM_URI));
        List<String> deletedGroups = distinctGroupNames(
                claimValues(attrs, IdentityProvisioningConstants.DELETED_GROUP_CLAIM_URI));
        boolean emailMembershipRemoved = false;
        boolean emailMembershipAdded = newGroups != null
                && newGroups.stream().anyMatch(this::isEmailMembershipGroup);
        if (emailMembershipAdded && findUserId(attrs, entity.getEntityName()) == null) {
            // Provision the account before processing any distribution-group deltas.
            request("POST", "/Users", userPayload(attrs, targetUserName(attrs, entity.getEntityName()),
                    targetUserIdentifier(attrs, entity.getEntityName())));
        }
        if (CollectionUtils.isNotEmpty(newGroups) || CollectionUtils.isNotEmpty(deletedGroups)) {
            if (newGroups != null) {
                for (String group : newGroups) {
                    if (isEmailMembershipGroup(group)) {
                        // Email entitlement is represented by User.active, not a Stalwart Group.
                    } else {
                        updateGroupMembership(group, targetUserIdentifier(attrs, entity.getEntityName()), true);
                    }
                }
            }
            if (deletedGroups != null) {
                for (String group : deletedGroups) {
                    if (isEmailMembershipGroup(group)) {
                        // Email access revocation is enforced through SCIM User.active.
                        // Do not make it depend on a corresponding Stalwart Group resource.
                        emailMembershipRemoved = true;
                    } else {
                        updateGroupMembership(group, targetUserIdentifier(attrs, entity.getEntityName()), false);
                    }
                }
            }
        }
        JsonObject profilePatch = userPatch(attrs, enableProfileUpdates, entity.getEntityName());
        if (profilePatch.getAsJsonArray("Operations").size() > 0) {
            if ("HYBRID".equals(provisioningMode)) {
                String externalId = targetUserIdentifier(attrs, entity.getEntityName());
                externalId = stripUserStoreDomain(externalId);
                String displayName = profilePatch.getAsJsonArray("Operations").get(0)
                        .getAsJsonObject().get("value").getAsString();
                if (StringUtils.isBlank(externalId)) {
                    throw new IdentityProvisioningException(
                            "HYBRID profile update requires the immutable WSO2 externalId.");
                }
                try {
                    hybridProfileOutbox.enqueue(hybridScopeId, externalId, displayName,
                            "CONNECTOR_PROFILE_CLAIM_SNAPSHOT", 3);
                } catch (SQLException e) {
                    throw new IdentityProvisioningException(
                            "Unable to persist the HYBRID profile update in WSO2CarbonDB.", e);
                }
            } else {
                String id = findUserId(attrs, entity.getEntityName());
                if (id != null) {
                    requestAt(profileUpdateBaseUrl(), "PATCH", "/Users/" + pathSegment(id), profilePatch);
                } else {
                    throw new IdentityProvisioningException(
                            "Stalwart user was not found for update; stable externalId lookup failed.");
                }
            }
        }
        // Compute one effective active value. Email entitlement has precedence
        // over the independently mapped active claim, and exactly one PATCH is sent.
        String activeClaim = mappedValue(attrs, activeClaimUri);
        Boolean desiredActive = null;
        if (StringUtils.isNotBlank(activeClaim)) {
            boolean activeValue = parseBooleanClaim(activeClaim, activeClaimUri);
            desiredActive = invertActiveClaim ? !activeValue : activeValue;
        }
        if (forceEmailEntitlement) {
            desiredActive = true;
        } else if (emailMembershipRemoved || emailMembershipAdded) {
            desiredActive = !emailMembershipRemoved;
        }
        if (desiredActive != null) {
            setStalwartActive(attrs, entity.getEntityName(), desiredActive);
        }
    }

    private boolean hasSupportedUpdate(Map<ClaimMapping, List<String>> attrs) {
        if (attrs == null || attrs.isEmpty()) return false;
        return StringUtils.isNotBlank(mappedValue(attrs, givenNameClaimUri))
                || StringUtils.isNotBlank(mappedValue(attrs, familyNameClaimUri))
                || StringUtils.isNotBlank(mappedValue(attrs, displayNameClaimUri))
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
            request("DELETE", "/Users/" + pathSegment(id), null);
            // Invalidate every cached lookup for this resource ID. DELETE events
            // commonly omit emailaddress; cache invalidation must not depend on it.
            invalidateResourceIdById("Users", id);
            invalidateResourceId("Users", "externalId",
                    mappedValue(entity.getAttributes(), externalIdClaimUri));
        }
    }

    private void updateUserGroupMemberships(ProvisioningEntity entity)
            throws IdentityProvisioningException {
        List<String> groups = groupNames(entity.getAttributes());
        if (groups != null) {
            String identifier = targetUserIdentifier(entity.getAttributes(), entity.getEntityName());
            for (String group : groups) {
                if (!isEmailMembershipGroup(group)) updateGroupMembership(group, identifier, true);
            }
        }
    }

    private void updateGroupMembership(String groupName, String username, boolean add)
            throws IdentityProvisioningException {
        groupName = stripUserStoreDomain(groupName);
        String groupId = findResourceId("Groups", "displayName", groupName);
        if (groupId == null) {
            throw new IdentityProvisioningException("Stalwart group was not found for membership update: " + groupName);
        }
        JsonArray operations = new JsonArray();
        String userId = findUserIdByIdentifier(username);
        if (userId == null) {
            throw new IdentityProvisioningException("Stalwart user was not found for group membership: " + username);
        }
        // Apply only the membership delta from WSO2. Avoid GET /Groups/{id}:
        // large groups may exceed Stalwart's members response limit.
        if (add) {
            JsonObject member = new JsonObject();
            member.addProperty("value", userId);
            member.addProperty("display", username);
            member.addProperty("type", "User");
            JsonArray members = new JsonArray();
            members.add(member);
            addOperation(operations, "add", "members", members);
        } else {
            addOperation(operations, "remove", "members[value eq \"" + escapeFilter(userId) + "\"]", null);
        }
        request("PATCH", "/Groups/" + pathSegment(groupId), patchDocument(operations));
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
        String active = mappedValue(attrs, activeClaimUri);
        if (StringUtils.isNotBlank(active)) {
            boolean activeValue = parseBooleanClaim(active, activeClaimUri);
            user.addProperty("active", invertActiveClaim ? !activeValue : activeValue);
        }
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
        if (includeProfile && (StringUtils.isNotBlank(given) || StringUtils.isNotBlank(family))) {
            String lookupName = entityName;
            if (StringUtils.isNotBlank(userStoreDomainName) && StringUtils.isNotBlank(lookupName)
                    && lookupName.indexOf('/') < 0) {
                lookupName = userStoreDomainName.trim() + "/" + lookupName.trim();
            }
            try {
                Map<String, String> snapshot = Wso2UserClaimSnapshot.read(lookupName,
                        new String[]{givenNameClaimUri, familyNameClaimUri});
                if (!snapshot.containsKey(givenNameClaimUri) || !snapshot.containsKey(familyNameClaimUri)) {
                    throw new IdentityProvisioningException(
                            "The current WSO2 UserStore snapshot did not include both FIRSTNAME and LASTNAME claims.");
                }
                String currentGiven = snapshot.get(givenNameClaimUri);
                String currentFamily = snapshot.get(familyNameClaimUri);
                String composed = joinName(currentGiven, currentFamily);
                if (StringUtils.isNotBlank(composed)) addReplace(operations, "displayName", composed);
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
        return StringUtils.isNotBlank(externalId) ? externalId : fallback;
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
        String cacheKey = cacheKey(resource, attribute, value);
        CacheEntry cached = RESOURCE_ID_CACHE.get(cacheKey);
        if (cached != null) {
            if (cached.isValid()) return cached.id;
            RESOURCE_ID_CACHE.remove(cacheKey, cached);
        }
        String filter = normalizeScimFilter(attribute + " Eq " + value);
        String query = "?filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8) + "&count=2"
                + ("Groups".equals(resource) ? "&excludedAttributes=members" : "");
        JsonObject response = request("GET", "/" + resource + query, null);
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

    private String cacheKey(String resource, String attribute, String value) {
        // Different provisioning principals can see different tenant-scoped IDs
        // even when they share the same SCIM host.
        return baseUrl + "|" + String.valueOf(userStoreDomainName) + "|"
                + Integer.toHexString(StringUtils.defaultString(bearerToken).hashCode()) + "|"
                + resource + "|" + attribute + "|" + value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void cacheResourceId(String resource, String attribute, String value, String id) {
        if (StringUtils.isBlank(value) || StringUtils.isBlank(id)) return;
        RESOURCE_ID_CACHE.put(cacheKey(resource, attribute, value), new CacheEntry(id));
        if (CACHE_INSERTIONS.incrementAndGet() % 256 == 0) {
            long now = System.currentTimeMillis();
            RESOURCE_ID_CACHE.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        }
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

    /**
     * Enqueue first, then attempt delivery. A committed row remains durable if
     * WSO2 or the host stops between enqueue and Stalwart acknowledgement.
     */
    private JsonObject requestBulkAt(String endpointBaseUrl, String method, String path, JsonObject data)
            throws IdentityProvisioningException {
        if (scimOperationOutbox == null || StringUtils.isBlank(scimOperationScope)) {
            throw new IdentityProvisioningException(
                    "Durable SCIM Bulk outbox is not initialized; refusing an unjournaled Bulk mutation.");
        }
        final String operationId;
        try {
            operationId = scimOperationOutbox.enqueue(scimOperationScope, endpointBaseUrl, method, path,
                    data == null ? null : data.toString());
        } catch (SQLException e) {
            throw new IdentityProvisioningException(
                    "Unable to persist the SCIM Bulk operation in WSO2CarbonDB; no Stalwart request was sent.", e);
        }

        // Low-latency path: try to drain this row immediately. If Stalwart is
        // unavailable, the durable worker will retry it after restart/recovery.
        flushScimOperationOutbox();
        long deadline = System.currentTimeMillis() + bulkRequestTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                ScimOperationOutbox.Result result = scimOperationOutbox.getResult(operationId);
                if (result == null) {
                    throw new IdentityProvisioningException("Durable Bulk operation disappeared from its outbox.");
                }
                if ("SENT".equals(result.status())) {
                    return StringUtils.isBlank(result.responseJson()) ? new JsonObject()
                            : JsonParser.parseString(result.responseJson()).getAsJsonObject();
                }
                if ("DEAD".equals(result.status())) {
                    throw new IdentityProvisioningException(
                            "Durable SCIM Bulk operation entered DEAD state: " + abbreviate(result.lastError()));
                }
                Thread.sleep(100L);
            } catch (SQLException e) {
                throw new IdentityProvisioningException("Unable to read the durable SCIM Bulk operation result.", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IdentityProvisioningException(
                        "Interrupted while waiting for the durable SCIM Bulk operation; it remains queued.", e);
            } catch (JsonParseException | IllegalStateException e) {
                throw new IdentityProvisioningException("Invalid response saved for a durable SCIM Bulk operation.", e);
            }
        }
        throw new IdentityProvisioningException(
                "SCIM Bulk operation was durably queued but Stalwart has not acknowledged it yet. "
                        + "The WSO2 outbox worker will continue delivery; operationId=" + operationId);
    }

    private void flushScimOperationOutbox() {
        if (scimOperationOutbox == null || StringUtils.isBlank(scimOperationScope)) return;
        synchronized (scimOutboxFlushLock) {
            String leaseOwner = java.util.UUID.randomUUID().toString();
            List<ScimOperationOutbox.Entry> claimed;
            try {
                scimOperationOutbox.purgeSent(30);
                claimed = scimOperationOutbox.claimBatch(scimOperationScope, 25, leaseOwner, 180_000L);
            } catch (Exception e) {
                log.error("Unable to claim durable SCIM Bulk operations.", e);
                return;
            }
            if (claimed.isEmpty()) return;
            Map<String, List<ScimOperationOutbox.Entry>> byEndpoint = new java.util.LinkedHashMap<>();
            for (ScimOperationOutbox.Entry entry : claimed) {
                byEndpoint.computeIfAbsent(entry.endpoint(), ignored -> new ArrayList<>()).add(entry);
            }
            for (Map.Entry<String, List<ScimOperationOutbox.Entry>> group : byEndpoint.entrySet()) {
                sendDurableBulkBatch(group.getKey(), group.getValue());
            }
        }
    }

    private void sendDurableBulkBatch(String endpoint, List<ScimOperationOutbox.Entry> entries) {
        if (bulkUnsupportedEndpoints.containsKey(endpoint)) {
            sendDurableIndividualFallback(endpoint, entries);
            return;
        }
        JsonArray operations = new JsonArray();
        List<JsonObject> bodies = new ArrayList<>();
        try {
            for (ScimOperationOutbox.Entry entry : entries) {
                JsonObject operation = new JsonObject();
                operation.addProperty("method", entry.method());
                operation.addProperty("path", entry.path());
                if ("POST".equalsIgnoreCase(entry.method())) {
                    operation.addProperty("bulkId", java.util.UUID.randomUUID().toString());
                }
                JsonObject body = StringUtils.isBlank(entry.dataJson()) ? null
                        : JsonParser.parseString(entry.dataJson()).getAsJsonObject();
                bodies.add(body);
                if (body != null) operation.add("data", body);
                operations.add(operation);
            }
        } catch (RuntimeException e) {
            for (ScimOperationOutbox.Entry entry : entries) {
                markBulkDead(entry, "Persisted SCIM operation payload is invalid: " + e);
            }
            return;
        }

        JsonObject envelope = new JsonObject();
        JsonArray schemas = new JsonArray();
        schemas.add("urn:ietf:params:scim:api:messages:2.0:BulkRequest");
        envelope.add("schemas", schemas);
        envelope.add("Operations", operations);
        String payload = envelope.toString();
        if (payload.getBytes(StandardCharsets.UTF_8).length > bulkMaxPayloadBytes) {
            if (entries.size() == 1) {
                markBulkDead(entries.get(0), "A single durable Bulk operation exceeds bulk-max-payload-bytes.");
                return;
            }
            int middle = entries.size() / 2;
            sendDurableBulkBatch(endpoint, entries.subList(0, middle));
            sendDurableBulkBatch(endpoint, entries.subList(middle, entries.size()));
            return;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/Bulk"))
                .timeout(Duration.ofMillis(bulkRequestTimeoutMs))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Accept", "application/scim+json")
                .header("Content-Type", "application/scim+json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        try {
            HttpResponse<String> response = sendWithLimit(request);
            int httpStatus = response.statusCode();
            if ((httpStatus == 404 || httpStatus == 405 || httpStatus == 501) && bulkFallbackToIndividual) {
                bulkUnsupportedEndpoints.put(endpoint, Boolean.TRUE);
                log.warn("SCIM Bulk endpoint unavailable; delivering durable queued operations individually.");
                sendDurableIndividualFallback(endpoint, entries);
                return;
            }
            if (httpStatus < 200 || httpStatus >= 300) {
                for (ScimOperationOutbox.Entry entry : entries) {
                    markBulkRetry(entry, "Stalwart Bulk endpoint returned HTTP " + httpStatus + ": "
                            + abbreviate(response.body()));
                }
                return;
            }
            JsonObject bulkResponse = StringUtils.isBlank(response.body())
                    ? new JsonObject() : JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray results = bulkResponse.has("Operations") && bulkResponse.get("Operations").isJsonArray()
                    ? bulkResponse.getAsJsonArray("Operations") : new JsonArray();
            if (results.size() != entries.size()) {
                for (ScimOperationOutbox.Entry entry : entries) {
                    markBulkRetry(entry, "Bulk response count did not match the submitted durable operation batch.");
                }
                return;
            }
            for (int i = 0; i < entries.size(); i++) {
                ScimOperationOutbox.Entry entry = entries.get(i);
                JsonObject operationResult = results.get(i).getAsJsonObject();
                int status = parseBulkOperationStatus(operationResult);
                if (status >= 200 && status < 300) {
                    JsonObject resource = operationResult.has("response")
                            && operationResult.get("response").isJsonObject()
                            ? operationResult.getAsJsonObject("response") : new JsonObject();
                    cacheCreatedResource(entry, bodies.get(i), resource);
                    markBulkSent(entry, resource);
                } else if (status == 404 && "DELETE".equalsIgnoreCase(entry.method())) {
                    markBulkSent(entry, new JsonObject());
                } else if (status == 409 && "POST".equalsIgnoreCase(entry.method())) {
                    try {
                        if (reconcileQueuedPost(entry, bodies.get(i))) {
                            // A prior attempt may have committed the create before its
                            // response was lost. Reconcile only by immutable externalId.
                            markBulkSent(entry, resolveCreatedResource(entry, bodies.get(i)));
                        } else {
                            markBulkDead(entry, "Stalwart rejected the queued POST with HTTP 409 and no matching resource was found.");
                        }
                    } catch (IdentityProvisioningException e) {
                        markBulkDead(entry, "Stalwart userName conflict does not match the immutable externalId: " + e);
                    }
                } else if (status >= 400 && status < 500 && status != 408 && status != 429) {
                    markBulkDead(entry, "Stalwart Bulk operation failed with HTTP " + status + ": "
                            + abbreviate(operationResult.toString()));
                } else {
                    markBulkRetry(entry, "Stalwart Bulk operation failed with HTTP " + status + ": "
                            + abbreviate(operationResult.toString()));
                }
            }
        } catch (IOException e) {
            // Do not discard uncertain work. POST replay is reconciled by immutable
            // externalId on the next attempt; PATCH/DELETE operations are idempotent.
            for (ScimOperationOutbox.Entry entry : entries) {
                if ("POST".equalsIgnoreCase(entry.method())) {
                    try {
                        if (reconcileQueuedPost(entry, bodies.get(entries.indexOf(entry)))) {
                            markBulkSent(entry, resolveCreatedResource(entry, bodies.get(entries.indexOf(entry))));
                            continue;
                        }
                    } catch (Exception reconcileError) {
                        markBulkRetry(entry, "Ambiguous Bulk I/O result; POST reconciliation failed: " + reconcileError);
                        continue;
                    }
                }
                markBulkRetry(entry, "Ambiguous Bulk I/O result; durable retry scheduled: " + e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            for (ScimOperationOutbox.Entry entry : entries) {
                markBulkRetry(entry, "Bulk delivery interrupted; durable retry scheduled.");
            }
        } catch (RuntimeException e) {
            for (ScimOperationOutbox.Entry entry : entries) {
                markBulkRetry(entry, "Invalid Bulk response; durable retry scheduled: " + e);
            }
        }
    }

    private void sendDurableIndividualFallback(String endpoint, List<ScimOperationOutbox.Entry> entries) {
        for (ScimOperationOutbox.Entry entry : entries) {
            try {
                JsonObject body = StringUtils.isBlank(entry.dataJson()) ? null
                        : JsonParser.parseString(entry.dataJson()).getAsJsonObject();
                JsonObject result = requestAtIndividual(endpoint, entry.method(), entry.path(), body);
                markBulkSent(entry, result);
            } catch (Exception e) {
                markBulkRetry(entry, "Individual fallback failed: " + e);
            }
        }
    }

    private boolean reconcileQueuedPost(ScimOperationOutbox.Entry entry, JsonObject body)
            throws IdentityProvisioningException {
        return body != null && "/Users".equals(entry.path()) && postResourceNowExists(entry.path(), body);
    }

    private JsonObject resolveCreatedResource(ScimOperationOutbox.Entry entry, JsonObject body)
            throws IdentityProvisioningException {
        if (body != null && "/Users".equals(entry.path()) && body.has("externalId")) {
            String id = findResourceId("Users", "externalId", body.get("externalId").getAsString());
            if (id != null) return requestAtIndividual(entry.endpoint(), "GET", "/Users/" + pathSegment(id), null);
        }
        return new JsonObject();
    }

    private void cacheCreatedResource(ScimOperationOutbox.Entry entry, JsonObject body, JsonObject resource) {
        if (body == null || resource == null || !resource.has("id")) return;
        String id = resource.get("id").getAsString();
        if ("/Users".equals(entry.path())) {
            if (body.has("userName")) cacheResourceId("Users", "userName", body.get("userName").getAsString(), id);
            if (body.has("externalId")) cacheResourceId("Users", "externalId", body.get("externalId").getAsString(), id);
        } else if ("/Groups".equals(entry.path()) && body.has("displayName")) {
            cacheResourceId("Groups", "displayName", body.get("displayName").getAsString(), id);
        }
    }

    private void markBulkSent(ScimOperationOutbox.Entry entry, JsonObject response) {
        try {
            JsonObject compact = new JsonObject();
            if (response != null) {
                for (String attribute : List.of("id", "userName", "externalId", "displayName")) {
                    if (response.has(attribute) && !response.get(attribute).isJsonNull()) {
                        compact.add(attribute, response.get(attribute));
                    }
                }
            }
            scimOperationOutbox.markSent(entry.id(), entry.leaseOwner(),
                    compact.size() == 0 ? null : compact.toString());
        } catch (SQLException e) {
            log.error("Unable to acknowledge durable SCIM operation " + entry.id() + ".", e);
        }
    }

    private void markBulkRetry(ScimOperationOutbox.Entry entry, String error) {
        try {
            scimOperationOutbox.markRetry(entry, error, 20);
        } catch (SQLException e) {
            log.error("Unable to persist retry state for durable SCIM operation " + entry.id() + ".", e);
        }
    }

    private void markBulkDead(ScimOperationOutbox.Entry entry, String error) {
        try {
            scimOperationOutbox.markDead(entry, error);
        } catch (SQLException e) {
            log.error("Unable to mark durable SCIM operation " + entry.id() + " as DEAD.", e);
        }
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
                throw new IdentityProvisioningException("Stalwart SCIM " + method + " request failed with HTTP "
                        + status + ": " + abbreviate(response.body()));
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

    private static String abbreviate(String value) {
        if (value == null) return "";
        return value.length() > 1200 ? value.substring(0, 1200) + "..." : value;
    }

    @Override
    protected String getUserStoreDomainName() {
        return userStoreDomainName;
    }

}
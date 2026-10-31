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
import org.wso2.carbon.identity.provisioning.ProvisioningOperation;
import org.wso2.carbon.identity.provisioning.ProvisioningUtil;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WSO2 outbound provisioning connector that speaks SCIM 2.0 directly to Stalwart.
 * The upstream WSO2 SCIM connector uses Charon's legacy SCIM 1.0 model and Basic
 * authentication; it is deliberately not used here.
 */
public class StalwartSCIMProvisioningConnector extends AbstractOutboundProvisioningConnector {

    private static final long serialVersionUID = 1L;
    private static final Log log = LogFactory.getLog(StalwartSCIMProvisioningConnector.class);

    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    private static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
    private static final String PATCH_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    private static final String USERNAME_CLAIM = IdentityProvisioningConstants.USERNAME_CLAIM_URI;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final long CACHE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final ConcurrentMap<String, CacheEntry> RESOURCE_ID_CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger CACHE_INSERTIONS = new AtomicInteger();
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
                    case StalwartSCIMProvisioningConnectorConstants.ACTIVE_CLAIM_URI -> activeClaimUri = value;
                    case StalwartSCIMProvisioningConnectorConstants.INVERT_ACTIVE_CLAIM ->
                            invertActiveClaim = Boolean.parseBoolean(value);
                    case StalwartSCIMProvisioningConnectorConstants.EMAIL_MEMBERSHIP_GROUP ->
                            emailMembershipGroup = value;
                    default -> log.debug("Ignoring unsupported Stalwart connector property: " + property.getName());
                }
            }
        }
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
        String userName = targetUserName(attrs, entity.getEntityName());
        JsonObject user = userPayload(attrs, userName);
        // WSO2 can retry a failed provisioning event. Reuse an existing account so
        // a later group-membership failure does not cause a duplicate-user conflict.
        String existingId = findUserId(attrs, entity.getEntityName());
        if (existingId == null) {
            request("POST", "/Users", user);
        } else {
            log.info("Stalwart user already exists; reconciling attributes and memberships: " + userName);
            updateUser(entity);
        }
        updateUserGroupMemberships(entity);
        List<String> initialGroups = groupNames(attrs);
        if (initialGroups != null && initialGroups.stream().anyMatch(this::isEmailMembershipGroup)) {
            setStalwartActive(attrs, entity.getEntityName(), true);
        }
    }

    private void updateUser(ProvisioningEntity entity) throws IdentityProvisioningException {
        Map<ClaimMapping, List<String>> attrs = entity.getAttributes();
        List<String> newGroups = claimValues(attrs, IdentityProvisioningConstants.NEW_GROUP_CLAIM_URI);
        List<String> deletedGroups = claimValues(attrs, IdentityProvisioningConstants.DELETED_GROUP_CLAIM_URI);
        boolean emailMembershipRemoved = false;
        boolean emailMembershipAdded = false;
        if (CollectionUtils.isNotEmpty(newGroups) || CollectionUtils.isNotEmpty(deletedGroups)) {
            if (newGroups != null) {
                for (String group : newGroups) {
                    if (isEmailMembershipGroup(group)) {
                        emailMembershipAdded = true;
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
        if (emailMembershipAdded && findUserId(attrs, entity.getEntityName()) == null) {
            // A user may be granted Email access before the initial USER POST
            // reaches this connector. Create the account here rather than
            // attempting to PATCH a resource that does not yet exist.
            request("POST", "/Users", userPayload(attrs, targetUserName(attrs, entity.getEntityName())));
        }
        JsonObject patch = userPatch(attrs, enableProfileUpdates);
        if (patch.getAsJsonArray("Operations").size() > 0) {
            String id = findUserId(attrs, entity.getEntityName());
            if (id != null) {
                // Profile claims are authoritative deltas from WSO2. Avoid a
                // read-before-write GET; SCIM replace operations are idempotent.
                requestAt(profileUpdateBaseUrl(), "PATCH", "/Users/" + pathSegment(id), patch);
            } else {
                throw new IdentityProvisioningException("Stalwart user was not found for update; stable externalId lookup failed.");
            }
        }
        String activeClaim = mappedValue(attrs, activeClaimUri);
        if (StringUtils.isNotBlank(activeClaim)) {
            boolean activeValue = parseBooleanClaim(activeClaim, activeClaimUri);
            setStalwartActive(attrs, entity.getEntityName(), invertActiveClaim ? !activeValue : activeValue);
        }
        // Email-group entitlement has precedence over an independently mapped active claim.
        if (emailMembershipRemoved || emailMembershipAdded) {
            // Group entitlement takes precedence over a simultaneous active claim.
            setStalwartActive(attrs, entity.getEntityName(), !emailMembershipRemoved);
        }
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
            invalidateResourceId("Users", "externalId", mappedValue(entity.getAttributes(), externalIdClaimUri));
            invalidateResourceId("Users", "userName", targetUserName(entity.getAttributes(), entity.getEntityName()));
        }
    }

    private void createGroup(ProvisioningEntity entity) throws IdentityProvisioningException {
        Map<ClaimMapping, List<String>> attrs = entity.getAttributes();
        List<String> names = groupNames(attrs);
        String displayName = CollectionUtils.isNotEmpty(names) ? names.get(0) : entity.getEntityName();
        // Bulk/JIT delivery can invoke the same create event more than once.
        // Check by stable group name before POST; the request layer also resolves
        // a concurrent duplicate POST after a 409 response.
        String existingId = findResourceId("Groups", "displayName", displayName);
        if (existingId != null) {
            log.info("Stalwart group already exists; reconciling memberships: " + displayName);
            List<String> existingUsers = userNames(attrs);
            if (existingUsers != null) {
                for (String user : existingUsers) {
                    updateGroupMembership(displayName, user, true);
                }
            }
            return;
        }

        JsonObject group = new JsonObject();
        JsonArray schemas = new JsonArray();
        schemas.add(GROUP_SCHEMA);
        group.add("schemas", schemas);
        group.addProperty("displayName", displayName);
        group.addProperty("externalId", displayName);
        JsonArray members = new JsonArray();
        List<String> users = userNames(attrs);
        if (users != null) {
            for (String user : users) {
                JsonObject member = resolveMember(user);
                members.add(member);
            }
        }
        if (!members.isEmpty()) {
            group.add("members", members);
        }
        request("POST", "/Groups", group);
    }

    private void updateGroup(ProvisioningEntity entity) throws IdentityProvisioningException {
        Map<ClaimMapping, List<String>> attrs = entity.getAttributes();
        List<String> names = groupNames(attrs);
        String groupName = CollectionUtils.isNotEmpty(names) ? names.get(0) : entity.getEntityName();
        String oldName = ProvisioningUtil.getAttributeValue(entity, IdentityProvisioningConstants.OLD_GROUP_NAME_CLAIM_URI);
        String lookupName = StringUtils.isNotBlank(oldName) ? oldName : groupName;
        String groupId = findResourceId("Groups", "displayName", lookupName);
        if (groupId == null) {
            throw new IdentityProvisioningException("Stalwart group was not found: " + lookupName);
        }
        JsonArray operations = new JsonArray();
        if (StringUtils.isNotBlank(oldName) && !oldName.equals(groupName)) {
            addReplace(operations, "displayName", groupName);
        }
        JsonObject currentGroup = request("GET", "/Groups/" + pathSegment(groupId), null);
        java.util.Set<String> currentMemberIds = new java.util.HashSet<>();
        if (currentGroup.has("members") && currentGroup.get("members").isJsonArray()) {
            for (JsonElement member : currentGroup.getAsJsonArray("members")) {
                if (member.isJsonObject() && member.getAsJsonObject().has("value")) {
                    currentMemberIds.add(member.getAsJsonObject().get("value").getAsString());
                }
            }
        }
        List<String> added = userNames(attrs);
        if (added != null) {
            for (String username : added) {
                JsonObject member = resolveMember(username);
                String memberId = member.get("value").getAsString();
                if (currentMemberIds.add(memberId)) {
                    JsonArray one = new JsonArray();
                    one.add(member);
                    addOperation(operations, "add", "members", one);
                }
            }
        }
        List<String> removed = claimValues(attrs, IdentityProvisioningConstants.DELETED_USER_CLAIM_URI);
        if (removed != null) {
            for (String username : removed) {
                String id = findUserIdByIdentifier(username);
                if (id != null && currentMemberIds.remove(id)) {
                    addOperation(operations, "remove", "members[value eq \"" + escapeFilter(id) + "\"]", null);
                }
            }
        }
        if (!operations.isEmpty()) {
            JsonObject patch = patchDocument(operations);
            request("PATCH", "/Groups/" + pathSegment(groupId), patch);
        }
    }

    private void deleteGroup(ProvisioningEntity entity) throws IdentityProvisioningException {
        List<String> names = groupNames(entity.getAttributes());
        String groupName = CollectionUtils.isNotEmpty(names) ? names.get(0) : entity.getEntityName();
        String id = findResourceId("Groups", "displayName", groupName);
        if (id != null) {
            request("DELETE", "/Groups/" + pathSegment(id), null);
            invalidateResourceId("Groups", "displayName", groupName);
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
        JsonObject user = new JsonObject();
        JsonArray schemas = new JsonArray();
        schemas.add(USER_SCHEMA);
        user.add("schemas", schemas);
        user.addProperty("userName", userName);
        String externalId = mappedValue(attrs, externalIdClaimUri);
        if (StringUtils.isBlank(externalId)) {
            throw new IdentityProvisioningException("A non-empty stable WSO2 externalId claim is required to create a Stalwart account.");
        }
        user.addProperty("externalId", externalId.trim());
        JsonObject name = new JsonObject();
        String given = mappedValue(attrs, givenNameClaimUri);
        String family = mappedValue(attrs, familyNameClaimUri);
        if (StringUtils.isNotBlank(given)) {
            name.addProperty("givenName", given);
        }
        if (StringUtils.isNotBlank(family)) {
            name.addProperty("familyName", family);
        }
        if (!name.isEmpty()) {
            user.add("name", name);
        }
        String displayName = mappedValue(attrs, displayNameClaimUri);
        if (StringUtils.isNotBlank(displayName)) user.addProperty("displayName", displayName.trim());
        // Email aliases are intentionally not provisioned. The primary address is immutable after creation.
        String active = mappedValue(attrs, activeClaimUri);
        if (StringUtils.isNotBlank(active)) {
            boolean activeValue = parseBooleanClaim(active, activeClaimUri);
            user.addProperty("active", invertActiveClaim ? !activeValue : activeValue);
        }
        return user;
    }

    private boolean patchChangesCurrent(JsonObject current, JsonArray operations) {
        for (JsonElement item : operations) {
            JsonObject operation = item.getAsJsonObject();
            if (!"replace".equalsIgnoreCase(operation.has("op") ? operation.get("op").getAsString() : "")) return true;
            String path = operation.has("path") ? operation.get("path").getAsString() : "";
            JsonElement wanted = operation.get("value");
            JsonElement actual = current;
            for (String segment : path.split("\\.")) {
                if (actual == null || !actual.isJsonObject() || !actual.getAsJsonObject().has(segment)) {
                    actual = null;
                    break;
                }
                actual = actual.getAsJsonObject().get(segment);
            }
            if (wanted == null || actual == null || !wanted.equals(actual)) return true;
        }
        return false;
    }

    private JsonObject userPatch(Map<ClaimMapping, List<String>> attrs, boolean includeProfile)
            throws IdentityProvisioningException {
        JsonArray operations = new JsonArray();

        // userName and externalId are identity keys, not mutable profile fields.
        // Never PATCH either one: changing userName changes the primary SMTP address.
        String given = mappedValue(attrs, givenNameClaimUri);
        String family = mappedValue(attrs, familyNameClaimUri);
        String display = mappedValue(attrs, displayNameClaimUri);
        // Update each supplied profile claim independently; absent claims are preserved.
        if (includeProfile && StringUtils.isNotBlank(given)) {
            addReplace(operations, "name.givenName", given.trim());
        }
        if (includeProfile && StringUtils.isNotBlank(family)) {
            addReplace(operations, "name.familyName", family.trim());
        }
        if (includeProfile && StringUtils.isNotBlank(display)) {
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

    private JsonObject resolveMember(String identifier) throws IdentityProvisioningException {
        String id = findUserIdByIdentifier(identifier);
        if (id == null) {
            throw new IdentityProvisioningException("Cannot resolve group member in Stalwart: " + identifier);
        }
        JsonObject member = new JsonObject();
        member.addProperty("value", id);
        member.addProperty("display", identifier);
        member.addProperty("type", "User");
        return member;
    }

    private String findUserId(Map<ClaimMapping, List<String>> attrs, String fallback)
            throws IdentityProvisioningException {
        String externalId = mappedValue(attrs, externalIdClaimUri);
        // externalId is mandatory for connector-created accounts and is the
        // authoritative key. Do not issue a second username lookup on every
        // cold cache miss; use userName only when no externalId claim is supplied.
        if (StringUtils.isNotBlank(externalId)) {
            return findResourceId("Users", "externalId", externalId);
        }
        return findResourceId("Users", "userName", targetUserName(attrs, fallback));
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
        String query = "?filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8) + "&count=2";
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
        return baseUrl + "|" + resource + "|" + attribute + "|"
                + value.trim().toLowerCase(java.util.Locale.ROOT);
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

    private String profileUpdateBaseUrl() {
        return enableProfileUpdates && StringUtils.isNotBlank(profileUpdateScimUrl) ? profileUpdateScimUrl : baseUrl;
    }

    private JsonObject request(String method, String path, JsonObject body) throws IdentityProvisioningException {
        return requestAt(baseUrl, method, path, body);
    }

    private JsonObject requestAt(String endpointBaseUrl, String method, String path, JsonObject body)
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
            HttpResponse<String> response = HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
            }
            if (status < 200 || status >= 300) {
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

    private boolean isIdempotentPatch(JsonObject body) {
        if (body == null || !body.has("Operations") || !body.get("Operations").isJsonArray()) return false;
        for (JsonElement element : body.getAsJsonArray("Operations")) {
            if (!element.isJsonObject() || !element.getAsJsonObject().has("op")) return false;
            String op = element.getAsJsonObject().get("op").getAsString();
            if (!"replace".equalsIgnoreCase(op) && !"remove".equalsIgnoreCase(op)) return false;
        }
        return !body.getAsJsonArray("Operations").isEmpty();
    }

    private boolean postResourceNowExists(String path, JsonObject body) throws IdentityProvisioningException {
        if (body == null || !path.matches("^/(Users|Groups)$")) return false;
        String resource = path.substring(1);
        String attribute = "Users".equals(resource) ? "userName" : "displayName";
        if (!body.has(attribute)) return false;
        String value = body.get(attribute).getAsString();
        return StringUtils.isNotBlank(value) && findResourceId(resource, attribute, value) != null;
    }

    private void retryBackoff(int attempt, String retryAfter) throws IdentityProvisioningException {
        try {
            long delay = 250L * (1L << (attempt - 1));
            if (StringUtils.isNotBlank(retryAfter)) {
                try { delay = Math.max(delay, Math.min(10_000L, Long.parseLong(retryAfter.trim()) * 1000L)); }
                catch (NumberFormatException ignored) { /* Fall back to bounded exponential backoff. */ }
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

    private List<String> userNames(Map<ClaimMapping, List<String>> attrs) {
        return claimValues(attrs, USERNAME_CLAIM);
    }

    private List<String> groupNames(Map<ClaimMapping, List<String>> attrs) {
        List<String> names = claimValues(attrs, IdentityProvisioningConstants.GROUP_CLAIM_URI);
        if (names != null) {
            List<String> normalized = new ArrayList<>();
            for (String name : names) normalized.add(stripUserStoreDomain(name));
            return normalized;
        }
        return null;
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

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
            .connectTimeout(Duration.ofSeconds(15)).build();

    private String baseUrl;
    private String bearerToken;
    private String userStoreDomainName;
    private boolean claimMappingEnabled = true;
    private String userNameClaimUri = "http://wso2.org/claims/emailaddress";
    private String externalIdClaimUri = "http://wso2.org/claims/username";
    private String givenNameClaimUri = "http://wso2.org/claims/givenname";
    private String familyNameClaimUri = "http://wso2.org/claims/lastname";
    private String activeClaimUri = "";
    private String emailAliasesClaimUri = "";
    private boolean invertActiveClaim = false;

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
                    case StalwartSCIMProvisioningConnectorConstants.ACTIVE_CLAIM_URI -> activeClaimUri = value;
                    case StalwartSCIMProvisioningConnectorConstants.EMAIL_ALIASES_CLAIM_URI -> emailAliasesClaimUri = value;
                    case StalwartSCIMProvisioningConnectorConstants.INVERT_ACTIVE_CLAIM ->
                            invertActiveClaim = Boolean.parseBoolean(value);
                    case IdentityProvisioningConstants.JIT_PROVISIONING_ENABLED ->
                            jitProvisioningEnabled = "1".equals(value) || Boolean.parseBoolean(value);
                    default -> log.debug("Ignoring unsupported Stalwart connector property: " + property.getName());
                }
            }
        }
        if (StringUtils.isBlank(baseUrl) || StringUtils.isBlank(bearerToken)) {
            throw new IdentityProvisioningException("Stalwart SCIM base URL and bearer token are required.");
        }
        if (!baseUrl.endsWith("/scim/v2")) {
            throw new IdentityProvisioningException("Base URL must end with /scim/v2, for example https://mail.example.com/scim/v2");
        }
    }

    @Override
    public ProvisionedIdentifier provision(ProvisioningEntity entity) throws IdentityProvisioningException {
        if (entity == null) {
            return null;
        }
        if (entity.isJitProvisioning()) {
            if (!isJitProvisioningEnabled()) {
                log.debug("JIT provisioning event skipped: connector JIT setting is disabled.");
                return null;
            }
            log.debug("JIT provisioning event accepted; executing outbound SCIM operation.");
        }
        if (entity.getEntityType() == ProvisioningEntityType.USER) {
            switch (entity.getOperation()) {
                case POST -> createUser(entity);
                case PUT, PATCH -> updateUser(entity);
                case DELETE -> deleteUser(entity);
                default -> log.warn("Unsupported user provisioning operation: " + entity.getOperation());
            }
        } else if (entity.getEntityType() == ProvisioningEntityType.GROUP) {
            switch (entity.getOperation()) {
                case POST -> createGroup(entity);
                case PUT, PATCH -> updateGroup(entity);
                case DELETE -> deleteGroup(entity);
                default -> log.warn("Unsupported group provisioning operation: " + entity.getOperation());
            }
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
    }

    private void updateUser(ProvisioningEntity entity) throws IdentityProvisioningException {
        Map<ClaimMapping, List<String>> attrs = entity.getAttributes();
        List<String> newGroups = claimValues(attrs, IdentityProvisioningConstants.NEW_GROUP_CLAIM_URI);
        List<String> deletedGroups = claimValues(attrs, IdentityProvisioningConstants.DELETED_GROUP_CLAIM_URI);
        if (CollectionUtils.isNotEmpty(newGroups) || CollectionUtils.isNotEmpty(deletedGroups)) {
            if (newGroups != null) {
                for (String group : newGroups) {
                    updateGroupMembership(group, targetUserName(attrs, entity.getEntityName()), true);
                }
            }
            if (deletedGroups != null) {
                for (String group : deletedGroups) {
                    updateGroupMembership(group, targetUserName(attrs, entity.getEntityName()), false);
                }
            }
        }
        JsonObject patch = userPatch(attrs);
        List<String> aliases = claimValues(attrs, emailAliasesClaimUri);
        boolean reconcileAliases = StringUtils.isNotBlank(emailAliasesClaimUri) && aliases != null;
        if (patch.getAsJsonArray("Operations").size() > 0 || reconcileAliases) {
            String id = findUserId(attrs, entity.getEntityName());
            if (id != null) {
                JsonObject current = request("GET", "/Users/" + pathSegment(id), null);
                if (reconcileAliases) reconcileEmailAliases(current, aliases, targetUserName(attrs, entity.getEntityName()),
                        patch.getAsJsonArray("Operations"));
                if (patchChangesCurrent(current, patch.getAsJsonArray("Operations"))) {
                    request("PATCH", "/Users/" + pathSegment(id), patch);
                } else {
                    log.debug("Skipping unchanged user PATCH; userId=" + id + ", operation=UPDATE");
                }
            } else {
                throw new IdentityProvisioningException("Stalwart user was not found for update: "
                        + targetUserName(attrs, entity.getEntityName()));
            }
        }
    }

    private void deleteUser(ProvisioningEntity entity) throws IdentityProvisioningException {
        String id = findUserId(entity.getAttributes(), entity.getEntityName());
        if (id != null) {
            request("DELETE", "/Users/" + pathSegment(id), null);
        }
    }

    private void createGroup(ProvisioningEntity entity) throws IdentityProvisioningException {
        Map<ClaimMapping, List<String>> attrs = entity.getAttributes();
        List<String> names = groupNames(attrs);
        String displayName = CollectionUtils.isNotEmpty(names) ? names.get(0) : entity.getEntityName();
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
        List<String> added = userNames(attrs);
        if (added != null) {
            for (String username : added) {
                JsonObject member = resolveMember(username);
                JsonArray one = new JsonArray();
                one.add(member);
                addOperation(operations, "add", "members", one);
            }
        }
        List<String> removed = claimValues(attrs, IdentityProvisioningConstants.DELETED_USER_CLAIM_URI);
        if (removed != null) {
            for (String username : removed) {
                String id = findUserIdByIdentifier(username);
                if (id != null) {
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
        }
    }

    private void updateUserGroupMemberships(ProvisioningEntity entity)
            throws IdentityProvisioningException {
        List<String> groups = claimValues(entity.getAttributes(), IdentityProvisioningConstants.GROUP_CLAIM_URI);
        if (groups != null) {
            String username = targetUserName(entity.getAttributes(), entity.getEntityName());
            for (String group : groups) {
                updateGroupMembership(group, username, true);
            }
        }
    }

    private void updateGroupMembership(String groupName, String username, boolean add)
            throws IdentityProvisioningException {
        String groupId = findResourceId("Groups", "displayName", groupName);
        if (groupId == null) {
            throw new IdentityProvisioningException("Stalwart group was not found for membership update: " + groupName);
        }
        JsonArray operations = new JsonArray();
        String userId = findUserIdByIdentifier(username);
        if (userId == null) {
            throw new IdentityProvisioningException("Stalwart user was not found for group membership: " + username);
        }
        JsonObject currentGroup = request("GET", "/Groups/" + pathSegment(groupId), null);
        boolean alreadyMember = false;
        if (currentGroup.has("members") && currentGroup.get("members").isJsonArray()) {
            for (JsonElement current : currentGroup.getAsJsonArray("members")) {
                if (current.isJsonObject() && current.getAsJsonObject().has("value")
                        && userId.equals(current.getAsJsonObject().get("value").getAsString())) {
                    alreadyMember = true;
                    break;
                }
            }
        }
        // Make membership reconciliation safe to retry after partial provisioning.
        if (add == alreadyMember) {
            return;
        }
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

    private JsonObject userPayload(Map<ClaimMapping, List<String>> attrs, String userName) {
        JsonObject user = new JsonObject();
        JsonArray schemas = new JsonArray();
        schemas.add(USER_SCHEMA);
        user.add("schemas", schemas);
        user.addProperty("userName", userName);
        String externalId = mappedValue(attrs, externalIdClaimUri);
        if (StringUtils.isNotBlank(externalId)) {
            user.addProperty("externalId", externalId);
        }
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
        // Stalwart derives the immutable primary email from userName; only add aliases here.
        List<String> aliases = claimValues(attrs, emailAliasesClaimUri);
        if (aliases != null) {
            JsonArray emails = new JsonArray();
            for (String alias : aliases) {
                if (StringUtils.isNotBlank(alias) && !userName.equalsIgnoreCase(alias.trim())) {
                    JsonObject email = new JsonObject();
                    email.addProperty("value", alias.trim());
                    email.addProperty("type", "work");
                    emails.add(email);
                }
            }
            if (!emails.isEmpty()) user.add("emails", emails);
        }
        String active = mappedValue(attrs, activeClaimUri);
        if (StringUtils.isNotBlank(active)) {
            user.addProperty("active", invertActiveClaim ? !Boolean.parseBoolean(active) : Boolean.parseBoolean(active));
        }
        return user;
    }

    private void reconcileEmailAliases(JsonObject current, List<String> desiredAliases, String primary,
                                       JsonArray operations) {
        java.util.Set<String> desired = new java.util.LinkedHashSet<>();
        for (String alias : desiredAliases) {
            if (StringUtils.isNotBlank(alias) && !alias.trim().equalsIgnoreCase(primary)) {
                desired.add(alias.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        java.util.Set<String> existing = new java.util.LinkedHashSet<>();
        if (current.has("emails") && current.get("emails").isJsonArray()) {
            for (JsonElement item : current.getAsJsonArray("emails")) {
                if (!item.isJsonObject() || !item.getAsJsonObject().has("value")) continue;
                JsonObject email = item.getAsJsonObject();
                String value = email.get("value").getAsString();
                boolean primaryEntry = (email.has("primary") && email.get("primary").getAsBoolean())
                        || value.equalsIgnoreCase(primary);
                if (!primaryEntry) existing.add(value.toLowerCase(java.util.Locale.ROOT));
            }
        }
        for (String alias : existing) {
            if (!desired.contains(alias)) addOperation(operations, "remove",
                    "emails[value eq \"" + escapeFilter(alias) + "\"]", null);
        }
        for (String alias : desired) {
            if (!existing.contains(alias)) {
                JsonObject email = new JsonObject();
                email.addProperty("value", alias);
                email.addProperty("type", "work");
                addOperation(operations, "add", "emails", email);
            }
        }
    }

    private boolean patchChangesCurrent(JsonObject current, JsonArray operations) {
        for (JsonElement item : operations) {
            JsonObject operation = item.getAsJsonObject();
            if (!"replace".equalsIgnoreCase(operation.has("op") ? operation.get("op").getAsString() : "")) return true;
            String path = operation.has("path") ? operation.get("path").getAsString() : "";
            JsonElement wanted = operation.get("value");
            JsonElement actual = current;
            for (String segment : path.split("\\\\.")) {
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

    private JsonObject userPatch(Map<ClaimMapping, List<String>> attrs) {
        JsonArray operations = new JsonArray();
        String userName = mappedValue(attrs, userNameClaimUri);
        if (StringUtils.isNotBlank(userName)) addReplace(operations, "userName", userName);
        String externalId = mappedValue(attrs, externalIdClaimUri);
        if (StringUtils.isNotBlank(externalId)) addReplace(operations, "externalId", externalId);
        String given = mappedValue(attrs, givenNameClaimUri);
        if (StringUtils.isNotBlank(given)) addReplace(operations, "name.givenName", given);
        String family = mappedValue(attrs, familyNameClaimUri);
        if (StringUtils.isNotBlank(family)) addReplace(operations, "name.familyName", family);
        String active = mappedValue(attrs, activeClaimUri);
        if (StringUtils.isNotBlank(active)) addReplace(operations, "active", invertActiveClaim ? !Boolean.parseBoolean(active) : Boolean.parseBoolean(active));
        return patchDocument(operations);
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
        if (StringUtils.isNotBlank(externalId)) {
            String id = findResourceId("Users", "externalId", externalId);
            if (id != null) return id;
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
        id = findResourceId("Users", "userName", candidate);
        return id != null ? id : (candidate.equals(identifier) ? null
                : findResourceId("Users", "userName", identifier));
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
        String filter = normalizeScimFilter(attribute + " Eq " + value);
        String query = "?filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8) + "&count=2";
        JsonObject response = request("GET", "/" + resource + query, null);
        JsonArray resources = response.has("Resources") && response.get("Resources").isJsonArray()
                ? response.getAsJsonArray("Resources") : new JsonArray();
        if (resources.isEmpty()) return null;
        return resources.get(0).getAsJsonObject().has("id")
                ? resources.get(0).getAsJsonObject().get("id").getAsString() : null;
    }

    private JsonObject request(String method, String path, JsonObject body) throws IdentityProvisioningException {
        final int maxAttempts = 3;
        String resourceId = resourceIdFromPath(path);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
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
            log.debug("Stalwart SCIM response; userId=" + resourceId + ", operation=" + method
                    + ", status=" + status + ", attempt=" + attempt);
            if (status == 408 || status == 429 || status >= 500) {
                if ("POST".equals(method) && postResourceNowExists(path, body)) return new JsonObject();
                if (attempt < maxAttempts) { retryBackoff(attempt); continue; }
            }
            if (status < 200 || status >= 300) {
                throw new IdentityProvisioningException("Stalwart SCIM " + method + " request failed with HTTP "
                        + status + ": " + abbreviate(response.body()));
            }
            if (StringUtils.isBlank(response.body())) return new JsonObject();
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (IOException e) {
            log.warn("Stalwart SCIM I/O failure; userId=" + resourceId + ", operation=" + method
                    + ", attempt=" + attempt, e);
            if ("POST".equals(method) && postResourceNowExists(path, body)) return new JsonObject();
            if (attempt == maxAttempts) {
                throw new IdentityProvisioningException("I/O error while calling Stalwart SCIM endpoint after "
                        + maxAttempts + " attempts.", e);
            }
            retryBackoff(attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdentityProvisioningException("Interrupted while calling Stalwart SCIM endpoint.", e);
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException e) {
            throw new IdentityProvisioningException("Invalid Stalwart SCIM response or endpoint.", e);
        }
        }
        throw new IdentityProvisioningException("Stalwart SCIM request failed after retries.");
    }

    private boolean postResourceNowExists(String path, JsonObject body) throws IdentityProvisioningException {
        if (body == null || !path.matches("^/(Users|Groups)$")) return false;
        String resource = path.substring(1);
        String attribute = "Users".equals(resource) ? "userName" : "displayName";
        if (!body.has(attribute)) return false;
        String value = body.get(attribute).getAsString();
        return StringUtils.isNotBlank(value) && findResourceId(resource, attribute, value) != null;
    }

    private void retryBackoff(int attempt) throws IdentityProvisioningException {
        try {
            Thread.sleep(250L * (1L << (attempt - 1)));
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
        if (matcher.matches()) normalized = matcher.group(1) + "\\\"" + escapeFilter(matcher.group(2).trim()) + "\\\"";
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

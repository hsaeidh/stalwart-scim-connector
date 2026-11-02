/*
 * Copyright (c) 2026, IRISA.
 * Licensed under the Apache License, Version 2.0.
 */
package org.stalwart.identity.provisioning.connector.scim.event;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.stalwart.identity.provisioning.connector.scim.HybridProfileOutbox;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Deactivate;
import org.wso2.carbon.identity.core.bean.context.MessageContext;
import org.wso2.carbon.identity.event.IdentityEventConstants;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.event.handler.AbstractEventHandler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.lang.reflect.Method;

/**
 * Observation-only WSO2 event handler for validating event delivery on IS 7.3.0.
 *
 * This bundle deliberately does not call Stalwart and does not enqueue work.
 * Outbound provisioning remains the only mutation path until durable outbox
 * delivery and duplicate-ingress semantics have been verified.
 */
@Component(
        name = "org.stalwart.identity.provisioning.connector.scim.event.StalwartSCIMProfileEventHandler",
        service = AbstractEventHandler.class,
        immediate = true
)
public final class StalwartSCIMProfileEventHandler extends AbstractEventHandler {

    private static final Log LOG = LogFactory.getLog(StalwartSCIMProfileEventHandler.class);
    private static volatile HybridProfileOutbox outbox;

    @Activate
    protected void activate() {
        LOG.info("Stalwart SCIM WSO2 event handler activated; HYBRID snapshot ingestion enabled="
                + Boolean.parseBoolean(System.getProperty("stalwart.scim.hybrid.enabled", "false")));
    }

    @Deactivate
    protected void deactivate() {
        LOG.info("Stalwart SCIM WSO2 event handler deactivated.");
        outbox = null;
    }

    @Override
    public String getName() {
        return "stalwart.scim.profile.observer";
    }

    @Override
    public int getPriority(MessageContext messageContext) {
        return 100;
    }

    @Override
    public void handleEvent(Event event) throws IdentityEventException {
        if (event == null || event.getEventName() == null) {
            return;
        }
        String eventName = event.getEventName();
        if (!IdentityEventConstants.Event.POST_ADD_USER.equals(eventName)
                && !IdentityEventConstants.Event.POST_SET_USER_CLAIMS.equals(eventName)) {
            return;
        }

        Map<String, Object> properties = event.getEventProperties();
        if (properties == null) {
            LOG.info("Stalwart SCIM observer received " + eventName + " with no event properties.");
            return;
        }

        Object userNameValue = properties.get(IdentityEventConstants.EventProperty.USER_NAME);
        String userName = userNameValue instanceof String ? (String) userNameValue : null;
        Object claimsValue = properties.get(IdentityEventConstants.EventProperty.USER_CLAIMS);
        boolean userStoreManagerAvailable =
                properties.get(IdentityEventConstants.EventProperty.USER_STORE_MANAGER) != null;

        boolean givenNameChanged = containsClaim(claimsValue, claimUri("stalwart.scim.hybrid.given-name-claim-uri", "http://wso2.org/claims/givenname"));
        boolean familyNameChanged = containsClaim(claimsValue, claimUri("stalwart.scim.hybrid.family-name-claim-uri", "http://wso2.org/claims/lastname"));
        boolean displayNameChanged = containsClaim(claimsValue, claimUri("stalwart.scim.hybrid.display-name-claim-uri", "http://wso2.org/claims/displayname"));

        LOG.info("Stalwart SCIM observer event=" + eventName
                + ", userRef=" + safeReference(userName)
                + ", userStoreManagerAvailable=" + userStoreManagerAvailable
                + ", userClaimsPresent=" + (claimsValue != null)
                + ", givenNameChanged=" + givenNameChanged
                + ", familyNameChanged=" + familyNameChanged
                + ", displayNameChanged=" + displayNameChanged);

        if (!"POST_SET_USER_CLAIMS".equals(eventName)
                || !(givenNameChanged || familyNameChanged || displayNameChanged)
                || !Boolean.parseBoolean(System.getProperty("stalwart.scim.hybrid.enabled", "false"))) {
            return;
        }
        try {
            enqueueAuthoritativeSnapshot(properties, userName, givenNameChanged, familyNameChanged,
                    displayNameChanged);
        } catch (Exception e) {
            LOG.error("Unable to persist authoritative WSO2 name snapshot in the Stalwart HYBRID outbox.", e);
            throw new IdentityEventException("Unable to persist Stalwart HYBRID profile update.", e);
        }
    }

    private void enqueueAuthoritativeSnapshot(Map<String, Object> properties, String userName,
                                              boolean givenChanged, boolean familyChanged,
                                              boolean displayChanged) throws Exception {
        if (userName == null || userName.isBlank()) {
            throw new IllegalStateException("POST_SET_USER_CLAIMS event did not include user-name.");
        }
        Object manager = properties.get(IdentityEventConstants.EventProperty.USER_STORE_MANAGER);
        if (manager == null) {
            throw new IllegalStateException("POST_SET_USER_CLAIMS event did not include userStoreManager.");
        }
        String userStoreDomain = stringValue(
                properties.get(IdentityEventConstants.EventProperty.USER_STORE_DOMAIN));
        String tenantDomain = stringValue(properties.get(IdentityEventConstants.EventProperty.TENANT_DOMAIN));
        if (tenantDomain == null) tenantDomain = "carbon.super";
        if (userStoreDomain == null) userStoreDomain = domainFromUserName(userName);
        if (userStoreDomain == null) userStoreDomain = "PRIMARY";

        String localUserName = removeDomain(userName, userStoreDomain);
        Map<String, String> snapshot = readClaims(manager, localUserName,
                new String[]{
                        claimUri("stalwart.scim.hybrid.given-name-claim-uri", "http://wso2.org/claims/givenname"),
                        claimUri("stalwart.scim.hybrid.family-name-claim-uri", "http://wso2.org/claims/lastname"),
                        claimUri("stalwart.scim.hybrid.display-name-claim-uri", "http://wso2.org/claims/displayname"),
                        claimUri("stalwart.scim.hybrid.external-id-claim-uri", "http://wso2.org/claims/username")
                });
        String externalIdClaim = claimUri("stalwart.scim.hybrid.external-id-claim-uri",
                "http://wso2.org/claims/username");
        String externalId = snapshot.get(externalIdClaim);
        if ((externalId == null || externalId.isBlank())
                && "http://wso2.org/claims/username".equals(externalIdClaim)) {
            externalId = localUserName;
        }
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalStateException("Current WSO2 snapshot did not contain a stable externalId.");
        }

        String displayName;
        String source;
        String displayClaim = claimUri("stalwart.scim.hybrid.display-name-claim-uri",
                "http://wso2.org/claims/displayname");
        String givenClaim = claimUri("stalwart.scim.hybrid.given-name-claim-uri",
                "http://wso2.org/claims/givenname");
        String familyClaim = claimUri("stalwart.scim.hybrid.family-name-claim-uri",
                "http://wso2.org/claims/lastname");
        if (displayChanged) {
            if (!snapshot.containsKey(displayClaim)) {
                throw new IllegalStateException("The authoritative WSO2 snapshot omitted the changed DISPLAYNAME claim.");
            }
            displayName = snapshot.get(displayClaim);
            source = "EVENT_DISPLAYNAME";
        } else if (givenChanged || familyChanged) {
            // Both claim keys must be returned by the authoritative user-store
            // read. Empty values are allowed; missing values are not guessed.
            if (!snapshot.containsKey(givenClaim) || !snapshot.containsKey(familyClaim)) {
                throw new IllegalStateException(
                        "The authoritative WSO2 snapshot is missing FIRSTNAME or LASTNAME; update remains unqueued.");
            }
            displayName = joinName(snapshot.get(givenClaim), snapshot.get(familyClaim));
            source = "EVENT_NAMEPARTS";
        } else {
            return;
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalStateException("The authoritative current WSO2 name snapshot is empty.");
        }

        String organizationId = stringValue(properties.get("USER_RESIDENT_ORGANIZATION_ID"));
        if (organizationId == null) {
            organizationId = stringValue(properties.get("ACCESSING_ORGANIZATION_ID"));
        }
        String targetId = System.getProperty("stalwart.scim.hybrid.target-id", "default");
        String scope = organizationId == null
                ? targetId + "|" + tenantDomain + "|" + userStoreDomain
                : targetId + "|" + organizationId + "|" + tenantDomain + "|" + userStoreDomain;
        String jndiName = System.getProperty("stalwart.scim.hybrid.outbox.jndi",
                HybridProfileOutbox.DEFAULT_JNDI_NAME);
        getOutbox(jndiName).enqueue(scope, externalId, displayName, source, 2);
        LOG.info("Stalwart HYBRID profile snapshot persisted; scope=" + scope
                + ", userRef=" + safeReference(externalId) + ", source=" + source);
    }

    private static synchronized HybridProfileOutbox getOutbox(String jndiName) throws Exception {
        if (outbox == null) outbox = HybridProfileOutbox.lookup(jndiName);
        return outbox;
    }

    private Map<String, String> readClaims(Object userStoreManager, String userName, String[] claimUris)
            throws Exception {
        ClassLoader loader = userStoreManager.getClass().getClassLoader();
        Class<?> managerApi = Class.forName("org.wso2.carbon.user.core.UserStoreManager", true, loader);
        Method method = managerApi.getMethod(
                "getUserClaimValues", String.class, String[].class, String.class);
        Object value = method.invoke(userStoreManager, userName, claimUris, null);
        if (!(value instanceof Map<?, ?> values)) {
            throw new IllegalStateException("UserStoreManager returned no claim snapshot.");
        }
        java.util.HashMap<String, String> result = new java.util.HashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        return result;
    }

    private boolean containsClaim(Object claims, String claimUri) {
        if (!(claims instanceof Map<?, ?> claimMap)) return false;
        if (claimMap.containsKey(claimUri)) return true;
        // WSO2 versions may expose USER_CLAIMS with ClaimMapping keys rather
        // than raw URI strings. Inspect the local claim URI without logging values.
        for (Object key : claimMap.keySet()) {
            if (key == null) continue;
            try {
                Object localClaim = key.getClass().getMethod("getLocalClaim").invoke(key);
                if (localClaim != null) {
                    Object uri = localClaim.getClass().getMethod("getClaimUri").invoke(localClaim);
                    if (claimUri.equals(uri)) return true;
                }
            } catch (ReflectiveOperationException ignored) {
                // A non-ClaimMapping key is simply not a matching claim.
            }
        }
        return false;
    }

    private String joinName(String given, String family) {
        String first = given == null ? "" : given.trim();
        String last = family == null ? "" : family.trim();
        if (first.isEmpty()) return last;
        if (last.isEmpty()) return first;
        return first + " " + last;
    }

    private String claimUri(String property, String defaultValue) {
        String value = System.getProperty(property);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String result = value.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private String domainFromUserName(String userName) {
        int separator = userName.indexOf('/');
        return separator > 0 ? userName.substring(0, separator) : null;
    }

    private String removeDomain(String userName, String domain) {
        String prefix = domain + "/";
        return userName != null && userName.startsWith(prefix) ? userName.substring(prefix.length()) : userName;
    }

    private String safeReference(String value) {
        if (value == null || value.isBlank()) {
            return "unavailable";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                result.append(String.format("%02x", digest[i]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            return "redacted";
        }
    }
}

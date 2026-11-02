/*
 * Copyright (c) 2026, IRISA.
 * Licensed under the Apache License, Version 2.0.
 */
package org.stalwart.identity.provisioning.connector.scim.event;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.core.bean.context.MessageContext;
import org.wso2.carbon.identity.event.IdentityEventConstants;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.event.handler.AbstractEventHandler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Observation-only WSO2 event handler for validating event delivery on IS 7.3.0.
 *
 * This handler deliberately does not call Stalwart and does not enqueue work.
 * Outbound provisioning remains the only mutation path until durable outbox
 * delivery and duplicate-ingress semantics have been verified.
 */
public final class StalwartSCIMProfileEventHandler extends AbstractEventHandler {

    private static final Log LOG = LogFactory.getLog(StalwartSCIMProfileEventHandler.class);

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

        boolean givenNameChanged = containsClaim(claimsValue, "http://wso2.org/claims/givenname");
        boolean familyNameChanged = containsClaim(claimsValue, "http://wso2.org/claims/lastname");
        boolean displayNameChanged = containsClaim(claimsValue, "http://wso2.org/claims/displayname");

        LOG.info("Stalwart SCIM observer event=" + eventName
                + ", userRef=" + safeReference(userName)
                + ", userStoreManagerAvailable=" + userStoreManagerAvailable
                + ", userClaimsPresent=" + (claimsValue != null)
                + ", givenNameChanged=" + givenNameChanged
                + ", familyNameChanged=" + familyNameChanged
                + ", displayNameChanged=" + displayNameChanged);
    }

    private boolean containsClaim(Object claims, String claimUri) {
        if (!(claims instanceof Map<?, ?> claimMap)) {
            return false;
        }
        return claimMap.containsKey(claimUri);
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

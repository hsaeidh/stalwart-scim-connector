/*
 * Copyright (c) 2026, IRISA.
 * Licensed under the Apache License, Version 2.0.
 */
package org.stalwart.identity.provisioning.connector.scim;

import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.user.api.UserRealm;
import org.wso2.carbon.user.api.UserStoreManager;
import org.wso2.carbon.user.core.service.RealmService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads authoritative current claims from the active WSO2 tenant realm.
 * The RealmService is injected by the connector bundle's own DS component;
 * no separate Event Handler or deployment.toml registration is required.
 */
public final class Wso2UserClaimSnapshot {

    private static final AtomicReference<RealmService> REALM_SERVICE = new AtomicReference<>();

    private Wso2UserClaimSnapshot() { }

    public static void bind(RealmService service) {
        REALM_SERVICE.set(service);
    }

    public static void unbind(RealmService service) {
        REALM_SERVICE.compareAndSet(service, null);
    }

    public static Map<String, String> read(String userName, String[] claimUris) throws Exception {
        if (userName == null || userName.isBlank()) {
            throw new IllegalArgumentException("A WSO2 local username is required to read profile claims.");
        }
        if (claimUris == null || claimUris.length == 0) {
            throw new IllegalArgumentException("At least one WSO2 claim URI is required.");
        }
        RealmService realmService = REALM_SERVICE.get();
        if (realmService == null) {
            throw new IllegalStateException("WSO2 RealmService is not available to the Stalwart connector bundle.");
        }
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        if (tenantId == -1) {
            throw new IllegalStateException("The current WSO2 tenant context is not available.");
        }
        UserRealm realm = realmService.getTenantUserRealm(tenantId);
        if (realm == null) {
            throw new IllegalStateException("WSO2 UserRealm was not found for the current tenant.");
        }
        UserStoreManager manager = realm.getUserStoreManager();
        if (manager == null) {
            throw new IllegalStateException("WSO2 UserStoreManager is not available for the current tenant.");
        }
        Map<String, String> values = manager.getUserClaimValues(userName, claimUris, null);
        if (values == null) {
            throw new IllegalStateException("WSO2 UserStoreManager returned a null claim snapshot.");
        }
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String claimUri : claimUris) {
            if (values.containsKey(claimUri)) {
                snapshot.put(claimUri, values.get(claimUri));
            }
        }
        return snapshot;
    }
}

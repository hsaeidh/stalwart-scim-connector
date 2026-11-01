/*
 * Copyright (c) 2015, WSO2 Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package org.stalwart.identity.provisioning.connector.scim;

public final class StalwartSCIMProvisioningConnectorConstants {
    private StalwartSCIMProvisioningConnectorConstants() { }

    public static final String BASE_URL = "scim-base-url";
    public static final String BEARER_TOKEN = "scim-bearer-token";
    public static final String USERSTORE_DOMAIN = "scim-user-store-domain";
    public static final String ENABLE_CLAIM_MAPPING = "enable-claim-mapping";
    public static final String USERNAME_CLAIM_URI = "username-claim-uri";
    public static final String EXTERNAL_ID_CLAIM_URI = "external-id-claim-uri";
    public static final String GIVEN_NAME_CLAIM_URI = "given-name-claim-uri";
    public static final String FAMILY_NAME_CLAIM_URI = "family-name-claim-uri";
    public static final String DISPLAY_NAME_CLAIM_URI = "display-name-claim-uri";
    public static final String ENABLE_PROFILE_UPDATES = "enable-profile-updates";
    public static final String PROFILE_UPDATE_SCIM_URL = "profile-update-scim-url";
    public static final String MAX_CONCURRENT_REQUESTS = "max-concurrent-requests";
    public static final String ACTIVE_CLAIM_URI = "active-claim-uri";
    public static final String INVERT_ACTIVE_CLAIM = "invert-active-claim";
    public static final String EMAIL_MEMBERSHIP_GROUP = "email-membership-group";
    public static final String PROVISIONING_MODE = "provisioning-mode";
    public static final String BULK_FALLBACK_TO_INDIVIDUAL = "bulk-fallback-to-individual";
    public static final String BULK_FAIL_ON_ERRORS = "bulk-fail-on-errors";
    public static final String BULK_REQUEST_TIMEOUT_MS = "bulk-request-timeout-ms";
    public static final String BULK_MAX_PAYLOAD_BYTES = "bulk-max-payload-bytes";
}

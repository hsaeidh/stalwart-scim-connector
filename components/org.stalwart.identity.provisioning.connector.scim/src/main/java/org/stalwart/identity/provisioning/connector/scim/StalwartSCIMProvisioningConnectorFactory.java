/*
 * Copyright (c) 2014, WSO2 Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package org.stalwart.identity.provisioning.connector.scim;

import org.wso2.carbon.identity.application.common.model.Property;
import org.wso2.carbon.identity.provisioning.AbstractProvisioningConnectorFactory;
import org.wso2.carbon.identity.provisioning.IdentityProvisioningException;

import java.util.ArrayList;
import java.util.List;

public class StalwartSCIMProvisioningConnectorFactory extends AbstractProvisioningConnectorFactory {

    public static final String CONNECTOR_TYPE = "stalwart-scim";

    @Override
    protected StalwartSCIMProvisioningConnector buildConnector(Property[] properties)
            throws IdentityProvisioningException {
        StalwartSCIMProvisioningConnector connector = new StalwartSCIMProvisioningConnector();
        connector.init(properties);
        return connector;
    }

    @Override
    public String getConnectorType() {
        return CONNECTOR_TYPE;
    }

    @Override
    public List<Property> getConfigurationProperties() {
        System.out.println(">>> STALWART-SCIM getConfigurationProperties() called");
        try {
            List<Property> properties = new ArrayList<>();
            properties.add(property(StalwartSCIMProvisioningConnectorConstants.BASE_URL,
                    "Stalwart SCIM Base URL", "https://mail.example.com/scim/v2", true, false, 1));
            properties.add(property(StalwartSCIMProvisioningConnectorConstants.BEARER_TOKEN,
                    "Stalwart SCIM Bearer Token", null, true, true, 2));
            properties.add(property(StalwartSCIMProvisioningConnectorConstants.USERSTORE_DOMAIN,
                    "User Store Domain", null, false, false, 3));

            Property enabled = property(StalwartSCIMProvisioningConnectorConstants.ENABLE_CLAIM_MAPPING,
                    "Enable Claim Mapping", "true", false, false, 4);
            enabled.setType("boolean");
            properties.add(enabled);

            properties.add(property(StalwartSCIMProvisioningConnectorConstants.USERNAME_CLAIM_URI,
                    "userName source WSO2 local claim URI", "http://wso2.org/claims/emailaddress", true, false, 5));
            properties.add(property(StalwartSCIMProvisioningConnectorConstants.EXTERNAL_ID_CLAIM_URI,
                    "Required immutable externalId source WSO2 local claim URI", "http://wso2.org/claims/username", true, false, 6));
            properties.add(property(StalwartSCIMProvisioningConnectorConstants.GIVEN_NAME_CLAIM_URI,
                    "name.givenName source WSO2 local claim URI", "http://wso2.org/claims/givenname", false, false, 7));
            properties.add(property(StalwartSCIMProvisioningConnectorConstants.FAMILY_NAME_CLAIM_URI,
                    "name.familyName source WSO2 local claim URI", "http://wso2.org/claims/lastname", false, false, 8));
            properties.add(property(StalwartSCIMProvisioningConnectorConstants.DISPLAY_NAME_CLAIM_URI,
                    "displayName source WSO2 local claim URI", "http://wso2.org/claims/displayname", false, false, 9));
            Property profileUpdates = property(StalwartSCIMProvisioningConnectorConstants.ENABLE_PROFILE_UPDATES,
                    "Enable Profile Updates", "true", false, false, 10);
            profileUpdates.setType("boolean");
            properties.add(profileUpdates);
            properties.add(property(StalwartSCIMProvisioningConnectorConstants.PROFILE_UPDATE_SCIM_URL,
                    "Profile Update SCIM URL (optional; defaults to Base URL)", "", false, false, 11));
            properties.add(property(StalwartSCIMProvisioningConnectorConstants.MAX_CONCURRENT_REQUESTS,
                    "Maximum Concurrent SCIM Requests (1-64)", "8", false, false, 12));
            properties.add(property(StalwartSCIMProvisioningConnectorConstants.ACTIVE_CLAIM_URI,
                    "active source WSO2 local Boolean claim URI (optional)", "", false, false, 13));
            Property invertActive = property(StalwartSCIMProvisioningConnectorConstants.INVERT_ACTIVE_CLAIM,
                    "Invert active source value (for accountDisabled)", "false", false, false, 14);
            invertActive.setType("boolean");
            properties.add(invertActive);
            properties.add(property(StalwartSCIMProvisioningConnectorConstants.EMAIL_MEMBERSHIP_GROUP,
                    "WSO2 group that grants email access", "Email", false, false, 15));
            Property mode = property(StalwartSCIMProvisioningConnectorConstants.PROVISIONING_MODE,
                    "Provisioning Mode (INDIVIDUAL or BULK)", "INDIVIDUAL", false, false, 16);
            mode.setDescription("INDIVIDUAL رفتار فعلی کانکتور است. BULK هر عملیات تغییردهنده را به‌صورت هم‌زمان از endpoint استاندارد SCIM Bulk ارسال می‌کند.");
            properties.add(mode);
            Property fallback = property(StalwartSCIMProvisioningConnectorConstants.BULK_FALLBACK_TO_INDIVIDUAL,
                    "Fallback to Individual if Bulk endpoint is unavailable", "true", false, false, 17);
            fallback.setType("boolean");
            fallback.setDescription("اگر سرور صریحاً اعلام کند endpoint مربوط به Bulk وجود ندارد، کانکتور به درخواست مستقل برمی‌گردد؛ خطاهای مبهم باعث ارسال مجدد نمی‌شوند.");
            properties.add(fallback);
            Property failOnErrors = property(StalwartSCIMProvisioningConnectorConstants.BULK_FAIL_ON_ERRORS,
                    "Bulk failOnErrors (0-1000)", "0", false, false, 18);
            failOnErrors.setDescription("حداکثر تعداد خطا قبل از توقف پردازش Bulk؛ مقدار صفر یعنی توقف خودکار بر اساس این محدودیت غیرفعال باشد.");
            properties.add(failOnErrors);
            Property bulkTimeout = property(StalwartSCIMProvisioningConnectorConstants.BULK_REQUEST_TIMEOUT_MS,
                    "Bulk request timeout (1000-120000 ms)", "30000", false, false, 19);
            bulkTimeout.setDescription("مهلت انتظار پاسخ سرور برای هر درخواست Bulk به میلی‌ثانیه.");
            properties.add(bulkTimeout);
            Property bulkPayload = property(StalwartSCIMProvisioningConnectorConstants.BULK_MAX_PAYLOAD_BYTES,
                    "Bulk maximum payload size (1024-1048576 bytes)", "900000", false, false, 20);
            bulkPayload.setDescription("حداکثر اندازه بدنه درخواست Bulk؛ مقدار پیش‌فرض با فاصله امن کمتر از محدودیت یک مگابایتی Stalwart تنظیم شده است.");
            properties.add(bulkPayload);

            System.out.println(">>> STALWART-SCIM getConfigurationProperties() returned "
                    + properties.size() + " properties");
            return properties;
        } catch (RuntimeException | Error e) {
            System.err.println(">>> STALWART-SCIM getConfigurationProperties() FAILED: " + e);
            e.printStackTrace(System.err);
            throw e;
        }
    }

    private Property property(String name, String displayName, String defaultValue,
                              boolean required, boolean confidential, int order) {
        Property property = new Property();
        property.setName(name);
        property.setDisplayName(displayName);
        property.setRequired(required);
        property.setType("string");
        property.setConfidential(confidential);
        property.setDefaultValue(defaultValue);
        property.setDisplayOrder(order);
        property.setDescription("Configure a WSO2 local claim URI or Stalwart SCIM connection value.");
        return property;
    }
}

/*
 * Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.irisadc.identity.provisioning.connector.stalwart.scim;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.application.common.model.Property;
import org.wso2.carbon.identity.application.common.model.SubProperty;
import org.wso2.carbon.identity.provisioning.AbstractProvisioningConnectorFactory;
import org.wso2.carbon.identity.provisioning.IdentityProvisioningException;

import java.util.ArrayList;
import java.util.List;

/**
 * @author
 */
public class StalwartSCIMProvisioningConnectorFactory extends AbstractProvisioningConnectorFactory {

    public static final String STALWART_SCIM = "stalwart-scim";
    private static final Log log = LogFactory.getLog(StalwartSCIMProvisioningConnectorFactory.class);

    @Override
    /**
     * @throws IdentityProvisioningException
     */
    protected StalwartSCIMProvisioningConnector buildConnector(Property[] provisioningProperties)
            throws IdentityProvisioningException {
        StalwartSCIMProvisioningConnector scimProvisioningConnector = new StalwartSCIMProvisioningConnector();
        scimProvisioningConnector.init(provisioningProperties);

        if (log.isDebugEnabled()) {
            log.debug("Created new connector of type : " + STALWART_SCIM);
        }
        return scimProvisioningConnector;
    }

    @Override
    public String getConnectorType() {
        return STALWART_SCIM;
    }

    /**
     * Get Configuration Properties.
     */
    @Override
    public List<Property> getConfigurationProperties() {

        List<Property> configProperties = new ArrayList<>();
        Property username = new Property();
        username.setName(StalwartSCIMProvisioningConnectorConstants.SCIM_USERNAME);
        username.setDisplayName("Username");
        username.setRequired(true);
        username.setType("string");
        username.setDisplayOrder(1);
        configProperties.add(username);

        Property password = new Property();
        password.setName(StalwartSCIMProvisioningConnectorConstants.SCIM_PASSWORD);
        password.setDisplayName("Password");
        password.setRequired(true);
        password.setType("string");
        password.setConfidential(true);
        password.setDisplayOrder(2);
        configProperties.add(password);

        Property userEp = new Property();
        userEp.setName(StalwartSCIMProvisioningConnectorConstants.SCIM_USER_EP);
        userEp.setDisplayName("User Endpoint");
        userEp.setRequired(true);
        userEp.setType("string");
        userEp.setDisplayOrder(3);
        configProperties.add(userEp);

        Property groupEp = new Property();
        groupEp.setName(StalwartSCIMProvisioningConnectorConstants.SCIM_GROUP_EP);
        groupEp.setDisplayName("Group Endpoint");
        groupEp.setRequired(false);
        groupEp.setType("string");
        groupEp.setDisplayOrder(4);
        configProperties.add(groupEp);

        Property userstoreDomain = new Property();
        userstoreDomain.setName(StalwartSCIMProvisioningConnectorConstants.SCIM_USERSTORE_DOMAIN);
        userstoreDomain.setDisplayName("User Store Domain");
        userstoreDomain.setRequired(false);
        userstoreDomain.setType("string");
        userstoreDomain.setDisplayOrder(5);
        configProperties.add(userstoreDomain);

        Property enablePwdProvisioning = new Property();
        enablePwdProvisioning.setName(StalwartSCIMProvisioningConnectorConstants.SCIM_ENABLE_PASSWORD_PROVISIONING);
        enablePwdProvisioning.setDisplayName("Enable Password Provisioning");
        enablePwdProvisioning.setRequired(false);
        enablePwdProvisioning.setDescription("Enable User password provisioning to a SCIM domain");
        enablePwdProvisioning.setType("boolean");
        enablePwdProvisioning.setDefaultValue("true");
        enablePwdProvisioning.setDisplayOrder(6);

        SubProperty defaultPwd = new SubProperty();
        defaultPwd.setName(StalwartSCIMProvisioningConnectorConstants.SCIM_DEFAULT_PASSWORD);
        defaultPwd.setDisplayName("Default Password");
        defaultPwd.setRequired(false);
        defaultPwd.setType("string");
        defaultPwd.setConfidential(true);
        enablePwdProvisioning.setSubProperties(new SubProperty[] {defaultPwd});
        configProperties.add(enablePwdProvisioning);


        Property mappingEnabled = new Property();
        mappingEnabled.setName(StalwartSCIMProvisioningConnectorConstants.ENABLE_CLAIM_MAPPING);
        mappingEnabled.setDisplayName("Enable Stalwart Claim Mapping");
        mappingEnabled.setDescription("Map WSO2 local claims to Stalwart SCIM User attributes.");
        mappingEnabled.setType("boolean");
        mappingEnabled.setDefaultValue("true");
        mappingEnabled.setDisplayOrder(7);
        configProperties.add(mappingEnabled);

        configProperties.add(claimProperty(StalwartSCIMProvisioningConnectorConstants.USERNAME_CLAIM_URI,
                "SCIM userName source claim URI", "http://wso2.org/claims/emailaddress", 8, true));
        configProperties.add(claimProperty(StalwartSCIMProvisioningConnectorConstants.EXTERNAL_ID_CLAIM_URI,
                "SCIM externalId source claim URI", "http://wso2.org/claims/username", 9, false));
        configProperties.add(claimProperty(StalwartSCIMProvisioningConnectorConstants.GIVEN_NAME_CLAIM_URI,
                "SCIM name.givenName source claim URI", "http://wso2.org/claims/givenname", 10, false));
        configProperties.add(claimProperty(StalwartSCIMProvisioningConnectorConstants.FAMILY_NAME_CLAIM_URI,
                "SCIM name.familyName source claim URI", "http://wso2.org/claims/lastname", 11, false));
        configProperties.add(claimProperty(StalwartSCIMProvisioningConnectorConstants.ACTIVE_CLAIM_URI,
                "SCIM active source claim URI (optional)", "", 12, false));

        return configProperties;
    }
    private Property claimProperty(String name, String displayName, String defaultValue, int order, boolean required) {
        Property property = new Property();
        property.setName(name);
        property.setDisplayName(displayName);
        property.setDescription("WSO2 local claim URI used as the source value.");
        property.setRequired(required);
        property.setType("string");
        property.setDefaultValue(defaultValue);
        property.setDisplayOrder(order);
        return property;
    }

}

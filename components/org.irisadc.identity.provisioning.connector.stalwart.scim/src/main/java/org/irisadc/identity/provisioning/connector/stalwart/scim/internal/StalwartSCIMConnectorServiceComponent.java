/*
 * Copyright (c) 2014, WSO2 Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package org.irisadc.identity.provisioning.connector.stalwart.scim.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.wso2.carbon.identity.provisioning.AbstractProvisioningConnectorFactory;
import org.irisadc.identity.provisioning.connector.stalwart.scim.StalwartSCIMProvisioningConnectorFactory;

@Component(
        name = "org.irisadc.identity.provisioning.connector.stalwart.scim.internal.StalwartSCIMConnectorServiceComponent",
        immediate = true
)
public class StalwartSCIMConnectorServiceComponent {

    private static final Log log = LogFactory.getLog(StalwartSCIMConnectorServiceComponent.class);

    @Activate
    protected void activate(ComponentContext context) {
        try {
            StalwartSCIMProvisioningConnectorFactory factory = new StalwartSCIMProvisioningConnectorFactory();
            context.getBundleContext().registerService(
                    AbstractProvisioningConnectorFactory.class.getName(), factory, null);
            log.info("Stalwart SCIM 2.0 provisioning connector activated.");
        } catch (Throwable e) {
            log.error("Error while activating Stalwart SCIM provisioning connector.", e);
        }
    }
}

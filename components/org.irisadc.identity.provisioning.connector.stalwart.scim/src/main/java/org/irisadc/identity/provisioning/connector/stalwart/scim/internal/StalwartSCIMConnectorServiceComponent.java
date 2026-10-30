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
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.framework.ServiceRegistration;
import org.wso2.carbon.identity.provisioning.AbstractProvisioningConnectorFactory;
import org.irisadc.identity.provisioning.connector.stalwart.scim.StalwartSCIMProvisioningConnectorFactory;

@Component(
        name = "org.irisadc.identity.provisioning.connector.stalwart.scim.internal.StalwartSCIMConnectorServiceComponent",
        immediate = true
)
public class StalwartSCIMConnectorServiceComponent {

    private static final Log log = LogFactory.getLog(StalwartSCIMConnectorServiceComponent.class);
    private ServiceRegistration<?> factoryRegistration;

    @Activate
    protected void activate(ComponentContext context) {
        try {
            StalwartSCIMProvisioningConnectorFactory factory = new StalwartSCIMProvisioningConnectorFactory();
            factoryRegistration = context.getBundleContext().registerService(
                    AbstractProvisioningConnectorFactory.class.getName(), factory, null);
            log.info("Stalwart SCIM 2.0 provisioning connector activated.");
        } catch (RuntimeException e) {
            log.error("Error while activating Stalwart SCIM provisioning connector.", e);
            throw e;
        }
    }

    @Deactivate
    protected void deactivate() {
        if (factoryRegistration != null) {
            try {
                factoryRegistration.unregister();
                log.info("Stalwart SCIM 2.0 provisioning connector deactivated.");
            } catch (IllegalStateException e) {
                log.debug("Stalwart SCIM connector service was already unregistered.", e);
            } finally {
                factoryRegistration = null;
            }
        }
    }
}

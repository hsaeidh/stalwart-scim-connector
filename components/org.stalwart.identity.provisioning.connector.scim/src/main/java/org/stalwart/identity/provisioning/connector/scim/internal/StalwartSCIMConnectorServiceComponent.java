/*
 * Copyright (c) 2014, WSO2 Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package org.stalwart.identity.provisioning.connector.scim.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.stalwart.identity.provisioning.connector.scim.StalwartSCIMProvisioningConnector;
import org.stalwart.identity.provisioning.connector.scim.StalwartSCIMProvisioningConnectorFactory;
import org.stalwart.identity.provisioning.connector.scim.Wso2UserClaimSnapshot;
import org.wso2.carbon.identity.provisioning.AbstractProvisioningConnectorFactory;
import org.wso2.carbon.user.core.service.RealmService;

@Component(
        name = "org.stalwart.identity.provisioning.connector.scim.internal.StalwartSCIMConnectorServiceComponent",
        immediate = true
)
public class StalwartSCIMConnectorServiceComponent {

    private static final Log LOG = LogFactory.getLog(StalwartSCIMConnectorServiceComponent.class);
    private ServiceRegistration<?> factoryRegistration;

    @Reference(service = RealmService.class, unbind = "unsetRealmService")
    protected void setRealmService(RealmService realmService) {
        Wso2UserClaimSnapshot.bind(realmService);
        LOG.debug("WSO2 RealmService bound to Stalwart SCIM connector.");
    }

    protected void unsetRealmService(RealmService realmService) {
        Wso2UserClaimSnapshot.unbind(realmService);
        LOG.debug("WSO2 RealmService unbound from Stalwart SCIM connector.");
    }

    @Activate
    protected void activate(ComponentContext context) {
        try {
            if (context == null) {
                throw new IllegalStateException("DS supplied a null ComponentContext.");
            }
            BundleContext bundleContext = context.getBundleContext();
            if (bundleContext == null) {
                throw new IllegalStateException("ComponentContext returned a null BundleContext.");
            }

            StalwartSCIMProvisioningConnectorFactory factory =
                    new StalwartSCIMProvisioningConnectorFactory();
            factoryRegistration = bundleContext.registerService(
                    AbstractProvisioningConnectorFactory.class.getName(), factory, null);
            if (factoryRegistration == null) {
                throw new IllegalStateException("OSGi registerService returned null for provisioning factory.");
            }
            LOG.info("Stalwart SCIM provisioning connector activated; connector type="
                    + factory.getConnectorType());
        } catch (Throwable t) {
            LOG.error("Stalwart SCIM connector activation failed.", t);
            unregisterFactory();
            if (t instanceof Error) throw (Error) t;
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new IllegalStateException("Stalwart SCIM connector activation failed.", t);
        }
    }

    @Deactivate
    protected void deactivate(ComponentContext context) {
        StalwartSCIMProvisioningConnector.shutdownBulkDispatcher();
        unregisterFactory();
        LOG.info("Stalwart SCIM provisioning connector deactivated.");
    }

    private void unregisterFactory() {
        if (factoryRegistration != null) {
            try {
                factoryRegistration.unregister();
            } catch (IllegalStateException e) {
                LOG.debug("Stalwart SCIM factory service was already unregistered.", e);
            } catch (RuntimeException e) {
                LOG.warn("Stalwart SCIM factory unregistration failed.", e);
            } finally {
                factoryRegistration = null;
            }
        }
    }
}

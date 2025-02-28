/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.domain.controller.operations;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.EXTENSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.IGNORED_RESOURCES;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.IGNORE_UNUSED_CONFIG;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.INITIAL_SERVER_GROUPS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MANAGEMENT_MAJOR_VERSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MANAGEMENT_MICRO_VERSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MANAGEMENT_MINOR_VERSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAMES;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PROFILE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RELEASE_CODENAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RELEASE_VERSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.WILDCARD;

import java.util.Collections;

import org.jboss.as.controller.ExpressionResolver;
import org.jboss.as.controller.ModelVersion;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ProcessType;
import org.jboss.as.controller.ResourceDefinition;
import org.jboss.as.controller.RunningMode;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.descriptions.NonResolvingResourceDescriptionResolver;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.controller.transform.ResourceTransformationContext;
import org.jboss.as.controller.transform.TransformationTarget;
import org.jboss.as.controller.transform.TransformationTarget.TransformationTargetType;
import org.jboss.as.controller.transform.TransformationTargetImpl;
import org.jboss.as.controller.transform.TransformerRegistry;
import org.jboss.as.controller.transform.Transformers;
import org.jboss.as.host.controller.mgmt.HostInfo;
import org.jboss.dmr.ModelNode;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 */
public class ReadPrimaryDomainModelHandlerTestCase {


    @Test
    public void testResourceTransformation() throws Exception {
        Resource resourceRoot = Resource.Factory.create();
        TransformerRegistry registry = TransformerRegistry.Factory.create();
        ManagementResourceRegistration resourceRegistration = ManagementResourceRegistration.Factory.forProcessType(ProcessType.EMBEDDED_SERVER).createRegistration(ROOT);

        final Resource extension = Resource.Factory.create();
        extension.getModel().get("attr").set("value");
        resourceRoot.registerChild(PathElement.pathElement("extension", "not-ignored"), extension);

        final Resource ignoredExtension = Resource.Factory.create();
        ignoredExtension.getModel().get("attr").set("value");
        resourceRoot.registerChild(PathElement.pathElement("extension", "ignored"), ignoredExtension);

        resourceRoot.registerChild(PathElement.pathElement("profile", "not-ignored"), createProfile());
        resourceRoot.registerChild(PathElement.pathElement("profile", "ignored"), createProfile());

        ModelNode original = Resource.Tools.readModel(resourceRoot);
        Assert.assertEquals("value", original.get("extension", "not-ignored", "attr").asString());
        Assert.assertEquals("value", original.get("extension", "ignored", "attr").asString());
        Assert.assertEquals("value", original.get("profile", "not-ignored", "subsystem", "thingy", "attr").asString());
        Assert.assertEquals("value", original.get("profile", "ignored", "subsystem", "thingy", "attr").asString());


        ModelNode hostInfo = new ModelNode();
        hostInfo.get(NAME).set("kabirs-macbook-pro.local");
        hostInfo.get(RELEASE_VERSION).set("8.0.0.Alpha1-SNAPSHOT");
        hostInfo.get(RELEASE_CODENAME).set("TBD");
        hostInfo.get(MANAGEMENT_MAJOR_VERSION).set(1);
        hostInfo.get(MANAGEMENT_MINOR_VERSION).set(4);
        hostInfo.get(MANAGEMENT_MICRO_VERSION).set(0);
        hostInfo.get(IGNORED_RESOURCES, PROFILE, WILDCARD);
        hostInfo.get(IGNORED_RESOURCES, PROFILE, NAMES).add("ignored");
        hostInfo.get(IGNORED_RESOURCES, EXTENSION, WILDCARD);
        hostInfo.get(IGNORED_RESOURCES, EXTENSION, NAMES).add("ignored");
        hostInfo.get(IGNORE_UNUSED_CONFIG).set(false);
        hostInfo.get(INITIAL_SERVER_GROUPS).setEmptyObject();
        hostInfo.get("domain-connection-id").set(1361470170404L);

        Resource transformedResource = transformResource(registry, resourceRoot, resourceRegistration, HostInfo.fromModelNode(hostInfo));
        ModelNode transformed = Resource.Tools.readModel(transformedResource);

        Assert.assertEquals("value", transformed.get("extension", "not-ignored", "attr").asString());
        Assert.assertFalse(transformed.get("extension").hasDefined("ignored"));
        Assert.assertEquals("value", transformed.get("profile", "not-ignored", "subsystem", "thingy", "attr").asString());
        Assert.assertFalse(transformed.get("profile").hasDefined("ignored"));
    }

    private Resource createProfile() {
        Resource profile = Resource.Factory.create();
        Resource subsystem = Resource.Factory.create();
        subsystem.getModel().get("attr").set("value");
        profile.registerChild(PathElement.pathElement("subsystem", "thingy"), subsystem);
        return profile;
    }


    private Resource transformResource(TransformerRegistry registry, Resource resourceRoot,
                                       ManagementResourceRegistration resourceRegistration,
                                       Transformers.ResourceIgnoredTransformationRegistry ignoredTransformationRegistry) throws OperationFailedException {
        final TransformationTarget target = create(registry, ModelVersion.create(1));
        final ResourceTransformationContext context = createContext(registry, resourceRoot, target, resourceRegistration, ignoredTransformationRegistry);
        return getTransfomers(target).transformResource(context, resourceRoot);
    }

    private ResourceTransformationContext createContext(TransformerRegistry registry, Resource resourceRoot,
                TransformationTarget target, ManagementResourceRegistration resourceRegistration,
                Transformers.ResourceIgnoredTransformationRegistry ignoredTransformationRegistry) {
        return Transformers.Factory.create(target, resourceRoot, resourceRegistration,
                ExpressionResolver.TEST_RESOLVER, RunningMode.NORMAL, ProcessType.STANDALONE_SERVER, null, ignoredTransformationRegistry);

    }

    private Transformers getTransfomers(final TransformationTarget target) {
        return Transformers.Factory.create(target);
    }

    protected TransformationTarget create(final TransformerRegistry registry, ModelVersion version) {
        return TransformationTargetImpl.create(null, registry, version, Collections.<PathAddress, ModelVersion>emptyMap(), TransformationTargetType.DOMAIN);
    }

    private static final ResourceDefinition ROOT = new SimpleResourceDefinition(PathElement.pathElement("test"), NonResolvingResourceDescriptionResolver.INSTANCE);
}

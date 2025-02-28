/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.controller.operations.global;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ACCESS_CONTROL;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILURE_DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.READ_CHILDREN_RESOURCES_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.READ_RESOURCE_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import static org.jboss.as.controller.operations.global.GlobalOperationAttributes.CHILD_TYPE;
import static org.jboss.as.controller.operations.global.GlobalOperationAttributes.INCLUDE_DEFAULTS;
import static org.jboss.as.controller.operations.global.GlobalOperationAttributes.INCLUDE_RUNTIME;
import static org.jboss.as.controller.operations.global.GlobalOperationAttributes.PROXIES;
import static org.jboss.as.controller.operations.global.GlobalOperationAttributes.RECURSIVE;
import static org.jboss.as.controller.operations.global.GlobalOperationAttributes.RECURSIVE_DEPTH;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationDefinition;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.descriptions.common.ControllerResolver;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * {@link org.jboss.as.controller.OperationStepHandler} querying the children resources of a given "child-type".
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 */
public class ReadChildrenResourcesHandler implements OperationStepHandler {


    static final OperationDefinition DEFINITION = new SimpleOperationDefinitionBuilder(READ_CHILDREN_RESOURCES_OPERATION, ControllerResolver.getResolver("global"))
            .setParameters(CHILD_TYPE, RECURSIVE, RECURSIVE_DEPTH, PROXIES, INCLUDE_RUNTIME, INCLUDE_DEFAULTS)
            .setReadOnly()
            .setReplyType(ModelType.LIST)
            .setReplyValueType(ModelType.OBJECT)
            .build();

    static final OperationStepHandler INSTANCE = new ReadChildrenResourcesHandler();

    @Override
    public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {

        final PathAddress address = context.getCurrentAddress();
        final String childType = CHILD_TYPE.resolveModelAttribute(context, operation).asString();

        // Build up the op we're going to repeatedly execute
        final ModelNode readOp = new ModelNode();
        readOp.get(OP).set(READ_RESOURCE_OPERATION);
        INCLUDE_RUNTIME.validateAndSet(operation, readOp);
        RECURSIVE.validateAndSet(operation, readOp);
        RECURSIVE_DEPTH.validateAndSet(operation, readOp);
        PROXIES.validateAndSet(operation, readOp);
        INCLUDE_DEFAULTS.validateAndSet(operation, readOp);

        final Map<PathElement, ModelNode> resources = new HashMap<PathElement, ModelNode>();

        final Resource resource = context.readResource(PathAddress.EMPTY_ADDRESS, false);
        final ImmutableManagementResourceRegistration registry = context.getResourceRegistration();
        Map<String, Set<String>> childAddresses = GlobalOperationHandlers.getChildAddresses(context, address, registry, resource, childType);
        Set<String> childNames = childAddresses.get(childType);
        if (childNames == null) {
            throw new OperationFailedException(ControllerLogger.ROOT_LOGGER.unknownChildType(childType));
        }

        // Track any excluded items
        FilteredData filteredData = new FilteredData(address);

        // We're going to add a bunch of steps that should immediately follow this one. We are going to add them
        // in reverse order of how they should execute, building up a stack.

        // Last to execute is the handler that assembles the overall response from the pieces created by all the other steps
        final ReadChildrenResourcesAssemblyHandler assemblyHandler = new ReadChildrenResourcesAssemblyHandler(resources, filteredData, address, childType);
        context.addStep(assemblyHandler, OperationContext.Stage.MODEL, true);

        for (final String key : childNames) {
            final PathElement childPath = PathElement.pathElement(childType, key);
            final PathAddress childAddress = PathAddress.EMPTY_ADDRESS.append(PathElement.pathElement(childType, key));

            final ModelNode readResOp = readOp.clone();
            readResOp.get(OP_ADDR).set(PathAddress.pathAddress(address, childPath).toModelNode());

            // See if there was an override registered for the standard :read-resource handling (unlikely!!!)
            OperationStepHandler overrideHandler = context.getResourceRegistration().getOperationHandler(childAddress, READ_RESOURCE_OPERATION);
            if (overrideHandler == null) {
                throw new OperationFailedException(ControllerLogger.ROOT_LOGGER.noOperationHandler());
            } else if (overrideHandler.getClass() == ReadResourceHandler.class) {
                // not an override
                overrideHandler = null;
            }
            OperationStepHandler rrHandler = new ReadResourceHandler(filteredData, overrideHandler, false);
            final ModelNode rrRsp = new ModelNode();
            resources.put(childPath, rrRsp);
            context.addStep(rrRsp, readResOp, rrHandler, OperationContext.Stage.MODEL, true);
        }
    }

    /**
     * Assembles the response to a read-resource request from the components gathered by earlier steps.
     */
    private static class ReadChildrenResourcesAssemblyHandler implements OperationStepHandler {

        private final Map<PathElement, ModelNode> resources;
        private final FilteredData filteredData;
        private final PathAddress address;
        private final String childType;

        /**
         * Creates a ReadResourceAssemblyHandler that will assemble the response using the contents
         * of the given maps.
         *
         * @param resources read-resource response from child resources, where the key is the path of the resource
         *                  relative to the address of the operation this handler is handling and the
         *                  value is the full read-resource response. Will not be {@code null}
         * @param filteredData record of any excluded data
         * @param address    the address of the targeted resource
         * @param childType  the type of child being read
         */
        private ReadChildrenResourcesAssemblyHandler(final Map<PathElement, ModelNode> resources, FilteredData filteredData,
                                                     PathAddress address, String childType) {
            this.resources = resources;
            this.filteredData = filteredData;
            this.address = address;
            this.childType = childType;
        }

        @Override
        public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {

            context.addStep(new OperationStepHandler() {
                @Override
                public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
                    Map<String, ModelNode> sortedChildren = new TreeMap<String, ModelNode>();
                    boolean failed = false;
                    for (Map.Entry<PathElement, ModelNode> entry : resources.entrySet()) {
                        PathElement path = entry.getKey();
                        ModelNode value = entry.getValue();
                        if (!value.has(FAILURE_DESCRIPTION)) {
                            if (value.hasDefined(RESULT)) {
                                sortedChildren.put(path.getValue(), value.get(RESULT));
                            } else {
                                // A child did not produce a response. We don't know if the definition
                                // of our resource indicates the child that has disappeared must be
                                // present, so we don't want to produce a response for our resource
                                // without the child if our resource is now gone as well.
                                // So, see if our resource has disappeared as well.
                                if (!filteredData.isAddressFiltered(address, path)) {
                                    // Wasn't filtered. Confirm our resource still exists
                                    context.readResourceFromRoot(address, false);
                                } // else there's no result because it was just filtered

                            }
                        } else if (!failed && value.hasDefined(FAILURE_DESCRIPTION)) {
                            context.getFailureDescription().set(value.get(FAILURE_DESCRIPTION));
                            failed = true;
                        }
                    }

                    if (!failed) {
                        boolean hasFilteredData = filteredData.hasFilteredData();
                        final ModelNode result = context.getResult();
                        result.setEmptyObject();

                        for (Map.Entry<String, ModelNode> entry : sortedChildren.entrySet()) {
                            if (!hasFilteredData || !filteredData.isAddressFiltered(address, PathElement.pathElement(childType, entry.getKey()))) {
                                result.get(entry.getKey()).set(entry.getValue());
                            }
                        }

                        if (hasFilteredData) {
                            context.getResponseHeaders().get(ACCESS_CONTROL).set(filteredData.toModelNode());
                        }
                    }
                }
            }, OperationContext.Stage.VERIFY);
        }
    }
}

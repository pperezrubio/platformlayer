package org.platformlayer.service.cloud.openstack.ops;

import java.io.IOException;
import java.util.List;

import javax.inject.Inject;

import org.apache.log4j.Logger;
import org.openstack.model.compute.Server;
import org.platformlayer.core.model.PlatformLayerKey;
import org.platformlayer.core.model.Tag;
import org.platformlayer.core.model.Tags;
import org.platformlayer.crypto.OpenSshUtils;
import org.platformlayer.ops.CustomRecursor;
import org.platformlayer.ops.Handler;
import org.platformlayer.ops.Machine;
import org.platformlayer.ops.MachineCreationRequest;
import org.platformlayer.ops.OpsContext;
import org.platformlayer.ops.OpsException;
import org.platformlayer.ops.OpsSystem;
import org.platformlayer.ops.OpsTarget;
import org.platformlayer.ops.helpers.InstanceHelpers;
import org.platformlayer.ops.helpers.ServiceContext;
import org.platformlayer.ops.helpers.SshKey;
import org.platformlayer.ops.machines.PlatformLayerHelpers;
import org.platformlayer.ops.metrics.collectd.OpsTreeBase;
import org.platformlayer.service.cloud.openstack.model.OpenstackCloud;
import org.platformlayer.service.cloud.openstack.model.OpenstackInstance;
import org.platformlayer.service.cloud.openstack.ops.openstack.OpenstackCloudContext;
import org.platformlayer.service.cloud.openstack.ops.openstack.OpenstackComputeMachine;

import com.google.common.base.Strings;
import com.google.common.collect.Iterables;

public class CloudInstanceMapper extends OpsTreeBase implements CustomRecursor {
    static final Logger log = Logger.getLogger(CloudInstanceMapper.class);

    public OpenstackInstance instance;
    public boolean createInstance = true;

    @Inject
    ServiceContext service;

    @Inject
    PlatformLayerHelpers platformLayer;

    @Inject
    OpsContext ops;

    @Inject
    InstanceHelpers instanceHelpers;

    @Inject
    OpenstackCloudContext openstack;

    @Handler
    public void doOperation() throws OpsException, IOException {
        Tags instanceTags = instance.getTags();

        OpenstackCloud cloud = findCloud();
        if (cloud == null) {
            throw new OpsException("Could not find cloud");
        }

        pushChildScope(cloud);

        List<String> assignedInstanceIds = instanceTags.find(Tag.ASSIGNED);
        if (assignedInstanceIds.isEmpty()) {
            if (createInstance && !OpsContext.isDelete()) {
                MachineCreationRequest request = buildMachineCreationRequest();

                PlatformLayerKey instanceKey = OpsSystem.toKey(instance);
                request.tags.add(Tag.buildParentTag(instanceKey));

                String serverName = buildServerName();

                Server created = openstack.createInstance(cloud, serverName, request);

                {
                    Tag instanceTag = new Tag(Tag.ASSIGNED, created.getId());
                    platformLayer.addTag(OpsSystem.toKey(instance), instanceTag);
                }

                assignedInstanceIds.add(created.getId());
            }
        }

        if (assignedInstanceIds.isEmpty() && !OpsContext.isDelete()) {
            throw new OpsException("Instance not yet assigned");
        }

        Machine machine = null;
        OpsTarget target = null;

        if (!assignedInstanceIds.isEmpty()) {
            if (assignedInstanceIds.size() != 1) {
                log.warn("Multiple instance ids found: " + assignedInstanceIds);
            }

            // We just take the first instance id
            String assignedInstanceId = Iterables.getFirst(assignedInstanceIds, null);

            Server server = openstack.findServerById(cloud, assignedInstanceId);

            server = openstack.ensureHasPublicIp(cloud, server);

            machine = new OpenstackComputeMachine(openstack, cloud, server);

            SshKey sshKey = service.getSshKey();
            target = machine.getTarget(sshKey);
        }

        if (!assignedInstanceIds.isEmpty() && OpsContext.isDelete()) {
            for (String instanceId : assignedInstanceIds) {
                openstack.terminateInstance(cloud, instanceId);
            }
        }

        pushChildScope(machine);
        pushChildScope(target);
    }

    private String buildServerName() {
        String serverName = "PlatformLayer ";
        if (!Strings.isNullOrEmpty(instance.hostname)) {
            serverName += instance.hostname;
        } else {
            serverName += OpsSystem.toKey(instance).getUrl();
        }
        return serverName;
    }

    private MachineCreationRequest buildMachineCreationRequest() throws IOException {
        MachineCreationRequest request = new MachineCreationRequest();
        request.sshPublicKey = OpenSshUtils.readSshPublicKey(instance.sshPublicKey);
        request.minimumMemoryMB = instance.minimumMemoryMb;
        request.recipeId = instance.recipeId;
        // request.securityGroups;
        request.hostPolicy = instance.hostPolicy;
        request.hostname = instance.hostname;
        request.publicPorts = instance.publicPorts;

        Tags tags = new Tags();
        request.tags = tags;
        return request;
    }

    private OpenstackCloud findCloud() throws OpsException {
        OpenstackCloud cloud = null;
        {
            cloud = platformLayer.getItem(OpenstackCloud.class, instance.cloud);
        }
        return cloud;
    }

    @Override
    protected void addChildren() throws OpsException {
    }
}

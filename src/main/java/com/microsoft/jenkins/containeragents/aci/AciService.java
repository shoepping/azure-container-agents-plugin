package com.microsoft.jenkins.containeragents.aci;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.containerinstance.ContainerGroup;
import com.microsoft.jenkins.containeragents.ContainerPlugin;
import com.microsoft.jenkins.containeragents.PodEnvVar;
import com.microsoft.jenkins.containeragents.util.AzureContainerUtils;
import com.microsoft.jenkins.containeragents.util.Constants;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.time.StopWatch;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;


public final class AciService {
    private static final Logger LOGGER = Logger.getLogger(AciService.class.getName());

    public static void createDeployment(final AciCloud cloud,
                                        final AciContainerTemplate template,
                                        final StopWatch stopWatch,
                                        final String agentName,
                                        final String deployName) throws Exception {

        try {
            final Azure azureClient = cloud.getAzureClient();

            final ObjectMapper mapper = new ObjectMapper();

            // register the container group for cleanup
            AciContainerGroupsCleanTask.ContainerGroupsRegistrar
                    containerGroupRegistrar = AciContainerGroupsCleanTask.ContainerGroupsRegistrar.getInstance();
            containerGroupRegistrar.registerContainerGroups(cloud.getName(), cloud.getResourceGroup(), deployName);

            String networkProfileName = "aci-network-profile-build-env-06.01-vnet-azure-aci-06.01-subnet";
            azureClient.containerGroups().define(agentName)
                    .withRegion(azureClient.resourceGroups().getByName(cloud.getResourceGroup()).regionName())
                    .withExistingResourceGroup(cloud.getResourceGroup())
                    .withLinux()
                    .withPublicImageRegistryOnly()
                    .withoutVolume()
                    .defineContainerInstance(agentName)
                    .withImage(template.getImage())
                    .withExternalTcpPort(Integer.parseInt(template.getSshPort()))
                    .withCpuCoreCount(Double.parseDouble(template.getCpu()))
                    .withMemorySizeInGB(Double.parseDouble(template.getMemory()))
                    .withEnvironmentVariables(
                            template.getEnvVars().stream().collect(
                                    Collectors.toMap(PodEnvVar::getKey, PodEnvVar::getValue)))
                    .attach()
                    .withNetworkProfileId(azureClient.subscriptionId(), cloud.getResourceGroup(), networkProfileName)
                    .withTag("jenkinsInstance", Jenkins.getInstance().getLegacyInstanceId())
                    .withTag("CREATION_TIME", String.valueOf(Instant.now().toEpochMilli()))
                    .create();

            final int retryInterval = 10 * 1000;

            LOGGER.log(Level.INFO, "Waiting for deployment {0}", deployName);
            while (true) {
                if (AzureContainerUtils.isTimeout(template.getTimeout(), stopWatch.getTime())) {
                    throw new TimeoutException("Deployment timeout");
                }
                ContainerGroup containerGroup =
                    azureClient.containerGroups().getByResourceGroup(cloud.getResourceGroup(), agentName);

                if (containerGroup.provisioningState().equalsIgnoreCase("succeeded")) {
                    LOGGER.log(Level.INFO, "Deployment {0} succeed", deployName);
                    break;
                } else if (containerGroup.provisioningState().equalsIgnoreCase("Failed")) {
                    throw new Exception(String.format("Deployment %s status: Failed", deployName));
                } else {
                    // If half of time passed, we need to inspect what happened from logs
                    if (AzureContainerUtils.isHalfTimePassed(template.getTimeout(), stopWatch.getTime())) {
                        ContainerGroup containerGrp
                                = azureClient.containerGroups()
                                .getByResourceGroup(cloud.getResourceGroup(), agentName);
                        if (containerGrp != null) {
                            LOGGER.log(Level.INFO, "Logs from container {0}: {1}",
                                    new Object[]{agentName,
                                            containerGrp.getLogContent(agentName)});
                        }
                    }
                    Thread.sleep(retryInterval);
                }
            }
        } catch (Exception e) {

            e.printStackTrace();
            throw new Exception(e.getMessage());
        }
    }

    private static String getDeploymentName(AciContainerTemplate template) {
        return AzureContainerUtils.generateName(template.getName(), Constants.ACI_DEPLOYMENT_RANDOM_NAME_LENGTH);
    }

    public static void deleteAciContainerGroup(String credentialsId,
                                               String resourceGroup,
                                               String containerGroupName,
                                               String deployName) {
        Azure azureClient = null;
        final Map<String, String> properties = new HashMap<>();

        try {
            azureClient = AzureContainerUtils.getAzureClient(credentialsId);
            azureClient.containerGroups().deleteByResourceGroup(resourceGroup, containerGroupName);
            LOGGER.log(Level.INFO, "Delete ACI Container Group: {0} successfully", containerGroupName);

            properties.put(Constants.AI_ACI_NAME, containerGroupName);
            ContainerPlugin.sendEvent(Constants.AI_ACI_AGENT, "Deleted", properties);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Delete ACI Container Group: {0} failed: {1}",
                    new Object[] {containerGroupName, e});

            properties.put("Message", e.getMessage());
            ContainerPlugin.sendEvent(Constants.AI_ACI_AGENT, "DeletedFailed", properties);
        }

        try {
            //To avoid to many deployments. May over deployment limits.
            properties.clear();
            if (deployName != null) {
                // Only to delete succeeded deployments for future debugging.
                if (azureClient.containerGroups().getByResourceGroup(resourceGroup, deployName).provisioningState()
                        .equalsIgnoreCase("succeeded")) {
                    azureClient.containerGroups().deleteByResourceGroup(resourceGroup, deployName);
                    LOGGER.log(Level.INFO, "Delete ACI deployment: {0} successfully", deployName);
                    properties.put(Constants.AI_ACI_NAME, containerGroupName);
                    properties.put(Constants.AI_ACI_DEPLOYMENT_NAME, deployName);
                    ContainerPlugin.sendEvent(Constants.AI_ACI_AGENT, "DeploymentDeleted", properties);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            LOGGER.log(Level.WARNING, "Delete ACI deployment: {0} failed: {1}",
                    new Object[] {deployName, e});
            properties.put(Constants.AI_ACI_NAME, containerGroupName);
            properties.put(Constants.AI_ACI_DEPLOYMENT_NAME, deployName);
            properties.put("Message", e.getMessage());
            ContainerPlugin.sendEvent(Constants.AI_ACI_AGENT, "DeploymentDeletedFailed", properties);
        }
    }

    private AciService() {
        //
    }
}

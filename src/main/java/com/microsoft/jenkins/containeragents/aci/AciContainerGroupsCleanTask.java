package com.microsoft.jenkins.containeragents.aci;


import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.containerinstance.ContainerGroup;
import com.microsoft.azure.management.resources.GenericResource;
import com.microsoft.jenkins.containeragents.util.AzureContainerUtils;
import com.microsoft.jenkins.containeragents.util.Constants;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class AciContainerGroupsCleanTask extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(AciContainerGroupsCleanTask.class.getName());

    private static final int RECURRENCE_PERIOD_IN_MILLIS = 30 * 60 * 1000;
    private static final long SUCCESSFUL_DEPLOYMENT_TIMEOUT_IN_MINUTES = 60;
    private static final long FAILING_DEPLOYMENT_TIMEOUT_IN_MINUTES = 60 * 8;

    public AciContainerGroupsCleanTask() {
        super("ACI Period Clean Task");
    }

    private void clean() {
        Jenkins instance = Jenkins.getInstanceOrNull();
        if (instance == null) {
            return;
        }
        for (AciCloud cloud : instance.clouds.getAll(AciCloud.class)) {
            cleanLeakedContainer(cloud);
        }
    }

    private static class ContainerGroupsInfo implements Serializable {
        ContainerGroupsInfo(String cloudName,
                       String resourceGroupName,
                       String containerGroupsName,
                       int deleteAttempts) {
            this.cloudName = cloudName;
            this.containerGroupsName = containerGroupsName;
            this.resourceGroupName = resourceGroupName;
            this.attemptsRemaining = deleteAttempts;
        }

        String getCloudName() {
            return cloudName;
        }

        String getContainerGroupsName() {
            return containerGroupsName;
        }

        String getResourceGroupName() {
            return resourceGroupName;
        }

        boolean hasAttemptsRemaining() {
            return attemptsRemaining > 0;
        }

        void decrementAttemptsRemaining() {
            attemptsRemaining--;
        }

        private String cloudName;
        private String containerGroupsName;
        private String resourceGroupName;
        private int attemptsRemaining;
    }

    public static class ContainerGroupsRegistrar {
        private static final String OUTPUT_FILE
                = Paths.get(loadProperty("JENKINS_HOME"), "aci-containerGroups.out").toString();

        private static ContainerGroupsRegistrar containerGroupsRegistrar = new ContainerGroupsRegistrar();

        private static final int MAX_DELETE_ATTEMPTS = 3;

        private ConcurrentLinkedQueue<ContainerGroupsInfo> containerGroupsToClean =
                new ConcurrentLinkedQueue<>();

        protected ContainerGroupsRegistrar() {
            ObjectInputStream ois = null;

            try {
                ois = new ObjectInputStream(new FileInputStream(OUTPUT_FILE));
                containerGroupsToClean = (ConcurrentLinkedQueue<ContainerGroupsInfo>) ois.readObject();
            } catch (FileNotFoundException e) {
                LOGGER.log(Level.WARNING,
                        "AzureAciCleanUpTask: readResolve: Cannot open containerGroups output file");
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "AzureAciCleanUpTask: readResolve: Cannot deserialize containerGroupsToClean", e);
            } finally {
                IOUtils.closeQuietly(ois);
            }
        }

        public static ContainerGroupsRegistrar getInstance() {
            return containerGroupsRegistrar;
        }

        public ConcurrentLinkedQueue<ContainerGroupsInfo> getContainerGroupsToClean() {
            return containerGroupsToClean;
        }

        public void registerContainerGroups(String cloudName,
                                       String resourceGroupName,
                                       String containerGroupsName) {
            LOGGER.log(Level.INFO,
                    "AzureAciCleanUpTask: registerContainerGroups: Registering containerGroups {0} in {1}",
                    new Object[]{containerGroupsName, resourceGroupName});
            ContainerGroupsInfo newContainerGroupsToClean =
                    new ContainerGroupsInfo(cloudName, resourceGroupName, containerGroupsName, MAX_DELETE_ATTEMPTS);
            containerGroupsToClean.add(newContainerGroupsToClean);

            syncContainerGroupsToClean();
        }

        public synchronized void syncContainerGroupsToClean() {
            ObjectOutputStream oos = null;
            try {
                oos = new ObjectOutputStream(new FileOutputStream(OUTPUT_FILE));
                oos.writeObject(containerGroupsToClean);
            } catch (FileNotFoundException e) {
                LOGGER.log(Level.WARNING,
                        "AzureAciCleanUpTask: registerContainerGroups: Cannot open containerGroups output file"
                                + OUTPUT_FILE);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "AzureAciCleanUpTask: registerContainerGroups: Serialize failed", e);
            } finally {
                IOUtils.closeQuietly(oos);
            }
        }
    }

    public static String loadProperty(final String name) {
        final String value = System.getProperty(name);
        if (StringUtils.isBlank(value)) {
            return loadEnv(name);
        }
        return value;
    }

    public static String loadEnv(final String name) {
        final String value = System.getenv(name);
        if (StringUtils.isBlank(value)) {
            return "";
        }
        return value;
    }

    public AciCloud getCloud(String cloudName) {
        return Jenkins.getInstanceOrNull() == null ? null : (AciCloud) Jenkins.getInstance().getCloud(cloudName);
    }

    public void cleanContainerGroups() {
        cleanContainerGroups(SUCCESSFUL_DEPLOYMENT_TIMEOUT_IN_MINUTES, FAILING_DEPLOYMENT_TIMEOUT_IN_MINUTES);
    }

    private void cleanContainerGroups(long successTimeoutInMinutes, long failTimeoutInMinutes) {
        ContainerGroupsInfo firstBackInQueue = null;
        ConcurrentLinkedQueue<ContainerGroupsInfo> containerGroupsToClean
                = ContainerGroupsRegistrar.getInstance().getContainerGroupsToClean();
        while (!containerGroupsToClean.isEmpty() && firstBackInQueue != containerGroupsToClean.peek()) {
            ContainerGroupsInfo info = containerGroupsToClean.remove();

            LOGGER.log(getNormalLoggingLevel(),
                    "AzureAciCleanUpTask: cleanContainerGroups: Checking containerGroups {0}",
                    info.getContainerGroupsName());

            AciCloud cloud = getCloud(info.getCloudName());

            if (cloud == null) {
                // Cloud could have been deleted, skip
                continue;
            }


            try {

                Azure azureClient = AzureContainerUtils.getAzureClient(cloud.getCredentialsId());

                // This will throw if the containerGroups can't be found.  This could happen in a couple instances
                // 1) The containerGroups has already been deleted
                // 2) The containerGroups doesn't exist yet (race between creating the containerGroups and it
                //    being accepted by Azure.
                // To avoid this, we implement a retry.  If we hit an exception, we will decrement the number
                // of retries.  If we hit 0, we remove the containerGroups from our list.
                ContainerGroup containerGroups;
                try {
                    containerGroups = azureClient.containerGroups().
                            getByResourceGroup(info.getResourceGroupName(), info.getContainerGroupsName());
                } catch (NullPointerException e) {
                    LOGGER.log(getNormalLoggingLevel(),
                            "AzureAciCleanUpTask: cleanContainerGroups: ContainerGroups not found, skipping");
                    continue;
                }
                if (containerGroups == null) {
                    LOGGER.log(getNormalLoggingLevel(),
                            "AzureAciCleanUpTask: cleanContainerGroups: ContainerGroups not found, skipping");
                    continue;
                }

                long containerGroupsTimeInMillis = Long.parseLong(containerGroups.tags().get("CREATION_TIME"));

                LOGGER.log(getNormalLoggingLevel(),
                        "AzureAciCleanUpTask: cleanContainerGroups: ContainerGroups created on {0}",
                        Instant.ofEpochMilli(containerGroupsTimeInMillis)
                                .atZone(ZoneId.systemDefault()).toLocalDateTime());

                // Compare to now
                //Calendar nowTime = Calendar.getInstance(containerGroupsTime.getZone().toTimeZone());
                long nowTimeInMillis = Instant.now().toEpochMilli();

                long diffTime = nowTimeInMillis - containerGroupsTimeInMillis;
                long diffTimeInMinutes = diffTime / Constants.MILLIS_IN_MINUTE;

                String state = containerGroups.provisioningState();

                if (!"succeeded".equalsIgnoreCase(state) && diffTimeInMinutes > failTimeoutInMinutes) {
                    LOGGER.log(getNormalLoggingLevel(),
                            "AzureAciCleanUpTask: cleanContainerGroups: "
                                    + "Failed containerGroups older than {0} minutes, deleting",
                            failTimeoutInMinutes);
                    // Delete the containerGroups
                    azureClient.containerGroups()
                            .deleteByResourceGroup(info.getResourceGroupName(), info.getContainerGroupsName());
                } else if ("succeeded".equalsIgnoreCase(state)
                        && diffTimeInMinutes > successTimeoutInMinutes) {
                    LOGGER.log(getNormalLoggingLevel(),
                            "AzureAciCleanUpTask: cleanContainerGroups: "
                                    + "Successful containerGroups older than {0} minutes, deleting",
                            successTimeoutInMinutes);
                    // Delete the containerGroups
                    azureClient.containerGroups()
                            .deleteByResourceGroup(info.getResourceGroupName(), info.getContainerGroupsName());
                } else {
                    LOGGER.log(getNormalLoggingLevel(),
                            "AzureAciCleanUpTask: cleanContainerGroups: ContainerGroups newer than timeout, keeping");

                    if (firstBackInQueue == null) {
                        firstBackInQueue = info;
                    }
                    // Put it back
                    containerGroupsToClean.add(info);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "AzureAciCleanUpTask: cleanContainerGroups: Failed to get/delete containerGroups: {0}",
                        e);
                // Check the number of attempts remaining. If greater than 0, decrement
                // and add back into the queue.
                if (info.hasAttemptsRemaining()) {
                    info.decrementAttemptsRemaining();

                    if (firstBackInQueue == null) {
                        firstBackInQueue = info;
                    }

                    // Put it back in the queue for another attempt
                    containerGroupsToClean.add(info);
                }
            }
        }
        ContainerGroupsRegistrar.getInstance().syncContainerGroupsToClean();
    }

    private void cleanLeakedContainer(final AciCloud cloud) {
        LOGGER.log(Level.INFO, "Starting to clean leaked containers for cloud " + cloud.getName());
        Azure azureClient = null;
        try {
            azureClient = cloud.getAzureClient();
        } catch (Exception e) {
            return;
        }

        final String resourceGroup = cloud.getResourceGroup();
        final String credentialsId = cloud.getCredentialsId();
        if (StringUtils.isBlank(resourceGroup) || StringUtils.isBlank(credentialsId)) {
            return;
        }

        Set<String> validContainerSet = getValidContainer();

        List<GenericResource> resourceList = azureClient.genericResources().listByResourceGroup(resourceGroup);
        for (final GenericResource resource : resourceList) {
            if (resource.resourceProviderNamespace().equalsIgnoreCase("Microsoft.ContainerInstance")
                    && resource.resourceType().equalsIgnoreCase("containerGroups")
                    && resource.tags().containsKey("JenkinsInstance")
                    && resource.tags().get("JenkinsInstance")
                    .equalsIgnoreCase(Jenkins.getInstance().getLegacyInstanceId())) {
                if (!validContainerSet.contains(resource.name())) {
                    AciCloud.getThreadPool().submit(new Runnable() {
                        @Override
                        public void run() {
                            AciService.deleteAciContainerGroup(credentialsId,
                                    resourceGroup,
                                    resource.name(),
                                    null);
                        }
                    });
                }
            }
        }
    }

    private Set<String> getValidContainer() {
        Set<String> result = new TreeSet<>();
        if (Jenkins.getInstance() != null) {
            for (Computer computer : Jenkins.getInstance().getComputers()) {
                if (computer instanceof AciComputer) {
                    result.add(computer.getName());
                }
            }
        }
        return result;
    }

    @Override
    public void execute(TaskListener arg0) {
        clean();
        cleanContainerGroups();
    }

    @Override
    public long getRecurrencePeriod() {
        return RECURRENCE_PERIOD_IN_MILLIS;
    }

}

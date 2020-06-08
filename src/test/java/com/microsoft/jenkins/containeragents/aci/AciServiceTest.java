package com.microsoft.jenkins.containeragents.aci;

import com.google.common.collect.ImmutableList;
import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.containerinstance.OperatingSystemTypes;
import com.microsoft.jenkins.containeragents.PodEnvVar;
import com.microsoft.jenkins.containeragents.remote.LaunchMethodTypeContent;
import com.microsoft.jenkins.containeragents.util.Constants;
import com.microsoft.rest.LogLevel;
import hudson.slaves.RetentionStrategy;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.time.StopWatch;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.ArrayList;

import static org.junit.Assert.assertThat;

public class AciServiceTest {

    public static final String RESOURCE_GROUP_NAME = "";
    private AciCloud cloud;
    private AciContainerTemplate aciContainerTemplate;
    private AciAgent aciAgent;
    private String SUBSCRIPTION_ID = "";

    @Before
    public void setup() throws Exception {

        String clientId = "";
        String tenant = "";
        String key = "";
        ApplicationTokenCredentials credentials = new ApplicationTokenCredentials(
                clientId,
                tenant,
                key,
                AzureEnvironment.AZURE);

        Azure client = Azure
                .configure()
                .withLogLevel(LogLevel.NONE)
                .authenticate(credentials)
                .withSubscription(SUBSCRIPTION_ID);


        aciContainerTemplate = Mockito.mock(AciContainerTemplate.class);
        cloud = Mockito.mock(AciCloud.class);
        Mockito.when(cloud.getAzureClient()).thenReturn(client);
        Mockito.when(cloud.getResourceGroup()).thenReturn(RESOURCE_GROUP_NAME);
        String SSH_KEY = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCx1IH/KVuZAHA4dWUg8cfJvuy4DFXrnkh+Oi/hl1mh+LfLRsPym6oPDqhIvyOvNDULJzN3ptgoMmFMZhfbs9uz9wo/PWFExsHHHtgKT6dLk2RZIqRBpHr7nzDNvGGOeQStgquvlzMpc2n8mcbSMdXUKi5TulQ079+jblg4ZXM8QEyI0CeMhigmL2+M8o3N15bXyd9/C0yHDTeQipeqj2JtVruaeeO4b5CJHbloF6WK+nIfEUBxB7IRp+0BjxM7XkTdLq8olbx17mY2c5Lkz9Bl0K3+/N0pyOiNJS4RRlpGTxNamoP4Z27LwFYomY7r2nTdwGByogEqMk//1bQt9XKP jenkins@jenkins.org";
        PodEnvVar podEnvVar = new PodEnvVar("JENKINS_AGENT_SSH_PUBKEY", SSH_KEY);
        DockerRegistryEndpoint dockerRegistryEndpoint = new DockerRegistryEndpoint(null, null);
        AciPort aciPort = new AciPort("22");
        aciContainerTemplate = new AciContainerTemplate(
                "jenkins-node-name-t",
                "jenkins-node-label-t",
                hashCode(),
                OperatingSystemTypes.LINUX.toString(),
                "jenkins/ssh-slave",
                "setup-sshd",
                "/home/jenkins",
                ImmutableList.of(aciPort),
                ImmutableList.of(dockerRegistryEndpoint),
                ImmutableList.of(podEnvVar),
                new ArrayList<>(),
                RetentionStrategy.INSTANCE,
                "1",
                "1");
        aciContainerTemplate.setLaunchMethodType(Constants.LAUNCH_METHOD_SSH);
        aciContainerTemplate.setLaunchMethodTypeContent(new LaunchMethodTypeContent("", "22"));

        aciAgent = Mockito.mock(AciAgent.class);
        Mockito.when(aciAgent.getNodeName()).thenReturn("ta-template-ujyht");
    }

    @Test
    public void testCreateDeployment() throws Exception {
        AciService.createDeployment(
                cloud, aciContainerTemplate, aciAgent, StopWatch.createStarted(), "jenkins_instance_id");
    }

}

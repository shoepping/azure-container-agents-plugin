package com.microsoft.jenkins.containeragents.aci;

import com.microsoft.jenkins.containeragents.KubernetesAgent;
import com.microsoft.jenkins.containeragents.KubernetesCloud;
import com.microsoft.jenkins.containeragents.PodTemplate;
import hudson.model.Descriptor;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.Mockito;

import java.io.IOException;

import static org.hamcrest.core.StringStartsWith.startsWith;
import static org.junit.Assert.assertThat;

public class AciServiceTest {

    private AciCloud aciCloud;
    private AciContainerTemplate aciContainerTemplate;

    @Before
    public void setup() {
        aciContainerTemplate = Mockito.mock(AciContainerTemplate.class);
        aciCloud = Mockito.mock(AciCloud.class);
        Mockito.when(aciCloud.getCredentialsId()).thenReturn("");

    }

    @Test
    public void testCreateDeployment() {

        //Assert.assertEquals("", aciCloud.getCredentialsId());
        //AciService.createDeployment(aciCloud, );

    }

}

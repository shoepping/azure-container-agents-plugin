package com.microsoft.jenkins.containeragents.remote;

import hudson.plugins.sshslaves.verifiers.KnownHostsFileKeyVerificationStrategy;
import hudson.plugins.sshslaves.verifiers.SshHostKeyVerificationStrategy;

public class SSHLauncher extends hudson.plugins.sshslaves.SSHLauncher {

    public SSHLauncher(String host, int port, String credentialsId,
                       String jvmOptions, String javaPath,
                       String prefixStartSlaveCmd, String suffixStartSlaveCmd,
                       Integer launchTimeoutSeconds, Integer maxNumRetries,
                       Integer retryWaitTime,
                       SshHostKeyVerificationStrategy sshHostKeyVerificationStrategy) {
        super(host, port, credentialsId, jvmOptions, javaPath,
                prefixStartSlaveCmd, suffixStartSlaveCmd, launchTimeoutSeconds,
                maxNumRetries, retryWaitTime, sshHostKeyVerificationStrategy);
    }

    public SSHLauncher() {
        this((String) null,   // Host
                (Integer) null,      // Port
                (String) null, // Credentials
                (String) null,  // JVM Options
                (String) null,      // JavaPath
                (String) null,  // Prefix Start Slave Command
                (String) null,  // Suffix Start Slave Command
                (Integer) null, // Connection Timeout in Seconds
                (Integer) null, // Maximum Number of Retries
                (Integer) null, // The number of seconds to wait between retries
                new KnownHostsFileKeyVerificationStrategy());
    }
}

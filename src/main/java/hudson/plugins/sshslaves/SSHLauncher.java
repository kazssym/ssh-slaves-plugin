package hudson.plugins.sshslaves;

import ch.ethz.ssh2.ChannelCondition;
import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.SFTPv3Client;
import ch.ethz.ssh2.SFTPv3FileAttributes;
import ch.ethz.ssh2.Session;
import ch.ethz.ssh2.StreamGobbler;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.JDK;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.slaves.SlaveComputer;
import hudson.tools.ToolLocationNodeProperty;
import hudson.tools.ToolLocationNodeProperty.ToolLocation;
import hudson.util.DescribableList;
import hudson.util.IOException2;
import hudson.util.NullStream;
import hudson.util.Secret;
import hudson.util.StreamCopyThread;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.CountingOutputStream;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.putty.PuTTYKey;
import org.kohsuke.stapler.DataBoundConstructor;

import static hudson.Util.fixEmpty;
import static java.util.logging.Level.FINE;

/**
 * A computer launcher that tries to start a linux slave by opening an SSH connection and trying to find java.
 */
public class SSHLauncher extends ComputerLauncher {
    private static final Logger LOGGER = Logger.getLogger(SSHLauncher.class.getName());

    private static final int DEFAULT_SSH_PORT = 22;

    /**
     * Field host
     */
    private final String host;

    /**
     * Field port
     */
    private final int port;

    /**
     * Field username
     */
    private final String username;

    /**
     * Field password
     * <p/>
     * todo remove password once authentication is stored in the descriptor.
     */
    private final Secret password;

    /**
     * File path of the private key.
     */
    private final String privatekey;

    /**
     * Field javaPath.
     */
    private final String javaPath;

    /**
     * Field jvmOptions.
     */
    private final String jvmOptions;

    /**
     * Field connection
     */
    private transient Connection connection;

    /**
     * Constructor SSHLauncher creates a new SSHLauncher instance.
     *
     * @param host The host to connect to.
     * @param port The port to connect on.
     * @param username The username to connect as.
     * @param password The password to connect with.
     * @param privatekey The ssh privatekey to connect with.
     * @param jvmOptions jvm options.
     * @param javaPath optional path to java.
     */
    @DataBoundConstructor
    public SSHLauncher(String host, int port, String username, String password, String privatekey, String jvmOptions,
                       String javaPath) {
        this.host = host;
        this.jvmOptions = jvmOptions;
        this.port = port == 0 ? DEFAULT_SSH_PORT : port;
        this.username = username;
        this.password = Secret.fromString(fixEmpty(password));
        this.privatekey = privatekey;
        this.javaPath = javaPath;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isLaunchSupported() {
        return true;
    }

    /**
     * Gets the JVM Options used to launch the slave JVM.
     *
     * @return string.
     */
    public String getJvmOptions() {
        return StringUtils.defaultString(jvmOptions);
    }

    /**
     * Gets the optional java command to use to launch the slave JVM.
     *
     * @return command.
     */
    public String getJavaPath() {
        return StringUtils.defaultString(javaPath);
    }

    /**
     * Gets the formatted current time stamp.
     *
     * @return the formatted current time stamp.
     */
    protected String getTimestamp() {
        return String.format("[%1$tD %1$tT]", new Date());
    }

    /**
     * Returns the remote root workspace (without trailing slash).
     *
     * @param computer The slave computer to get the root workspace of.
     * @return the remote root workspace (without trailing slash).
     */
    private static String getWorkingDirectory(SlaveComputer computer) {
        return getWorkingDirectory(computer.getNode());
    }

    private static String getWorkingDirectory(Slave slave) {
        String workingDirectory = slave.getRemoteFS();
        while(workingDirectory.endsWith("/")) {
            workingDirectory = workingDirectory.substring(0, workingDirectory.length() - 1);
        }
        return workingDirectory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void launch(final SlaveComputer computer, final TaskListener listener)
        throws InterruptedException {
        connection = new Connection(host, port);
        try {
            openConnection(listener);

            verifyNoHeaderJunk(listener);
            reportEnvironment(listener);

            String java = resolveJava(computer, listener);

            String workingDirectory = getWorkingDirectory(computer);
            copySlaveJar(listener, workingDirectory);

            startSlave(computer, listener, java, workingDirectory);

            PluginImpl.register(connection);
        } catch(RuntimeException e) {
            e.printStackTrace(listener.error(Messages.SSHLauncher_UnexpectedError()));
        } catch(Error e) {
            e.printStackTrace(listener.error(Messages.SSHLauncher_UnexpectedError()));
        } catch(IOException e) {
            e.printStackTrace(listener.getLogger());
            connection.close();
            connection = null;
            listener.getLogger().println(Messages.SSHLauncher_ConnectionClosed(getTimestamp()));
        }
    }

    /**
     * Finds local Java, and if none exist, install one.
     * If javaPath is specified, return specified value.
     *
     * @param computer slave.
     * @param listener task listener.
     * @return java location.
     * @throws InterruptedException if any.
     * @throws IOException2         if any.
     */
    protected String resolveJava(SlaveComputer computer, TaskListener listener)
        throws InterruptedException, IOException2 {
        if(StringUtils.isNotBlank(javaPath)) {
            return javaPath;
        }
//        String workingDirectory = getWorkingDirectory(computer);

        List<String> tried = new ArrayList<String>();
        for(JavaProvider provider : JavaProvider.all()) {
            for(String javaCommand : provider.getJavas(computer, listener, connection)) {
                LOGGER.fine("Trying Java at " + javaCommand);
                try {
                    tried.add(javaCommand);
                    String java = checkJavaVersion(listener, javaCommand);
                    if(java != null) {
                        return java;
                    }
                } catch(IOException e) {
                    LOGGER.log(FINE, "Failed to check the Java version", e);
                    // try the next one
                }
            }
        }
        //TODO enable installer when it will be ready
        throw new InterruptedException(
            "Could not find any known supported java version, please install JDK on slave node");
        // attempt auto JDK installation
//        try {
//            return attemptToInstallJDK(listener, workingDirectory);
//        } catch(IOException e) {
//            throw new IOException2("Could not find any known supported java version in " + tried
//                + ", and we also failed to install JDK as a fallback", e);
//        }
    }

    /**
     * Makes sure that SSH connection won't produce any unwanted text, which will interfere with sftp execution.
     *
     * @param listener task listener.
     * @throws IOException          if any.
     * @throws InterruptedException if any.
     */
    private void verifyNoHeaderJunk(TaskListener listener) throws IOException, InterruptedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        exec("true", baos);
        String s = baos.toString();
        if(s.length() != 0) {
            listener.getLogger().println(Messages.SSHLauncher_SSHHeeaderJunkDetected());
            listener.getLogger().println(s);
            throw new AbortException();
        }
    }

    /**
     * Attempts to install JDK, and return the path to Java.
     *
     * @param listener task listener.
     * @param workingDirectory String.
     * @return new path to java executable.
     * @throws IOException          if any.
     * @throws InterruptedException if any.
     */
//    private String attemptToInstallJDK(TaskListener listener, String workingDirectory)
//        throws IOException, InterruptedException {
//        ByteArrayOutputStream unameOutput = new ByteArrayOutputStream();
//        if (exec("uname -a", new TeeOutputStream(unameOutput, listener.getLogger())) != 0) {
//            throw new IOException("Failed to run 'uname' to obtain the environment");
//        }
//
//        // guess the platform from uname output. I don't use the specific options because I'm not sure
//        // if various platforms have the consistent options
//        //
//        // === some of the output collected ====
//        // Linux bear 2.6.28-15-generic #49-Ubuntu SMP Tue Aug 18 19:25:34 UTC 2009 x86_64 GNU/Linux
//        // Linux wssqe20 2.6.24-24-386 #1 Tue Aug 18 16:24:26 UTC 2009 i686 GNU/Linux
//        // SunOS hudson 5.11 snv_79a i86pc i386 i86pc
//        // SunOS legolas 5.9 Generic_112233-12 sun4u sparc SUNW,Sun-Fire-280R
//        // CYGWIN_NT-5.1 franz 1.7.0(0.185/5/3) 2008-07-22 19:09 i686 Cygwin
//        // Windows_NT WINXPIE7 5 01 586
//        //        (this one is from MKS)
//
//        String uname = unameOutput.toString();
//        Platform p = null;
//        CPU cpu = null;
//        if(uname.contains("GNU/Linux")) {
//            p = Platform.LINUX;
//        }
//        if(uname.contains("SunOS")) {
//            p = Platform.SOLARIS;
//        }
//        if(uname.contains("CYGWIN")) {
//            p = Platform.WINDOWS;
//        }
//        if(uname.contains("Windows_NT")) {
//            p = Platform.WINDOWS;
//        }
//
//        if(uname.contains("sparc")) {
//            cpu = CPU.Sparc;
//        }
//        if(uname.contains("x86_64")) {
//            cpu = CPU.amd64;
//        }
//        if(Pattern.compile("\\bi?[3-6]86\\b").matcher(uname).find()) {
//            cpu = CPU.i386;  // look for ix86 as a word
//        }
//
//        if(p == null || cpu == null) {
//            throw new IOException(Messages.SSHLauncher_FailedToDetectEnvironment(uname));
//        }
//
//        String javaDir = workingDirectory + "/jdk"; // this is where we install Java to
//        String bundleFile = workingDirectory + "/" + p.bundleFileName; // this is where we download the bundle to
//
//        SFTPClient sftp = new SFTPClient(connection);
//        // wipe out and recreate the Java directory
//        exec("rm -rf " + javaDir, listener.getLogger());
//        sftp.mkdirs(javaDir, 755);
//
//        JDKInstaller jdk = new JDKInstaller("jdk-6u16-oth-JPR@CDS-CDS_Developer", true);
//        URL bundle = jdk.locate(listener, p, cpu);
//
//        listener.getLogger().println("Installing JDK6u16");
//        Util.copyStreamAndClose(bundle.openStream(), new BufferedOutputStream(sftp.writeToFile(bundleFile), 32 * 1024));
//        sftp.chmod(bundleFile, 755);
//
//        jdk.install(new RemoteLauncher(listener, connection), p, new SFTPFileSystem(sftp), listener, javaDir,
//            bundleFile);
//        return javaDir + "/bin/java";
//    }

    /**
     * Starts the slave process.
     *
     * @param computer The computer.
     * @param listener The listener.
     * @param java The full path name of the java executable to use.
     * @param workingDirectory The working directory from which to start the java process.
     * @throws IOException If something goes wrong.
     */
    private void startSlave(SlaveComputer computer, final TaskListener listener, String java,
                            String workingDirectory) throws IOException {
        final Session session = connection.openSession();
        String cmd = "cd '" + workingDirectory + "' && " + java + " " + getJvmOptions() + " -jar slave.jar";
        listener.getLogger().println(Messages.SSHLauncher_StartingSlaveProcess(getTimestamp(), cmd));
        session.execCommand(cmd);
        final StreamGobbler out = new StreamGobbler(session.getStdout());
        final StreamGobbler err = new StreamGobbler(session.getStderr());

        // capture error information from stderr. this will terminate itself
        // when the process is killed.
        new StreamCopyThread("stderr copier for remote agent on " + computer.getDisplayName(),
            err, listener.getLogger()).start();

        try {
            computer.setChannel(out, session.getStdin(), listener.getLogger(), new Channel.Listener() {
                @Override
                public void onClosed(Channel channel, IOException cause) {
                    if(cause != null) {
                        cause.printStackTrace(listener.error(hudson.model.Messages.Slave_Terminated(getTimestamp())));
                    }
                    try {
                        session.close();
                    } catch(Throwable t) {
                        t.printStackTrace(listener.error(Messages.SSHLauncher_ErrorWhileClosingConnection()));
                    }
                    try {
                        out.close();
                    } catch(Throwable t) {
                        t.printStackTrace(listener.error(Messages.SSHLauncher_ErrorWhileClosingConnection()));
                    }
                    try {
                        err.close();
                    } catch(Throwable t) {
                        t.printStackTrace(listener.error(Messages.SSHLauncher_ErrorWhileClosingConnection()));
                    }
                }
            });

        } catch(InterruptedException e) {
            session.close();
            throw new IOException2(Messages.SSHLauncher_AbortedDuringConnectionOpen(), e);
        }
    }

    /**
     * Method copies the slave jar to the remote system.
     *
     * @param listener The listener.
     * @param workingDirectory The directory into which the slave jar will be copied.
     * @throws IOException          If something goes wrong.
     * @throws InterruptedException if any.
     */
    @SuppressWarnings("PMD.AvoidUsingOctalValues")
    private void copySlaveJar(TaskListener listener, String workingDirectory) throws IOException, InterruptedException {
        String fileName = workingDirectory + "/slave.jar";

        listener.getLogger().println(Messages.SSHLauncher_StartingSFTPClient(getTimestamp()));
        SFTPClient sftpClient = null;
        try {
            sftpClient = new SFTPClient(connection);

            try {
                SFTPv3FileAttributes fileAttributes = sftpClient._stat(workingDirectory);
                if(fileAttributes == null) {
                    listener.getLogger().println(Messages.SSHLauncher_RemoteFSDoesNotExist(getTimestamp(),
                        workingDirectory));
                    // It would be OK with 0777 or 0755, but Jenkins appears to do this way.
                    sftpClient.mkdirs(workingDirectory, 0700);
                } else if(fileAttributes.isRegularFile()) {
                    throw new IOException(Messages.SSHLauncher_RemoteFSIsAFile(workingDirectory));
                }

                try {
                    // try to delete the file in case the slave we are copying is shorter than the slave
                    // that is already there
                    sftpClient.rm(fileName);
                } catch(IOException e) {
                    // the file did not exist... so no need to delete it!
                    LOGGER.log(Level.FINEST, "Couldn't delete "+ fileName + ". File doesn't exists.");
                }

                listener.getLogger().println(Messages.SSHLauncher_CopyingSlaveJar(getTimestamp()));

                try {
                    CountingOutputStream os = new CountingOutputStream(sftpClient.writeToFile(fileName));
                    Util.copyStreamAndClose(
                        Hudson.getInstance().servletContext.getResourceAsStream("/WEB-INF/slave.jar"),
                        os);
                    listener.getLogger()
                        .println(Messages.SSHLauncher_CopiedXXXBytes(getTimestamp(), os.getByteCount()));
                } catch(Exception e) {
                    throw new IOException2(Messages.SSHLauncher_ErrorCopyingSlaveJarTo(fileName), e);
                }
            } catch(Exception e) {
                throw new IOException2(Messages.SSHLauncher_ErrorCopyingSlaveJar(), e);
            }
        } catch(IOException e) {
            if(sftpClient == null) {
                // lets try to recover if the slave doesn't have an SFTP service
                copySlaveJarUsingSCP(listener, workingDirectory);
            } else {
                throw e;
            }
        } finally {
            if(sftpClient != null) {
                sftpClient.close();
            }
        }
    }

    /**
     * Method copies the slave jar to the remote system using scp.
     *
     * @param listener The listener.
     * @param workingDirectory The directory into which the slave jar will be copied.
     * @throws IOException          If something goes wrong.
     * @throws InterruptedException If something goes wrong.
     */
    private void copySlaveJarUsingSCP(TaskListener listener, String workingDirectory)
        throws IOException, InterruptedException {
        listener.getLogger().println(Messages.SSHLauncher_StartingSCPClient(getTimestamp()));
        HudsonSCPClient scp = new HudsonSCPClient(connection);
        try {
            // check if the working directory exists
            if (exec("test -d " + workingDirectory, listener.getLogger()) != 0) {
                listener.getLogger()
                    .println(Messages.SSHLauncher_RemoteFSDoesNotExist(getTimestamp(), workingDirectory));
                // working directory doesn't exist, lets make it.
                if (exec("mkdir -p " + workingDirectory, listener.getLogger()) != 0) {
                    listener.getLogger().println("Failed to create " + workingDirectory);
                }
            }

            // delete the slave jar as we do with SFTP
            exec("rm " + workingDirectory + "/slave.jar", new NullStream());

            // SCP it to the slave. hudson.Util.ByteArrayOutputStream2 doesn't work for this. It pads the byte array.
            InputStream is = Hudson.getInstance().servletContext.getResourceAsStream("/WEB-INF/slave.jar");
            listener.getLogger().println(Messages.SSHLauncher_CopyingSlaveJar(getTimestamp()));
            scp.put(IOUtils.toByteArray(is), "slave.jar", workingDirectory, "0644");
        } catch(IOException e) {
            throw new IOException2(Messages.SSHLauncher_ErrorCopyingSlaveJar(), e);
        }
    }

    protected void reportEnvironment(TaskListener listener) throws IOException, InterruptedException {
        listener.getLogger().println(Messages._SSHLauncher_RemoteUserEnvironment(getTimestamp()));
        exec("set", listener.getLogger());
    }

    private String checkJavaVersion(TaskListener listener, String javaCommand)
        throws IOException, InterruptedException {
        listener.getLogger().println(Messages.SSHLauncher_CheckingDefaultJava(getTimestamp(), javaCommand));
        StringWriter output = new StringWriter();   // record output from Java

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exec(javaCommand + " " + getJvmOptions() + " -version", out);
        BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(out.toByteArray())));
        final String result = checkJavaVersion(listener.getLogger(), javaCommand, r, output);

        if(null == result) {
            listener.getLogger().println(Messages.SSHLauncher_UknownJavaVersion(javaCommand));
            listener.getLogger().println(output);
            throw new IOException(Messages.SSHLauncher_UknownJavaVersion(javaCommand));
        } else {
            return result;
        }
    }

    /**
     * Given the output of "java -version" in <code>r</code>, determine if this
     * version of Java is supported. This method has default visibility for testing.
     *
     * @param logger where to log the output
     * @param javaCommand the command executed, used for logging
     * @param r the output of "java -version"
     * @param output copy the data from <code>r</code> into this output buffer
     * @return java command or null if r is null
     * @throws IOException if any.
     */
    protected String checkJavaVersion(final PrintStream logger, String javaCommand,
                                      final BufferedReader r, final StringWriter output)
        throws IOException {
        String line;
        while(null != (line = r.readLine())) {
            output.write(line);
            output.write("\n");
            line = line.toLowerCase();
            if(line.startsWith("java version \"")
                || line.startsWith("openjdk version \"")) {
                final String versionStr = line.substring(
                    line.indexOf('\"') + 1, line.lastIndexOf('\"'));
                logger.println(Messages.SSHLauncher_JavaVersionResult(getTimestamp(), javaCommand, versionStr));

                // parse as a number and we should be OK as all we care about is up through the first dot.
                try {
                    final Number version =
                        NumberFormat.getNumberInstance(Locale.US).parse(versionStr);
                    if(version.doubleValue() < 1.5) {
                        throw new IOException(Messages.SSHLauncher_NoJavaFound(line));
                    }
                } catch(final ParseException e) {
                    throw new IOException(Messages.SSHLauncher_NoJavaFound(line));
                }
                return javaCommand;
            }
        }
        return null;
    }

    protected void openConnection(TaskListener listener) throws IOException {
        listener.getLogger().println(Messages.SSHLauncher_OpeningSSHConnection(getTimestamp(), host + ":" + port));
        connection.connect();

        String username = this.username;
        if(fixEmpty(username) == null) {
            username = System.getProperty("user.name");
            LOGGER.fine("Defaulting the user name to " + username);
        }

        String pass = Util.fixNull(getPassword());

        boolean isAuthenticated = false;
        if(fixEmpty(privatekey) == null && fixEmpty(pass) == null) {
            // check the default key locations if no authentication method is explicitly configured.
            File home = new File(System.getProperty("user.home"));
            for(String keyName : Arrays.asList("id_rsa", "id_dsa", "identity")) {
                File key = new File(home, ".ssh/" + keyName);
                if(key.exists()) {
                    listener.getLogger()
                        .println(Messages.SSHLauncher_AuthenticatingPublicKey(getTimestamp(), username, key));
                    isAuthenticated = connection.authenticateWithPublicKey(username, key, null);
                }
                if(isAuthenticated) {
                    break;
                }
            }
        }
        if(!isAuthenticated && fixEmpty(privatekey) != null) {
            File key = new File(privatekey);
            if(key.exists()) {
                listener.getLogger()
                    .println(Messages.SSHLauncher_AuthenticatingPublicKey(getTimestamp(), username, privatekey));
                if(PuTTYKey.isPuTTYKeyFile(key)) {
                    LOGGER.fine(key + " is a PuTTY key file");
                    String openSshKey = new PuTTYKey(key, pass).toOpenSSH();
                    isAuthenticated = connection.authenticateWithPublicKey(username, openSshKey.toCharArray(), pass);
                } else {
                    isAuthenticated = connection.authenticateWithPublicKey(username, key, pass);
                }
            }
        }
        if(!isAuthenticated) {
            listener.getLogger()
                .println(Messages.SSHLauncher_AuthenticatingUserPass(getTimestamp(), username, "******"));
            isAuthenticated = connection.authenticateWithPassword(username, pass);
        }

        if(isAuthenticated && connection.isAuthenticationComplete()) {
            listener.getLogger().println(Messages.SSHLauncher_AuthenticationSuccessful(getTimestamp()));
        } else {
            listener.getLogger().println(Messages.SSHLauncher_AuthenticationFailed(getTimestamp()));
            connection.close();
            connection = null;
            listener.getLogger().println(Messages.SSHLauncher_ConnectionClosed(getTimestamp()));
            throw new AbortException(Messages.SSHLauncher_AuthenticationFailedException());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void afterDisconnect(SlaveComputer slaveComputer, TaskListener listener) {
        Slave n = slaveComputer.getNode();
        if(connection != null) {
            if(n != null) {
                String workingDirectory = getWorkingDirectory(n);
                String fileName = workingDirectory + "/slave.jar";

                SFTPv3Client sftpClient = null;
                try {
                    sftpClient = new SFTPv3Client(connection);
                    sftpClient.rm(fileName);
                } catch(Exception e) {
                    if(sftpClient == null) {// system without SFTP
                        try {
                            exec("rm " + fileName, listener.getLogger());
                        } catch(Exception x) {
                            x.printStackTrace(listener.error(Messages.SSHLauncher_ErrorDeletingFile(getTimestamp())));
                        }
                    } else {
                        e.printStackTrace(listener.error(Messages.SSHLauncher_ErrorDeletingFile(getTimestamp())));
                    }
                } finally {
                    if(sftpClient != null) {
                        sftpClient.close();
                    }
                }
            }

            connection.close();
            PluginImpl.unregister(connection);
            connection = null;
            listener.getLogger().println(Messages.SSHLauncher_ConnectionClosed(getTimestamp()));
        }
        super.afterDisconnect(slaveComputer, listener);
    }

    /**
     * Getter for property 'host'.
     *
     * @return Value for property 'host'.
     */
    public String getHost() {
        return host;
    }

    /**
     * Getter for property 'port'.
     *
     * @return Value for property 'port'.
     */
    public int getPort() {
        return port;
    }

    /**
     * Getter for property 'username'.
     *
     * @return Value for property 'username'.
     */
    public String getUsername() {
        return username;
    }

    /**
     * Getter for property 'password'.
     *
     * @return Value for property 'password'.
     */
    public String getPassword() {
        return password != null ? Secret.toString(password) : null;
    }

    /**
     * Getter for property 'privatekey'.
     *
     * @return Value for property 'privatekey'.
     */
    public String getPrivatekey() {
        return privatekey;
    }

    public Connection getConnection() {
        return connection;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ComputerLauncher> {

        // TODO move the authentication storage to descriptor... see SubversionSCM.java

        // TODO add support for key files

        /**
         * {@inheritDoc}
         */
        public String getDisplayName() {
            return Messages.SSHLauncher_DescriptorDisplayName();
        }

        public Class getSshConnectorClass() {
            return SSHConnector.class;
        }

        /**
         * Delegates the help link to the {@link SSHConnector}.
         */
        @Override
        public String getHelpFile(String fieldName) {
            String n = super.getHelpFile(fieldName);
            if(n == null) {
                n = Hudson.getInstance().getDescriptor(SSHConnector.class).getHelpFile(fieldName);
            }
            return n;
        }

    }

    @Extension
    public static class DefaultJavaProvider extends JavaProvider {
        @Override
        public List<String> getJavas(SlaveComputer computer, TaskListener listener, Connection connection) {
            List<String> javas = new ArrayList<String>(Arrays.asList(
                "java",
                "/usr/bin/java",
                "/usr/java/default/bin/java",
                "/usr/java/latest/bin/java",
                "/usr/local/bin/java",
                "/usr/local/java/bin/java",
                getWorkingDirectory(computer) + "/jdk/bin/java")); // this is where we attempt to auto-install

            DescribableList<NodeProperty<?>, NodePropertyDescriptor> list = computer.getNode().getNodeProperties();
            if(list != null) {
                Descriptor jdk = Hudson.getInstance().getDescriptorByType(JDK.DescriptorImpl.class);
                for(NodeProperty prop : list) {
                    if(prop instanceof EnvironmentVariablesNodeProperty) {
                        EnvVars env = ((EnvironmentVariablesNodeProperty) prop).getEnvVars();
                        if(env != null && env.containsKey("JAVA_HOME")) {
                            javas.add(env.get("JAVA_HOME") + "/bin/java");
                        }
                    } else if(prop instanceof ToolLocationNodeProperty) {
                        for(ToolLocation tool : ((ToolLocationNodeProperty) prop).getLocations()) {
                            if(tool.getType() == jdk) {
                                javas.add(tool.getHome() + "/bin/java");
                            }
                        }
                    }
                }
            }
            return javas;
        }

    }

    /**
     * Executes a process remotely and blocks until its completion.
     *
     * @param command command for execution,
     * @param output The stdout/stderr will be sent to this stream.
     * @return result of command execution. -1 by default.
     * @throws IOException          if any
     * @throws InterruptedException if any.
     */
    public int exec(String command, OutputStream output) throws IOException, InterruptedException {
        if (null != connection) {
            Session session = connection.openSession();
            try {
                session.execCommand(command);
                PumpThread t1 = new PumpThread(session.getStdout(), output);
                t1.start();
                PumpThread t2 = new PumpThread(session.getStderr(), output);
                t2.start();
                session.getStdin().close();
                t1.join();
                t2.join();
                // wait for some time since the delivery of the exit status often gets delayed
                session.waitForCondition(ChannelCondition.EXIT_STATUS, 3000);
                Integer r = session.getExitStatus();
                if (r != null) {
                    return r;
                }
                return -1;
            } finally {
                session.close();
            }
        }
        return -1;
    }

    /**
     * Pumps {@link InputStream} to {@link OutputStream}.
     *
     * @author Kohsuke Kawaguchi
     */
    private static final class PumpThread extends Thread {
        private final InputStream in;
        private final OutputStream out;

        public PumpThread(InputStream in, OutputStream out) {
            super("pump thread");
            this.in = in;
            this.out = out;
        }

        @Override
        public void run() {
            byte[] buf = new byte[1024];
            try {
                while (true) {
                    int len = in.read(buf);
                    if (len < 0) {
                        in.close();
                        return;
                    }
                    out.write(buf, 0, len);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}

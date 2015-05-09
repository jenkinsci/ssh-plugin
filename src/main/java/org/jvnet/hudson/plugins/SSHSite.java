package org.jvnet.hudson.plugins;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.logging.Logger;
import java.util.StringTokenizer;

public class SSHSite {
	String hostname;
	int port;
	String username;
	String password;
	String keyfile;
	int serverAliveInterval = 0;
	int timeout = 0;
	Boolean pty = Boolean.FALSE;
	transient String resolvedHostname = null;

	public static final Logger LOGGER = Logger.getLogger(SSHSite.class.getName());

	public SSHSite() {

	}

	public SSHSite(String hostname, String port, String username, String password, String keyfile, String serverAliveInterval, final String timeout) {
		this.hostname = hostname;
		try {
			this.port = Integer.parseInt(port);
		} catch (NumberFormatException e) {
			this.port = 22;
		}
		this.username = username;
		this.password = password;
		this.keyfile = keyfile;
		this.setServerAliveInterval(serverAliveInterval);
		this.setTimeout(timeout);
	}

	public String getKeyfile() {
		return keyfile;
	}

	public void setKeyfile(String keyfile) {
		this.keyfile = keyfile;
	}

	public String getHostname() {
		return hostname;
	}

	public void setHostname(String hostname) {
		this.hostname = hostname;
	}

	public String getPort() {
		return "" + port;
	}

	public void setPort(String port) {
		try {
			this.port = Integer.parseInt(port);
		} catch (NumberFormatException e) {
			this.port = 22;
		}
	}

	public String getServerAliveInterval() {
		return "" + serverAliveInterval;
	}
	
	public void setServerAliveInterval(String serverAliveInterval) {
		try {
			this.serverAliveInterval = Integer.parseInt(serverAliveInterval);
		} catch (NumberFormatException e) {
			this.serverAliveInterval = 0;
		}
	}

	public void setPty(Boolean pty) {
		this.pty = pty;
	}

	public Boolean getPty() {
		return pty;
	}

	public int getIntegerPort() {
		return port;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getSitename() {
		return username + "@" + hostname + ":" + port;
	}
	
	public void setResolvedHostname(String hostname) {
		this.resolvedHostname = hostname;
	}
	
	private String getResolvedHostname() {
		return resolvedHostname == null ? hostname : resolvedHostname;
	}
	
	public String getTimeout() {
        return "" + timeout;
    }
	
	 public void setTimeout(final String timeout) {
        try {
            this.timeout = Integer.parseInt(timeout);
        } catch (NumberFormatException e) {
            this.timeout = 0;
        }
    }

	private Session createSession() throws JSchException {
		JSch jsch = new JSch();

		Session session = jsch.getSession(username, getResolvedHostname(), port);
		if (this.keyfile != null && this.keyfile.length() > 0) {
			jsch.addIdentity(this.keyfile, this.password);
		} else {
			session.setPassword(password);
		}

		UserInfo ui = new SSHUserInfo(password);
		session.setUserInfo(ui);

		session.setServerAliveInterval(serverAliveInterval);

		java.util.Properties config = new java.util.Properties();
		config.put("StrictHostKeyChecking", "no");
		session.setConfig(config);
		session.connect(timeout);

		return session;
	}

	//preserved on the off chance this is used by another plugin
	public int executeCommand(PrintStream logger, String command) throws InterruptedException {
		return executeCommand(logger, command, false);
	}

	public int executeCommand(PrintStream logger, String command, boolean execEachLine) throws InterruptedException {
		Session session = null;
		int status = -1;
		try {
			session = createSession();
			if (execEachLine) {
				StringTokenizer commands = new StringTokenizer(command,"\n\r\f");
				while (commands.hasMoreTokens()) {
					int i = execCommand(session, logger, commands.nextToken().trim());
					if (i != 0) {
						status = i;
						break;
					}
					//if there are no more commands to execute return the status of the last command
					if (!commands.hasMoreTokens()) {
						status = i;
					}
				}
			}
			else {
				status = execCommand(session, logger, command);
			}
			logger.printf("%n[SSH] completed");
			logger.printf("%n[SSH] exit-status: " + status + "%n%n");
		} catch (JSchException e) {
			logger.println("[SSH] Exception:" + e.getMessage());
			e.printStackTrace(logger);
		} catch (IOException e) {
			logger.println("[SSH] Exception:" + e.getMessage());
			e.printStackTrace(logger);
		} finally {
			if (session != null && session.isConnected()) {
				session.disconnect();
			}
		}

		return status;
	}

	private int execCommand(Session session, PrintStream logger, String command) throws InterruptedException, IOException, JSchException {
//		logger.println("	Executing: " + command);
		ChannelExec channel = null;
		int status = -1;
		try {
			channel = createChannel(logger, session);
			channel.setCommand(command);
			InputStream in = channel.getInputStream();
			channel.connect();
			byte[] tmp = new byte[1024];
			while (true) {
				while (in.available() > 0) {
					int i = in.read(tmp, 0, 1024);
					if (i < 0)
						break;
					logger.write(tmp, 0, i);
				}
				if (channel.isClosed()) {
					status = channel.getExitStatus();
					break;
				}
				logger.flush();
				Thread.sleep(1000);
			}
		} catch (JSchException e) {
			throw e;
		} finally {
			if (channel != null && channel.isConnected()) {
				channel.disconnect();
			}
		}

		return status;
	}

	public void testConnection(PrintStream logger) throws JSchException, IOException {
		Session session = createSession();
		closeSession(session, null);
	}

	private ChannelExec createChannel(PrintStream logger, Session session) throws JSchException {
		ChannelExec channel = (ChannelExec) session.openChannel("exec");
		channel.setOutputStream(logger, true);
		channel.setExtOutputStream(logger, true);
		channel.setInputStream(null);
		if (pty == null) {
			pty = Boolean.FALSE;
		}
		channel.setPty(pty);

		return channel;
	}

	private void closeSession(Session session, ChannelExec channel) {
		if (channel != null) {
			channel.disconnect();
		}
		if (session != null) {
			session.disconnect();
		}
	}

	@Override
	public String toString() {
		return "SSHSite [hostname=" + hostname + ", port=" + port
				  + ", username=" + username + ", password=" + password
				  + ", keyfile=" + keyfile + ", pty=" + pty + "]";
	}

}

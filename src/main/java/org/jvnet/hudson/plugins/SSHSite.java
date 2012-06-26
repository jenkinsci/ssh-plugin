package org.jvnet.hudson.plugins;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.logging.Logger;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

public class SSHSite {
	String hostname;
	int port;
	String username;
	String password;
	String keyfile;

	public static final Logger LOGGER = Logger.getLogger(SSHSite.class
			.getName());

	public SSHSite() {

	}

	public SSHSite(String hostname, String port, String username,
			String password, String keyfile) {
		this.hostname = hostname;
		try {
			this.port = Integer.parseInt(port);
		} catch (Exception e) {
			this.port = 22;
		}
		this.username = username;
		this.password = password;
		this.keyfile = keyfile;
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
		} catch (Exception e) {
			this.port = 22;
		}
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

	private Session createSession(PrintStream logger) throws JSchException {
		JSch jsch = new JSch();

		Session session = jsch.getSession(username, hostname, port);
		if (this.keyfile != null && this.keyfile.length() > 0) {
			jsch.addIdentity(this.keyfile, this.password);
		} else {
			session.setPassword(password);
		}

		UserInfo ui = new SSHUserInfo(password);
		session.setUserInfo(ui);

		java.util.Properties config = new java.util.Properties();
		config.put("StrictHostKeyChecking", "no");
		session.setConfig(config);
		session.connect();

		return session;
	}

	public int executeCommand(PrintStream logger, String command) {
		Session session = null;
		ChannelExec channel = null;
		int status = -1;
		try {
			session = createSession(logger);
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
					logger.print(new String(tmp, 0, i));
				}
				if (channel.isClosed()) {
					status = channel.getExitStatus();
					logger.println("[SSH] " + "exit-status: " + status);
					break;
				}
				try {
					Thread.sleep(1000);
				} catch (Exception ee) {
				}
			}
			closeSession(logger, session, channel);
		} catch (JSchException e) {
			logger.println("[SSH] Exception:" + e.getMessage());
			e.printStackTrace(logger);
			if (channel != null && channel.isConnected()) {
				channel.disconnect();
			}
			if (session != null && session.isConnected()) {
				session.disconnect();
			}
			session = null;
		} catch (IOException e) {
			logger.println("[SSH] Exception:" + e.getMessage());
			e.printStackTrace(logger);
		}
		return status;
	}

	public void testConnection(PrintStream logger) throws JSchException,
			IOException {
		Session session = createSession(logger);
		closeSession(logger, session, null);
	}

	private ChannelExec createChannel(PrintStream logger, Session session)
			throws JSchException {
		ChannelExec channel = (ChannelExec) session.openChannel("exec");
		channel.setOutputStream(logger,true);
		channel.setExtOutputStream(logger, true);
		channel.setInputStream(null);
		channel.setPty(true);
		return channel;
	}

	private void closeSession(PrintStream logger, Session session,
			ChannelExec channel) {
		if (channel != null) {
			channel.disconnect();
			channel = null;
		}
		if (session != null) {
			session.disconnect();
			session = null;
		}
	}

	@Override
	public String toString() {
		return "SSHSite [hostname=" + hostname
				+ ", port=" + port + ", username=" + username + ", password="
				+ password + ", keyfile=" + keyfile + "]";
	}
}

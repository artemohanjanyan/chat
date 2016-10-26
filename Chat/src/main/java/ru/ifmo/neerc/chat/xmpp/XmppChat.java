package ru.ifmo.neerc.chat.xmpp;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.KeyManagementException;
import java.util.Calendar;
import java.util.Date;

import org.jivesoftware.smack.AbstractConnectionListener;
import org.jivesoftware.smack.AbstractXMPPConnection;
import org.jivesoftware.smack.MessageListener;
import org.jivesoftware.smack.PacketCollector;
import org.jivesoftware.smack.PresenceListener;
import org.jivesoftware.smack.ReconnectionManager;
import org.jivesoftware.smack.SmackConfiguration;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.StanzaExtensionFilter;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smack.util.TLSUtils;
import org.jivesoftware.smackx.muc.DiscussionHistory;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.jivesoftware.smackx.delay.packet.DelayInformation;
import org.jivesoftware.smackx.muc.packet.MUCUser;
import org.jivesoftware.smackx.muc.packet.MUCItem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.ifmo.neerc.chat.client.Chat;
import ru.ifmo.neerc.chat.message.Message;
import ru.ifmo.neerc.chat.message.UserMessage;
import ru.ifmo.neerc.chat.user.UserEntry;
import ru.ifmo.neerc.chat.user.UserRegistry;
import ru.ifmo.neerc.chat.utils.DebugUtils;
import ru.ifmo.neerc.chat.xmpp.provider.*;
import ru.ifmo.neerc.chat.xmpp.packet.*;
import ru.ifmo.neerc.task.Task;
import ru.ifmo.neerc.task.TaskStatus;
import ru.ifmo.neerc.task.TaskRegistry;
import ru.ifmo.neerc.utils.XmlUtils;

/**
 * @author Evgeny Mandrikov
 */
public class XmppChat implements Chat {
    private static final Logger LOG = LoggerFactory.getLogger(XmppChat.class);

    private static final String SERVER_HOST = System.getProperty("server.host", "localhost");
    private static final String SERVER_HOSTNAME = System.getProperty("server.hostname", SERVER_HOST);
    private static final int SERVER_PORT = Integer.parseInt(System.getProperty("server.port", "5222"));
    private static final String ROOM = "neerc@conference." + SERVER_HOSTNAME;
	private static final String NEERC_SERVICE = "neerc." + SERVER_HOSTNAME;

    private MultiUserChat muc;
    private AbstractXMPPConnection connection;
    private boolean connected;
    
    private String name;
    private String password = System.getProperty("password", "12345");

    private MUCListener mucListener;
    private Date lastActivity = null;

    public XmppChat(
            String name,
            MUCListener mucListener
    ) {
        this.name = name;
        this.mucListener = mucListener;

        NeercTaskPacketExtensionProvider.register();
        NeercClockPacketExtensionProvider.register();
        NeercIQProvider.register();

        ReconnectionManager.setEnabledPerDefault(true);
    }

    public synchronized void disconnect() {
        connected = false;
        if (connection != null) {
            connection.disconnect();
        }
    }
    
    public synchronized void connect() {
        disconnect();
        LOG.info("connecting to server");

        // Create the configuration for this new connection
        XMPPTCPConnectionConfiguration.Builder builder = XMPPTCPConnectionConfiguration.builder()
            .setUsernameAndPassword(name, password)
            .setServiceName(SERVER_HOSTNAME)
            .setHost(SERVER_HOST)
            .setPort(SERVER_PORT)
            .setCompressionEnabled(true);

        try {
            TLSUtils.acceptAllCertificates(builder);
            TLSUtils.disableHostnameVerificationForTlsCertificicates(builder);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            LOG.error("Unable to configure connection", e);
        }

        XMPPTCPConnectionConfiguration config = builder.build();

        connection = new XMPPTCPConnection(config);
        connection.addConnectionListener(new MyConnectionListener());
        connection.addAsyncStanzaListener(new TaskPacketListener(), new StanzaExtensionFilter(new NeercTaskPacketExtension()));

        // Connect to the server
        try {
            connection.connect();
            connection.login();
        } catch (XMPPException | SmackException | IOException e) {
            LOG.error("Unable to connect", e);
            throw new RuntimeException(e);
        }
    }
    
    public boolean isConnected() {
        return connected;
    }
    
    private void join() {
        try {
            // Joins the new room and retrieves history
            DiscussionHistory history = new DiscussionHistory();
            if (lastActivity != null) {
                history.setSince(new Date(lastActivity.getTime() + 1));
            } else {
                if (System.getProperty("history") != null) {
                    int size = Integer.parseInt(System.getProperty("history"));
                    history.setMaxStanzas(size);
                } else {
                    Calendar calendar = Calendar.getInstance();
                    calendar.set(Calendar.HOUR_OF_DAY, 0);
                    calendar.set(Calendar.MINUTE, 0);
                    history.setSince(calendar.getTime());
                }
            }
            muc.join(
                    name, // nick
                    "",   // password
                    history,
                    SmackConfiguration.getDefaultPacketReplyTimeout()
            );
        } catch (XMPPException | SmackException e) {
            LOG.error("Unable to join room", e);
        }

        try {
            queryUsers();
            queryTasks();
        } catch (XMPPException | SmackException e) {
            LOG.error("Unable to communicate with NEERC service", e);
        }
    }

    public void debugConnection() {
        LOG.debug("User: {}", connection.getUser());
        LOG.debug("Connected: {}", connection.isConnected());
        LOG.debug("Authenticated: {}", connection.isAuthenticated());
        LOG.debug("Joined: {}", muc.isJoined());
    }

    @Override
    public void write(Message message) {
        try {
            if (message instanceof UserMessage) {
                UserMessage userMessage = (UserMessage) message;
                muc.sendMessage(userMessage.getText());
            } else {
                throw new UnsupportedOperationException(message.getClass().getSimpleName());
            }
        } catch (SmackException e) {
            LOG.error("Unable to write message", e);
        }
    }
    
	@Override
	public void write(Task task) {
        if (task.getScheduleType() == Task.ScheduleType.NONE) {
            NeercTaskIQ packet = new NeercTaskIQ(task);
            packet.setTo(NEERC_SERVICE);
            try {
                connection.sendStanza(packet);
            } catch (SmackException e) {
                LOG.error("Unable to write task", e);
            }
        }
        else
            TaskRegistry.getInstance().update(task);
    }

	@Override
	public void write(Task task, TaskStatus status) {
		NeercTaskResultIQ packet = new NeercTaskResultIQ(task, status);
		packet.setTo(NEERC_SERVICE);
        try {
            connection.sendStanza(packet);
        } catch (SmackException e) {
            LOG.error("Unable to write task status", e);
        }
    }

	public IQ query(String what) throws XMPPException, SmackException {
		IQ packet = new NeercIQ(what);
		packet.setTo(NEERC_SERVICE);
		
        PacketCollector collector = connection.createPacketCollectorAndSend(packet);
        return collector.nextResultOrThrow();
    }

	public void queryUsers() throws XMPPException, SmackException {
		IQ iq = query("users");
		if (!(iq instanceof NeercUserListIQ)) {
		    throw new XMPPException.XMPPErrorException("unparsed iq packet", null);
		}
		NeercUserListIQ packet = (NeercUserListIQ) iq;
        UserRegistry registry = UserRegistry.getInstance();
		for (UserEntry user: packet.getUsers()) {
		    // TODO: replace with registry.add(UserEntry user)
            UserEntry reguser = registry.findOrRegister(user.getName());
            reguser.setPower(user.isPower());
            reguser.setGroup(user.getGroup());
		}
	}

	public void queryTasks() throws XMPPException, SmackException {
		IQ iq = query("tasks");
		if (!(iq instanceof NeercTaskListIQ)) {
		    throw new XMPPException.XMPPErrorException("unparsed iq packet", null);
		}
		NeercTaskListIQ packet = (NeercTaskListIQ) iq;
		TaskRegistry.getInstance().reset();
		for (Task task: packet.getTasks()) {
			TaskRegistry.getInstance().update(task);
		}
	}

    public MultiUserChat getMultiUserChat() {
        return muc;
    }

    public AbstractXMPPConnection getConnection() {
        return connection;
    }

    private class MyConnectionListener extends AbstractConnectionListener {
        @Override
        public void authenticated(XMPPConnection connection, boolean resumed) {
            muc = MultiUserChatManager.getInstanceFor(connection)
                .getMultiUserChat(ROOM);
            muc.addMessageListener(new MyMessageListener());
            muc.addParticipantListener(new MyPresenceListener());

            join();

            debugConnection();

            connected = true;
            mucListener.connected(XmppChat.this);
        }
    }

    private class MyPresenceListener implements PresenceListener {
        @Override
        public void processPresence(Presence presence) {
            final String from = presence.getFrom();
            final MUCUser mucExtension = presence.getExtension(MUCUser.ELEMENT, MUCUser.NAMESPACE);
            if (mucExtension != null) {
                MUCItem item = mucExtension.getItem();
                LOG.debug(from + " " + DebugUtils.userItemToString(item));
                mucListener.roleChanged(from, item.getRole());
            }
            if (presence.isAvailable()) {
                mucListener.joined(from);
            } else {
                mucListener.left(from);
            }
        }
    }

    private class MyMessageListener implements MessageListener {
        @Override
        public void processMessage(org.jivesoftware.smack.packet.Message message) {
            Date timestamp = null;
            for (ExtensionElement extension : message.getExtensions()) {
                if (DelayInformation.NAMESPACE.equals(extension.getNamespace())) {
                    DelayInformation delayInformation = (DelayInformation) extension;
                    timestamp = delayInformation.getStamp();
                } else {
                    LOG.debug("Found unknown packet extenstion {} with namespace {}",
                            extension.getClass().getSimpleName(),
                            extension.getNamespace()
                    );
                }
            }

            boolean history = true;
            if (timestamp == null) {
                timestamp = new Date();
                history = false;
            }

            if (history) {
                mucListener.historyMessageReceived(
                        message.getFrom(),
                        message.getBody(),
                        timestamp
                );
            } else {
                mucListener.messageReceived(
                        message.getFrom(),
                        message.getBody(),
                        timestamp
                );
            }

            lastActivity = timestamp;
        }
    }
}
